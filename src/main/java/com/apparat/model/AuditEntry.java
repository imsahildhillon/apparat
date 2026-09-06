package com.apparat.model;

import java.time.LocalDateTime;

public class AuditEntry {
    private Long id;
    private Long actorId;
    private String action;
    private String entityType;
    private Long entityId;
    private String details;
    private LocalDateTime createdAt;

    public static AuditEntry of(Long actorId, String action, String entityType, Long entityId, String details) {
        AuditEntry e = new AuditEntry();
        e.actorId = actorId;
        e.action = action;
        e.entityType = entityType;
        e.entityId = entityId;
        e.details = details;
        return e;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getDetails() { return details; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
