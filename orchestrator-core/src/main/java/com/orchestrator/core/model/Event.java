package com.orchestrator.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of something that happened.
 * Append-only event log for audit and replay.
 * 
 * Primary Key: eventId
 * Index: (workflowInstanceId, sequenceNumber)
 * 
 * Invariants:
 * - sequenceNumber is contiguous within instance
 * - Events are never deleted or modified
 * - idempotencyKey prevents duplicate events
 */
public record Event(
    // Primary key
    UUID eventId,
    
    // Foreign key
    UUID workflowInstanceId,
    
    // Ordering
    long sequenceNumber,
    
    // Event data
    EventType type,
    Instant timestamp,
    JsonNode payload,
    
    // Causality
    UUID causedByEventId,
    String idempotencyKey,
    
    // Tracing
    String traceId,
    String spanId,
    
    // Actor (who/what caused this event)
    String actorType,
    String actorId
) {
    /**
     * Actor types for event attribution.
     */
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_WORKER = "WORKER";
    public static final String ACTOR_SCHEDULER = "SCHEDULER";
    public static final String ACTOR_RECOVERY = "RECOVERY";
    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_ADVISORY = "ADVISORY";

    /**
     * Create a new event.
     */
    public static Event create(
            UUID workflowInstanceId,
            long sequenceNumber,
            EventType type,
            JsonNode payload,
            String idempotencyKey,
            String traceId,
            String actorType,
            String actorId) {
        return new Event(
            UUID.randomUUID(),
            workflowInstanceId,
            sequenceNumber,
            type,
            Instant.now(),
            payload,
            null,
            idempotencyKey,
            traceId,
            null,
            actorType,
            actorId
        );
    }

    /**
     * Create a new event caused by another event.
     */
    public static Event createWithCause(
            UUID workflowInstanceId,
            long sequenceNumber,
            EventType type,
            JsonNode payload,
            UUID causedByEventId,
            String idempotencyKey,
            String traceId,
            String actorType,
            String actorId) {
        return new Event(
            UUID.randomUUID(),
            workflowInstanceId,
            sequenceNumber,
            type,
            Instant.now(),
            payload,
            causedByEventId,
            idempotencyKey,
            traceId,
            null,
            actorType,
            actorId
        );
    }

    /**
     * Check if this event is a workflow lifecycle event.
     */
    public boolean isWorkflowEvent() {
        return type.name().startsWith("WORKFLOW_");
    }

    /**
     * Check if this event is a task lifecycle event.
     */
    public boolean isTaskEvent() {
        return type.name().startsWith("TASK_");
    }

    /**
     * Check if this event is a compensation event.
     */
    public boolean isCompensationEvent() {
        return type.name().startsWith("COMPENSATION_");
    }
}
