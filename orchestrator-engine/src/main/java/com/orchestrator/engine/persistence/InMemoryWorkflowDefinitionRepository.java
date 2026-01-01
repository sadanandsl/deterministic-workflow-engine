package com.orchestrator.engine.persistence;

import com.orchestrator.core.model.WorkflowDefinition;
import com.orchestrator.core.repository.WorkflowDefinitionRepository;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of WorkflowDefinitionRepository.
 * For demonstration and testing purposes.
 */
@Repository
public class InMemoryWorkflowDefinitionRepository implements WorkflowDefinitionRepository {
    
    // Key: namespace:name:version
    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    
    private String buildKey(String namespace, String name, int version) {
        return namespace + ":" + name + ":" + version;
    }
    
    @Override
    public void save(WorkflowDefinition definition) {
        String key = buildKey(definition.namespace(), definition.name(), definition.version());
        definitions.put(key, definition);
    }
    
    @Override
    public Optional<WorkflowDefinition> find(String namespace, String name, int version) {
        String key = buildKey(namespace, name, version);
        return Optional.ofNullable(definitions.get(key));
    }
    
    @Override
    public Optional<WorkflowDefinition> findLatest(String namespace, String name) {
        return definitions.values().stream()
            .filter(d -> d.namespace().equals(namespace) && d.name().equals(name))
            .max(Comparator.comparing(WorkflowDefinition::version));
    }
    
    @Override
    public List<WorkflowDefinition> listVersions(String namespace, String name) {
        return definitions.values().stream()
            .filter(d -> d.namespace().equals(namespace) && d.name().equals(name))
            .sorted(Comparator.comparing(WorkflowDefinition::version).reversed())
            .collect(Collectors.toList());
    }
    
    @Override
    public List<WorkflowDefinition> listByNamespace(String namespace) {
        // Return latest version of each workflow in namespace
        Map<String, WorkflowDefinition> latestByName = new HashMap<>();
        definitions.values().stream()
            .filter(d -> d.namespace().equals(namespace))
            .forEach(d -> {
                WorkflowDefinition existing = latestByName.get(d.name());
                if (existing == null || d.version() > existing.version()) {
                    latestByName.put(d.name(), d);
                }
            });
        return new ArrayList<>(latestByName.values());
    }
    
    @Override
    public boolean exists(String namespace, String name, int version) {
        String key = buildKey(namespace, name, version);
        return definitions.containsKey(key);
    }
    
    @Override
    public int getNextVersion(String namespace, String name) {
        return definitions.values().stream()
            .filter(d -> d.namespace().equals(namespace) && d.name().equals(name))
            .mapToInt(WorkflowDefinition::version)
            .max()
            .orElse(0) + 1;
    }
}
