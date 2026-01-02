package com.orchestrator.engine.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.core.model.Event;
import com.orchestrator.core.model.EventType;
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
import java.util.HashMap;
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
    private final EventRowMapper rowMapper;

    public JdbcEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = new EventRowMapper(objectMapper);
    }

    @Override
    @Transactional
    public void append(Event event) {
        String sql = """
            INSERT INTO events (
                event_id, workflow_instance_id, sequence_number,
                event_type, event_timestamp, payload,
                caused_by_event_id, idempotency_key,
                trace_id, span_id, actor_type, actor_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

        try {
            String payloadJson = serializePayload(event.payload());

            int rows = jdbcTemplate.update(sql,
                event.eventId(),
                event.workflowInstanceId(),
                event.sequenceNumber(),
                event.type().name(),
                Timestamp.from(event.timestamp()),
                payloadJson,
                event.causedByEventId(),
                event.idempotencyKey(),
                event.traceId(),
                event.spanId(),
                event.actorType(),
                event.actorId()
            );

            if (rows > 0) {
                log.debug("Appended event {} (seq={}) for workflow {}", 
                    event.eventId(), event.sequenceNumber(), event.workflowInstanceId());
            } else {
                log.debug("Event with idempotency key {} already exists, skipped", 
                    event.idempotencyKey());
            }
        } catch (Exception e) {
            log.error("Failed to append event: {}", e.getMessage());
            throw new RuntimeException("Failed to append event", e);
        }
    }

    @Override
    @Transactional
    public void appendAll(List<Event> events) {
        String sql = """
            INSERT INTO events (
                event_id, workflow_instance_id, sequence_number,
                event_type, event_timestamp, payload,
                caused_by_event_id, idempotency_key,
                trace_id, span_id, actor_type, actor_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

        jdbcTemplate.batchUpdate(sql, events, events.size(), (ps, event) -> {
            ps.setObject(1, event.eventId());
            ps.setObject(2, event.workflowInstanceId());
            ps.setLong(3, event.sequenceNumber());
            ps.setString(4, event.type().name());
            ps.setTimestamp(5, Timestamp.from(event.timestamp()));
            ps.setString(6, serializePayload(event.payload()));
            ps.setObject(7, event.causedByEventId());
            ps.setString(8, event.idempotencyKey());
            ps.setString(9, event.traceId());
            ps.setString(10, event.spanId());
            ps.setString(11, event.actorType());
            ps.setString(12, event.actorId());
        });

        log.debug("Batch appended {} events", events.size());
    }

    @Override
    public Optional<Event> findById(UUID eventId) {
        String sql = "SELECT * FROM events WHERE event_id = ?";
        List<Event> results = jdbcTemplate.query(sql, rowMapper, eventId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<Event> findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT * FROM events WHERE idempotency_key = ?";
        List<Event> results = jdbcTemplate.query(sql, rowMapper, idempotencyKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Event> findByWorkflowInstance(UUID workflowInstanceId) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ?
            ORDER BY sequence_number ASC
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId);
    }

    @Override
    public List<Event> findByWorkflowInstanceFrom(UUID workflowInstanceId, long fromSequence) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ? AND sequence_number >= ?
            ORDER BY sequence_number ASC
            """;
        return jdbcTemplate.query(sql, rowMapper, workflowInstanceId, fromSequence);
    }

    @Override
    public List<Event> findByWorkflowInstanceAndTypes(UUID workflowInstanceId, List<EventType> types) {
        if (types == null || types.isEmpty()) {
            return findByWorkflowInstance(workflowInstanceId);
        }
        
        String placeholders = String.join(",", types.stream().map(t -> "?").toList());
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ? AND event_type IN (%s)
            ORDER BY sequence_number ASC
            """.formatted(placeholders);
        
        Object[] params = new Object[types.size() + 1];
        params[0] = workflowInstanceId;
        for (int i = 0; i < types.size(); i++) {
            params[i + 1] = types.get(i).name();
        }
        
        return jdbcTemplate.query(sql, rowMapper, params);
    }

    @Override
    public long getNextSequenceNumber(UUID workflowInstanceId) {
        String sql = """
            SELECT COALESCE(MAX(sequence_number), -1) + 1 
            FROM events 
            WHERE workflow_instance_id = ?
            """;
        Long seq = jdbcTemplate.queryForObject(sql, Long.class, workflowInstanceId);
        return seq != null ? seq : 0L;
    }

    @Override
    public Optional<Event> findLatestByWorkflowInstance(UUID workflowInstanceId) {
        String sql = """
            SELECT * FROM events 
            WHERE workflow_instance_id = ?
            ORDER BY sequence_number DESC
            LIMIT 1
            """;
        List<Event> results = jdbcTemplate.query(sql, rowMapper, workflowInstanceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Event> findByTimeRange(Instant from, Instant to, List<EventType> types, int limit) {
        if (types == null || types.isEmpty()) {
            String sql = """
                SELECT * FROM events 
                WHERE event_timestamp >= ? AND event_timestamp < ?
                ORDER BY event_timestamp DESC
                LIMIT ?
                """;
            return jdbcTemplate.query(sql, rowMapper, Timestamp.from(from), Timestamp.from(to), limit);
        }
        
        String placeholders = String.join(",", types.stream().map(t -> "?").toList());
        String sql = """
            SELECT * FROM events 
            WHERE event_timestamp >= ? AND event_timestamp < ? AND event_type IN (%s)
            ORDER BY event_timestamp DESC
            LIMIT ?
            """.formatted(placeholders);
        
        Object[] params = new Object[types.size() + 3];
        params[0] = Timestamp.from(from);
        params[1] = Timestamp.from(to);
        for (int i = 0; i < types.size(); i++) {
            params[i + 2] = types.get(i).name();
        }
        params[params.length - 1] = limit;
        
        return jdbcTemplate.query(sql, rowMapper, params);
    }

    @Override
    public Map<EventType, Long> countByType(UUID workflowInstanceId) {
        String sql = """
            SELECT event_type, COUNT(*) as count 
            FROM events 
            WHERE workflow_instance_id = ?
            GROUP BY event_type
            """;
        
        Map<EventType, Long> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String typeName = rs.getString("event_type");
            Long count = rs.getLong("count");
            try {
                result.put(EventType.valueOf(typeName), count);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown event type in database: {}", typeName);
            }
        }, workflowInstanceId);
        
        return result;
    }

    private String serializePayload(JsonNode payload) {
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

    private class EventRowMapper implements RowMapper<Event> {
        private final ObjectMapper objectMapper;

        EventRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public Event mapRow(ResultSet rs, int rowNum) throws SQLException {
            JsonNode payload = deserializePayload(rs.getString("payload"));
            
            UUID causedByEventId = rs.getString("caused_by_event_id") != null
                ? UUID.fromString(rs.getString("caused_by_event_id")) : null;
            
            return new Event(
                UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("workflow_instance_id")),
                rs.getLong("sequence_number"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("event_timestamp").toInstant(),
                payload,
                causedByEventId,
                rs.getString("idempotency_key"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("actor_type"),
                rs.getString("actor_id")
            );
        }

        private JsonNode deserializePayload(String json) {
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return null;
            }
            try {
                return objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize payload: {}", e.getMessage());
                return null;
            }
        }
    }
}
