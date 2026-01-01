package com.orchestrator.engine.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.CompensationStrategy;
import com.orchestrator.core.model.RetryPolicy;
import com.orchestrator.core.model.TaskDefinition;
import com.orchestrator.core.model.WorkflowDefinition;
import com.orchestrator.core.repository.WorkflowDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL-backed implementation of WorkflowDefinitionRepository.
 * Workflow definitions are immutable once stored.
 * 
 * Uses JSONB columns for flexible storage of:
 * - Task definitions (tasks)
 * - Edge mappings (edges)
 * - Terminal task IDs
 * - Labels
 */
@Repository("jdbcWorkflowDefinitionRepository")
public class JdbcWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowDefinitionRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowDefinitionRowMapper rowMapper;

    public JdbcWorkflowDefinitionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = new WorkflowDefinitionRowMapper(objectMapper);
    }

    @Override
    @Transactional
    public void save(WorkflowDefinition definition) {
        if (exists(definition.namespace(), definition.name(), definition.version())) {
            throw new IllegalArgumentException(
                "Workflow definition already exists: " + definition.id());
        }

        String sql = """
            INSERT INTO workflow_definitions (
                namespace, name, version,
                tasks, edges, entry_task_id, terminal_task_ids,
                default_retry_max_attempts, default_retry_initial_delay_ms,
                default_retry_max_delay_ms, default_retry_backoff_multiplier,
                max_duration_ms, compensation_strategy,
                created_at, created_by, description, labels
            ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

        try {
            RetryPolicy retry = definition.defaultRetryPolicy();
            
            jdbcTemplate.update(sql,
                definition.namespace(),
                definition.name(),
                definition.version(),
                serializeJson(definition.tasks()),
                serializeJson(definition.edges()),
                definition.entryTaskId(),
                serializeJson(definition.terminalTaskIds()),
                retry.maxAttempts(),
                retry.initialDelay().toMillis(),
                retry.maxDelay().toMillis(),
                retry.backoffMultiplier(),
                definition.maxDuration().toMillis(),
                definition.compensationStrategy().name(),
                Timestamp.from(definition.createdAt()),
                definition.createdBy(),
                definition.description(),
                serializeJson(definition.labels())
            );

            log.info("Saved workflow definition: {}", definition.id());
        } catch (Exception e) {
            log.error("Failed to save workflow definition {}: {}", definition.id(), e.getMessage());
            throw new RuntimeException("Failed to save workflow definition", e);
        }
    }

    @Override
    public Optional<WorkflowDefinition> find(String namespace, String name, int version) {
        String sql = """
            SELECT * FROM workflow_definitions 
            WHERE namespace = ? AND name = ? AND version = ?
            """;
        List<WorkflowDefinition> results = jdbcTemplate.query(sql, rowMapper, namespace, name, version);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<WorkflowDefinition> findLatest(String namespace, String name) {
        String sql = """
            SELECT * FROM workflow_definitions 
            WHERE namespace = ? AND name = ?
            ORDER BY version DESC
            LIMIT 1
            """;
        List<WorkflowDefinition> results = jdbcTemplate.query(sql, rowMapper, namespace, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<WorkflowDefinition> listVersions(String namespace, String name) {
        String sql = """
            SELECT * FROM workflow_definitions 
            WHERE namespace = ? AND name = ?
            ORDER BY version DESC
            """;
        return jdbcTemplate.query(sql, rowMapper, namespace, name);
    }

    @Override
    public List<WorkflowDefinition> listByNamespace(String namespace) {
        // Returns only the latest version of each workflow
        String sql = """
            SELECT DISTINCT ON (namespace, name) * 
            FROM workflow_definitions 
            WHERE namespace = ?
            ORDER BY namespace, name, version DESC
            """;
        return jdbcTemplate.query(sql, rowMapper, namespace);
    }

    @Override
    public boolean exists(String namespace, String name, int version) {
        String sql = """
            SELECT COUNT(*) FROM workflow_definitions 
            WHERE namespace = ? AND name = ? AND version = ?
            """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, namespace, name, version);
        return count != null && count > 0;
    }

    @Override
    public int getNextVersion(String namespace, String name) {
        String sql = """
            SELECT COALESCE(MAX(version), 0) + 1 
            FROM workflow_definitions 
            WHERE namespace = ? AND name = ?
            """;
        Integer version = jdbcTemplate.queryForObject(sql, Integer.class, namespace, name);
        return version != null ? version : 1;
    }

    private String serializeJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private class WorkflowDefinitionRowMapper implements RowMapper<WorkflowDefinition> {
        private final ObjectMapper objectMapper;

        WorkflowDefinitionRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public WorkflowDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
            List<TaskDefinition> tasks = deserializeList(
                rs.getString("tasks"), new TypeReference<List<TaskDefinition>>() {});
            
            Map<String, List<String>> edges = deserializeMap(
                rs.getString("edges"), new TypeReference<Map<String, List<String>>>() {});
            
            Set<String> terminalTaskIds = deserializeSet(
                rs.getString("terminal_task_ids"), new TypeReference<Set<String>>() {});
            
            Map<String, String> labels = deserializeMap(
                rs.getString("labels"), new TypeReference<Map<String, String>>() {});

            RetryPolicy retryPolicy = new RetryPolicy(
                rs.getInt("default_retry_max_attempts"),
                Duration.ofMillis(rs.getLong("default_retry_initial_delay_ms")),
                Duration.ofMillis(rs.getLong("default_retry_max_delay_ms")),
                rs.getDouble("default_retry_backoff_multiplier"),
                List.of() // retryableExceptions loaded separately if needed
            );

            return new WorkflowDefinition(
                rs.getString("namespace"),
                rs.getString("name"),
                rs.getInt("version"),
                tasks,
                edges,
                rs.getString("entry_task_id"),
                terminalTaskIds,
                retryPolicy,
                Duration.ofMillis(rs.getLong("max_duration_ms")),
                CompensationStrategy.valueOf(rs.getString("compensation_strategy")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
                rs.getString("description"),
                labels
            );
        }

        private <T> List<T> deserializeList(String json, TypeReference<List<T>> typeRef) {
            if (json == null || json.isBlank() || "[]".equals(json)) {
                return List.of();
            }
            try {
                return objectMapper.readValue(json, typeRef);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize list: {}", e.getMessage());
                return List.of();
            }
        }

        private <K, V> Map<K, V> deserializeMap(String json, TypeReference<Map<K, V>> typeRef) {
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(json, typeRef);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize map: {}", e.getMessage());
                return Map.of();
            }
        }

        private <T> Set<T> deserializeSet(String json, TypeReference<Set<T>> typeRef) {
            if (json == null || json.isBlank() || "[]".equals(json)) {
                return Set.of();
            }
            try {
                return objectMapper.readValue(json, typeRef);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize set: {}", e.getMessage());
                return Set.of();
            }
        }
    }
}
