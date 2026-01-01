package com.orchestrator.core.repository;

import com.orchestrator.core.model.WorkflowDefinition;
import java.util.List;
import java.util.Optional;

/**
 * Repository for WorkflowDefinition persistence.
 * Workflow definitions are immutable once stored.
 */
public interface WorkflowDefinitionRepository {

    /**
     * Store a new workflow definition.
     * 
     * @param definition The workflow definition to store
     * @throws IllegalArgumentException if definition with same id already exists
     */
    void save(WorkflowDefinition definition);

    /**
     * Find a workflow definition by namespace, name, and version.
     * 
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return The workflow definition if found
     */
    Optional<WorkflowDefinition> find(String namespace, String name, int version);

    /**
     * Find the latest version of a workflow definition.
     * 
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @return The latest workflow definition if found
     */
    Optional<WorkflowDefinition> findLatest(String namespace, String name);

    /**
     * List all versions of a workflow definition.
     * 
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @return All versions ordered by version number descending
     */
    List<WorkflowDefinition> listVersions(String namespace, String name);

    /**
     * List all workflow definitions in a namespace.
     * 
     * @param namespace The workflow namespace
     * @return All workflow definitions (latest versions only)
     */
    List<WorkflowDefinition> listByNamespace(String namespace);

    /**
     * Check if a workflow definition exists.
     * 
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return true if the definition exists
     */
    boolean exists(String namespace, String name, int version);

    /**
     * Get the next available version number for a workflow.
     * 
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @return The next version number (1 if no versions exist)
     */
    int getNextVersion(String namespace, String name);
}
