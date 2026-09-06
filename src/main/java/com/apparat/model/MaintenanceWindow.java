package com.apparat.model;

import java.time.LocalDateTime;

public class MaintenanceWindow {
    private Long id;
    private Long resourceId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;

    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return this.startAt.isBefore(otherEnd) && this.endAt.isAfter(otherStart);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
