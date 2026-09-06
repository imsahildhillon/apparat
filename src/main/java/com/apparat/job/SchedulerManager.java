package com.apparat.job;

import com.apparat.config.ConnectionFactory;
import com.apparat.dao.BackgroundJobDao;
import com.apparat.model.BackgroundJobRecord;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns Apparat's two thread pools and drives the outbox worker cycle. See
 * PROJECT_BLUEPRINT_CORRECTED.md §18.1-18.2.
 *
 * ScheduledExecutorService (2 threads) — recurring maintenance:
 *   - pollAndDispatch: finds PENDING background_jobs rows and hands them to the worker pool
 *   - checkpointAndReclaim: (a) best-effort local checkpoint of in-flight jobs,
 *     purely diagnostic (see Job's Javadoc — the DB stays authoritative regardless);
 *     (b) resets stalled RUNNING rows back to PENDING so a crashed worker's job is retried
 *
 * ExecutorService (fixed pool) — job execution: claims a row with a single
 * conditional UPDATE (BackgroundJobDao#claim — never a SELECT then a
 * separate UPDATE, see CORRECTIONS_LOG.md Risk #8), executes it, marks it
 * COMPLETE or reschedules it on failure.
 */
public class SchedulerManager {

    private static final int MAX_ATTEMPTS = 5;
    private static final int STALE_RUNNING_SECONDS = 120;

    private final BackgroundJobDao backgroundJobDao;
    private final JobContext jobContext;
    private final Path checkpointFile;
    private final int pollLimit;
    private final int schedulerPoolSize;
    private final int workerPoolSize;
    private final int pollIntervalSeconds;

    private ScheduledExecutorService scheduledExecutor;
    private ExecutorService workerPool;
    private final Set<Job> inFlight = ConcurrentHashMap.newKeySet();

    public SchedulerManager(BackgroundJobDao backgroundJobDao, JobContext jobContext, Path dataDir,
                             int schedulerPoolSize, int workerPoolSize, int pollIntervalSeconds) {
        this.backgroundJobDao = backgroundJobDao;
        this.jobContext = jobContext;
        this.checkpointFile = dataDir.resolve("jobs").resolve("checkpoint.ser");
        this.pollLimit = workerPoolSize;
        this.schedulerPoolSize = schedulerPoolSize;
        this.workerPoolSize = workerPoolSize;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public void start() {
        loadCheckpointForDiagnosticsOnly();

        scheduledExecutor = Executors.newScheduledThreadPool(schedulerPoolSize);
        workerPool = Executors.newFixedThreadPool(workerPoolSize);

        scheduledExecutor.scheduleAtFixedRate(this::pollAndDispatch, 2, pollIntervalSeconds, TimeUnit.SECONDS);
        scheduledExecutor.scheduleAtFixedRate(this::checkpointAndReclaim, 30, 60, TimeUnit.SECONDS);
    }

    private void pollAndDispatch() {
        try (Connection con = ConnectionFactory.get()) {
            List<BackgroundJobRecord> due = backgroundJobDao.findDue(con, pollLimit);
            for (BackgroundJobRecord record : due) {
                workerPool.submit(() -> claimAndExecute(record));
            }
        } catch (SQLException e) {
            System.err.println("SchedulerManager: poll failed: " + e.getMessage());
        }
    }

    private void claimAndExecute(BackgroundJobRecord record) {
        boolean claimed;
        try (Connection con = ConnectionFactory.get()) {
            con.setAutoCommit(true); // claim is a single statement; no multi-step transaction needed here
            claimed = backgroundJobDao.claim(con, record.getId());
        } catch (SQLException e) {
            System.err.println("SchedulerManager: claim failed for job " + record.getId() + ": " + e.getMessage());
            return;
        }
        if (!claimed) {
            return; // another worker (or another poll cycle) already claimed it — expected under concurrency, not an error
        }

        Job job = Job.from(record);
        inFlight.add(job);
        try {
            job.execute(jobContext);
            try (Connection con = ConnectionFactory.get()) {
                backgroundJobDao.markComplete(con, record.getId());
            }
        } catch (Exception e) {
            try (Connection con = ConnectionFactory.get()) {
                backgroundJobDao.markFailedOrRetry(con, record.getId(), record.getAttemptCount(), MAX_ATTEMPTS, e.getMessage());
            } catch (SQLException inner) {
                System.err.println("SchedulerManager: failed to record job failure for " + record.getId() + ": " + inner.getMessage());
            }
        } finally {
            inFlight.remove(job);
        }
    }

    private void checkpointAndReclaim() {
        writeCheckpoint();
        try (Connection con = ConnectionFactory.get()) {
            int reclaimed = backgroundJobDao.reclaimStalled(con, STALE_RUNNING_SECONDS);
            if (reclaimed > 0) {
                jobContext.getLogWriter().append("JOBS_RECLAIMED", null, "count=" + reclaimed);
            }
        } catch (SQLException e) {
            System.err.println("SchedulerManager: reclaim failed: " + e.getMessage());
        }
    }

    private void writeCheckpoint() {
        try {
            Files.createDirectories(checkpointFile.getParent());
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(checkpointFile))) {
                oos.writeObject(List.copyOf(inFlight));
            }
        } catch (IOException e) {
            // A failed checkpoint write must never break the application — see Job's Javadoc: nothing important is lost either way.
            System.err.println("SchedulerManager: checkpoint write failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Loads and logs the last checkpoint purely for diagnostic visibility —
     * it is deliberately NOT re-submitted for execution. Correctness never
     * depends on this file: whatever it describes is either already
     * COMPLETE, or still PENDING/RUNNING in background_jobs and will be
     * picked up by the normal poll/reclaim cycle regardless of whether this
     * file exists. See PROJECT_BLUEPRINT_CORRECTED.md §17.3's canonical test:
     * "if I deleted checkpoint.ser entirely, the system would still be correct."
     */
    @SuppressWarnings("unchecked")
    private void loadCheckpointForDiagnosticsOnly() {
        if (!Files.exists(checkpointFile)) return;
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(checkpointFile))) {
            List<Job> recovered = (List<Job>) ois.readObject();
            jobContext.getLogWriter().append("CHECKPOINT_LOADED", null,
                    "recoveredJobCount=" + recovered.size() + " (diagnostic only — not re-executed; see Job Javadoc)");
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("SchedulerManager: could not read checkpoint (non-fatal, ignoring): " + e.getMessage());
        }
    }

    /** Graceful shutdown — see PROJECT_BLUEPRINT_CORRECTED.md §18.2. Threads must not outlive the web application. */
    public void shutdown() {
        if (scheduledExecutor != null) scheduledExecutor.shutdown();
        if (workerPool != null) workerPool.shutdown();
        try {
            if (scheduledExecutor != null && !scheduledExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
            if (workerPool != null && !workerPool.awaitTermination(20, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (scheduledExecutor != null) scheduledExecutor.shutdownNow();
            if (workerPool != null) workerPool.shutdownNow();
        }
        writeCheckpoint();
    }
}
