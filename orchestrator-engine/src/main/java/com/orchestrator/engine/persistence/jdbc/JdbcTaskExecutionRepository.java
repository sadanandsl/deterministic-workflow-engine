package com.orchestrator.engine.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.TaskExecution;
import com.orchestrator.core.model.TaskState;
import com.orchestrator.core.repository.TaskExecutionRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed implementation of TaskExecutionRepository.
 * Maintains exactly-once semantics via idempotency keys and fence tokens.
 */
@Repository("jdbcTaskExecutionRepository")
public class JdbcTaskExecutionRepository implements TaskExecutionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskExecutionRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskExecutionRowMapper rowMapper;

    public JdbcTaskExecutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = new TaskExecutionRowMapper();
    }

    @Override
    @Transactional
    public void save(TaskExecution execution) {
        String sql = """
            INSERT INTO task_executions (
                execution_id, workflow_instance_id, task_id,
                idempotency_key, attempt_number, state,
                lease_holder, lease_expires_at, fence_token,
                input_json, output_json, error_message, error_code, stack_trace,
                scheduled_at, started_at, completed_at,
                trace_id, span_id, worker_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

        int rows = jdbcTemplate.update(sql,
            execution.executionId(),
            execution.workflowInstanceId(),
            execution.taskId(),
            execution.idempotencyKey(),
            execution.attemptNumber(),
            execution.state().name(),
            execution.leaseHolder(),
            toTimestamp(execution.leaseExpiresAt()),
            execution.fenceToken(),
            toJson(execution.input()),
            toJson(execution.output()),
            execution.errorMessage(),
            execution.errorCode(),
            execution.stackTrace(),
            toTimestamp(execution.scheduledAt()),
            toTimestamp(execution.startedAt()),
            toTimestamp(execution.completedAt()),
            execution.traceId(),
            execution.spanId(),
            execution.workerId()
        );

        if (rows == 0) {
            log.debug("Task execution already exists: {}", execution.idempotencyKey());
        }
    }

    @Override
    @Transactional
    public void update(TaskExecution execution) {
        String sql = """
            UPDATE task_executions SET
                state = ?,
                lease_holder = ?,
                lease_expires_at = ?,
                fence_token = ?,
                output_json = ?::jsonb,
                error_message = ?,
                error_code = ?,
                stack_trace = ?,
                started_at = ?,
                completed_at = ?,
                span_id = ?,
                worker_id = ?
            WHERE execution_id = ?
            """;

        jdbcTemplate.update(sql,
            execution.state().name(),
            execution.leaseHolder(),
            toTimestamp(execution.leaseExpiresAt()),
            execution.fenceToken(),
            toJson(execution.output()),
            execution.errorMessage(),
            execution.errorCode(),
            execution.stackTrace(),
            toTimestamp(execution.startedAt()),
            toTimestamp(execution.completedAt()),
            execution.spanId(),
            execution.workerId(),
            execution.executionId()
        );
    }

    /**
     * Update task execution with fence token validation.
     * Returns true if update succeeded (fence token matched).
     */
    @Transactional
    public boolean updateWithFenceToken(TaskExecution execution, long expectedFenceToken) {
        String sql = """
            UPDATE task_executions SET
                state = ?,
                output_json = ?::jsonb,
                error_message = ?,
                error_code = ?,
                stack_trace = ?,
                completed_at = ?,
                worker_id = ?
            WHERE execution_id = ? AND fence_token = ?
            """;

        int rows = jdbcTemplate.update(sql,
            execution.state().name(),
            toJson(execution.output()),
            execution.errorMessage(),
            execution.errorCode(),
            execution.stackTrace(),
            toTimestamp(execution.completedAt()),
            execution.workerId(),
            execution.executionId(),
            expectedFenceToken
        );

        if (rows == 0) {
            log.warn("Fence token mismatch for task {}: expected {}", 
                execution.executionId(), expectedFenceToken);
        }
        return rows > 0;
    }

    @Override
    public Optional<TaskExecution> findById(UUID executionId) {
        String sql = "SELECT * FROM task_executions WHERE execution_id = ?";
        List<TaskExecution> results = jdbcTemplate.query(sql, rowMapper, executionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<TaskExecution> findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT * FROM task_executions WHERE idempotency_key = ?";
        List<TaskExecution> results = jdbcTemplate.query(sql, rowMapper, idempotencyKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<TaskExecution> findByWorkflowInstance(UUID workflowInstanceId) {
        String sql = """
            SELECT * FROM task_executions 
            WHERE workflow_instance_id = ? 
            ORDER BY scheduled_at
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId);
    }

    @Override
    public List<TaskExecution> findByTask(UUID workflowInstanceId, String taskId) {
        String sql = """
            SELECT * FROM task_executions 
            WHERE workflow_instance_id = ? AND task_id = ?
            ORDER BY attempt_number
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId, taskId);
    }

    @Override
    public Optional<TaskExecution> findLatestByTask(UUID workflowInstanceId, String taskId) {
        String sql = """
            SELECT * FROM task_executions 
            WHERE workflow_instance_id = ? AND task_id = ?
            ORDER BY attempt_number DESC
            LIMIT 1
            """;
        List<TaskExecution> results = jdbcTemplate.query(sql, rowMapper, workflowInstanceId, taskId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<TaskExecution> findByState(TaskState state, int limit) {
        String sql = "SELECT * FROM task_executions WHERE state = ? ORDER BY scheduled_at LIMIT ?";
        return jdbcTemplate.query(sql, rowMapper, state.name(), limit);
    }

    @Override
    public List<TaskExecution> findExpiredLeases(Instant now, int limit) {
        String sql = """
            SELECT * FROM task_executions 
            WHERE state = 'RUNNING' 
              AND lease_expires_at < ?
            ORDER BY lease_expires_at
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, Timestamp.from(now), limit);
    }

    @Override
    public List<TaskExecution> findQueuedByActivityType(String activityType, int limit) {
        String sql = """
            SELECT te.* FROM task_executions te
            WHERE te.state = 'QUEUED'
            ORDER BY te.scheduled_at
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, limit);
    }

    @Override
    public int getCurrentAttemptCount(UUID workflowInstanceId, String taskId) {
        String sql = """
            SELECT COALESCE(MAX(attempt_number), 0)
            FROM task_executions
            WHERE workflow_instance_id = ? AND task_id = ?
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, workflowInstanceId, taskId);
        return count != null ? count : 0;
    }

    public int countByWorkflowAndState(UUID workflowInstanceId, TaskState state) {
        String sql = """
            SELECT COUNT(*) FROM task_executions 
            WHERE workflow_instance_id = ? AND state = ?
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, workflowInstanceId, state.name());
        return count != null ? count : 0;
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

    private class TaskExecutionRowMapper implements RowMapper<TaskExecution> {
        @Override
        public TaskExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                UUID leaseHolder = rs.getString("lease_holder") != null 
                    ? UUID.fromString(rs.getString("lease_holder")) : null;
                    
                return new TaskExecution(
                    UUID.fromString(rs.getString("execution_id")),
                    UUID.fromString(rs.getString("workflow_instance_id")),
                    rs.getString("task_id"),
                    rs.getString("idempotency_key"),
                    rs.getInt("attempt_number"),
                    TaskState.valueOf(rs.getString("state")),
                    leaseHolder,
                    toInstant(rs.getTimestamp("lease_expires_at")),
                    rs.getLong("fence_token"),
                    parseJsonNode(rs.getString("input_json")),
                    parseJsonNode(rs.getString("output_json")),
                    rs.getString("error_message"),
                    rs.getString("error_code"),
                    rs.getString("stack_trace"),
                    toInstant(rs.getTimestamp("scheduled_at")),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at")),
                    rs.getString("trace_id"),
                    rs.getString("span_id"),
                    rs.getString("worker_id")
                );
            } catch (Exception e) {
                throw new SQLException("Failed to map task execution row", e);
            }
        }

        private JsonNode parseJsonNode(String json) throws JsonProcessingException {
            if (json == null || json.isEmpty()) return null;
            return objectMapper.readTree(json);
        }

        private Instant toInstant(Timestamp ts) {
            return ts != null ? ts.toInstant() : null;
        }
    }
}
