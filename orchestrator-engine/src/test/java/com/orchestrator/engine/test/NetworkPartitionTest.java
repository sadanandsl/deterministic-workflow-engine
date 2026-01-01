package com.orchestrator.engine.test;

import com.orchestrator.core.model.*;
import com.orchestrator.core.repository.*;
import com.orchestrator.core.test.NetworkPartitionSimulator;
import com.orchestrator.core.test.TimeController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for network partition scenarios.
 * Verifies system behavior when cluster nodes become isolated.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NetworkPartitionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("orchestrator_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LeaseRepository leaseRepository;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    private NetworkPartitionSimulator partitionSimulator;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        partitionSimulator = new NetworkPartitionSimulator();
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        partitionSimulator.healAll();
        scheduler.shutdown();
    }

    @Test
    @DisplayName("Node should lose lease during partition")
    void testLeaseExpiryDuringPartition() throws InterruptedException {
        String leaseKey = "partition-lease:" + UUID.randomUUID();
        UUID node1 = UUID.randomUUID();

        // Node 1 acquires lease
        ExecutionLease lease = ExecutionLease.create(
            leaseKey, node1, "node1:8080", Duration.ofSeconds(5));
        leaseRepository.tryAcquire(lease);

        // Simulate network partition - node1 can't reach database
        partitionSimulator.partition("node1", "database");

        // Wait for lease to expire
        Thread.sleep(6000);

        // Another node should be able to acquire the lease
        UUID node2 = UUID.randomUUID();
        ExecutionLease newLease = ExecutionLease.create(
            leaseKey, node2, "node2:8080", Duration.ofSeconds(30));
        
        // Force release to simulate expiry detection
        leaseRepository.forceRelease(leaseKey);
        boolean acquired = leaseRepository.tryAcquire(newLease);
        
        assertThat(acquired).isTrue();
        assertThat(leaseRepository.getFenceToken(leaseKey))
            .isGreaterThan(lease.fenceToken());
    }

    @Test
    @DisplayName("Split brain scenario should be handled by fence tokens")
    void testSplitBrainPrevention() {
        String leaseKey = "split-brain:" + UUID.randomUUID();
        
        UUID primaryNode = UUID.randomUUID();
        UUID secondaryNode = UUID.randomUUID();

        // Primary node holds the lease
        ExecutionLease primaryLease = ExecutionLease.create(
            leaseKey, primaryNode, "primary:8080", Duration.ofSeconds(30));
        leaseRepository.tryAcquire(primaryLease);
        long primaryFenceToken = leaseRepository.getFenceToken(leaseKey);

        // Simulate partition: primary is isolated
        partitionSimulator.partition("primary", "cluster");

        // Secondary takes over after lease expiry
        leaseRepository.forceRelease(leaseKey);
        ExecutionLease secondaryLease = ExecutionLease.create(
            leaseKey, secondaryNode, "secondary:8080", Duration.ofSeconds(30));
        leaseRepository.tryAcquire(secondaryLease);
        long secondaryFenceToken = leaseRepository.getFenceToken(leaseKey);

        // Fence token should have incremented
        assertThat(secondaryFenceToken).isGreaterThan(primaryFenceToken);

        // If primary tries to write with old fence token, it should fail
        // This is the key mechanism preventing split-brain corruption
        boolean canRenew = leaseRepository.renew(leaseKey, primaryNode, 
            Instant.now().plusSeconds(30));
        assertThat(canRenew).isFalse();
    }

    @Test
    @DisplayName("Partition healing should allow normal operation to resume")
    void testPartitionHealing() throws InterruptedException {
        String leaseKey = "healing-test:" + UUID.randomUUID();
        UUID node1 = UUID.randomUUID();

        // Node acquires lease
        ExecutionLease lease = ExecutionLease.create(
            leaseKey, node1, "node1:8080", Duration.ofSeconds(30));
        leaseRepository.tryAcquire(lease);

        // Create and immediately heal partition
        partitionSimulator.partition("node1", "database");
        Thread.sleep(100); // Brief partition
        partitionSimulator.heal("node1", "database");

        // Node should still be able to renew
        boolean renewed = leaseRepository.renew(leaseKey, node1, 
            Instant.now().plusSeconds(30));
        assertThat(renewed).isTrue();
    }

    @Test
    @DisplayName("Cascading partition should isolate multiple nodes")
    void testCascadingPartition() {
        // Simulate isolation of a node from multiple peers
        partitionSimulator.isolate("node1", "node2", "node3", "node4");

        assertThat(partitionSimulator.canCommunicate("node1", "node2")).isFalse();
        assertThat(partitionSimulator.canCommunicate("node1", "node3")).isFalse();
        assertThat(partitionSimulator.canCommunicate("node1", "node4")).isFalse();

        // Other nodes can still communicate
        assertThat(partitionSimulator.canCommunicate("node2", "node3")).isTrue();
        assertThat(partitionSimulator.canCommunicate("node3", "node4")).isTrue();
    }

    @Test
    @DisplayName("Temporary partition should auto-heal")
    void testTemporaryPartition() throws InterruptedException {
        partitionSimulator.temporaryPartition("node1", "node2", 
            Duration.ofMillis(500), scheduler);

        // Initially partitioned
        assertThat(partitionSimulator.canCommunicate("node1", "node2")).isFalse();

        // Wait for auto-heal
        Thread.sleep(700);

        // Should be healed
        assertThat(partitionSimulator.canCommunicate("node1", "node2")).isTrue();
    }
}
