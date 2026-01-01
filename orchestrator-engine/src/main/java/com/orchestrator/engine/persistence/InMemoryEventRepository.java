package com.orchestrator.engine.persistence;

import com.orchestrator.core.model.Event;
import com.orchestrator.core.model.EventType;
import com.orchestrator.core.repository.EventRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory implementation of EventRepository.
 * For demonstration and testing purposes.
 */
@Repository
public class InMemoryEventRepository implements EventRepository {
    
    private final Map<UUID, Event> events = new ConcurrentHashMap<>();
    private final Map<String, Event> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    
    @Override
    public void append(Event event) {
        events.put(event.eventId(), event);
        if (event.idempotencyKey() != null) {
            byIdempotencyKey.put(event.idempotencyKey(), event);
        }
    }
    
    @Override
    public void appendAll(List<Event> eventList) {
        for (Event event : eventList) {
            append(event);
        }
    }
    
    @Override
    public Optional<Event> findById(UUID eventId) {
        return Optional.ofNullable(events.get(eventId));
    }
    
    @Override
    public Optional<Event> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }
    
    @Override
    public List<Event> findByWorkflowInstance(UUID workflowInstanceId) {
        return events.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
            .sorted(Comparator.comparing(Event::sequenceNumber))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Event> findByWorkflowInstanceFrom(UUID workflowInstanceId, long fromSequence) {
        return events.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
            .filter(e -> e.sequenceNumber() >= fromSequence)
            .sorted(Comparator.comparing(Event::sequenceNumber))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Event> findByWorkflowInstanceAndTypes(UUID workflowInstanceId, List<EventType> types) {
        Set<EventType> typeSet = new HashSet<>(types);
        return events.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
            .filter(e -> typeSet.contains(e.type()))
            .sorted(Comparator.comparing(Event::sequenceNumber))
            .collect(Collectors.toList());
    }
    
    @Override
    public long getNextSequenceNumber(UUID workflowInstanceId) {
        return sequenceCounters
            .computeIfAbsent(workflowInstanceId, k -> new AtomicLong(0))
            .incrementAndGet();
    }
    
    @Override
    public Optional<Event> findLatestByWorkflowInstance(UUID workflowInstanceId) {
        return events.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
            .max(Comparator.comparing(Event::sequenceNumber));
    }
    
    @Override
    public List<Event> findByTimeRange(Instant from, Instant to, List<EventType> types, int limit) {
        return events.values().stream()
            .filter(e -> !e.timestamp().isBefore(from) && !e.timestamp().isAfter(to))
            .filter(e -> types.isEmpty() || types.contains(e.type()))
            .sorted(Comparator.comparing(Event::timestamp))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public Map<EventType, Long> countByType(UUID workflowInstanceId) {
        return events.values().stream()
            .filter(e -> e.workflowInstanceId().equals(workflowInstanceId))
            .collect(Collectors.groupingBy(Event::type, Collectors.counting()));
    }
}
