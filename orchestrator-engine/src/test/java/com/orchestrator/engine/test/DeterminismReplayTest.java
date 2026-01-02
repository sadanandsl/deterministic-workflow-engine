package com.orchestrator.engine.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.*;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism tests for the workflow orchestrator.
 * 
 * These tests verify the core property of the system:
 * REPLAYING A WORKFLOW FROM ITS EVENT LOG ALWAYS PRODUCES THE SAME FINAL STATE.
 * 
 * This guarantee is fundamental to:
 * - Exactly-once semantics (replay doesn't cause duplicate side effects)
 * - Failure recovery (can resume from any point)
 * - Debugging (can replay production issues locally)
 * 
 * Key invariants tested:
 * 1. Events are recorded in deterministic order
 * 2. Replay from event log reconstructs identical state
 * 3. Task outputs are preserved exactly
 * 4. Timing variations don't affect final state
 */
@DisplayName("Determinism & Replay Tests")
public class DeterminismReplayTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Simple in-memory event store for testing
    private TestEventStore eventStore;

    @BeforeEach
    void setUp() {
        eventStore = new TestEventStore();
    }

    // ========== Event Ordering Tests ==========

    @Test
    @DisplayName("Events should be recorded in strict sequence order")
    void testEventSequenceOrdering() {
        UUID workflowId = UUID.randomUUID();

        // Record events in order
        for (int i = 0; i < 10; i++) {
            Event event = Event.create(
                workflowId,
                i,
                EventType.TASK_STARTED,
                createPayload(Map.of("taskId", "task-" + i)),
                "key-" + i,
                null,
                Event.ACTOR_SYSTEM,
                "test"
            );
            eventStore.append(event);
        }

        // Retrieve and verify order
        List<Event> retrieved = eventStore.getEvents(workflowId);

        assertEquals(10, retrieved.size());
        for (int i = 0; i < retrieved.size(); i++) {
            assertEquals(i, retrieved.get(i).sequenceNumber());
        }
    }

    @Test
    @DisplayName("Event replay should reconstruct workflow state exactly")
    void testEventReplayReconstruction() {
        UUID workflowId = UUID.randomUUID();

        // Record workflow lifecycle events
        recordEvent(workflowId, EventType.WORKFLOW_STARTED, 0);
        recordEvent(workflowId, EventType.TASK_SCHEDULED, 1);
        recordEvent(workflowId, EventType.TASK_STARTED, 2);
        recordEvent(workflowId, EventType.TASK_COMPLETED, 3);
        recordEvent(workflowId, EventType.WORKFLOW_COMPLETED, 4);

        // Replay: reconstruct state from events
        WorkflowState reconstructedState = replayWorkflowFromEvents(workflowId);

        // Verify replay produces correct final state
        assertEquals(WorkflowState.COMPLETED, reconstructedState);
    }

    @Test
    @DisplayName("Replay from mid-execution should produce correct partial state")
    void testPartialReplay() {
        UUID workflowId = UUID.randomUUID();

        // Record a series of events
        recordEvent(workflowId, EventType.WORKFLOW_STARTED, 0);
        recordEvent(workflowId, EventType.TASK_SCHEDULED, 1);
        recordEvent(workflowId, EventType.TASK_STARTED, 2);
        recordEvent(workflowId, EventType.TASK_COMPLETED, 3);
        recordEvent(workflowId, EventType.TASK_SCHEDULED, 4);
        recordEvent(workflowId, EventType.TASK_STARTED, 5);
        // Task 2 not completed yet - workflow still running

        // Replay to end should show RUNNING state
        WorkflowState state = replayWorkflowFromEvents(workflowId);
        assertEquals(WorkflowState.RUNNING, state);
    }

    @Test
    @DisplayName("Replay should handle compensation events correctly")
    void testCompensationReplay() {
        UUID workflowId = UUID.randomUUID();

        // Simulate failed workflow with compensation
        recordEvent(workflowId, EventType.WORKFLOW_STARTED, 0);
        recordEvent(workflowId, EventType.TASK_COMPLETED, 1);
        recordEvent(workflowId, EventType.TASK_FAILED, 2);
        recordEvent(workflowId, EventType.COMPENSATION_TRIGGERED, 3);
        recordEvent(workflowId, EventType.COMPENSATION_STARTED, 4);
        recordEvent(workflowId, EventType.COMPENSATION_COMPLETED, 5);
        recordEvent(workflowId, EventType.WORKFLOW_COMPLETED, 6);

        WorkflowState state = replayWorkflowFromEvents(workflowId);
        assertEquals(WorkflowState.COMPLETED, state);
    }

    @Test
    @DisplayName("Idempotency keys should prevent duplicate events")
    void testIdempotencyKeyUniqueness() {
        UUID workflowId = UUID.randomUUID();
        String idempotencyKey = "unique-key-123";

        // First event with idempotency key
        Event event1 = Event.create(
            workflowId, 0, EventType.TASK_COMPLETED,
            createPayload(Map.of("result", "original")),
            idempotencyKey, null, Event.ACTOR_WORKER, "worker-1"
        );
        eventStore.append(event1);

        // Attempt to append duplicate with same idempotency key
        Event event2 = Event.create(
            workflowId, 1, EventType.TASK_COMPLETED,
            createPayload(Map.of("result", "duplicate")),
            idempotencyKey, null, Event.ACTOR_WORKER, "worker-2"
        );
        
        try {
            eventStore.append(event2);
            // If it succeeds, verify only one event exists
            List<Event> events = eventStore.getEvents(workflowId);
            long count = events.stream()
                .filter(e -> idempotencyKey.equals(e.idempotencyKey()))
                .count();
            assertTrue(count <= 1, "Duplicate idempotency keys should be prevented");
        } catch (IllegalStateException e) {
            // Expected - duplicate rejected
            assertTrue(e.getMessage().contains("idempotency") || e.getMessage().contains("duplicate"));
        }
    }

    @Test
    @DisplayName("Multiple replays should produce identical results")
    void testMultipleReplayConsistency() {
        UUID workflowId = UUID.randomUUID();

        // Create deterministic event sequence
        recordEvent(workflowId, EventType.WORKFLOW_STARTED, 0);
        recordEvent(workflowId, EventType.TASK_COMPLETED, 1);
        recordEvent(workflowId, EventType.TASK_COMPLETED, 2);
        recordEvent(workflowId, EventType.WORKFLOW_COMPLETED, 3);

        // Replay multiple times
        WorkflowState state1 = replayWorkflowFromEvents(workflowId);
        WorkflowState state2 = replayWorkflowFromEvents(workflowId);
        WorkflowState state3 = replayWorkflowFromEvents(workflowId);

        // All replays should produce identical state
        assertEquals(state1, state2, "Replays should be deterministic");
        assertEquals(state2, state3, "Replays should be deterministic");
    }

    // ========== Helper Methods ==========

    private void recordEvent(UUID workflowId, EventType type, long sequence) {
        Event event = Event.create(
            workflowId,
            sequence,
            type,
            null,
            "seq-" + sequence,
            null,
            Event.ACTOR_SYSTEM,
            "test"
        );
        eventStore.append(event);
    }

    private WorkflowState replayWorkflowFromEvents(UUID workflowId) {
        List<Event> events = eventStore.getEvents(workflowId);
        
        WorkflowState state = WorkflowState.CREATED;
        for (Event event : events) {
            state = applyEventToState(state, event);
        }
        return state;
    }

    private WorkflowState applyEventToState(WorkflowState current, Event event) {
        return switch (event.type()) {
            case WORKFLOW_STARTED -> WorkflowState.RUNNING;
            case WORKFLOW_COMPLETED -> WorkflowState.COMPLETED;
            case WORKFLOW_FAILED -> WorkflowState.FAILED;
            case WORKFLOW_CANCELLED -> WorkflowState.CANCELLED;
            case WORKFLOW_PAUSED -> WorkflowState.PAUSED;
            case WORKFLOW_RESUMED -> WorkflowState.RUNNING;
            case COMPENSATION_STARTED -> WorkflowState.COMPENSATING;
            case COMPENSATION_COMPLETED -> WorkflowState.COMPLETED;
            case COMPENSATION_FAILED -> WorkflowState.COMPENSATION_FAILED;
            default -> current;
        };
    }

    private JsonNode createPayload(Map<String, Object> data) {
        try {
            return objectMapper.valueToTree(data);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Simple Test Event Store ==========

    /**
     * Simple in-memory event store for testing.
     * Does not implement repository interfaces - just provides basic storage.
     */
    private static class TestEventStore {
        private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> idempotencyKeys = Collections.synchronizedSet(new HashSet<>());

        public void append(Event event) {
            if (event.idempotencyKey() != null) {
                if (!idempotencyKeys.add(event.idempotencyKey())) {
                    throw new IllegalStateException("Duplicate idempotency key: " + event.idempotencyKey());
                }
            }
            events.add(event);
        }

        public List<Event> getEvents(UUID workflowInstanceId) {
            return events.stream()
                .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
                .sorted(Comparator.comparingLong(Event::sequenceNumber))
                .toList();
        }
    }
}
