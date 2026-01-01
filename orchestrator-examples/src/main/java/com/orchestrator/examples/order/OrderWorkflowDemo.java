package com.orchestrator.examples.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demonstration runner for the Order Processing Workflow.
 * 
 * Shows:
 * 1. Normal successful execution
 * 2. Worker crash mid-task (lease expiration and recovery)
 * 3. Duplicate task execution attempt (idempotency)
 * 4. Orchestrator restart (state recovery)
 * 5. Failure with compensation
 */
public class OrderWorkflowDemo {
    
    private static final Logger log = LoggerFactory.getLogger(OrderWorkflowDemo.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    // Simple in-memory state for demo (without Spring dependencies)
    private final Map<String, TaskExecution> taskExecutions = new ConcurrentHashMap<>();
    private final Map<String, LeaseInfo> leases = new ConcurrentHashMap<>();
    private final Map<String, Long> fenceTokens = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong(0);
    
    private final String workerId = "WORKER-" + UUID.randomUUID().toString().substring(0, 8);
    
    // Simple lease info class for demo
    record LeaseInfo(String leaseKey, String holderId, Instant expiresAt, long fenceToken) {}
    
    public static void main(String[] args) throws Exception {
        OrderWorkflowDemo demo = new OrderWorkflowDemo();
        
        log.info("╔══════════════════════════════════════════════════════════════════════╗");
        log.info("║     WORKFLOW ORCHESTRATOR - ORDER PROCESSING DEMONSTRATION           ║");
        log.info("╠══════════════════════════════════════════════════════════════════════╣");
        log.info("║  Demonstrating deterministic execution with exactly-once semantics   ║");
        log.info("╚══════════════════════════════════════════════════════════════════════╝");
        log.info("");
        
        // Demo 1: Normal execution
        demo.runScenario1_NormalExecution();
        
        // Demo 2: Retry on transient failure
        demo.runScenario2_RetryOnFailure();
        
        // Demo 3: Worker crash simulation
        demo.runScenario3_WorkerCrash();
        
        // Demo 4: Duplicate execution prevention
        demo.runScenario4_DuplicatePrevention();
        
        // Demo 5: Compensation on failure
        demo.runScenario5_CompensationOnFailure();
        
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════════╗");
        log.info("║                    ALL DEMONSTRATIONS COMPLETE                       ║");
        log.info("╚══════════════════════════════════════════════════════════════════════╝");
    }
    
    /**
     * SCENARIO 1: Normal successful workflow execution.
     */
    public void runScenario1_NormalExecution() throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("SCENARIO 1: Normal Successful Execution");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("");
        
        OrderActivityHandlers.disableFailures();
        
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode orderInput = OrderProcessingWorkflow.createSampleOrderInput(
            orderId, new BigDecimal("499.99"));
        
        UUID workflowInstanceId = UUID.randomUUID();
        log.info("Starting workflow for order: {} (workflow: {})", orderId, workflowInstanceId);
        
        // Execute each task in sequence
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_VALIDATE_ORDER, orderInput);
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_CHECK_INVENTORY, orderInput);
        // Skip human approval for low-value order
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_RESERVE_INVENTORY, orderInput);
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_PROCESS_PAYMENT, orderInput);
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_SHIP_ORDER, orderInput);
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_SEND_CONFIRMATION, orderInput);
        
        log.info("");
        log.info("✓ SCENARIO 1 COMPLETE: Workflow executed successfully");
        log.info("");
    }
    
    /**
     * SCENARIO 2: Automatic retry on transient failure.
     */
    public void runScenario2_RetryOnFailure() throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("SCENARIO 2: Automatic Retry on Transient Failure");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("");
        
        // Configure to fail twice before succeeding
        OrderActivityHandlers.enableInventoryFailures(2);
        
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode orderInput = OrderProcessingWorkflow.createSampleOrderInput(
            orderId, new BigDecimal("299.99"));
        
        UUID workflowInstanceId = UUID.randomUUID();
        log.info("Starting workflow with simulated inventory failures: {} (workflow: {})", 
            orderId, workflowInstanceId);
        
        // Execute with retry
        executeTask(workflowInstanceId, OrderProcessingWorkflow.TASK_VALIDATE_ORDER, orderInput);
        executeTaskWithRetry(workflowInstanceId, OrderProcessingWorkflow.TASK_CHECK_INVENTORY, 
            orderInput, 3, Duration.ofMillis(500));
        
        OrderActivityHandlers.disableFailures();
        
        log.info("");
        log.info("✓ SCENARIO 2 COMPLETE: Task succeeded after automatic retry");
        log.info("");
    }
    
    /**
     * SCENARIO 3: Worker crash mid-task (lease expiration and recovery).
     */
    public void runScenario3_WorkerCrash() throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("SCENARIO 3: Worker Crash Mid-Task (Lease Expiration Recovery)");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("");
        
        OrderActivityHandlers.disableFailures();
        
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        String taskId = OrderProcessingWorkflow.TASK_PROCESS_PAYMENT;
        UUID workflowInstanceId = UUID.randomUUID();
        String leaseKey = workflowInstanceId + ":" + taskId;
        
        log.info("Simulating worker crash during payment processing...");
        log.info("");
        
        // Worker 1 acquires lease and starts task
        String worker1Id = "WORKER-CRASHED";
        long fenceToken = fenceTokens.computeIfAbsent(leaseKey, k -> 0L);
        fenceToken++;
        fenceTokens.put(leaseKey, fenceToken);
        
        LeaseInfo lease1 = new LeaseInfo(leaseKey, worker1Id, 
            Instant.now().plusSeconds(2), fenceToken);
        leases.put(leaseKey, lease1);
        
        log.info("Worker {} acquired lease for task {} with fence token {}", 
            worker1Id, taskId, fenceToken);
        
        log.info("Worker {} CRASHED while processing task!", worker1Id);
        log.info("Waiting for lease to expire (2 seconds)...");
        Thread.sleep(2500);
        
        // Check for expired leases (recovery process)
        LeaseInfo currentLease = leases.get(leaseKey);
        if (currentLease != null && currentLease.expiresAt().isBefore(Instant.now())) {
            log.info("Recovery detected expired lease for: {}", leaseKey);
            log.info("  - Held by: {} expired at: {}", 
                currentLease.holderId(), currentLease.expiresAt());
            
            // Force release and increment fence token
            long newFenceToken = fenceTokens.get(leaseKey) + 1;
            fenceTokens.put(leaseKey, newFenceToken);
            leases.remove(leaseKey);
            log.info("  - Force released, new fence token: {}", newFenceToken);
        }
        
        // Worker 2 picks up the task
        String worker2Id = "WORKER-RECOVERY";
        long worker2FenceToken = fenceTokens.get(leaseKey);
        LeaseInfo lease2 = new LeaseInfo(leaseKey, worker2Id, 
            Instant.now().plusSeconds(30), worker2FenceToken);
        leases.put(leaseKey, lease2);
        
        log.info("Worker {} reacquired lease for task {} with fence token {}", 
            worker2Id, taskId, worker2FenceToken);
        
        log.info("Worker {} completing the task...", worker2Id);
        // Execute the task
        JsonNode orderInput = OrderProcessingWorkflow.createSampleOrderInput(
            orderId, new BigDecimal("199.99"));
        String idempotencyKey = workflowInstanceId + ":" + taskId + ":2";
        JsonNode result = OrderActivityHandlers.processPayment(idempotencyKey, orderInput);
        log.info("Task completed successfully by recovery worker");
        
        log.info("");
        log.info("✓ SCENARIO 3 COMPLETE: Task recovered after worker crash");
        log.info("");
    }
    
    /**
     * SCENARIO 4: Duplicate task execution prevention.
     */
    public void runScenario4_DuplicatePrevention() throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("SCENARIO 4: Duplicate Task Execution Prevention (Idempotency)");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("");
        
        OrderActivityHandlers.disableFailures();
        
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        UUID workflowInstanceId = UUID.randomUUID();
        String taskId = OrderProcessingWorkflow.TASK_VALIDATE_ORDER;
        
        JsonNode orderInput = OrderProcessingWorkflow.createSampleOrderInput(
            orderId, new BigDecimal("149.99"));
        
        // Use the same idempotency key for both attempts
        String idempotencyKey = workflowInstanceId + ":" + taskId + ":1";
        
        log.info("Executing task with idempotency key: {}", idempotencyKey);
        
        // First execution
        log.info("");
        log.info("--- First Execution Attempt ---");
        JsonNode result1 = OrderActivityHandlers.validateOrder(idempotencyKey, orderInput);
        String validatedAt1 = result1.get("validatedAt").asText();
        
        // Store execution record
        taskExecutions.put(idempotencyKey, createTaskExecution(workflowInstanceId, taskId, idempotencyKey));
        log.info("Execution recorded with idempotency key: {}", idempotencyKey);
        
        Thread.sleep(100);
        
        // Second execution attempt (simulating duplicate request)
        log.info("");
        log.info("--- Duplicate Execution Attempt ---");
        log.info("Checking for existing execution with key: {}", idempotencyKey);
        
        TaskExecution existing = taskExecutions.get(idempotencyKey);
        if (existing != null) {
            log.info("DUPLICATE DETECTED: Task already executed at {}", validatedAt1);
            log.info("Returning cached result instead of re-executing");
            // In real implementation, would return the stored result
        } else {
            log.info("No duplicate found, executing task...");
            OrderActivityHandlers.validateOrder(idempotencyKey, orderInput);
        }
        
        log.info("");
        log.info("✓ SCENARIO 4 COMPLETE: Duplicate execution prevented via idempotency key");
        log.info("");
    }
    
    /**
     * SCENARIO 5: Workflow failure with compensation (saga rollback).
     */
    public void runScenario5_CompensationOnFailure() throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("SCENARIO 5: Compensation on Failure (Saga Rollback)");
        log.info("═══════════════════════════════════════════════════════════════════════");
        log.info("");
        
        OrderActivityHandlers.disableFailures();
        
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        UUID workflowInstanceId = UUID.randomUUID();
        
        JsonNode orderInput = OrderProcessingWorkflow.createSampleOrderInput(
            orderId, new BigDecimal("999.99"));
        
        log.info("Starting workflow that will fail at shipping step...");
        log.info("");
        
        // Track completed tasks for compensation
        List<String> completedTasks = new ArrayList<>();
        Map<String, JsonNode> taskOutputs = new HashMap<>();
        
        // Execute successfully up to payment
        String key1 = workflowInstanceId + ":validate:1";
        JsonNode validateResult = OrderActivityHandlers.validateOrder(key1, orderInput);
        completedTasks.add(OrderProcessingWorkflow.TASK_VALIDATE_ORDER);
        
        String key2 = workflowInstanceId + ":inventory-check:1";
        JsonNode inventoryResult = OrderActivityHandlers.checkInventory(key2, orderInput);
        completedTasks.add(OrderProcessingWorkflow.TASK_CHECK_INVENTORY);
        
        String key3 = workflowInstanceId + ":reserve:1";
        JsonNode reserveResult = OrderActivityHandlers.reserveInventory(key3, orderInput);
        taskOutputs.put(OrderProcessingWorkflow.TASK_RESERVE_INVENTORY, reserveResult);
        completedTasks.add(OrderProcessingWorkflow.TASK_RESERVE_INVENTORY);
        
        String key4 = workflowInstanceId + ":payment:1";
        JsonNode paymentResult = OrderActivityHandlers.processPayment(key4, orderInput);
        taskOutputs.put(OrderProcessingWorkflow.TASK_PROCESS_PAYMENT, paymentResult);
        completedTasks.add(OrderProcessingWorkflow.TASK_PROCESS_PAYMENT);
        
        // Simulate failure at shipping
        log.info("");
        log.info("!!! SIMULATED FAILURE at shipping step !!!");
        log.info("Initiating backward compensation (saga rollback)...");
        log.info("");
        
        // Execute compensation in reverse order
        log.info("--- Compensation Phase ---");
        
        // Compensation for payment (refund)
        if (completedTasks.contains(OrderProcessingWorkflow.TASK_PROCESS_PAYMENT)) {
            String compKey1 = workflowInstanceId + ":refund:1";
            JsonNode paymentOutput = taskOutputs.get(OrderProcessingWorkflow.TASK_PROCESS_PAYMENT);
            OrderActivityHandlers.refundPayment(compKey1, paymentOutput);
        }
        
        // Compensation for inventory reservation (release)
        if (completedTasks.contains(OrderProcessingWorkflow.TASK_RESERVE_INVENTORY)) {
            String compKey2 = workflowInstanceId + ":release:1";
            JsonNode reserveOutput = taskOutputs.get(OrderProcessingWorkflow.TASK_RESERVE_INVENTORY);
            OrderActivityHandlers.releaseInventory(compKey2, reserveOutput);
        }
        
        log.info("");
        log.info("✓ SCENARIO 5 COMPLETE: Compensation executed successfully");
        log.info("  - Payment refunded to customer");
        log.info("  - Inventory reservation released");
        log.info("");
    }
    
    // Helper methods
    
    private void executeTask(UUID workflowInstanceId, String taskId, JsonNode input) {
        String idempotencyKey = workflowInstanceId + ":" + taskId + ":1";
        log.info("");
        log.info("--- Executing Task: {} ---", taskId);
        
        try {
            JsonNode result = switch (taskId) {
                case OrderProcessingWorkflow.TASK_VALIDATE_ORDER -> 
                    OrderActivityHandlers.validateOrder(idempotencyKey, input);
                case OrderProcessingWorkflow.TASK_CHECK_INVENTORY -> 
                    OrderActivityHandlers.checkInventory(idempotencyKey, input);
                case OrderProcessingWorkflow.TASK_RESERVE_INVENTORY -> 
                    OrderActivityHandlers.reserveInventory(idempotencyKey, input);
                case OrderProcessingWorkflow.TASK_PROCESS_PAYMENT -> 
                    OrderActivityHandlers.processPayment(idempotencyKey, input);
                case OrderProcessingWorkflow.TASK_SHIP_ORDER -> 
                    OrderActivityHandlers.shipOrder(idempotencyKey, input);
                case OrderProcessingWorkflow.TASK_SEND_CONFIRMATION -> 
                    OrderActivityHandlers.sendConfirmation(idempotencyKey, input);
                default -> throw new IllegalArgumentException("Unknown task: " + taskId);
            };
            log.info("Task {} completed successfully", taskId);
        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage());
            throw e;
        }
    }
    
    private void executeTaskWithRetry(UUID workflowInstanceId, String taskId, 
            JsonNode input, int maxAttempts, Duration backoff) {
        String baseKey = workflowInstanceId + ":" + taskId;
        log.info("");
        log.info("--- Executing Task with Retry: {} (max {} attempts) ---", taskId, maxAttempts);
        
        int attempt = 0;
        Duration currentBackoff = backoff;
        while (attempt < maxAttempts) {
            attempt++;
            String idempotencyKey = baseKey + ":" + attempt;
            
            try {
                log.info("Attempt {}/{}", attempt, maxAttempts);
                JsonNode result = switch (taskId) {
                    case OrderProcessingWorkflow.TASK_CHECK_INVENTORY -> 
                        OrderActivityHandlers.checkInventory(idempotencyKey, input);
                    default -> throw new IllegalArgumentException("Task not configured for retry: " + taskId);
                };
                log.info("Task {} completed on attempt {}", taskId, attempt);
                return; // Success
            } catch (Exception e) {
                log.warn("Attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                
                if (attempt < maxAttempts) {
                    log.info("Waiting {} ms before retry...", currentBackoff.toMillis());
                    try {
                        Thread.sleep(currentBackoff.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    // Exponential backoff
                    currentBackoff = currentBackoff.multipliedBy(2);
                }
            }
        }
        throw new RuntimeException("Task " + taskId + " failed after " + maxAttempts + " attempts");
    }
    
    private TaskExecution createTaskExecution(UUID workflowInstanceId, String taskId, String idempotencyKey) {
        UUID workerUUID = UUID.nameUUIDFromBytes(workerId.getBytes());
        return new TaskExecution(
            UUID.randomUUID(),
            workflowInstanceId,
            taskId,
            idempotencyKey,
            1,
            TaskState.COMPLETED,
            workerUUID,
            null,
            1L,
            null, // input
            null, // output
            null, // errorMessage
            null, // errorCode
            null, // stackTrace
            Instant.now(),
            Instant.now(),
            Instant.now(),
            UUID.randomUUID().toString(), // traceId
            UUID.randomUUID().toString(), // spanId
            workerId
        );
    }
}
