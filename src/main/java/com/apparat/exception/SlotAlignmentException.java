package com.apparat.exception;

import java.time.LocalTime;

/**
 * Thrown when a requested booking interval does not align to the resource's
 * atomic slot boundaries (e.g. 09:15 against a 30-minute-slot resource whose
 * open_time is 09:00). Apparat REJECTS misaligned requests rather than
 * silently rounding them — see PROJECT_BLUEPRINT_CORRECTED.md §19.4 for why
 * silent rounding was rejected as a policy (it would let a user believe they
 * booked a different interval than what was actually reserved).
 *
 * Carries the two nearest valid alternatives so the UI can suggest a fix
 * instead of only rejecting.
 */
public class SlotAlignmentException extends BookingException {

    private final int slotMinutes;
    private final LocalTime nearestBefore;
    private final LocalTime nearestAfter;

    public SlotAlignmentException(int slotMinutes, LocalTime nearestBefore, LocalTime nearestAfter) {
        super(String.format(
                "This resource books in %d-minute slots. The nearest valid start times are %s and %s.",
                slotMinutes, nearestBefore, nearestAfter), 400);
        this.slotMinutes = slotMinutes;
        this.nearestBefore = nearestBefore;
        this.nearestAfter = nearestAfter;
    }

    public int getSlotMinutes() { return slotMinutes; }
    public LocalTime getNearestBefore() { return nearestBefore; }
    public LocalTime getNearestAfter() { return nearestAfter; }
}
