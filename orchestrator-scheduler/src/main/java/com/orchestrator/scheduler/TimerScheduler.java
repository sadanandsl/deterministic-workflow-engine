package com.orchestrator.scheduler;

import com.orchestrator.core.model.Event;
import com.orchestrator.core.model.EventType;
import com.orchestrator.core.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Time-based scheduler for workflow timers and delays.
 * 
 * Responsibilities:
 * - Schedule timers for WAIT tasks
 * - Handle cron-based workflow triggers
 * - Fire timer events when due
 */
public class TimerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimerScheduler.class);
    
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 100;

    private final TimerRepository timerRepository;
    private final EventRepository eventRepository;
    private final TimerCallback callback;
    
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public TimerScheduler(
            TimerRepository timerRepository,
            EventRepository eventRepository,
            TimerCallback callback) {
        this.timerRepository = timerRepository;
        this.eventRepository = eventRepository;
        this.callback = callback;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * Start the scheduler.
     */
    public void start() {
        if (running) {
            log.warn("Timer scheduler already running");
            return;
        }
        
        running = true;
        log.info("Starting timer scheduler");
        
        // Schedule timer polling
        scheduler.scheduleWithFixedDelay(
            this::pollTimers,
            POLL_INTERVAL.toSeconds(),
            POLL_INTERVAL.toSeconds(),
            TimeUnit.SECONDS
        );
        
        log.info("Timer scheduler started");
    }

    /**
     * Stop the scheduler.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Timer scheduler stopped");
    }

    /**
     * Schedule a timer to fire at a specific time.
     * 
     * @param workflowInstanceId The workflow instance
     * @param taskId The task waiting for this timer
     * @param fireAt When the timer should fire
     * @return The timer ID
     */
    public UUID scheduleTimer(UUID workflowInstanceId, String taskId, Instant fireAt) {
        ScheduledTimer timer = new ScheduledTimer(
            UUID.randomUUID(),
            workflowInstanceId,
            taskId,
            TimerType.ABSOLUTE,
            fireAt,
            false,
            null,
            Instant.now(),
            null
        );
        
        timerRepository.save(timer);
        
        log.info("Scheduled timer {} for workflow {} at {}", timer.timerId(), workflowInstanceId, fireAt);
        return timer.timerId();
    }

    /**
     * Schedule a timer to fire after a delay.
     * 
     * @param workflowInstanceId The workflow instance
     * @param taskId The task waiting for this timer
     * @param delay The delay before firing
     * @return The timer ID
     */
    public UUID scheduleDelay(UUID workflowInstanceId, String taskId, Duration delay) {
        return scheduleTimer(workflowInstanceId, taskId, Instant.now().plus(delay));
    }

    /**
     * Cancel a scheduled timer.
     * 
     * @param timerId The timer ID
     * @return true if the timer was cancelled
     */
    public boolean cancelTimer(UUID timerId) {
        return timerRepository.cancel(timerId);
    }

    /**
     * Poll for due timers and fire them.
     */
    private void pollTimers() {
        if (!running) return;
        
        try {
            Instant now = Instant.now();
            List<ScheduledTimer> dueTimers = timerRepository.findDue(now, BATCH_SIZE);
            
            for (ScheduledTimer timer : dueTimers) {
                try {
                    fireTimer(timer);
                } catch (Exception e) {
                    log.error("Failed to fire timer: {}", timer.timerId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error polling timers", e);
        }
    }

    private void fireTimer(ScheduledTimer timer) {
        log.info("Firing timer {} for workflow {}", timer.timerId(), timer.workflowInstanceId());
        
        // Mark timer as fired
        timerRepository.markFired(timer.timerId(), Instant.now());
        
        // Record timer fired event
        recordEvent(timer.workflowInstanceId(), EventType.TIMER_FIRED,
            "timer-fired:" + timer.timerId());
        
        // Notify callback
        callback.onTimerFired(timer);
    }

    private void recordEvent(UUID workflowInstanceId, EventType type, String idempotencyKey) {
        long sequenceNumber = eventRepository.getNextSequenceNumber(workflowInstanceId);
        
        Event event = Event.create(
            workflowInstanceId,
            sequenceNumber,
            type,
            null,
            idempotencyKey,
            null,
            Event.ACTOR_SCHEDULER,
            "timer-scheduler"
        );
        
        eventRepository.append(event);
    }

    /**
     * Callback for timer events.
     */
    public interface TimerCallback {
        void onTimerFired(ScheduledTimer timer);
    }

    /**
     * Repository for scheduled timers.
     */
    public interface TimerRepository {
        void save(ScheduledTimer timer);
        List<ScheduledTimer> findDue(Instant now, int limit);
        boolean cancel(UUID timerId);
        void markFired(UUID timerId, Instant firedAt);
    }

    /**
     * Timer type.
     */
    public enum TimerType {
        ABSOLUTE,   // Fire at specific time
        DELAY,      // Fire after delay
        CRON        // Fire on schedule
    }

    /**
     * Scheduled timer record.
     */
    public record ScheduledTimer(
        UUID timerId,
        UUID workflowInstanceId,
        String taskId,
        TimerType type,
        Instant fireAt,
        boolean fired,
        Instant firedAt,
        Instant createdAt,
        Object payload
    ) {}
}
