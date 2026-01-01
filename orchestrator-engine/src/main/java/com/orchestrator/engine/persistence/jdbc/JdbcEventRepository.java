package com.orchestrator.engine.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.event.WorkflowEvent;
import com.orchestrator.core.event.EventType;
import com.orchestrator.core.repository.EventRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed implementation of EventRepository.
 * Provides append-only event sourcing with sequence ordering.
 * 
 * Events are immutable and ordered by sequence number within each workflow.
 * Idempotency keys prevent duplicate event appends during retries.
 */
@Repository("jdbcEventRepository")
public class JdbcEventRepository implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcEventRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowEventRowMapper rowMapper;

    public JdbcEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = new WorkflowEventRowMapper(objectMapper);
    }

    @Override
    @Transactional
    public WorkflowEvent append(WorkflowEvent event) {
        String sql = """
            INSERT INTO events (
                event_id, workflow_instance_id, sequence_number,
                event_type, event_timestamp, task_name, task_execution_id,
                idempotency_key, payload, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING event_id
            """;

        try {
            String payloadJson = serializePayload(event.payload());
            String metadataJson = serializeMetadata(event.metadata());

            UUID returnedId = jdbcTemplate.queryForObject(sql, UUID.class,
                event.eventId(),
                event.workflowInstanceId(),
                event.sequenceNumber(),
                event.eventType().name(),
                Timestamp.from(event.timestamp()),
                event.taskName(),
                event.taskExecutionId(),
                event.idempotencyKey(),
                payloadJson,
                metadataJson
            );

            if (returnedId != null) {
                log.debug("Appended event {} (seq={}) for workflow {}", 
                    event.eventId(), event.sequenceNumber(), event.workflowInstanceId());
            } else {
                log.debug("Event with idempotency key {} already exists, skipped", 
                    event.idempotencyKey());
            }

            return event;
        } catch (Exception e) {
            log.error("Failed to append event: {}", e.getMessage());
            throw new RuntimeException("Failed to append event", e);
        }
    }

    @Override
    @Transactional
    public List<WorkflowEvent> appendAll(List<WorkflowEvent> events) {
        // Batch insert for efficiency
        String sql = """
            INSERT INTO events (
                event_id, workflow_instance_id, sequence_number,
                event_type, event_timestamp, task_name, task_execution_id,
                idempotency_key, payload, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

        jdbcTemplate.batchUpdate(sql, events, events.size(), (ps, event) -> {
            ps.setObject(1, event.eventId());
            ps.setObject(2, event.workflowInstanceId());
            ps.setLong(3, event.sequenceNumber());
            ps.setString(4, event.eventType().name());
            ps.setTimestamp(5, Timestamp.from(event.timestamp()));
            ps.setString(6, event.taskName());
            ps.setObject(7, event.taskExecutionId());
            ps.setString(8, event.idempotencyKey());
            ps.setString(9, serializePayload(event.payload()));
            ps.setString(10, serializeMetadata(event.metadata()));
        });

        log.debug("Batch appended {} events", events.size());
        return events;
    }

    @Override
    public List<WorkflowEvent> findByWorkflowInstance(UUID workflowInstanceId) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ?
            ORDER BY sequence_number ASC
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId);
    }

    @Override
    public List<WorkflowEvent> findByWorkflowInstance(UUID workflowInstanceId, long fromSequence) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ? AND sequence_number >= ?
            ORDER BY sequence_number ASC
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId, fromSequence);
    }

    @Override
    public List<WorkflowEvent> findByWorkflowInstance(UUID workflowInstanceId, 
                                                       long fromSequence, long toSequence) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ? 
              AND sequence_number >= ? 
              AND sequence_number <= ?
            ORDER BY sequence_number ASC
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId, fromSequence, toSequence);
    }

    @Override
    public List<WorkflowEvent> findByEventType(UUID workflowInstanceId, EventType eventType) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ? AND event_type = ?
            ORDER BY sequence_number ASC
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId, eventType.name());
    }

    @Override
    public Optional<WorkflowEvent> findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT * FROM events WHERE idempotency_key = ?";
        List<WorkflowEvent> results = jdbcTemplate.query(sql, rowMapper, idempotencyKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public long getNextSequenceNumber(UUID workflowInstanceId) {
        String sql = """
            SELECT COALESCE(MAX(sequence_number), 0) + 1 
            FROM events 
            WHERE workflow_instance_id = ?
            """;
        Long seq = jdbcTemplate.queryForObject(sql, Long.class, workflowInstanceId);
        return seq != null ? seq : 1L;
    }

    @Override
    public long countEvents(UUID workflowInstanceId) {
        String sql = "SELECT COUNT(*) FROM events WHERE workflow_instance_id = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, workflowInstanceId);
        return count != null ? count : 0L;
    }

    @Override
    public List<WorkflowEvent> findRecentEvents(int limit) {
        String sql = """
            SELECT * FROM events 
            ORDER BY event_timestamp DESC, sequence_number DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, limit);
    }

    @Override
    @Transactional
    public int deleteByWorkflowInstance(UUID workflowInstanceId) {
        String sql = "DELETE FROM events WHERE workflow_instance_id = ?";
        return jdbcTemplate.update(sql, workflowInstanceId);
    }

    private String serializePayload(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload: {}", e.getMessage());
            return "{}";
        }
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return "{}";
        }
    }

    private class WorkflowEventRowMapper implements RowMapper<WorkflowEvent> {
        private final ObjectMapper objectMapper;

        WorkflowEventRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public WorkflowEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            Object payload = deserializePayload(rs.getString("payload"));
            Map<String, String> metadata = deserializeMetadata(rs.getString("metadata"));
            
            return new WorkflowEvent(
                UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("workflow_instance_id")),
                rs.getLong("sequence_number"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("event_timestamp").toInstant(),
                rs.getString("task_name"),
                rs.getString("task_execution_id") != null 
                    ? UUID.fromString(rs.getString("task_execution_id")) : null,
                rs.getString("idempotency_key"),
                payload,
                metadata
            );
        }

        private Object deserializePayload(String json) {
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return null;
            }
            try {
                return objectMapper.readValue(json, Object.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize payload: {}", e.getMessage());
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> deserializeMetadata(String json) {
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(json, Map.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize metadata: {}", e.getMessage());
                return Map.of();
            }
        }
    }
}
