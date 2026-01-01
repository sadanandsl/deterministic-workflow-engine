package com.orchestrator.core.repository;

import com.orchestrator.core.model.Event;
import com.orchestrator.core.model.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Event persistence.
 * Events are append-only and immutable.
 */
public interface EventRepository {

    /**
     * Append a new event to the log.
     * 
     * @param event The event to append
     * @throws DuplicateEventException if idempotency key already exists
     */
    void append(Event event);

    /**
     * Append multiple events atomically.
     * 
     * @param events The events to append
     * @throws DuplicateEventException if any idempotency key already exists
     */
    void appendAll(List<Event> events);

    /**
     * Find an event by ID.
     * 
     * @param eventId The event ID
     * @return The event if found
     */
    Optional<Event> findById(UUID eventId);

    /**
     * Find an event by idempotency key.
     * 
     * @param idempotencyKey The idempotency key
     * @return The event if found
     */
    Optional<Event> findByIdempotencyKey(String idempotencyKey);

    /**
     * Get all events for a workflow instance in order.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @return All events ordered by sequence number
     */
    List<Event> findByWorkflowInstance(UUID workflowInstanceId);

    /**
     * Get events for a workflow instance starting from a sequence number.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param fromSequence Start sequence number (inclusive)
     * @return Events from the given sequence number
     */
    List<Event> findByWorkflowInstanceFrom(UUID workflowInstanceId, long fromSequence);

    /**
     * Get events for a workflow instance of specific types.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @param types Event types to filter by
     * @return Matching events ordered by sequence number
     */
    List<Event> findByWorkflowInstanceAndTypes(UUID workflowInstanceId, List<EventType> types);

    /**
     * Get the next sequence number for a workflow instance.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @return Next sequence number (0 if no events exist)
     */
    long getNextSequenceNumber(UUID workflowInstanceId);

    /**
     * Get the latest event for a workflow instance.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @return The latest event if any exists
     */
    Optional<Event> findLatestByWorkflowInstance(UUID workflowInstanceId);

    /**
     * Get events within a time range for analytics.
     * 
     * @param from Start time (inclusive)
     * @param to End time (exclusive)
     * @param types Event types to filter by (empty = all)
     * @param limit Maximum number of results
     * @return Events within the time range
     */
    List<Event> findByTimeRange(Instant from, Instant to, List<EventType> types, int limit);

    /**
     * Count events by type for a workflow instance.
     * 
     * @param workflowInstanceId The workflow instance ID
     * @return Count per event type
     */
    java.util.Map<EventType, Long> countByType(UUID workflowInstanceId);
}
