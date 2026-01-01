package com.orchestrator.api.rest;

import com.orchestrator.engine.history.ExecutionHistoryService;
import com.orchestrator.engine.history.ExecutionHistoryService.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * REST API for workflow execution history and replay.
 */
@RestController
@RequestMapping("/api/v1/workflows/{instanceId}/history")
public class HistoryController {

    private final ExecutionHistoryService historyService;

    public HistoryController(ExecutionHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * Get full execution history.
     */
    @GetMapping
    public ResponseEntity<ExecutionHistory> getHistory(@PathVariable UUID instanceId) {
        ExecutionHistory history = historyService.getHistory(instanceId);
        return ResponseEntity.ok(history);
    }

    /**
     * Get execution timeline.
     */
    @GetMapping("/timeline")
    public ResponseEntity<ExecutionHistory> getTimeline(@PathVariable UUID instanceId) {
        ExecutionHistory history = historyService.getHistory(instanceId);
        // Return only timeline portion
        return ResponseEntity.ok(ExecutionHistory.builder()
            .workflowInstanceId(instanceId)
            .namespace(history.namespace())
            .workflowName(history.workflowName())
            .currentState(history.currentState())
            .timeline(history.timeline())
            .statistics(history.statistics())
            .build());
    }

    /**
     * Get task-level history.
     */
    @GetMapping("/tasks")
    public ResponseEntity<ExecutionHistory> getTaskHistory(@PathVariable UUID instanceId) {
        ExecutionHistory history = historyService.getHistory(instanceId);
        // Return only task history portion
        return ResponseEntity.ok(ExecutionHistory.builder()
            .workflowInstanceId(instanceId)
            .namespace(history.namespace())
            .workflowName(history.workflowName())
            .currentState(history.currentState())
            .taskHistory(history.taskHistory())
            .statistics(history.statistics())
            .build());
    }

    /**
     * Replay state to a specific sequence number.
     */
    @GetMapping("/replay")
    public ResponseEntity<ReplayResult> replay(
            @PathVariable UUID instanceId,
            @RequestParam(required = false) Long sequence,
            @RequestParam(required = false) Instant timestamp) {
        
        ReplayResult result;
        if (sequence != null) {
            result = historyService.replayToSequence(instanceId, sequence);
        } else if (timestamp != null) {
            result = historyService.replayToTimestamp(instanceId, timestamp);
        } else {
            // Replay to current state
            ExecutionHistory history = historyService.getHistory(instanceId);
            long lastSeq = history.events().isEmpty() ? 0 
                : history.events().get(history.events().size() - 1).sequenceNumber();
            result = historyService.replayToSequence(instanceId, lastSeq);
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get events in a range.
     */
    @GetMapping("/events")
    public ResponseEntity<?> getEvents(
            @PathVariable UUID instanceId,
            @RequestParam(defaultValue = "0") long from,
            @RequestParam(defaultValue = "999999") long to) {
        
        return ResponseEntity.ok(historyService.getEventRange(instanceId, from, to));
    }
}
