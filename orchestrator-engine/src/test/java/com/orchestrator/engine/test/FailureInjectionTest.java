package com.orchestrator.engine.test;

import com.orchestrator.core.model.*;
import com.orchestrator.core.repository.*;
import com.orchestrator.core.test.FailureInjector;
import com.orchestrator.core.test.TimeController;
import com.orchestrator.engine.persistence.jdbc.*;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Failure injection tests for the workflow orchestrator.
 * Tests system behavior under various failure conditions.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FailureInjectionTest {

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
    private WorkflowInstanceRepository workflowInstanceRepository;

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    private ExecutorService executor;
    private TimeController timeController;

    @BeforeAll
    void setUp() {
        executor = Executors.newFixedThreadPool(10);
        timeController = TimeController.frozen();
    }

    @AfterAll
    void tearDown() {
        executor.shutdown();
    }

    // ========== Lease Expiry Tests ==========

    @Test
    @DisplayName("Expired lease should be reclaimable by another worker")
    void testLeaseExpiry() {
        String leaseKey = "lease-expiry-test:" + UUID.randomUUID();
        UUID worker1 = UUID.randomUUID();
        UUID worker2 = UUID.randomUUID();

        // Worker 1 acquires lease
        ExecutionLease lease1 = ExecutionLease.create(
            leaseKey, worker1, "worker1:8080", Duration.ofSeconds(30));
        boolean acquired1 = leaseRepository.tryAcquire(lease1);
        assertThat(acquired1).isTrue();

        // Worker 2 cannot acquire while lease is valid
        ExecutionLease lease2 = ExecutionLease.create(
            leaseKey, worker2, "worker2:8080", Duration.ofSeconds(30));
        boolean acquired2 = leaseRepository.tryAcquire(lease2);
        assertThat(acquired2).isFalse();

        // Simulate time passing beyond lease expiry
        // (In real test, we'd wait or use a time controller)
        // Force release simulates expiry
        long newFenceToken = leaseRepository.forceRelease(leaseKey);
        assertThat(newFenceToken).isGreaterThan(lease1.fenceToken());

        // Worker 2 can now acquire
        acquired2 = leaseRepository.tryAcquire(lease2);
        assertThat(acquired2).isTrue();

        // Original fence token is now invalid
        long currentFenceToken = leaseRepository.getFenceToken(leaseKey);
        assertThat(currentFenceToken).isGreaterThan(lease1.fenceToken());
    }

    @Test
    @DisplayName("Zombie worker with stale fence token should be rejected")
    void testZombieWorkerRejection() {
        String leaseKey = "zombie-test:" + UUID.randomUUID();
        UUID zombieWorker = UUID.randomUUID();
        UUID newWorker = UUID.randomUUID();

        // Zombie acquires lease
        ExecutionLease zombieLease = ExecutionLease.create(
            leaseKey, zombieWorker, "zombie:8080", Duration.ofSeconds(30));
        leaseRepository.tryAcquire(zombieLease);
        long zombieFenceToken = leaseRepository.getFenceToken(leaseKey);

        // Lease expires and new worker takes over
        leaseRepository.forceRelease(leaseKey);
        ExecutionLease newLease = ExecutionLease.create(
            leaseKey, newWorker, "new-worker:8080", Duration.ofSeconds(30));
        leaseRepository.tryAcquire(newLease);

        // Zombie's fence token is now invalid
        long newFenceToken = leaseRepository.getFenceToken(leaseKey);
        assertThat(newFenceToken).isGreaterThan(zombieFenceToken);
        
        // Zombie cannot renew with old holder ID
        boolean renewed = leaseRepository.renew(leaseKey, zombieWorker, Instant.now().plusSeconds(60));
        assertThat(renewed).isFalse();
    }

    // ========== Concurrent Task Execution Tests ==========

    @Test
    @DisplayName("Concurrent lease acquisitions should be serialized")
    void testConcurrentLeaseAcquisition() throws InterruptedException {
        String leaseKey = "concurrent-lease:" + UUID.randomUUID();
        int numWorkers = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numWorkers);
        AtomicInteger successCount = new AtomicInteger(0);

        // Create multiple workers trying to acquire the same lease
        for (int i = 0; i < numWorkers; i++) {
            final int workerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    UUID worker = UUID.randomUUID();
                    ExecutionLease lease = ExecutionLease.create(
                        leaseKey, worker, "worker-" + workerId + ":8080", Duration.ofSeconds(30));
                    if (leaseRepository.tryAcquire(lease)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all workers simultaneously
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // Exactly one worker should have acquired the lease
        assertThat(successCount.get()).isEqualTo(1);
    }

    // ========== Optimistic Locking Tests ==========

    @Test
    @DisplayName("Concurrent workflow updates should detect conflicts")
    void testOptimisticLockingConflict() throws Exception {
        // Create a workflow instance
        WorkflowInstance instance = WorkflowInstance.create(
            "test", "conflict-workflow", 1, 
            "run-" + UUID.randomUUID(), null, null, null);
        workflowInstanceRepository.save(instance);

        // Read the same instance twice
        WorkflowInstance read1 = workflowInstanceRepository.findById(instance.instanceId())
            .orElseThrow();
        WorkflowInstance read2 = workflowInstanceRepository.findById(instance.instanceId())
            .orElseThrow();

        // First update succeeds
        WorkflowInstance updated1 = read1.toBuilder()
            .state(WorkflowState.RUNNING)
            .incrementSequence()
            .build();
        workflowInstanceRepository.update(updated1);

        // Second update with stale sequence number should fail
        WorkflowInstance updated2 = read2.toBuilder()
            .state(WorkflowState.PAUSED)
            .incrementSequence()
            .build();
        
        // This should throw due to optimistic locking conflict
        assertThatThrownBy(() -> workflowInstanceRepository.update(updated2))
            .isInstanceOf(RuntimeException.class);
    }

    // ========== Idempotency Tests ==========

    @Test
    @DisplayName("Duplicate workflow creation should be idempotent")
    void testIdempotentWorkflowCreation() {
        String runId = "idempotent-run-" + UUID.randomUUID();
        
        // Create workflow instance
        WorkflowInstance instance1 = WorkflowInstance.create(
            "test", "idempotent-workflow", 1, runId, null, null, null);
        workflowInstanceRepository.save(instance1);

        // Try to create another instance with same run_id
        WorkflowInstance instance2 = WorkflowInstance.create(
            "test", "idempotent-workflow", 1, runId, null, null, null);
        
        // Second save should be ignored (idempotent)
        // or return the existing instance depending on implementation
        workflowInstanceRepository.save(instance2);

        // Should still have only one instance
        var existing = workflowInstanceRepository.findByRunId("test", "idempotent-workflow", runId);
        assertThat(existing).isPresent();
        assertThat(existing.get().instanceId()).isEqualTo(instance1.instanceId());
    }

    // ========== Recovery Tests ==========

    @Test
    @DisplayName("Stuck workflows should be detectable")
    void testStuckWorkflowDetection() {
        // Create a workflow that appears stuck
        WorkflowInstance stuckInstance = WorkflowInstance.create(
            "test", "stuck-workflow", 1, 
            "run-" + UUID.randomUUID(), null, null, null);
        stuckInstance = stuckInstance.toBuilder()
            .state(WorkflowState.RUNNING)
            .startedAt(Instant.now().minus(Duration.ofHours(2)))
            .build();
        workflowInstanceRepository.save(stuckInstance);

        // Find stuck workflows (running for more than 1 hour with no progress)
        List<WorkflowInstance> stuck = workflowInstanceRepository.findStuckInstances(
            Duration.ofHours(1), 100);

        assertThat(stuck).isNotEmpty();
        assertThat(stuck).anyMatch(w -> w.instanceId().equals(stuckInstance.instanceId()));
    }

    // ========== Failure Injection Utility Tests ==========

    @Test
    @DisplayName("Failure injector should fail at configured rate")
    void testFailureInjectorRate() {
        FailureInjector injector = FailureInjector.builder()
            .withFailureRate(0.5)
            .withSeed(12345) // Reproducible
            .build();

        int iterations = 1000;
        int failures = 0;

        for (int i = 0; i < iterations; i++) {
            try {
                injector.maybeThrow(new RuntimeException("Test failure"));
            } catch (RuntimeException e) {
                failures++;
            }
        }

        // Should be approximately 50% (within reasonable tolerance)
        double actualRate = (double) failures / iterations;
        assertThat(actualRate).isBetween(0.4, 0.6);
    }

    @Test
    @DisplayName("Failure injector can be disabled")
    void testFailureInjectorDisable() {
        FailureInjector injector = FailureInjector.alwaysFail();
        
        // Should fail when enabled
        assertThatThrownBy(() -> injector.maybeThrow(new RuntimeException("Fail")))
            .isInstanceOf(RuntimeException.class);

        // Disable and should not fail
        injector.disable();
        assertThatNoException().isThrownBy(() -> 
            injector.maybeThrow(new RuntimeException("Should not throw")));
    }
}
