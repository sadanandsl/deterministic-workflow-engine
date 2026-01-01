package com.orchestrator.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowStateTest {

    @Test
    void isTerminal_shouldIdentifyTerminalStates() {
        assertTrue(WorkflowState.COMPLETED.isTerminal());
        assertTrue(WorkflowState.FAILED.isTerminal());
        assertTrue(WorkflowState.COMPENSATED.isTerminal());
        assertTrue(WorkflowState.COMPENSATION_FAILED.isTerminal());
        
        assertFalse(WorkflowState.CREATED.isTerminal());
        assertFalse(WorkflowState.RUNNING.isTerminal());
        assertFalse(WorkflowState.PAUSED.isTerminal());
        assertFalse(WorkflowState.COMPLETING.isTerminal());
        assertFalse(WorkflowState.FAILING.isTerminal());
        assertFalse(WorkflowState.COMPENSATING.isTerminal());
    }

    @Test
    void allowsExecution_shouldIdentifyExecutionStates() {
        assertTrue(WorkflowState.RUNNING.allowsExecution());
        assertTrue(WorkflowState.COMPENSATING.allowsExecution());
        
        assertFalse(WorkflowState.CREATED.allowsExecution());
        assertFalse(WorkflowState.PAUSED.allowsExecution());
        assertFalse(WorkflowState.COMPLETED.allowsExecution());
        assertFalse(WorkflowState.FAILED.allowsExecution());
    }

    @Test
    void canTransitionTo_fromCreated_shouldOnlyAllowRunning() {
        assertTrue(WorkflowState.CREATED.canTransitionTo(WorkflowState.RUNNING));
        
        assertFalse(WorkflowState.CREATED.canTransitionTo(WorkflowState.COMPLETED));
        assertFalse(WorkflowState.CREATED.canTransitionTo(WorkflowState.FAILED));
        assertFalse(WorkflowState.CREATED.canTransitionTo(WorkflowState.PAUSED));
    }

    @Test
    void canTransitionTo_fromRunning_shouldAllowCompletingFailingPaused() {
        assertTrue(WorkflowState.RUNNING.canTransitionTo(WorkflowState.COMPLETING));
        assertTrue(WorkflowState.RUNNING.canTransitionTo(WorkflowState.FAILING));
        assertTrue(WorkflowState.RUNNING.canTransitionTo(WorkflowState.PAUSED));
        
        assertFalse(WorkflowState.RUNNING.canTransitionTo(WorkflowState.COMPLETED));
        assertFalse(WorkflowState.RUNNING.canTransitionTo(WorkflowState.CREATED));
    }

    @Test
    void canTransitionTo_fromPaused_shouldAllowRunningOrFailing() {
        assertTrue(WorkflowState.PAUSED.canTransitionTo(WorkflowState.RUNNING));
        assertTrue(WorkflowState.PAUSED.canTransitionTo(WorkflowState.FAILING));
        
        assertFalse(WorkflowState.PAUSED.canTransitionTo(WorkflowState.COMPLETED));
    }

    @Test
    void canTransitionTo_fromFailing_shouldAllowRetryCompensateOrFail() {
        assertTrue(WorkflowState.FAILING.canTransitionTo(WorkflowState.RUNNING)); // retry
        assertTrue(WorkflowState.FAILING.canTransitionTo(WorkflowState.COMPENSATING));
        assertTrue(WorkflowState.FAILING.canTransitionTo(WorkflowState.FAILED));
        
        assertFalse(WorkflowState.FAILING.canTransitionTo(WorkflowState.COMPLETED));
    }

    @Test
    void canTransitionTo_fromFailed_shouldAllowManualRecovery() {
        assertTrue(WorkflowState.FAILED.canTransitionTo(WorkflowState.COMPENSATING));
        assertTrue(WorkflowState.FAILED.canTransitionTo(WorkflowState.RUNNING)); // manual retry
        
        assertFalse(WorkflowState.FAILED.canTransitionTo(WorkflowState.COMPLETED));
    }

    @Test
    void canTransitionTo_fromTerminalStates_shouldNotAllowAny() {
        assertFalse(WorkflowState.COMPLETED.canTransitionTo(WorkflowState.RUNNING));
        assertFalse(WorkflowState.COMPLETED.canTransitionTo(WorkflowState.FAILED));
        
        assertFalse(WorkflowState.COMPENSATED.canTransitionTo(WorkflowState.RUNNING));
        assertFalse(WorkflowState.COMPENSATION_FAILED.canTransitionTo(WorkflowState.RUNNING));
    }
}
