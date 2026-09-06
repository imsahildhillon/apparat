package com.apparat.model.enums;

/**
 * MVP booking status set (PROJECT_BLUEPRINT_CORRECTED.md scope note: NO_SHOW
 * and CHECKED_IN are part of the full blueprint but were deliberately left
 * out of this MVP — no-show sweeping is a SHOULD-HAVE, not required to
 * demonstrate the graded concepts, and adding it would grow the state
 * machine without adding a new Java concept to the story).
 *
 * PENDING and APPROVED are the two "occupying" statuses that hold live
 * booking_slot rows (see dao.BookingSlotDao and schema.sql's comment on the
 * booking_slot lifecycle).
 */
public enum BookingStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    COMPLETED;

    public boolean isOccupying() {
        return this == PENDING || this == APPROVED;
    }
}
