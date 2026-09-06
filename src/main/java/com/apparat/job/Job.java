package com.apparat.job;

import com.apparat.model.BackgroundJobRecord;

import java.io.Serializable;

/**
 * The DISPOSABLE, in-memory, Serializable counterpart to a
 * model.BackgroundJobRecord (the durable database row). A Job object is
 * constructed FROM a claimed BackgroundJobRecord when a worker picks it up,
 * and is periodically checkpointed to disk (jobs/checkpoint.ser) purely so a
 * restarted worker doesn't have to cold-start — see job.QueueCheckpointer.
 *
 * THE DISTINCTION THAT MATTERS (PROJECT_BLUEPRINT_CORRECTED.md §17.3):
 *   background_jobs table  -> authoritative durable job state. Survives any crash.
 *   Job / checkpoint.ser    -> local worker-state checkpoint. If deleted, nothing
 *                              important is lost — the worker simply re-polls
 *                              background_jobs on its next tick and rebuilds
 *                              this list from the authoritative source.
 *
 * Explicit serialVersionUID declared on every subclass so a field addition
 * does not throw InvalidClassException against an old checkpoint file — see
 * ARCHITECTURE_DECISIONS.md ADR-10.
 */
public abstract class Job implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long jobRecordId;
    private final String payload;

    protected Job(long jobRecordId, String payload) {
        this.jobRecordId = jobRecordId;
        this.payload = payload;
    }

    public long getJobRecordId() { return jobRecordId; }
    public String getPayload() { return payload; }

    /**
     * Executes the job's actual work. Dependencies (LogWriter, ReportService,
     * ...) are passed in via JobContext at call time rather than stored as
     * instance fields — Job must stay Serializable, and its collaborators
     * (a file handle, a DAO-backed service) are not. This sidesteps needing
     * `transient` fields entirely, which is the cleaner version of the same
     * "don't serialize non-serializable dependencies" discipline described in
     * PROJECT_BLUEPRINT_CORRECTED.md §17.4.
     */
    public abstract void execute(JobContext ctx) throws Exception;

    /** Maps a claimed, durable BackgroundJobRecord to its disposable, executable Job counterpart — the one place a JobType enum value becomes a polymorphic Job subtype. */
    public static Job from(BackgroundJobRecord record) {
        return switch (record.getJobType()) {
            case SEND_REMINDER -> new NotificationJob(record.getId(), record.getPayload());
            case GENERATE_REPORT -> new ReportJob(record.getId(), record.getPayload());
        };
    }
}
