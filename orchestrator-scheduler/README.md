# orchestrator-scheduler

Time-based scheduling for the Workflow Orchestrator.

## Overview

This module provides timer and scheduling capabilities:

- **Timer tasks**: Delay execution for a specified duration
- **Cron schedules**: Trigger workflows on a schedule
- **Workflow deadlines**: Track and enforce time limits

## Key Component

### TimerScheduler

Manages time-based events:

```java
public class TimerScheduler {
    
    // Schedule a timer for a workflow task
    void scheduleTimer(UUID workflowInstanceId, String taskId, 
                       Instant fireAt, TimerType type);
    
    // Cancel a scheduled timer
    void cancelTimer(UUID timerId);
    
    // Poll and fire due timers
    void fireDueTimers();
}
```

## Timer Types

| Type | Description | Use Case |
|------|-------------|----------|
| `DELAY` | Fire after duration | Wait 5 minutes before next step |
| `ABSOLUTE` | Fire at specific time | Run at midnight |
| `CRON` | Recurring schedule | Run every hour |

## Database Schema

```sql
CREATE TABLE scheduled_timers (
    timer_id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL,
    task_id VARCHAR(255),
    timer_type VARCHAR(50) NOT NULL,
    fire_at TIMESTAMP WITH TIME ZONE NOT NULL,
    fired BOOLEAN NOT NULL DEFAULT FALSE,
    fired_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_timer_fire ON scheduled_timers(fire_at) 
  WHERE fired = FALSE;
```

## Usage in Workflows

### Delay Task

```java
TaskDefinition.builder()
    .taskId("wait-for-approval")
    .type(TaskType.TIMER)
    .timerDuration(Duration.ofHours(24))  // Wait 24 hours
    .build()
```

### Wait for Signal with Timeout

```java
TaskDefinition.builder()
    .taskId("wait-for-payment")
    .type(TaskType.WAIT)
    .timeout(Duration.ofHours(1))  // Timeout after 1 hour
    .build()
```

## Configuration

```yaml
orchestrator:
  scheduler:
    enabled: true
    poll-interval: 5s
    batch-size: 100
```

## Dependencies

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-scheduler</artifactId>
</dependency>
```
