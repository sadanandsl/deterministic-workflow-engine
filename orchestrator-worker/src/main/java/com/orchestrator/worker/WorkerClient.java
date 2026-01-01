package com.orchestrator.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.TaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker client that polls for tasks and executes activities.
 * 
 * Usage:
 * <pre>
 * WorkerClient worker = new WorkerClient("http://orchestrator:8080", workerId);
 * worker.registerActivity("send-email", context -> {
 *     // Handle send-email activity
 *     return context.toJsonNode(Map.of("sent", true));
 * });
 * worker.start();
 * </pre>
 */
public class WorkerClient {
    
    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);
    
    private final String orchestratorUrl;
    private final UUID workerId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, ActivityHandler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final ScheduledExecutorService heartbeatScheduler;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Duration pollInterval;
    private final int maxConcurrentTasks;
    
    public WorkerClient(String orchestratorUrl, UUID workerId) {
        this(orchestratorUrl, workerId, Duration.ofSeconds(1), 10);
    }
    
    public WorkerClient(String orchestratorUrl, UUID workerId, Duration pollInterval, int maxConcurrentTasks) {
        this.orchestratorUrl = orchestratorUrl;
        this.workerId = workerId;
        this.pollInterval = pollInterval;
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(maxConcurrentTasks);
        this.heartbeatScheduler = Executors.newScheduledThreadPool(2);
    }
    
    /**
     * Register an activity handler.
     */
    public void registerActivity(String activityType, ActivityHandler handler) {
        handlers.put(activityType, handler);
        log.info("Registered activity handler: {}", activityType);
    }
    
    /**
     * Start the worker.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting worker {} with {} activity types", workerId, handlers.keySet());
            
            // Start polling thread for each activity type
            for (String activityType : handlers.keySet()) {
                executorService.submit(() -> pollLoop(activityType));
            }
        }
    }
    
    /**
     * Stop the worker gracefully.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping worker {}", workerId);
            executorService.shutdown();
            heartbeatScheduler.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void pollLoop(String activityType) {
        while (running.get()) {
            try {
                List<TaskExecution> tasks = pollTasks(activityType, 1);
                
                for (TaskExecution task : tasks) {
                    executeTask(activityType, task);
                }
                
                if (tasks.isEmpty()) {
                    Thread.sleep(pollInterval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in poll loop for {}", activityType, e);
                try {
                    Thread.sleep(pollInterval.toMillis() * 2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    private List<TaskExecution> pollTasks(String activityType, int maxTasks) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "activityType", activityType,
            "workerId", workerId.toString(),
            "maxTasks", maxTasks
        ));
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(orchestratorUrl + "/api/v1/tasks/poll"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.warn("Poll failed with status {}", response.statusCode());
            return List.of();
        }
        
        return Arrays.asList(objectMapper.readValue(response.body(), TaskExecution[].class));
    }
    
    private void executeTask(String activityType, TaskExecution task) {
        ActivityHandler handler = handlers.get(activityType);
        if (handler == null) {
            log.error("No handler registered for activity type: {}", activityType);
            return;
        }
        
        log.info("Executing task {} (attempt {})", task.executionId(), task.attemptNumber());
        
        // Start heartbeat
        ScheduledFuture<?> heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
            () -> sendHeartbeat(task),
            10, 10, TimeUnit.SECONDS
        );
        
        try {
            ActivityContext context = new ActivityContext(
                task,
                objectMapper,
                () -> sendHeartbeat(task)
            );
            
            JsonNode output = handler.execute(context);
            
            // Report completion
            completeTask(task, output);
            log.info("Task {} completed successfully", task.executionId());
            
        } catch (ActivityException e) {
            log.warn("Task {} failed: {} - {}", task.executionId(), e.getErrorCode(), e.getMessage());
            failTask(task, e.getErrorCode(), e.getMessage(), getStackTrace(e));
            
        } catch (Exception e) {
            log.error("Task {} failed with unexpected error", task.executionId(), e);
            failTask(task, "INTERNAL_ERROR", e.getMessage(), getStackTrace(e));
            
        } finally {
            heartbeatFuture.cancel(false);
        }
    }
    
    private boolean sendHeartbeat(TaskExecution task) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "workerId", workerId.toString(),
                "fenceToken", task.fenceToken()
            ));
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(orchestratorUrl + "/api/v1/tasks/" + task.executionId() + "/heartbeat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                return Boolean.TRUE.equals(result.get("renewed"));
            }
            return false;
            
        } catch (Exception e) {
            log.warn("Heartbeat failed for task {}", task.executionId(), e);
            return false;
        }
    }
    
    private void completeTask(TaskExecution task, JsonNode output) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "workerId", workerId.toString(),
                "fenceToken", task.fenceToken(),
                "output", output
            ));
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(orchestratorUrl + "/api/v1/tasks/" + task.executionId() + "/complete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Failed to report task completion: {}", response.body());
            }
            
        } catch (Exception e) {
            log.error("Error reporting task completion", e);
        }
    }
    
    private void failTask(TaskExecution task, String errorCode, String errorMessage, String stackTrace) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "workerId", workerId.toString(),
                "fenceToken", task.fenceToken(),
                "errorCode", errorCode,
                "errorMessage", errorMessage != null ? errorMessage : "",
                "stackTrace", stackTrace != null ? stackTrace : ""
            ));
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(orchestratorUrl + "/api/v1/tasks/" + task.executionId() + "/fail"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Failed to report task failure: {}", response.body());
            }
            
        } catch (Exception e) {
            log.error("Error reporting task failure", e);
        }
    }
    
    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
