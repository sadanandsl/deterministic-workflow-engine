package com.orchestrator.advisory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * LLM Advisory Service interface.
 * 
 * CRITICAL: This service is READ-ONLY. It can observe workflow state
 * and provide recommendations, but CANNOT mutate any state directly.
 * 
 * All recommendations must be applied through the standard API
 * by a human operator or approved automation.
 */
public interface AdvisoryService {

    /**
     * Analyze a failed workflow and suggest recovery options.
     * 
     * @param workflowInstanceId The failed workflow instance
     * @return List of recommended actions (NOT automatically applied)
     */
    List<AdvisoryRecommendation> analyzeFailure(UUID workflowInstanceId);

    /**
     * Analyze failure patterns across multiple workflows.
     * 
     * @param namespace The namespace to analyze
     * @param timeRangeHours How far back to look
     * @return Analysis report with patterns and recommendations
     */
    FailureAnalysisReport analyzeFailurePatterns(String namespace, int timeRangeHours);

    /**
     * Generate a human-readable explanation of a workflow's state.
     * 
     * @param workflowInstanceId The workflow instance
     * @return Human-readable explanation
     */
    String explainWorkflowState(UUID workflowInstanceId);

    /**
     * Suggest optimal retry policy based on historical data.
     * 
     * @param activityType The activity type
     * @return Suggested retry policy configuration
     */
    RetryPolicySuggestion suggestRetryPolicy(String activityType);

    /**
     * A recommendation from the advisory service.
     * This is a SUGGESTION ONLY - it must be reviewed and applied by humans or approved automation.
     */
    record AdvisoryRecommendation(
        String recommendationType,  // RETRY, COMPENSATE, SKIP, MANUAL_INTERVENTION
        String targetType,          // TASK, WORKFLOW
        String targetId,
        String reasoning,           // Why this recommendation
        double confidence,          // 0.0 to 1.0
        JsonNode suggestedParams,   // Parameters for the action
        List<String> risks,         // Potential risks
        boolean requiresHumanApproval
    ) {}

    /**
     * Report on failure patterns.
     */
    record FailureAnalysisReport(
        String namespace,
        int analyzedWorkflows,
        int failedWorkflows,
        List<FailurePattern> patterns,
        List<String> recommendations,
        String summary
    ) {}

    /**
     * A detected failure pattern.
     */
    record FailurePattern(
        String patternType,
        String description,
        int occurrenceCount,
        List<String> affectedActivityTypes,
        String suggestedMitigation
    ) {}

    /**
     * Suggested retry policy.
     */
    record RetryPolicySuggestion(
        String activityType,
        int suggestedMaxAttempts,
        long suggestedInitialBackoffMs,
        double suggestedBackoffMultiplier,
        String reasoning,
        double confidence
    ) {}
}
