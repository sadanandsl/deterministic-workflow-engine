package com.orchestrator.engine.persistence;

import com.orchestrator.core.model.ExecutionLease;
import com.orchestrator.core.repository.LeaseRepository;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory implementation of LeaseRepository.
 * For demonstration and testing purposes.
 */
@Repository
public class InMemoryLeaseRepository implements LeaseRepository {
    
    private final Map<String, ExecutionLease> leases = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> fenceTokens = new ConcurrentHashMap<>();
    
    @Override
    public boolean tryAcquire(ExecutionLease lease) {
        synchronized (leases) {
            ExecutionLease existing = leases.get(lease.leaseKey());
            Instant now = Instant.now();
            
            if (existing != null && existing.expiresAt().isAfter(now)) {
                // Lease still held by someone
                return false;
            }
            
            // Store the lease
            leases.put(lease.leaseKey(), lease);
            fenceTokens.computeIfAbsent(lease.leaseKey(), k -> new AtomicLong(0))
                .set(lease.fenceToken());
            return true;
        }
    }
    
    @Override
    public boolean renew(String leaseKey, UUID holderId, Instant newExpiresAt) {
        synchronized (leases) {
            ExecutionLease existing = leases.get(leaseKey);
            if (existing == null || !existing.holderId().equals(holderId)) {
                return false;
            }
            
            Instant now = Instant.now();
            if (existing.expiresAt().isBefore(now)) {
                // Lease already expired
                return false;
            }
            
            ExecutionLease renewed = new ExecutionLease(
                existing.leaseKey(),
                existing.holderId(),
                existing.holderAddress(),
                existing.acquiredAt(),
                newExpiresAt,
                existing.leaseDuration(),
                existing.renewalCount() + 1,
                existing.fenceToken()
            );
            leases.put(leaseKey, renewed);
            return true;
        }
    }
    
    @Override
    public boolean release(String leaseKey, UUID holderId) {
        synchronized (leases) {
            ExecutionLease existing = leases.get(leaseKey);
            if (existing == null || !existing.holderId().equals(holderId)) {
                return false;
            }
            leases.remove(leaseKey);
            return true;
        }
    }
    
    @Override
    public long forceRelease(String leaseKey) {
        synchronized (leases) {
            leases.remove(leaseKey);
            long newToken = fenceTokens
                .computeIfAbsent(leaseKey, k -> new AtomicLong(0))
                .incrementAndGet();
            return newToken;
        }
    }
    
    @Override
    public Optional<ExecutionLease> findByKey(String leaseKey) {
        return Optional.ofNullable(leases.get(leaseKey));
    }
    
    @Override
    public List<ExecutionLease> findByHolder(UUID holderId) {
        return leases.values().stream()
            .filter(l -> l.holderId().equals(holderId))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<ExecutionLease> findExpired(Instant now, int limit) {
        return leases.values().stream()
            .filter(l -> l.expiresAt().isBefore(now))
            .sorted(Comparator.comparing(ExecutionLease::expiresAt))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Override
    public long getFenceToken(String leaseKey) {
        AtomicLong token = fenceTokens.get(leaseKey);
        return token != null ? token.get() : 0;
    }
    
    @Override
    public boolean validateFenceToken(String leaseKey, long fenceToken) {
        AtomicLong token = fenceTokens.get(leaseKey);
        return token != null && token.get() == fenceToken;
    }
    
    @Override
    public int deleteExpiredBefore(Instant expiredBefore) {
        List<String> toRemove = leases.entrySet().stream()
            .filter(e -> e.getValue().expiresAt().isBefore(expiredBefore))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        toRemove.forEach(leases::remove);
        return toRemove.size();
    }
}
