package com.orchestrator.engine.lifecycle;

import com.orchestrator.core.repository.LeaseRepository;
import com.orchestrator.engine.logging.LoggingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages graceful shutdown for the orchestrator.
 * 
 * On shutdown:
 * 1. Stops accepting new tasks
 * 2. Waits for in-flight tasks to complete (with timeout)
 * 3. Releases all held leases
 * 4. Logs shutdown status
 * 
 * This prevents:
 * - Zombie leases that block other workers
 * - Lost work from interrupted tasks
 * - Inconsistent state from mid-operation shutdowns
 */
@Component
public class GracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final LeaseRepository leaseRepository;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Set<UUID> activeTaskExecutions = ConcurrentHashMap.newKeySet();
    private final Set<String> heldLeases = ConcurrentHashMap.newKeySet();
    private volatile String nodeId;

    public GracefulShutdownHandler(LeaseRepository leaseRepository) {
        this.leaseRepository = leaseRepository;
    }

    /**
     * Set the node ID for this instance.
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * Check if shutdown is in progress.
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Register a task execution as active.
     * Call this when a worker starts processing a task.
     */
    public void registerActiveTask(UUID taskExecutionId) {
        if (shuttingDown.get()) {
            throw new IllegalStateException("Cannot accept new tasks during shutdown");
        }
        activeTaskExecutions.add(taskExecutionId);
        log.debug("Registered active task: {}", taskExecutionId);
    }

    /**
     * Unregister a task execution as complete.
     * Call this when a worker finishes processing a task.
     */
    public void unregisterActiveTask(UUID taskExecutionId) {
        activeTaskExecutions.remove(taskExecutionId);
        log.debug("Unregistered active task: {}", taskExecutionId);
    }

    /**
     * Register a lease as held by this node.
     */
    public void registerLease(String leaseKey) {
        heldLeases.add(leaseKey);
    }

    /**
     * Unregister a lease (released or expired).
     */
    public void unregisterLease(String leaseKey) {
        heldLeases.remove(leaseKey);
    }

    /**
     * Get the count of currently active tasks.
     */
    public int getActiveTaskCount() {
        return activeTaskExecutions.size();
    }

    /**
     * Get the count of held leases.
     */
    public int getHeldLeaseCount() {
        return heldLeases.size();
    }

    /**
     * Handle application shutdown event.
     * This runs before Spring context is fully closed.
     */
    @EventListener(ContextClosedEvent.class)
    @Order(0) // Run early in shutdown sequence
    public void onShutdown(ContextClosedEvent event) {
        log.info("Initiating graceful shutdown for node: {}", nodeId);
        shuttingDown.set(true);

        // Step 1: Wait for in-flight tasks
        waitForActiveTasks();

        // Step 2: Release all held leases
        releaseAllLeases();

        log.info("Graceful shutdown complete for node: {}", nodeId);
    }

    /**
     * Wait for active tasks to complete with timeout.
     */
    private void waitForActiveTasks() {
        if (activeTaskExecutions.isEmpty()) {
            log.info("No active tasks to wait for");
            return;
        }

        log.info("Waiting for {} active tasks to complete (timeout: {}s)", 
                activeTaskExecutions.size(), SHUTDOWN_TIMEOUT_SECONDS);

        long deadline = System.currentTimeMillis() + (SHUTDOWN_TIMEOUT_SECONDS * 1000);
        while (!activeTaskExecutions.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
                if (!activeTaskExecutions.isEmpty()) {
                    log.debug("Still waiting for {} tasks", activeTaskExecutions.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for tasks to complete");
                break;
            }
        }

        if (!activeTaskExecutions.isEmpty()) {
            log.warn("Shutdown timeout reached with {} tasks still active: {}", 
                    activeTaskExecutions.size(), activeTaskExecutions);
        } else {
            log.info("All active tasks completed");
        }
    }

    /**
     * Release all leases held by this node.
     */
    private void releaseAllLeases() {
        if (heldLeases.isEmpty()) {
            log.info("No leases to release");
            return;
        }

        log.info("Releasing {} held leases", heldLeases.size());
        int released = 0;
        int failed = 0;

        for (String leaseKey : heldLeases) {
            try {
                // Parse lease key to get components
                // Format: "workflow:{workflowId}:task:{taskId}" or "task:{taskExecutionId}"
                boolean success = releaseLeaseByKey(leaseKey);
                if (success) {
                    released++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Failed to release lease {}: {}", leaseKey, e.getMessage());
                failed++;
            }
        }

        log.info("Released {}/{} leases ({} failed)", released, heldLeases.size(), failed);
        heldLeases.clear();
    }

    /**
     * Release a specific lease by key.
     */
    private boolean releaseLeaseByKey(String leaseKey) {
        try {
            // Try to find and release the lease
            var leaseOpt = leaseRepository.findByKey(leaseKey);
            if (leaseOpt.isPresent()) {
                var lease = leaseOpt.get();
                leaseRepository.release(lease.leaseKey(), lease.holderId());
                log.debug("Released lease: {}", leaseKey);
                return true;
            } else {
                log.debug("Lease not found (may have already expired): {}", leaseKey);
                return true; // Consider this success - lease is not held
            }
        } catch (Exception e) {
            log.warn("Error releasing lease {}: {}", leaseKey, e.getMessage());
            return false;
        }
    }

    /**
     * Check if new tasks can be accepted.
     * Workers should check this before polling for tasks.
     */
    public boolean canAcceptTasks() {
        return !shuttingDown.get();
    }

    /**
     * Create a shutdown-aware task wrapper.
     */
    public Runnable wrapTask(UUID taskExecutionId, Runnable task) {
        return () -> {
            try {
                registerActiveTask(taskExecutionId);
                task.run();
            } finally {
                unregisterActiveTask(taskExecutionId);
            }
        };
    }
}
