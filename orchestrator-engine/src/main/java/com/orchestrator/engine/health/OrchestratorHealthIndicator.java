package com.orchestrator.engine.health;

import com.orchestrator.core.repository.LeaseRepository;
import com.orchestrator.core.repository.WorkflowInstanceRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom health indicator for the orchestrator.
 * Reports health status based on:
 * - Database connectivity
 * - Workflow processing state
 * - Lease health
 */
@Component
public class OrchestratorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowInstanceRepository workflowRepository;
    private final LeaseRepository leaseRepository;

    public OrchestratorHealthIndicator(
            JdbcTemplate jdbcTemplate,
            WorkflowInstanceRepository workflowRepository,
            LeaseRepository leaseRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.workflowRepository = workflowRepository;
        this.leaseRepository = leaseRepository;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        
        try {
            // Check database connectivity
            boolean dbHealthy = checkDatabase(details);
            if (!dbHealthy) {
                return Health.down()
                    .withDetails(details)
                    .build();
            }

            // Check workflow processing health
            checkWorkflowHealth(details);
            
            // Check lease health
            checkLeaseHealth(details);

            return Health.up()
                .withDetails(details)
                .build();
                
        } catch (Exception e) {
            return Health.down()
                .withException(e)
                .withDetails(details)
                .build();
        }
    }

    private boolean checkDatabase(Map<String, Object> details) {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            details.put("database", "connected");
            details.put("databaseCheck", result == 1 ? "OK" : "FAILED");
            return result != null && result == 1;
        } catch (Exception e) {
            details.put("database", "disconnected");
            details.put("databaseError", e.getMessage());
            return false;
        }
    }

    private void checkWorkflowHealth(Map<String, Object> details) {
        try {
            // Count workflows by state
            Map<String, Integer> workflowCounts = new HashMap<>();
            
            String sql = """
                SELECT state, COUNT(*) as count 
                FROM workflow_instances 
                GROUP BY state
                """;
            
            jdbcTemplate.query(sql, (rs) -> {
                workflowCounts.put(rs.getString("state"), rs.getInt("count"));
            });
            
            details.put("workflows", workflowCounts);
            
            // Check for stuck workflows (running for > 1 hour without progress)
            String stuckSql = """
                SELECT COUNT(*) FROM workflow_instances 
                WHERE state = 'RUNNING' 
                AND started_at < NOW() - INTERVAL '1 hour'
                AND last_recovery_at IS NULL OR last_recovery_at < NOW() - INTERVAL '1 hour'
                """;
            
            Integer stuckCount = jdbcTemplate.queryForObject(stuckSql, Integer.class);
            details.put("stuckWorkflows", stuckCount != null ? stuckCount : 0);
            
        } catch (Exception e) {
            details.put("workflowHealthError", e.getMessage());
        }
    }

    private void checkLeaseHealth(Map<String, Object> details) {
        try {
            // Count active leases
            String activeSql = """
                SELECT COUNT(*) FROM execution_leases 
                WHERE expires_at > NOW()
                """;
            Integer activeLeases = jdbcTemplate.queryForObject(activeSql, Integer.class);
            details.put("activeLeases", activeLeases != null ? activeLeases : 0);
            
            // Count expired leases (indicates potential issues)
            String expiredSql = """
                SELECT COUNT(*) FROM execution_leases 
                WHERE expires_at <= NOW()
                """;
            Integer expiredLeases = jdbcTemplate.queryForObject(expiredSql, Integer.class);
            details.put("expiredLeases", expiredLeases != null ? expiredLeases : 0);
            
            // Warn if too many expired leases
            if (expiredLeases != null && expiredLeases > 10) {
                details.put("leaseWarning", "High number of expired leases - recovery may be slow");
            }
            
        } catch (Exception e) {
            details.put("leaseHealthError", e.getMessage());
        }
    }
}
