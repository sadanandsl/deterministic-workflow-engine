# orchestrator-engine

The execution engine and coordinators for the Workflow Orchestrator.

## Overview

This module contains the core orchestration logic:
- **WorkflowCoordinator**: Manages workflow lifecycle and state transitions
- **TaskCoordinator**: Handles task acquisition, completion, and retry
- **Persistence**: PostgreSQL-backed repository implementations

## Package Structure

```
com.orchestrator.engine/
├── coordinator/     # WorkflowCoordinator, TaskCoordinator
├── persistence/     # Repository implementations
└── service/         # Service interfaces
```

## Key Components

### WorkflowCoordinator

Central control plane for workflow orchestration:

```java
public class WorkflowCoordinator implements WorkflowService {
    
    // Register a new workflow definition
    WorkflowDefinition registerWorkflow(WorkflowDefinition definition);
    
    // Start a workflow (idempotent via runId)
    WorkflowInstance startWorkflow(StartWorkflowRequest request);
    
    // Get workflow state
    WorkflowInstance getWorkflow(UUID instanceId);
    
    // Control operations
    void pauseWorkflow(UUID instanceId);
    void resumeWorkflow(UUID instanceId);
    void cancelWorkflow(UUID instanceId);
    
    // Saga compensation
    void triggerCompensation(UUID instanceId, String fromTaskId);
}
```

### TaskCoordinator

Manages task execution lifecycle:

```java
public class TaskCoordinator implements TaskService {
    
    // Worker acquires a task (gets lease)
    Optional<TaskExecution> acquireTask(UUID executionId, UUID workerId);
    
    // Complete task with result (validates fence token)
    void completeTask(UUID executionId, UUID workerId, long fenceToken, JsonNode output);
    
    // Report task failure
    void failTask(UUID executionId, UUID workerId, long fenceToken, String error);
    
    // Renew lease (heartbeat)
    boolean renewLease(UUID executionId, UUID workerId);
    
    // Retry a failed task
    void retryTask(UUID workflowInstanceId, String taskId, JsonNode input);
}
```

## Database Schema

The engine uses PostgreSQL with the following key tables:

### workflow_instances
```sql
CREATE TABLE workflow_instances (
    instance_id UUID PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    run_id VARCHAR(512) NOT NULL,
    state VARCHAR(50) NOT NULL,
    sequence_number BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking
    CONSTRAINT uk_idempotency UNIQUE (namespace, workflow_name, run_id)
);
```

### task_executions
```sql
CREATE TABLE task_executions (
    execution_id UUID PRIMARY KEY,
    workflow_instance_id UUID REFERENCES workflow_instances,
    task_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    state VARCHAR(50) NOT NULL,
    lease_holder UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    fence_token BIGINT NOT NULL DEFAULT 0
);
```

### execution_leases
```sql
CREATE TABLE execution_leases (
    lease_key VARCHAR(512) PRIMARY KEY,
    holder_id UUID,
    expires_at TIMESTAMP WITH TIME ZONE,
    fence_token BIGINT NOT NULL DEFAULT 1
);
```

### events
```sql
CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    workflow_instance_id UUID REFERENCES workflow_instances,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL UNIQUE
);
```

## Migrations

Database migrations are managed by Flyway and located in:
```
src/main/resources/db/migration/
├── V1__initial_schema.sql
```

## Configuration

Key configuration properties:

```yaml
orchestrator:
  lease:
    default-duration: 30s
    renewal-interval: 10s
    
  datasource:
    url: jdbc:postgresql://localhost:5432/orchestrator
    username: orchestrator
    password: orchestrator
```

## Usage

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-engine</artifactId>
</dependency>
```

## Testing

```bash
# Unit tests
mvn test -pl orchestrator-engine

# Integration tests (requires Docker)
mvn verify -pl orchestrator-engine -Pintegration
```
