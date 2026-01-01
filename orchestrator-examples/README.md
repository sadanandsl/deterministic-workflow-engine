# orchestrator-examples

Example workflows demonstrating the Workflow Orchestrator capabilities.

## Overview

This module contains complete, runnable examples that showcase:

- Normal workflow execution
- Automatic retry on transient failures
- Worker crash recovery (lease expiration)
- Duplicate execution prevention (idempotency)
- Saga compensation on failure

## Quick Start

Run the demo (no infrastructure required):

```bash
mvn exec:java -Dexec.mainClass="com.orchestrator.examples.order.OrderWorkflowDemo"
```

## Order Processing Workflow

The main example is an e-commerce order processing workflow.

### Workflow Steps

```
┌──────────────────┐
│ validate_order   │  Validate order data, customer info
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ check_inventory  │  Check product availability (external API)
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ human_approval   │  Required for orders > $1000 (human task)
└────────┬─────────┘
         │
         ▼
┌──────────────────┐     Compensation: release_inventory
│reserve_inventory │  ◄─────────────────────────────────
└────────┬─────────┘
         │
         ▼
┌──────────────────┐     Compensation: refund_payment
│ process_payment  │  ◄─────────────────────────────────
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│   ship_order     │  Create shipping label and ship
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│send_confirmation │  Send order confirmation email
└──────────────────┘
```

### Demo Scenarios

The demo runs 5 scenarios to showcase different capabilities:

#### Scenario 1: Normal Successful Execution

```
SCENARIO 1: Normal Successful Execution
═══════════════════════════════════════════════════════════════════════
Starting workflow for order: ORD-abc12345

--- Executing Task: validate_order ---
✓ Order validated successfully

--- Executing Task: check_inventory ---
✓ Inventory available for all items

--- Executing Task: reserve_inventory ---
✓ Inventory reserved: RES-xyz789

--- Executing Task: process_payment ---
✓ Payment processed: TXN-payment123

--- Executing Task: ship_order ---
✓ Order shipped: SHIP-tracking456

--- Executing Task: send_confirmation ---
✓ Confirmation sent to customer@example.com

✓ SCENARIO 1 COMPLETE: Workflow finished successfully
```

#### Scenario 2: Automatic Retry on Transient Failure

```
SCENARIO 2: Automatic Retry on Transient Failure
═══════════════════════════════════════════════════════════════════════
Simulating transient failure during payment processing...

--- Attempt 1 ---
✗ Payment failed: Network timeout (transient)
  → Scheduling retry with backoff: 1s

--- Attempt 2 ---
✗ Payment failed: Service unavailable (transient)
  → Scheduling retry with backoff: 2s

--- Attempt 3 ---
✓ Payment succeeded on attempt 3

✓ SCENARIO 2 COMPLETE: Transient failure recovered via retry
```

#### Scenario 3: Worker Crash Recovery

```
SCENARIO 3: Worker Crash Recovery (Lease Expiration)
═══════════════════════════════════════════════════════════════════════
Simulating worker crash during payment processing...

Worker WORKER-CRASHED acquires lease
  lease_key: workflow-123:process_payment
  fence_token: 1
  expires_at: T0 + 30s

Worker WORKER-CRASHED starts processing...
💀 Worker WORKER-CRASHED crashes!

... time passes, lease expires ...

Recovery Engine detects expired lease
  → Force-releasing lease
  → Incrementing fence_token to 2

Worker WORKER-NEW acquires lease
  lease_key: workflow-123:process_payment
  fence_token: 2

Worker WORKER-NEW completes payment successfully

✓ SCENARIO 3 COMPLETE: Crashed worker recovered via lease expiration
```

#### Scenario 4: Duplicate Execution Prevention

```
SCENARIO 4: Duplicate Task Execution Prevention (Idempotency)
═══════════════════════════════════════════════════════════════════════
Testing idempotency with key: workflow-456:validate_order:1

--- First Execution Attempt ---
✓ Task executed, result stored
  idempotency_key: workflow-456:validate_order:1
  validatedAt: 2024-01-15T10:30:00Z

--- Second Execution Attempt (same key) ---
✓ Duplicate detected, returning stored result
  idempotency_key: workflow-456:validate_order:1
  validatedAt: 2024-01-15T10:30:00Z (same as first!)

✓ SCENARIO 4 COMPLETE: Duplicate execution prevented via idempotency key
```

#### Scenario 5: Compensation on Failure (Saga)

```
SCENARIO 5: Compensation on Failure (Saga Rollback)
═══════════════════════════════════════════════════════════════════════
Testing saga compensation when shipping fails...

--- Forward Execution ---
✓ validate_order completed
✓ check_inventory completed  
✓ reserve_inventory completed (reservation: RES-123)
✓ process_payment completed (transaction: TXN-456)
✗ ship_order FAILED: Shipping service unavailable

--- Compensation Phase ---
Triggering saga compensation...

↩ Compensating: process_payment
  → Refunding transaction TXN-456
  ✓ Refund processed

↩ Compensating: reserve_inventory
  → Releasing reservation RES-123
  ✓ Inventory released

✓ SCENARIO 5 COMPLETE: Failed workflow compensated successfully
```

## Project Structure

```
orchestrator-examples/
└── src/main/java/com/orchestrator/examples/
    └── order/
        ├── OrderWorkflowDemo.java      # Main demo runner
        ├── OrderProcessingWorkflow.java # Workflow definition
        └── OrderActivityHandlers.java   # Activity implementations
```

## Key Files

### OrderProcessingWorkflow.java

Defines the workflow structure:

```java
public static WorkflowDefinition createDefinition() {
    return WorkflowDefinition.builder()
        .namespace("ecommerce")
        .name("order-processing")
        .tasks(List.of(
            TaskDefinition.builder()
                .taskId("validate_order")
                .type(TaskType.ACTIVITY)
                .activityType("order.validate")
                .retryPolicy(RetryPolicy.defaultPolicy())
                .build(),
            TaskDefinition.builder()
                .taskId("process_payment")
                .type(TaskType.ACTIVITY)
                .activityType("payment.process")
                .compensationTaskId("refund_payment")  // Saga
                .build(),
            // ... more tasks
        ))
        .edges(Map.of(
            "validate_order", List.of("check_inventory"),
            "check_inventory", List.of("reserve_inventory"),
            // ... edges define execution order
        ))
        .build();
}
```

### OrderActivityHandlers.java

Implements the activity logic:

```java
public static JsonNode validateOrder(String idempotencyKey, JsonNode input) {
    // Validate order data
    // Uses idempotencyKey for any external calls
    return result;
}

public static JsonNode processPayment(String idempotencyKey, JsonNode input) {
    // Process payment via payment gateway
    // idempotencyKey prevents duplicate charges
    return result;
}

public static JsonNode refundPayment(String idempotencyKey, JsonNode input) {
    // Compensation: refund the payment
    return result;
}
```

## Running with Full Infrastructure

To run with the actual orchestrator API:

1. Start infrastructure:
   ```bash
   docker-compose up -d postgres kafka
   ```

2. Start the orchestrator:
   ```bash
   cd ../orchestrator-api
   mvn spring-boot:run
   ```

3. Register the workflow definition via API

4. Start workers that implement the activity handlers

5. Start workflows via the REST API

## Dependencies

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-core</artifactId>
</dependency>
```
