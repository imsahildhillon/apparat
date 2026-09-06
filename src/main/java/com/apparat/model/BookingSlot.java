package com.apparat.model;

import java.time.LocalDateTime;

/**
 * Mirrors one row of the booking_slot table — a DERIVED OCCUPANCY structure,
 * not an independent business entity. It exists purely so MySQL can enforce
 * UNIQUE(resource_id, slot_start), the layer-3 correctness backstop. See
 * sql/schema.sql's comment on this table and
 * PROJECT_BLUEPRINT_CORRECTED.md §19.4-19.6.
 */
public class BookingSlot {
    private Long id;
    private Long bookingId;
    private Long resourceId;
    private LocalDateTime slotStart;
    private boolean buffer;

    public BookingSlot() { }

    public BookingSlot(Long bookingId, Long resourceId, LocalDateTime slotStart, boolean buffer) {
        this.bookingId = bookingId;
        this.resourceId = resourceId;
        this.slotStart = slotStart;
        this.buffer = buffer;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public LocalDateTime getSlotStart() { return slotStart; }
    public void setSlotStart(LocalDateTime slotStart) { this.slotStart = slotStart; }
    public boolean isBuffer() { return buffer; }
    public void setBuffer(boolean buffer) { this.buffer = buffer; }
}
