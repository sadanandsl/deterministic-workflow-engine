package com.orchestrator.engine.metrics;

import com.orchestrator.core.model.TaskState;
import com.orchestrator.core.model.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Periodically syncs gauge metrics from database state.
 * This ensures metric accuracy after restarts and across cluster nodes.
 */
@Component
public class MetricsSyncService {

    private static final Logger log = LoggerFactory.getLogger(MetricsSyncService.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowMetrics workflowMetrics;

    public MetricsSyncService(JdbcTemplate jdbcTemplate, WorkflowMetrics workflowMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.workflowMetrics = workflowMetrics;
    }

    /**
     * Sync workflow state gauges every 30 seconds.
     */
    @Scheduled(fixedRate = 30000, initialDelay = 5000)
    public void syncWorkflowStateGauges() {
        try {
            String sql = """
                SELECT state, COUNT(*) as count 
                FROM workflow_instances 
                GROUP BY state
                """;
            
            jdbcTemplate.query(sql, rs -> {
                String state = rs.getString("state");
                int count = rs.getInt("count");
                workflowMetrics.syncWorkflowStateGauge(state, count);
            });
            
            log.debug("Synced workflow state gauges");
        } catch (Exception e) {
            log.warn("Failed to sync workflow state gauges: {}", e.getMessage());
        }
    }

    /**
     * Sync task state gauges every 30 seconds.
     */
    @Scheduled(fixedRate = 30000, initialDelay = 10000)
    public void syncTaskStateGauges() {
        try {
            String sql = """
                SELECT state, COUNT(*) as count 
                FROM task_executions 
                GROUP BY state
                """;
            
            jdbcTemplate.query(sql, rs -> {
                String state = rs.getString("state");
                int count = rs.getInt("count");
                workflowMetrics.syncTaskStateGauge(state, count);
            });
            
            log.debug("Synced task state gauges");
        } catch (Exception e) {
            log.warn("Failed to sync task state gauges: {}", e.getMessage());
        }
    }

    /**
     * Calculate and expose aggregate metrics every minute.
     */
    @Scheduled(fixedRate = 60000, initialDelay = 15000)
    public void calculateAggregateMetrics() {
        try {
            // Calculate average task latency
            String latencySql = """
                SELECT 
                    AVG(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000) as avg_latency_ms,
                    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000) as p50_latency_ms,
                    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000) as p95_latency_ms,
                    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000) as p99_latency_ms
                FROM task_executions 
                WHERE completed_at IS NOT NULL 
                  AND started_at IS NOT NULL
                  AND completed_at > NOW() - INTERVAL '1 hour'
                """;
            
            jdbcTemplate.query(latencySql, rs -> {
                // Log latency stats for debugging
                log.debug("Task latency stats - avg: {}ms, p50: {}ms, p95: {}ms, p99: {}ms",
                    rs.getDouble("avg_latency_ms"),
                    rs.getDouble("p50_latency_ms"),
                    rs.getDouble("p95_latency_ms"),
                    rs.getDouble("p99_latency_ms"));
            });
            
        } catch (Exception e) {
            log.warn("Failed to calculate aggregate metrics: {}", e.getMessage());
        }
    }
}
