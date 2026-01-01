package com.orchestrator.core.model;

/**
 * Types of events that can occur in the workflow system.
 * Events are immutable facts recorded in the event log.
 */
public enum EventType {
    // Workflow lifecycle events
    WORKFLOW_CREATED,
    WORKFLOW_STARTED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    WORKFLOW_PAUSED,
    WORKFLOW_RESUMED,
    WORKFLOW_CANCELLED,

    // Task lifecycle events
    TASK_SCHEDULED,
    TASK_STARTED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_TIMED_OUT,
    TASK_RETRYING,
    TASK_CANCELLED,
    TASK_SKIPPED,

    // Compensation events
    COMPENSATION_TRIGGERED,
    COMPENSATION_STARTED,
    COMPENSATION_COMPLETED,
    COMPENSATION_FAILED,

    // Timer events
    TIMER_SCHEDULED,
    TIMER_FIRED,
    TIMER_CANCELLED,

    // Signal events
    SIGNAL_RECEIVED,
    SIGNAL_SENT,

    // Human interaction events
    HUMAN_TASK_ASSIGNED,
    HUMAN_TASK_COMPLETED,
    HUMAN_OVERRIDE_APPLIED,

    // System events
    LEASE_ACQUIRED,
    LEASE_RENEWED,
    LEASE_RELEASED,
    LEASE_EXPIRED,

    // Recovery events
    RECOVERY_STARTED,
    RECOVERY_COMPLETED,
    STATE_SNAPSHOT_CREATED,

    // Advisory events (LLM layer)
    ADVISORY_RECOMMENDATION_GENERATED,
    ADVISORY_RECOMMENDATION_APPLIED,
    ADVISORY_RECOMMENDATION_REJECTED
}
