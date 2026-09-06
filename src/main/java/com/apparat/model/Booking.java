package com.apparat.model;

import com.apparat.model.enums.BookingStatus;

import java.time.LocalDateTime;
import java.time.Duration;

/**
 * Domain behaviour lives on the model, not only in the DAO — overlaps() and
 * durationMinutes() are pure functions, unit-testable with no database (see
 * OverlapDetectionTest). Deliberately avoiding the anaemic-model
 * anti-pattern where a "model" is just getters/setters and all real logic
 * lives in the service.
 */
public class Booking {

    private Long id;
    private Long resourceId;
    private Long requesterId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private BookingStatus status = BookingStatus.PENDING;
    private String purpose;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String cancelReason;
    private LocalDateTime createdAt;

    /**
     * Strict interval-overlap test: A.start < B.end AND A.end > B.start.
     * Both sides strict, so two bookings that only touch at a shared
     * boundary (one ending exactly when the other starts) are correctly
     * treated as NOT overlapping — see CONCURRENCY_TEST_PLAN.md Test 2.
     */
    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return this.startAt.isBefore(otherEnd) && this.endAt.isAfter(otherStart);
    }

    public long durationMinutes() {
        return Duration.between(startAt, endAt).toMinutes();
    }

    public boolean canTransitionTo(BookingStatus target) {
        return switch (status) {
            case PENDING -> target == BookingStatus.APPROVED || target == BookingStatus.REJECTED || target == BookingStatus.CANCELLED;
            case APPROVED -> target == BookingStatus.CANCELLED || target == BookingStatus.COMPLETED;
            case REJECTED, CANCELLED, COMPLETED -> false;
        };
    }

    // --- encapsulated accessors ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }

    public Long getRequesterId() { return requesterId; }
    public void setRequesterId(Long requesterId) { this.requesterId = requesterId; }

    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }

    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) {
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        this.endAt = endAt;
    }

    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
