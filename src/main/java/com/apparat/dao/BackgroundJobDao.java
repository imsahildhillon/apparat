package com.apparat.dao;

import com.apparat.model.BackgroundJobRecord;
import com.apparat.model.enums.JobStatus;
import com.apparat.model.enums.JobType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO for the durable job/outbox table. See
 * PROJECT_BLUEPRINT_CORRECTED.md §17.2 and ARCHITECTURE_DECISIONS.md ADR-9.
 *
 * #enqueue is always called with a caller-supplied Connection FROM WITHIN
 * the same transaction as the domain change that produced the job (e.g.
 * BookingService#createBooking) — that is the entire point of the outbox
 * pattern: the job's intent commits atomically with the change it follows
 * from, so there is no crash window between "domain change committed" and
 * "background work is now guaranteed to happen eventually."
 *
 * #claimNext uses a single conditional UPDATE, never a SELECT followed by a
 * separate UPDATE — see CORRECTIONS_LOG.md Risk #8 / CONCURRENCY_TEST_PLAN.md
 * Test 8: two workers racing to claim the same row must never both succeed.
 */
public class BackgroundJobDao extends BaseDao {

    public Long enqueue(Connection con, JobType type, String payload) throws SQLException {
        String sql = "INSERT INTO background_jobs (job_type, payload, status, available_at) VALUES (?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type.name());
            ps.setString(2, payload);
            ps.setString(3, JobStatus.PENDING.name());
            ps.setObject(4, LocalDateTime.now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Finds up to {@code limit} PENDING rows whose available_at has passed — the worker's poll, a plain SELECT that does NOT itself claim anything (see #claimNext). */
    public List<BackgroundJobRecord> findDue(Connection con, int limit) throws SQLException {
        String sql = "SELECT * FROM background_jobs WHERE status = 'PENDING' AND available_at <= ? ORDER BY available_at LIMIT ?";
        List<BackgroundJobRecord> jobs = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, LocalDateTime.now());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) jobs.add(mapRow(rs));
            }
        }
        return jobs;
    }

    /**
     * The claim itself: a single conditional UPDATE whose WHERE clause
     * re-checks status = 'PENDING'. If two workers race for the same row,
     * exactly one UPDATE affects a row; the other affects zero and must not
     * proceed to execute the job. This is what makes claiming race-free
     * without any additional application-level lock.
     */
    public boolean claim(Connection con, Long jobId) throws SQLException {
        String sql = "UPDATE background_jobs SET status = 'RUNNING', locked_at = ? WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, LocalDateTime.now());
            ps.setLong(2, jobId);
            return ps.executeUpdate() == 1;
        }
    }

    public void markComplete(Connection con, Long jobId) throws SQLException {
        String sql = "UPDATE background_jobs SET status = 'COMPLETE', completed_at = ? WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, LocalDateTime.now());
            ps.setLong(2, jobId);
            ps.executeUpdate();
        }
    }

    /** Failure path: bump attempt_count, reschedule with a small linear backoff, or mark FAILED once the retry budget is exhausted. */
    public void markFailedOrRetry(Connection con, Long jobId, int attemptCount, int maxAttempts, String errorMessage) throws SQLException {
        if (attemptCount + 1 >= maxAttempts) {
            String sql = "UPDATE background_jobs SET status = 'FAILED', attempt_count = ?, error_message = ? WHERE id = ?";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, attemptCount + 1);
                ps.setString(2, errorMessage);
                ps.setLong(3, jobId);
                ps.executeUpdate();
            }
        } else {
            String sql = "UPDATE background_jobs SET status = 'PENDING', attempt_count = ?, available_at = ?, error_message = ? WHERE id = ?";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, attemptCount + 1);
                ps.setObject(2, LocalDateTime.now().plusSeconds(10L * (attemptCount + 1)));
                ps.setString(3, errorMessage);
                ps.setLong(4, jobId);
                ps.executeUpdate();
            }
        }
    }

    /** Reclaims RUNNING rows whose lock is older than {@code staleAfterSeconds} — a worker that crashed mid-job leaves its row stuck RUNNING otherwise. Called periodically by job.QueueCheckpointer. */
    public int reclaimStalled(Connection con, int staleAfterSeconds) throws SQLException {
        String sql = "UPDATE background_jobs SET status = 'PENDING' WHERE status = 'RUNNING' AND locked_at < ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, LocalDateTime.now().minusSeconds(staleAfterSeconds));
            return ps.executeUpdate();
        }
    }

    private BackgroundJobRecord mapRow(ResultSet rs) throws SQLException {
        BackgroundJobRecord r = new BackgroundJobRecord();
        r.setId(rs.getLong("id"));
        r.setJobType(JobType.valueOf(rs.getString("job_type")));
        r.setPayload(rs.getString("payload"));
        r.setStatus(JobStatus.valueOf(rs.getString("status")));
        r.setAttemptCount(rs.getInt("attempt_count"));
        r.setAvailableAt(rs.getObject("available_at", LocalDateTime.class));
        r.setLockedAt(rs.getObject("locked_at", LocalDateTime.class));
        r.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return r;
    }
}
