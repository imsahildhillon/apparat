package com.apparat.model.dto;

import java.time.LocalDateTime;

/** Plain request DTO the servlet builds from form parameters and hands to BookingService — the service never touches HttpServletRequest directly, which keeps it unit-testable without a servlet container. */
public class BookingRequest {
    private final long resourceId;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final String purpose;

    public BookingRequest(long resourceId, LocalDateTime startAt, LocalDateTime endAt, String purpose) {
        this.resourceId = resourceId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.purpose = purpose;
    }

    public long getResourceId() { return resourceId; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public String getPurpose() { return purpose; }
    public long durationMinutes() { return java.time.Duration.between(startAt, endAt).toMinutes(); }
}
