package com.apparat.model;

import com.apparat.model.enums.JobStatus;
import com.apparat.model.enums.JobType;

import java.time.LocalDateTime;

/**
 * Mirrors one row of the background_jobs table — the DURABLE, authoritative
 * record of pending work (PROJECT_BLUEPRINT_CORRECTED.md §17.2). This is a
 * plain (non-Serializable) model class; job.Job is the disposable,
 * Serializable, in-memory counterpart constructed FROM a claimed record of
 * this type — see job.Job's Javadoc for the durable-vs-checkpoint
 * distinction this split is built around.
 */
public class BackgroundJobRecord {
    private Long id;
    private JobType jobType;
    private String payload;
    private JobStatus status = JobStatus.PENDING;
    private int attemptCount;
    private LocalDateTime availableAt;
    private LocalDateTime lockedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private LocalDateTime createdAt;

    public static BackgroundJobRecord pending(JobType type, String payload) {
        BackgroundJobRecord r = new BackgroundJobRecord();
        r.jobType = type;
        r.payload = payload;
        r.status = JobStatus.PENDING;
        r.availableAt = LocalDateTime.now();
        return r;
    }

    public boolean canRetry(int maxAttempts) { return attemptCount < maxAttempts; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public JobType getJobType() { return jobType; }
    public void setJobType(JobType jobType) { this.jobType = jobType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getAvailableAt() { return availableAt; }
    public void setAvailableAt(LocalDateTime availableAt) { this.availableAt = availableAt; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
