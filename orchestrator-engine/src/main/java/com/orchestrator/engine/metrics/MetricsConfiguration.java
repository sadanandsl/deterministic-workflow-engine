package com.orchestrator.engine.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus metrics configuration for the workflow orchestrator.
 * 
 * Configures:
 * - Common tags for all metrics
 * - Histogram buckets for latency metrics
 * - Metric registry customization
 */
@Configuration
public class MetricsConfiguration {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
            .commonTags("application", "workflow-orchestrator");
    }

    @Bean
    public WorkflowMetrics workflowMetrics() {
        return new WorkflowMetrics();
    }
}
