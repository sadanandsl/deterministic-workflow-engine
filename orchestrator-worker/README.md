# orchestrator-worker

Worker SDK for implementing activity handlers in the Workflow Orchestrator.

## Overview

This module provides the client SDK that workers use to:
- Poll for available tasks
- Execute activity handlers
- Report completion/failure
- Send heartbeats to renew leases

## Quick Start

### 1. Create a Worker

```java
import com.orchestrator.worker.WorkerClient;
import com.orchestrator.worker.ActivityContext;

WorkerClient worker = new WorkerClient("http://localhost:8080");
```

### 2. Register Activity Handlers

```java
// Simple handler
worker.registerActivity("order.validate", context -> {
    OrderInput order = context.getInput(OrderInput.class);
    
    // Validate order
    boolean isValid = validateOrder(order);
    
    return context.toJsonNode(Map.of(
        "valid", isValid,
        "validatedAt", Instant.now()
    ));
});

// Handler with external API call (uses idempotency key)
worker.registerActivity("payment.process", context -> {
    PaymentRequest request = context.getInput(PaymentRequest.class);
    
    // Use idempotency key for external API
    String idempotencyKey = context.getIdempotencyKey();
    
    PaymentResult result = paymentGateway.charge(
        request.amount(),
        request.customerId(),
        idempotencyKey  // Prevents duplicate charges
    );
    
    return context.toJsonNode(result);
});

// Long-running handler with heartbeats
worker.registerActivity("report.generate", context -> {
    ReportRequest request = context.getInput(ReportRequest.class);
    
    for (int page = 0; page < request.pageCount(); page++) {
        generatePage(page);
        
        // Renew lease periodically
        if (page % 10 == 0) {
            if (!context.heartbeat()) {
                throw new RuntimeException("Lost lease, aborting");
            }
        }
    }
    
    return context.toJsonNode(Map.of("status", "completed"));
});
```

### 3. Start the Worker

```java
// Start polling for tasks (blocking)
worker.start();

// Or start in background
worker.startAsync();
```

## ActivityContext

The `ActivityContext` provides execution context to handlers:

```java
public interface ActivityContext {
    // Get task input
    JsonNode getInput();
    <T> T getInput(Class<T> type);
    
    // Execution metadata
    UUID getWorkflowInstanceId();
    String getTaskId();
    int getAttemptNumber();
    
    // Idempotency key for external calls
    String getIdempotencyKey();
    
    // Lease management
    boolean heartbeat();
    
    // Result helpers
    JsonNode toJsonNode(Object result);
}
```

## Idempotency Key Usage

The idempotency key format is: `{workflowInstanceId}:{taskId}:{attemptNumber}`

Use it when making external API calls:

```java
worker.registerActivity("inventory.reserve", context -> {
    // Get the unique idempotency key
    String idempotencyKey = context.getIdempotencyKey();
    // Example: "abc-123:reserve-inventory:1"
    
    // Pass to external service
    ReservationResult result = inventoryService.reserve(
        items,
        idempotencyKey  // Service stores result keyed by this
    );
    
    return context.toJsonNode(result);
});
```

If the task is retried (attempt 2), the key becomes `abc-123:reserve-inventory:2`, allowing a fresh attempt.

## Heartbeats

For long-running tasks, send heartbeats to prevent lease expiration:

```java
worker.registerActivity("data.import", context -> {
    List<Record> records = loadRecords();
    
    for (int i = 0; i < records.size(); i++) {
        processRecord(records.get(i));
        
        // Heartbeat every 100 records
        if (i % 100 == 0) {
            boolean leaseValid = context.heartbeat();
            if (!leaseValid) {
                // Another worker took over, abort gracefully
                throw new LeaseExpiredException("Lost lease");
            }
        }
    }
    
    return context.toJsonNode(Map.of("imported", records.size()));
});
```

Default lease duration is 30 seconds. Heartbeat interval should be ~1/3 of lease duration (10 seconds).

## Error Handling

```java
worker.registerActivity("risky.operation", context -> {
    try {
        return doRiskyOperation();
    } catch (TransientException e) {
        // Will be retried according to retry policy
        throw e;
    } catch (PermanentException e) {
        // Mark as non-retryable
        throw new NonRetryableException(e);
    }
});
```

## Configuration

```java
WorkerClient worker = WorkerClient.builder()
    .orchestratorUrl("http://localhost:8080")
    .pollInterval(Duration.ofSeconds(1))
    .heartbeatInterval(Duration.ofSeconds(10))
    .activityTypes(List.of("order.*", "payment.*"))  // Filter activities
    .concurrency(10)  // Parallel task execution
    .build();
```

## Dependencies

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-worker</artifactId>
</dependency>
```
