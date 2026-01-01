package com.orchestrator.engine.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.WorkflowInstance;
import com.orchestrator.core.model.WorkflowState;
import com.orchestrator.core.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL-backed implementation of WorkflowInstanceRepository.
 * Supports optimistic locking via sequence numbers for concurrent access.
 */
@Repository("jdbcWorkflowInstanceRepository")
public class JdbcWorkflowInstanceRepository implements WorkflowInstanceRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowInstanceRepository.class);
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowInstanceRowMapper rowMapper;

    public JdbcWorkflowInstanceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = new WorkflowInstanceRowMapper();
    }

    @Override
    @Transactional
    public void save(WorkflowInstance instance) {
        String sql = """
            INSERT INTO workflow_instances (
                instance_id, namespace, workflow_name, workflow_version,
                run_id, correlation_id, state, current_task_id,
                completed_task_ids, failed_task_ids, input_json, output_json,
                task_outputs_json, last_error, last_error_task_id,
                created_at, started_at, completed_at, deadline,
                recovery_attempts, last_recovery_at, sequence_number
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (namespace, workflow_name, run_id) DO NOTHING
            """;
        
        int rows = jdbcTemplate.update(sql,
            instance.instanceId(),
            instance.namespace(),
            instance.workflowName(),
            instance.workflowVersion(),
            instance.runId(),
            instance.correlationId(),
            instance.state().name(),
            instance.currentTaskId(),
            toJson(instance.completedTaskIds()),
            toJson(instance.failedTaskIds()),
            toJson(instance.input()),
            toJson(instance.output()),
            toJson(instance.taskOutputs()),
            instance.lastError(),
            instance.lastErrorTaskId(),
            toTimestamp(instance.createdAt()),
            toTimestamp(instance.startedAt()),
            toTimestamp(instance.completedAt()),
            toTimestamp(instance.deadline()),
            instance.recoveryAttempts(),
            toTimestamp(instance.lastRecoveryAt()),
            instance.sequenceNumber()
        );
        
        if (rows == 0) {
            log.debug("Workflow instance already exists: {}:{}:{}", 
                instance.namespace(), instance.workflowName(), instance.runId());
        }
    }

    @Override
    @Transactional
    public void update(WorkflowInstance instance) {
        String sql = """
            UPDATE workflow_instances SET
                state = ?,
                current_task_id = ?,
                completed_task_ids = ?::jsonb,
                failed_task_ids = ?::jsonb,
                output_json = ?::jsonb,
                task_outputs_json = ?::jsonb,
                last_error = ?,
                last_error_task_id = ?,
                started_at = ?,
                completed_at = ?,
                recovery_attempts = ?,
                last_recovery_at = ?,
                sequence_number = ?
            WHERE instance_id = ? AND sequence_number = ?
            """;
        
        int rows = jdbcTemplate.update(sql,
            instance.state().name(),
            instance.currentTaskId(),
            toJson(instance.completedTaskIds()),
            toJson(instance.failedTaskIds()),
            toJson(instance.output()),
            toJson(instance.taskOutputs()),
            instance.lastError(),
            instance.lastErrorTaskId(),
            toTimestamp(instance.startedAt()),
            toTimestamp(instance.completedAt()),
            instance.recoveryAttempts(),
            toTimestamp(instance.lastRecoveryAt()),
            instance.sequenceNumber(),
            instance.instanceId(),
            instance.sequenceNumber() - 1  // Expected previous sequence
        );
        
        if (rows == 0) {
            throw new OptimisticLockException(
                "Workflow instance was modified concurrently: " + instance.instanceId());
        }
    }

    @Override
    public Optional<WorkflowInstance> findById(UUID instanceId) {
        String sql = "SELECT * FROM workflow_instances WHERE instance_id = ?";
        List<WorkflowInstance> results = jdbcTemplate.query(sql, rowMapper, instanceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<WorkflowInstance> findByRunId(String namespace, String workflowName, String runId) {
        String sql = """
            SELECT * FROM workflow_instances 
            WHERE namespace = ? AND workflow_name = ? AND run_id = ?
            """;
        List<WorkflowInstance> results = jdbcTemplate.query(sql, rowMapper, namespace, workflowName, runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<WorkflowInstance> findByCorrelationId(String correlationId) {
        String sql = "SELECT * FROM workflow_instances WHERE correlation_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, rowMapper, correlationId);
    }

    @Override
    public List<WorkflowInstance> findByState(WorkflowState state, int limit) {
        String sql = "SELECT * FROM workflow_instances WHERE state = ? ORDER BY created_at LIMIT ?";
        return jdbcTemplate.query(sql, rowMapper, state.name(), limit);
    }

    @Override
    public List<WorkflowInstance> findExpiredDeadlines(Instant now, int limit) {
        String sql = """
            SELECT * FROM workflow_instances 
            WHERE deadline < ? 
              AND state NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'COMPENSATION_FAILED')
            ORDER BY deadline
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, Timestamp.from(now), limit);
    }

    @Override
    public List<WorkflowInstance> findStuckInstances(Instant stuckSince, int limit) {
        String sql = """
            SELECT * FROM workflow_instances 
            WHERE state = 'RUNNING'
              AND (last_recovery_at IS NULL OR last_recovery_at < ?)
            ORDER BY created_at
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, Timestamp.from(stuckSince), limit);
    }

    @Override
    public Map<WorkflowState, Long> countByState(String namespace, String workflowName) {
        String sql = """
            SELECT state, COUNT(*) as count 
            FROM workflow_instances 
            WHERE namespace = ? AND workflow_name = ?
            GROUP BY state
            """;
        
        Map<WorkflowState, Long> counts = new EnumMap<>(WorkflowState.class);
        jdbcTemplate.query(sql, rs -> {
            WorkflowState state = WorkflowState.valueOf(rs.getString("state"));
            long count = rs.getLong("count");
            counts.put(state, count);
        }, namespace, workflowName);
        
        return counts;
    }

    @Override
    public int deleteCompletedBefore(Instant completedBefore) {
        String sql = """
            DELETE FROM workflow_instances 
            WHERE state IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'COMPENSATION_FAILED')
              AND completed_at < ?
            """;
        return jdbcTemplate.update(sql, Timestamp.from(completedBefore));
    }

    // ========== Helper Methods ==========

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private class WorkflowInstanceRowMapper implements RowMapper<WorkflowInstance> {
        @Override
        public WorkflowInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                return new WorkflowInstance(
                    UUID.fromString(rs.getString("instance_id")),
                    rs.getString("namespace"),
                    rs.getString("workflow_name"),
                    rs.getInt("workflow_version"),
                    rs.getString("run_id"),
                    rs.getString("correlation_id"),
                    WorkflowState.valueOf(rs.getString("state")),
                    rs.getString("current_task_id"),
                    parseStringSet(rs.getString("completed_task_ids")),
                    parseStringSet(rs.getString("failed_task_ids")),
                    parseJsonNode(rs.getString("input_json")),
                    parseJsonNode(rs.getString("output_json")),
                    parseJsonMap(rs.getString("task_outputs_json")),
                    rs.getString("last_error"),
                    rs.getString("last_error_task_id"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at")),
                    toInstant(rs.getTimestamp("deadline")),
                    rs.getInt("recovery_attempts"),
                    toInstant(rs.getTimestamp("last_recovery_at")),
                    rs.getLong("sequence_number")
                );
            } catch (Exception e) {
                throw new SQLException("Failed to map workflow instance row", e);
            }
        }

        private Set<String> parseStringSet(String json) throws JsonProcessingException {
            if (json == null || json.isEmpty()) return Set.of();
            List<String> list = objectMapper.readValue(json, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return new HashSet<>(list);
        }

        private JsonNode parseJsonNode(String json) throws JsonProcessingException {
            if (json == null || json.isEmpty()) return null;
            return objectMapper.readTree(json);
        }

        @SuppressWarnings("unchecked")
        private Map<String, JsonNode> parseJsonMap(String json) throws JsonProcessingException {
            if (json == null || json.isEmpty()) return Map.of();
            return objectMapper.readValue(json, Map.class);
        }

        private Instant toInstant(Timestamp ts) {
            return ts != null ? ts.toInstant() : null;
        }
    }

    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }
}
