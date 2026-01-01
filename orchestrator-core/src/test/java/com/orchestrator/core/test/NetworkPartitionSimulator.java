package com.orchestrator.core.test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Network partition simulator for testing distributed system behavior.
 * Simulates network partitions between cluster nodes.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * NetworkPartitionSimulator simulator = new NetworkPartitionSimulator();
 * 
 * // Create a partition between node1 and node2
 * simulator.partition("node1", "node2");
 * 
 * // Check if nodes can communicate
 * if (!simulator.canCommunicate("node1", "node2")) {
 *     // Handle partition scenario
 * }
 * 
 * // Heal the partition
 * simulator.heal("node1", "node2");
 * }</pre>
 */
public class NetworkPartitionSimulator {

    private final ConcurrentMap<String, ConcurrentSet<String>> partitions;
    private final ConcurrentMap<String, Long> partitionTimestamps;
    private final AtomicInteger partitionCount;
    private BiConsumer<String, String> partitionCallback;

    public NetworkPartitionSimulator() {
        this.partitions = new ConcurrentHashMap<>();
        this.partitionTimestamps = new ConcurrentHashMap<>();
        this.partitionCount = new AtomicInteger(0);
    }

    /**
     * Create a partition between two nodes.
     * Communication in both directions will be blocked.
     */
    public void partition(String node1, String node2) {
        addPartition(node1, node2);
        addPartition(node2, node1);
        partitionTimestamps.put(partitionKey(node1, node2), System.currentTimeMillis());
        partitionCount.incrementAndGet();
        
        if (partitionCallback != null) {
            partitionCallback.accept(node1, node2);
        }
    }

    /**
     * Heal a partition between two nodes.
     */
    public void heal(String node1, String node2) {
        removePartition(node1, node2);
        removePartition(node2, node1);
        partitionTimestamps.remove(partitionKey(node1, node2));
    }

    /**
     * Heal all partitions.
     */
    public void healAll() {
        partitions.clear();
        partitionTimestamps.clear();
    }

    /**
     * Check if two nodes can communicate.
     */
    public boolean canCommunicate(String from, String to) {
        ConcurrentSet<String> blocked = partitions.get(from);
        return blocked == null || !blocked.contains(to);
    }

    /**
     * Check if a node is completely isolated.
     */
    public boolean isIsolated(String node) {
        ConcurrentSet<String> blocked = partitions.get(node);
        return blocked != null && !blocked.isEmpty();
    }

    /**
     * Get the duration of a partition.
     */
    public Duration getPartitionDuration(String node1, String node2) {
        Long timestamp = partitionTimestamps.get(partitionKey(node1, node2));
        if (timestamp == null) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(System.currentTimeMillis() - timestamp);
    }

    /**
     * Get total number of partitions created.
     */
    public int getPartitionCount() {
        return partitionCount.get();
    }

    /**
     * Set a callback for when partitions are created.
     */
    public void onPartition(BiConsumer<String, String> callback) {
        this.partitionCallback = callback;
    }

    /**
     * Create a temporary partition that heals after a duration.
     */
    public void temporaryPartition(String node1, String node2, Duration duration, 
                                   ScheduledExecutorService executor) {
        partition(node1, node2);
        executor.schedule(() -> heal(node1, node2), duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Isolate a node from all others.
     */
    public void isolate(String node, String... otherNodes) {
        for (String other : otherNodes) {
            partition(node, other);
        }
    }

    private void addPartition(String from, String to) {
        partitions.computeIfAbsent(from, k -> new ConcurrentSet<>()).add(to);
    }

    private void removePartition(String from, String to) {
        ConcurrentSet<String> blocked = partitions.get(from);
        if (blocked != null) {
            blocked.remove(to);
        }
    }

    private String partitionKey(String node1, String node2) {
        // Ensure consistent key regardless of order
        return node1.compareTo(node2) < 0 ? node1 + ":" + node2 : node2 + ":" + node1;
    }

    /**
     * Thread-safe set implementation.
     */
    private static class ConcurrentSet<E> {
        private final ConcurrentMap<E, Boolean> map = new ConcurrentHashMap<>();

        public void add(E element) {
            map.put(element, Boolean.TRUE);
        }

        public void remove(E element) {
            map.remove(element);
        }

        public boolean contains(E element) {
            return map.containsKey(element);
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public void clear() {
            map.clear();
        }
    }
}
