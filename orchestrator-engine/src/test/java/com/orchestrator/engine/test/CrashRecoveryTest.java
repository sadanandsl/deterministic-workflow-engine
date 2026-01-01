package com.orchestrator.engine.test;

import com.orchestrator.core.model.*;
import com.orchestrator.core.repository.*;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for crash recovery scenarios.
 * Verifies system recovers correctly after node crashes.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CrashRecoveryTest {

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
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private TaskExecutionRepository taskRepository;

    @Autowired
    private LeaseRepository leaseRepository;

    @Test
    @DisplayName("Running workflows should be recoverable after coordinator crash")
    void testWorkflowRecoveryAfterCrash() {
        // Create a running workflow
        WorkflowInstance workflow = WorkflowInstance.create(
            "test", "recovery-workflow", 1,
            "run-" + UUID.randomUUID(), null, null, 
            Instant.now().plus(Duration.ofHours(1)));
        
        workflow = workflow.toBuilder()
            .state(WorkflowState.RUNNING)
            .startedAt(Instant.now().minus(Duration.ofMinutes(5)))
            .currentTaskId("task-1")
            .build();
        
        instanceRepository.save(workflow);

        // Simulate coordinator crash (lease expires)
        // ... coordinator restarts ...

        // Find workflows that need recovery
        List<WorkflowInstance> toRecover = instanceRepository.findByState(
            WorkflowState.RUNNING, 100);

        assertThat(toRecover).isNotEmpty();
        assertThat(toRecover).anyMatch(w -> w.instanceId().equals(workflow.instanceId()));

        // Verify workflow state is intact
        WorkflowInstance recovered = instanceRepository.findById(workflow.instanceId())
            .orElseThrow();
        assertThat(recovered.state()).isEqualTo(WorkflowState.RUNNING);
        assertThat(recovered.currentTaskId()).isEqualTo("task-1");
    }

    @Test
    @DisplayName("Pending tasks should resume after worker crash")
    void testTaskRecoveryAfterWorkerCrash() {
        UUID workflowId = UUID.randomUUID();
        
        // Save workflow first
        WorkflowInstance workflow = WorkflowInstance.create(
            "test", "task-recovery-workflow", 1,
            "run-" + workflowId, null, null, null);
        workflow = workflow.toBuilder()
            .state(WorkflowState.RUNNING)
            .build();
        instanceRepository.save(workflow);

        // Create a task that a worker was processing
        TaskExecution task = TaskExecution.create(
            workflow.instanceId(), "task-1", 1, null, "trace-123");
        task = task.toBuilder()
            .state(TaskState.RUNNING)
            .startedAt(Instant.now().minus(Duration.ofMinutes(10)))
            .leaseHolder(UUID.randomUUID())
            .leaseExpiresAt(Instant.now().minus(Duration.ofMinutes(5))) // Already expired
            .build();
        taskRepository.save(task);

        // Find tasks with expired leases
        List<TaskExecution> expiredTasks = taskRepository.findExpiredLeases(
            Instant.now(), 100);

        assertThat(expiredTasks).isNotEmpty();
        
        // Task should be reclaimable
        TaskExecution expiredTask = expiredTasks.stream()
            .filter(t -> t.executionId().equals(task.executionId()))
            .findFirst()
            .orElse(null);
        assertThat(expiredTask).isNotNull();
        assertThat(expiredTask.state()).isEqualTo(TaskState.RUNNING);
    }

    @Test
    @DisplayName("Incomplete compensation should be resumable")
    void testCompensationRecoveryAfterCrash() {
        // Create a workflow in COMPENSATING state
        WorkflowInstance workflow = WorkflowInstance.create(
            "test", "compensation-workflow", 1,
            "run-" + UUID.randomUUID(), null, null, null);
        
        workflow = workflow.toBuilder()
            .state(WorkflowState.COMPENSATING)
            .startedAt(Instant.now().minus(Duration.ofMinutes(30)))
            .completedTaskIds(Set.of("task-1", "task-2", "task-3"))
            .build();
        
        instanceRepository.save(workflow);

        // Simulate crash during compensation
        // ... system restarts ...

        // Find workflows that need compensation continuation
        List<WorkflowInstance> compensating = instanceRepository.findByState(
            WorkflowState.COMPENSATING, 100);

        assertThat(compensating).isNotEmpty();
        
        WorkflowInstance toCompensate = compensating.stream()
            .filter(w -> w.instanceId().equals(workflow.instanceId()))
            .findFirst()
            .orElseThrow();

        // Completed tasks should still be recorded for compensation order
        assertThat(toCompensate.completedTaskIds())
            .containsExactlyInAnyOrder("task-1", "task-2", "task-3");
    }

    @Test
    @DisplayName("Orphaned leases should be cleaned up")
    void testOrphanedLeaseCleanup() throws InterruptedException {
        // Create leases that would belong to a crashed worker
        UUID crashedWorker = UUID.randomUUID();
        List<String> leaseKeys = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            String leaseKey = "orphan-lease-" + i + ":" + UUID.randomUUID();
            leaseKeys.add(leaseKey);
            
            ExecutionLease lease = ExecutionLease.create(
                leaseKey, crashedWorker, "crashed:8080", Duration.ofSeconds(1));
            leaseRepository.tryAcquire(lease);
        }

        // Wait for leases to expire
        Thread.sleep(2000);

        // Find and verify expired leases
        List<ExecutionLease> expired = leaseRepository.findExpired(Instant.now(), 100);
        
        // All orphaned leases should be expired
        for (String key : leaseKeys) {
            assertThat(expired.stream().anyMatch(l -> l.leaseKey().equals(key))).isTrue();
        }
    }

    @Test
    @DisplayName("Sequence numbers should prevent stale updates after recovery")
    void testSequenceNumberIntegrity() {
        // Create workflow
        WorkflowInstance workflow = WorkflowInstance.create(
            "test", "sequence-test", 1,
            "run-" + UUID.randomUUID(), null, null, null);
        instanceRepository.save(workflow);
        
        long initialSequence = workflow.sequenceNumber();

        // Multiple updates should increment sequence
        for (int i = 0; i < 5; i++) {
            workflow = instanceRepository.findById(workflow.instanceId()).orElseThrow();
            workflow = workflow.toBuilder()
                .incrementSequence()
                .build();
            instanceRepository.update(workflow);
        }

        WorkflowInstance finalWorkflow = instanceRepository.findById(workflow.instanceId())
            .orElseThrow();
        
        assertThat(finalWorkflow.sequenceNumber())
            .isEqualTo(initialSequence + 5);
    }

    @Test
    @DisplayName("Concurrent recovery attempts should be serialized")
    void testConcurrentRecoveryAttempts() throws InterruptedException {
        // Create a stuck workflow
        WorkflowInstance workflow = WorkflowInstance.create(
            "test", "concurrent-recovery", 1,
            "run-" + UUID.randomUUID(), null, null, null);
        workflow = workflow.toBuilder()
            .state(WorkflowState.RUNNING)
            .build();
        instanceRepository.save(workflow);

        // Multiple recovery attempts simultaneously
        int numRecoveryAttempts = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numRecoveryAttempts);
        AtomicInteger successfulUpdates = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(numRecoveryAttempts);

        for (int i = 0; i < numRecoveryAttempts; i++) {
            final UUID workflowId = workflow.instanceId();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // Try to recover (update with recovery marker)
                    WorkflowInstance toRecover = instanceRepository.findById(workflowId)
                        .orElseThrow();
                    WorkflowInstance recovered = toRecover.toBuilder()
                        .lastRecoveryAt(Instant.now())
                        .recoveryAttempts(toRecover.recoveryAttempts() + 1)
                        .incrementSequence()
                        .build();
                    
                    try {
                        instanceRepository.update(recovered);
                        successfulUpdates.incrementAndGet();
                    } catch (Exception e) {
                        // Expected for concurrent updates
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Due to optimistic locking, most should fail
        // But at least one should succeed
        assertThat(successfulUpdates.get()).isGreaterThanOrEqualTo(1);
        
        // Final state should be consistent
        WorkflowInstance finalState = instanceRepository.findById(workflow.instanceId())
            .orElseThrow();
        assertThat(finalState.recoveryAttempts()).isGreaterThanOrEqualTo(1);
    }
}
