# orchestrator-api

REST API layer for the Workflow Orchestrator (Spring Boot).

## Overview

This module provides the HTTP API for interacting with the orchestrator:

- Workflow management (start, pause, resume, cancel)
- Task operations (poll, complete, fail, heartbeat)
- Admin operations (force complete, view status)

## Quick Start

### Start the API

```bash
# With Maven
mvn spring-boot:run

# Or with Java
java -jar target/orchestrator-api-*.jar
```

The API will be available at `http://localhost:8080`.

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

## API Endpoints

### Workflow APIs

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/workflows/{ns}/{name}/start` | POST | Start a new workflow instance |
| `/api/v1/workflows/{id}` | GET | Get workflow instance state |
| `/api/v1/workflows/{id}/events` | GET | Get workflow event history |
| `/api/v1/workflows/{id}/signal` | POST | Send signal to workflow |
| `/api/v1/workflows/{id}/pause` | POST | Pause workflow execution |
| `/api/v1/workflows/{id}/resume` | POST | Resume paused workflow |
| `/api/v1/workflows/{id}/cancel` | POST | Cancel workflow |
| `/api/v1/workflows/{id}/compensate` | POST | Trigger saga compensation |

### Task APIs (Worker)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/tasks/poll` | POST | Poll for available tasks |
| `/api/v1/tasks/{id}/complete` | POST | Complete task with result |
| `/api/v1/tasks/{id}/fail` | POST | Report task failure |
| `/api/v1/tasks/{id}/heartbeat` | POST | Renew task lease |

### Admin APIs

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/admin/tasks/{id}/force-complete` | POST | Force complete stuck task |
| `/api/v1/admin/workflows/{id}/force-fail` | POST | Force fail stuck workflow |
| `/api/v1/admin/workflows/stuck` | GET | List stuck workflows |

## Example Requests

### Start a Workflow

```bash
curl -X POST http://localhost:8080/api/v1/workflows/orders/order-fulfillment/start \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "order-123",
    "correlationId": "customer-456",
    "input": {
      "orderId": "order-123",
      "items": [
        {"sku": "ITEM-001", "quantity": 2, "price": 29.99}
      ],
      "customer": {
        "id": "cust-456",
        "email": "customer@example.com"
      },
      "total": 59.98
    }
  }'
```

Response:
```json
{
  "instanceId": "abc-123-def-456",
  "namespace": "orders",
  "workflowName": "order-fulfillment",
  "state": "RUNNING",
  "currentTaskId": "validate-order",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### Get Workflow Status

```bash
curl http://localhost:8080/api/v1/workflows/abc-123-def-456
```

### Poll for Tasks (Worker)

```bash
curl -X POST http://localhost:8080/api/v1/tasks/poll \
  -H "Content-Type: application/json" \
  -d '{
    "workerId": "worker-001",
    "activityTypes": ["order.validate", "payment.process"],
    "maxTasks": 1
  }'
```

Response:
```json
{
  "tasks": [
    {
      "executionId": "exec-789",
      "workflowInstanceId": "abc-123-def-456",
      "taskId": "validate-order",
      "idempotencyKey": "abc-123-def-456:validate-order:1",
      "fenceToken": 1,
      "leaseExpiresAt": "2024-01-15T10:31:00Z",
      "input": { "orderId": "order-123", ... }
    }
  ]
}
```

### Complete a Task (Worker)

```bash
curl -X POST http://localhost:8080/api/v1/tasks/exec-789/complete \
  -H "Content-Type: application/json" \
  -d '{
    "workerId": "worker-001",
    "fenceToken": 1,
    "output": {
      "valid": true,
      "validatedAt": "2024-01-15T10:30:30Z"
    }
  }'
```

### Send Heartbeat (Worker)

```bash
curl -X POST http://localhost:8080/api/v1/tasks/exec-789/heartbeat \
  -H "Content-Type: application/json" \
  -d '{
    "workerId": "worker-001"
  }'
```

## Configuration

`application.yml`:

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orchestrator
    username: orchestrator
    password: orchestrator
  
  flyway:
    enabled: true

orchestrator:
  lease:
    default-duration: 30s
    renewal-interval: 10s
  
  recovery:
    enabled: true
    lease-check-interval: 5s
  
  api:
    max-poll-tasks: 10
    poll-timeout: 30s
```

## Error Responses

All errors follow a consistent format:

```json
{
  "error": "NOT_FOUND",
  "message": "WorkflowInstance not found: abc-123",
  "timestamp": "2024-01-15T10:30:00Z",
  "path": "/api/v1/workflows/abc-123"
}
```

Common error codes:

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `NOT_FOUND` | 404 | Resource not found |
| `INVALID_STATE` | 409 | Invalid state transition |
| `DUPLICATE_KEY` | 409 | Idempotency violation |
| `LEASE_EXPIRED` | 409 | Task lease expired |
| `FENCE_TOKEN_MISMATCH` | 409 | Stale worker completion |

## Dependencies

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-api</artifactId>
</dependency>
```

This module depends on:
- `orchestrator-core`
- `orchestrator-engine`
- `orchestrator-recovery`
- Spring Boot Web
- Spring Boot Actuator
