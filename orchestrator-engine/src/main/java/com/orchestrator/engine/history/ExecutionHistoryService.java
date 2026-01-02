package com.orchestrator.engine.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestrator.core.model.Event;
import com.orchestrator.core.model.EventType;
import com.orchestrator.core.model.WorkflowInstance;
import com.orchestrator.core.model.WorkflowState;
import com.orchestrator.core.repository.EventRepository;
import com.orchestrator.core.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for workflow execution history and replay.
 * 
 * Provides:
 * - Full event history retrieval
 * - State reconstruction from events
 * - Workflow replay for debugging
 * - Timeline visualization data
 */
@Service
public class ExecutionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionHistoryService.class);

    private final EventRepository eventRepository;
    private final WorkflowInstanceRepository instanceRepository;

    public ExecutionHistoryService(EventRepository eventRepository, 
                                   WorkflowInstanceRepository instanceRepository) {
        this.eventRepository = eventRepository;
        this.instanceRepository = instanceRepository;
    }

    /**
     * Get full execution history for a workflow instance.
     */
    public ExecutionHistory getHistory(UUID workflowInstanceId) {
        WorkflowInstance instance = instanceRepository.findById(workflowInstanceId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowInstanceId));
        
        List<Event> events = eventRepository.findByWorkflowInstance(workflowInstanceId);
        
        return ExecutionHistory.builder()
            .workflowInstanceId(workflowInstanceId)
            .namespace(instance.namespace())
            .workflowName(instance.workflowName())
            .runId(instance.runId())
            .currentState(instance.state())
            .events(events)
            .timeline(buildTimeline(events))
            .taskHistory(buildTaskHistory(events))
            .statistics(calculateStatistics(events, instance))
            .build();
    }

    /**
     * Get events in a specific sequence range.
     */
    public List<Event> getEventRange(UUID workflowInstanceId, long fromSeq, long toSeq) {
        List<Event> allEvents = eventRepository.findByWorkflowInstanceFrom(workflowInstanceId, fromSeq);
        return allEvents.stream()
            .filter(e -> e.sequenceNumber() <= toSeq)
            .collect(Collectors.toList());
    }

    /**
     * Replay workflow state to a specific point in time.
     * Returns the reconstructed state at that sequence number.
     */
    @Transactional(readOnly = true)
    public ReplayResult replayToSequence(UUID workflowInstanceId, long targetSequence) {
        log.info("Replaying workflow {} to sequence {}", workflowInstanceId, targetSequence);
        
        List<Event> events = eventRepository.findByWorkflowInstance(workflowInstanceId);
        List<Event> filteredEvents = events.stream()
            .filter(e -> e.sequenceNumber() <= targetSequence)
            .collect(Collectors.toList());
        
        return reconstructState(workflowInstanceId, filteredEvents);
    }

    /**
     * Replay workflow state to a specific timestamp.
     */
    @Transactional(readOnly = true)
    public ReplayResult replayToTimestamp(UUID workflowInstanceId, Instant targetTime) {
        log.info("Replaying workflow {} to timestamp {}", workflowInstanceId, targetTime);
        
        List<Event> events = eventRepository.findByWorkflowInstance(workflowInstanceId);
        
        // Filter events up to target time
        List<Event> filteredEvents = events.stream()
            .filter(e -> !e.timestamp().isAfter(targetTime))
            .collect(Collectors.toList());
        
        return reconstructState(workflowInstanceId, filteredEvents);
    }

    /**
     * Reconstruct workflow state from a list of events.
     */
    private ReplayResult reconstructState(UUID workflowInstanceId, List<Event> events) {
        WorkflowState state = WorkflowState.CREATED;
        Set<String> completedTasks = new HashSet<>();
        Set<String> failedTasks = new HashSet<>();
        Map<String, JsonNode> taskOutputs = new HashMap<>();
        String currentTaskId = null;
        JsonNode output = null;
        String lastError = null;
        Instant startedAt = null;
        Instant completedAt = null;
        
        for (Event event : events) {
            switch (event.type()) {
                case WORKFLOW_STARTED -> {
                    state = WorkflowState.RUNNING;
                    startedAt = event.timestamp();
                }
                case WORKFLOW_COMPLETED -> {
                    state = WorkflowState.COMPLETED;
                    completedAt = event.timestamp();
                    output = event.payload();
                }
                case WORKFLOW_FAILED -> {
                    state = WorkflowState.FAILED;
                    completedAt = event.timestamp();
                    if (event.payload() != null && event.payload().has("error")) {
                        lastError = event.payload().get("error").asText();
                    }
                }
                case WORKFLOW_PAUSED -> state = WorkflowState.PAUSED;
                case WORKFLOW_RESUMED -> state = WorkflowState.RUNNING;
                case WORKFLOW_CANCELLED -> {
                    state = WorkflowState.FAILED;
                    completedAt = event.timestamp();
                }
                case TASK_SCHEDULED -> {
                    if (event.payload() != null && event.payload().has("taskId")) {
                        currentTaskId = event.payload().get("taskId").asText();
                    }
                }
                case TASK_COMPLETED -> {
                    if (event.payload() != null && event.payload().has("taskId")) {
                        String taskId = event.payload().get("taskId").asText();
                        completedTasks.add(taskId);
                        if (event.payload().has("output")) {
                            taskOutputs.put(taskId, event.payload().get("output"));
                        }
                    }
                }
                case TASK_FAILED -> {
                    if (event.payload() != null && event.payload().has("taskId")) {
                        String taskId = event.payload().get("taskId").asText();
                        failedTasks.add(taskId);
                        if (event.payload().has("error")) {
                            lastError = event.payload().get("error").asText();
                        }
                    }
                }
                case COMPENSATION_STARTED -> state = WorkflowState.COMPENSATING;
                default -> { /* Other events don't affect state reconstruction */ }
            }
        }
        
        long lastSequence = events.isEmpty() ? 0 : events.get(events.size() - 1).sequenceNumber();
        Instant lastEventTime = events.isEmpty() ? null : events.get(events.size() - 1).timestamp();
        
        return new ReplayResult(
            workflowInstanceId,
            lastSequence,
            lastEventTime,
            state,
            completedTasks,
            failedTasks,
            taskOutputs,
            currentTaskId,
            output,
            lastError,
            startedAt,
            completedAt,
            events.size()
        );
    }

    /**
     * Build a timeline of key events for visualization.
     */
    private List<TimelineEntry> buildTimeline(List<Event> events) {
        return events.stream()
            .filter(e -> isKeyEvent(e.type()))
            .map(e -> new TimelineEntry(
                e.timestamp(),
                e.sequenceNumber(),
                e.type().name(),
                extractTaskId(e),
                summarizePayload(e.payload())
            ))
            .collect(Collectors.toList());
    }

    /**
     * Build history of each task execution.
     */
    private Map<String, TaskHistory> buildTaskHistory(List<Event> events) {
        Map<String, List<TaskHistoryEntry>> taskEvents = new LinkedHashMap<>();
        
        for (Event event : events) {
            String taskId = extractTaskId(event);
            if (taskId != null && isTaskEvent(event.type())) {
                taskEvents.computeIfAbsent(taskId, k -> new ArrayList<>())
                    .add(new TaskHistoryEntry(
                        event.timestamp(),
                        event.type().name(),
                        extractAttemptNumber(event.payload()),
                        summarizePayload(event.payload())
                    ));
            }
        }
        
        return taskEvents.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new TaskHistory(e.getKey(), e.getValue(), calculateTaskDuration(e.getValue()))
            ));
    }

    /**
     * Calculate execution statistics.
     */
    private ExecutionStatistics calculateStatistics(List<Event> events, WorkflowInstance instance) {
        long taskCount = events.stream()
            .filter(e -> e.type() == EventType.TASK_COMPLETED)
            .count();
        
        long retryCount = events.stream()
            .filter(e -> e.type() == EventType.TASK_RETRYING)
            .count();
        
        long failureCount = events.stream()
            .filter(e -> e.type() == EventType.TASK_FAILED)
            .count();
        
        Duration totalDuration = instance.completedAt() != null && instance.startedAt() != null
            ? Duration.between(instance.startedAt(), instance.completedAt())
            : instance.startedAt() != null 
                ? Duration.between(instance.startedAt(), Instant.now())
                : Duration.ZERO;
        
        return new ExecutionStatistics(
            events.size(),
            taskCount,
            retryCount,
            failureCount,
            totalDuration,
            instance.recoveryAttempts()
        );
    }

    private boolean isKeyEvent(EventType type) {
        return switch (type) {
            case WORKFLOW_STARTED, WORKFLOW_COMPLETED, WORKFLOW_FAILED,
                 WORKFLOW_PAUSED, WORKFLOW_RESUMED, WORKFLOW_CANCELLED,
                 TASK_SCHEDULED, TASK_COMPLETED, TASK_FAILED,
                 COMPENSATION_STARTED, COMPENSATION_COMPLETED -> true;
            default -> false;
        };
    }

    private boolean isTaskEvent(EventType type) {
        return type.name().startsWith("TASK_");
    }

    private String extractTaskId(Event event) {
        if (event.payload() != null && event.payload().has("taskId")) {
            return event.payload().get("taskId").asText();
        }
        return null;
    }

    private String summarizePayload(JsonNode payload) {
        if (payload == null) return null;
        String str = payload.toString();
        return str.length() > 200 ? str.substring(0, 200) + "..." : str;
    }

    private int extractAttemptNumber(JsonNode payload) {
        if (payload != null && payload.has("attemptNumber")) {
            return payload.get("attemptNumber").asInt(1);
        }
        return 1;
    }

    private Duration calculateTaskDuration(List<TaskHistoryEntry> entries) {
        Instant started = null;
        Instant completed = null;
        
        for (TaskHistoryEntry entry : entries) {
            if (entry.eventType().equals("TASK_STARTED")) {
                started = entry.timestamp();
            }
            if (entry.eventType().equals("TASK_COMPLETED") || entry.eventType().equals("TASK_FAILED")) {
                completed = entry.timestamp();
            }
        }
        
        if (started != null && completed != null) {
            return Duration.between(started, completed);
        }
        return Duration.ZERO;
    }

    // ========== DTOs ==========

    public record ExecutionHistory(
        UUID workflowInstanceId,
        String namespace,
        String workflowName,
        String runId,
        WorkflowState currentState,
        List<Event> events,
        List<TimelineEntry> timeline,
        Map<String, TaskHistory> taskHistory,
        ExecutionStatistics statistics
    ) {
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private UUID workflowInstanceId;
            private String namespace;
            private String workflowName;
            private String runId;
            private WorkflowState currentState;
            private List<Event> events = List.of();
            private List<TimelineEntry> timeline = List.of();
            private Map<String, TaskHistory> taskHistory = Map.of();
            private ExecutionStatistics statistics;

            public Builder workflowInstanceId(UUID id) { this.workflowInstanceId = id; return this; }
            public Builder namespace(String ns) { this.namespace = ns; return this; }
            public Builder workflowName(String name) { this.workflowName = name; return this; }
            public Builder runId(String runId) { this.runId = runId; return this; }
            public Builder currentState(WorkflowState state) { this.currentState = state; return this; }
            public Builder events(List<Event> events) { this.events = events; return this; }
            public Builder timeline(List<TimelineEntry> timeline) { this.timeline = timeline; return this; }
            public Builder taskHistory(Map<String, TaskHistory> history) { this.taskHistory = history; return this; }
            public Builder statistics(ExecutionStatistics stats) { this.statistics = stats; return this; }
            
            public ExecutionHistory build() {
                return new ExecutionHistory(workflowInstanceId, namespace, workflowName, runId,
                    currentState, events, timeline, taskHistory, statistics);
            }
        }
    }

    public record ReplayResult(
        UUID workflowInstanceId,
        long replayedToSequence,
        Instant replayedToTimestamp,
        WorkflowState reconstructedState,
        Set<String> completedTasks,
        Set<String> failedTasks,
        Map<String, JsonNode> taskOutputs,
        String currentTaskId,
        JsonNode output,
        String lastError,
        Instant startedAt,
        Instant completedAt,
        int eventCount
    ) {}

    public record TimelineEntry(
        Instant timestamp,
        long sequenceNumber,
        String eventType,
        String taskName,
        String summary
    ) {}

    public record TaskHistory(
        String taskName,
        List<TaskHistoryEntry> entries,
        Duration duration
    ) {}

    public record TaskHistoryEntry(
        Instant timestamp,
        String eventType,
        int attemptNumber,
        String summary
    ) {}

    public record ExecutionStatistics(
        long totalEvents,
        long completedTasks,
        long retries,
        long failures,
        Duration totalDuration,
        int recoveryAttempts
    ) {}
}
