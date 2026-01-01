package com.orchestrator.engine.persistence.jdbc;

import com.orchestrator.core.model.ExecutionLease;
import com.orchestrator.core.repository.LeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed implementation of LeaseRepository.
 * Provides distributed locking with fence tokens for exactly-once execution.
 * 
 * The fence token mechanism ensures that even if a zombie worker
 * (one that lost its lease but doesn't know it) tries to complete
 * a task, its completion will be rejected due to token mismatch.
 */
@Repository("jdbcLeaseRepository")
public class JdbcLeaseRepository implements LeaseRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcLeaseRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ExecutionLeaseRowMapper rowMapper = new ExecutionLeaseRowMapper();

    public JdbcLeaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public boolean tryAcquire(ExecutionLease lease) {
        // Try to insert or update only if lease is expired or doesn't exist
        String sql = """
            INSERT INTO execution_leases (
                lease_key, holder_id, holder_address,
                acquired_at, expires_at, lease_duration_ms,
                renewal_count, fence_token
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (lease_key) DO UPDATE SET
                holder_id = EXCLUDED.holder_id,
                holder_address = EXCLUDED.holder_address,
                acquired_at = EXCLUDED.acquired_at,
                expires_at = EXCLUDED.expires_at,
                lease_duration_ms = EXCLUDED.lease_duration_ms,
                renewal_count = 0,
                fence_token = execution_leases.fence_token + 1
            WHERE execution_leases.expires_at < NOW() OR execution_leases.holder_id IS NULL
            RETURNING fence_token
            """;

        try {
            Long newFenceToken = jdbcTemplate.queryForObject(sql, Long.class,
                lease.leaseKey(),
                lease.holderId(),
                lease.holderAddress(),
                Timestamp.from(lease.acquiredAt()),
                Timestamp.from(lease.expiresAt()),
                lease.leaseDuration().toMillis(),
                lease.renewalCount(),
                lease.fenceToken()
            );
            
            log.debug("Acquired lease {} with fence token {}", lease.leaseKey(), newFenceToken);
            return true;
        } catch (Exception e) {
            // Could not acquire - lease is held by another worker
            log.debug("Failed to acquire lease {}: {}", lease.leaseKey(), e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public boolean renew(String leaseKey, UUID holderId, Instant newExpiresAt) {
        String sql = """
            UPDATE execution_leases SET
                expires_at = ?,
                renewal_count = renewal_count + 1
            WHERE lease_key = ? AND holder_id = ? AND expires_at > NOW()
            """;

        int rows = jdbcTemplate.update(sql,
            Timestamp.from(newExpiresAt),
            leaseKey,
            holderId
        );

        if (rows > 0) {
            log.debug("Renewed lease {} for holder {}", leaseKey, holderId);
            return true;
        } else {
            log.warn("Failed to renew lease {} for holder {} - lease expired or holder mismatch", 
                leaseKey, holderId);
            return false;
        }
    }

    @Override
    @Transactional
    public boolean release(String leaseKey, UUID holderId) {
        String sql = """
            UPDATE execution_leases SET
                holder_id = NULL,
                expires_at = NOW()
            WHERE lease_key = ? AND holder_id = ?
            """;

        int rows = jdbcTemplate.update(sql, leaseKey, holderId);

        if (rows > 0) {
            log.debug("Released lease {} by holder {}", leaseKey, holderId);
            return true;
        } else {
            log.debug("Lease {} not held by {} or already released", leaseKey, holderId);
            return false;
        }
    }

    @Override
    @Transactional
    public long forceRelease(String leaseKey) {
        // Force release and increment fence token to invalidate any zombie worker
        String sql = """
            UPDATE execution_leases SET
                holder_id = NULL,
                expires_at = NOW(),
                fence_token = fence_token + 1
            WHERE lease_key = ?
            RETURNING fence_token
            """;

        try {
            Long newFenceToken = jdbcTemplate.queryForObject(sql, Long.class, leaseKey);
            log.info("Force released lease {} with new fence token {}", leaseKey, newFenceToken);
            return newFenceToken != null ? newFenceToken : 0L;
        } catch (Exception e) {
            // Lease doesn't exist
            log.debug("No lease found to force release: {}", leaseKey);
            return 0L;
        }
    }

    @Override
    public Optional<ExecutionLease> findByKey(String leaseKey) {
        String sql = "SELECT * FROM execution_leases WHERE lease_key = ?";
        List<ExecutionLease> results = jdbcTemplate.query(sql, rowMapper, leaseKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<ExecutionLease> findByHolder(UUID holderId) {
        String sql = "SELECT * FROM execution_leases WHERE holder_id = ?";
        return jdbcTemplate.query(sql, rowMapper, holderId);
    }

    @Override
    public List<ExecutionLease> findExpired(Instant now, int limit) {
        String sql = """
            SELECT * FROM execution_leases 
            WHERE expires_at < ? AND holder_id IS NOT NULL
            ORDER BY expires_at
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, rowMapper, Timestamp.from(now), limit);
    }

    @Override
    public long getFenceToken(String leaseKey) {
        String sql = "SELECT fence_token FROM execution_leases WHERE lease_key = ?";
        try {
            Long token = jdbcTemplate.queryForObject(sql, Long.class, leaseKey);
            return token != null ? token : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public int cleanupExpired(Instant olderThan) {
        String sql = """
            DELETE FROM execution_leases 
            WHERE expires_at < ? AND holder_id IS NULL
            """;
        return jdbcTemplate.update(sql, Timestamp.from(olderThan));
    }

    private static class ExecutionLeaseRowMapper implements RowMapper<ExecutionLease> {
        @Override
        public ExecutionLease mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID holderId = rs.getString("holder_id") != null 
                ? UUID.fromString(rs.getString("holder_id")) : null;
            
            Timestamp acquiredAt = rs.getTimestamp("acquired_at");
            Timestamp expiresAt = rs.getTimestamp("expires_at");
            
            return new ExecutionLease(
                rs.getString("lease_key"),
                holderId,
                rs.getString("holder_address"),
                acquiredAt != null ? acquiredAt.toInstant() : null,
                expiresAt != null ? expiresAt.toInstant() : null,
                Duration.ofMillis(rs.getLong("lease_duration_ms")),
                rs.getInt("renewal_count"),
                rs.getLong("fence_token")
            );
        }
    }
}
