# orchestrator-advisory

LLM-powered advisory layer for the Workflow Orchestrator.

## Overview

This module provides AI-assisted analysis and recommendations for workflow operations. 

**⚠️ CRITICAL: This service is strictly READ-ONLY.** It observes workflow state and provides suggestions, but cannot directly mutate any state. All recommendations must be reviewed and applied by humans or approved automation through the standard API.

## Features

- **Failure Analysis**: Analyze failed workflows and suggest recovery options
- **Pattern Detection**: Identify failure patterns across workflows
- **State Explanation**: Generate human-readable workflow state descriptions
- **Retry Optimization**: Suggest optimal retry policies based on historical data

## Design Principles

### Read-Only Access

The advisory service can only:
- ✅ Read workflow instances
- ✅ Read task executions
- ✅ Read event history
- ✅ Analyze patterns
- ✅ Generate recommendations

It cannot:
- ❌ Start/stop workflows
- ❌ Complete/fail tasks
- ❌ Modify state
- ❌ Execute compensations

### Recommendations Are Suggestions

All advisory output is clearly marked as recommendations that require human approval:

```java
record AdvisoryRecommendation(
    String recommendationType,  // RETRY, COMPENSATE, SKIP, MANUAL_INTERVENTION
    String targetType,          // TASK, WORKFLOW
    String targetId,
    String reasoning,           // Why this recommendation
    double confidence,          // 0.0 to 1.0
    JsonNode suggestedAction    // What action to take
) {}
```

## Interface

```java
public interface AdvisoryService {

    // Analyze a failed workflow and suggest recovery options
    List<AdvisoryRecommendation> analyzeFailure(UUID workflowInstanceId);

    // Analyze failure patterns across multiple workflows
    FailureAnalysisReport analyzeFailurePatterns(String namespace, int timeRangeHours);

    // Generate a human-readable explanation of a workflow's state
    String explainWorkflowState(UUID workflowInstanceId);

    // Suggest optimal retry policy based on historical data
    RetryPolicySuggestion suggestRetryPolicy(String activityType);
}
```

## Example Usage

### Failure Analysis

```java
AdvisoryService advisory = // ...

// Get recommendations for a failed workflow
List<AdvisoryRecommendation> recommendations = 
    advisory.analyzeFailure(failedWorkflowId);

for (AdvisoryRecommendation rec : recommendations) {
    log.info("Recommendation: {} (confidence: {})",
        rec.recommendationType(), rec.confidence());
    log.info("Reasoning: {}", rec.reasoning());
    
    // Human reviews and decides whether to apply
    if (operatorApproves(rec)) {
        applyRecommendation(rec);  // Through standard API
    }
}
```

### State Explanation

```java
// Get human-readable explanation
String explanation = advisory.explainWorkflowState(workflowId);

// Example output:
// "Order workflow ORD-123 is currently PAUSED waiting for human approval.
//  The order total ($1,500) exceeds the automatic approval threshold ($1,000).
//  3 of 7 tasks have completed successfully. No errors detected."
```

### Retry Policy Optimization

```java
RetryPolicySuggestion suggestion = 
    advisory.suggestRetryPolicy("payment.process");

// Example output:
// "Based on 1,247 executions of payment.process:
//  - Success rate: 94.2%
//  - Average retries needed: 1.3
//  - Most failures are transient (network timeout)
//  
//  Suggested policy:
//  - maxAttempts: 4 (currently 3)
//  - initialBackoff: 2s (currently 1s)
//  - Add 'PaymentGatewayTimeout' to retryableErrors"
```

## Configuration

```yaml
orchestrator:
  advisory:
    enabled: true
    # LLM configuration (if using external model)
    llm:
      provider: openai  # or local, azure, etc.
      model: gpt-4
      api-key: ${LLM_API_KEY}
    # Analysis settings
    analysis:
      max-events-per-workflow: 1000
      pattern-detection-window-hours: 168  # 7 days
```

## Dependencies

```xml
<dependency>
    <groupId>com.orchestrator</groupId>
    <artifactId>orchestrator-advisory</artifactId>
</dependency>
```

This module depends on:
- `orchestrator-core` (read-only access to models)
- LLM client library (optional, for AI-powered analysis)
