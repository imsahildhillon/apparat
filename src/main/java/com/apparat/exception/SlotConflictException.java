package com.apparat.exception;

import java.time.LocalDateTime;

/**
 * The requested interval is already occupied. This is thrown in two places,
 * both legitimate (see PROJECT_BLUEPRINT_CORRECTED.md §18.3 / §20.5):
 *  (1) the defence-in-depth overlap SELECT against `bookings` catches it first, most of the time;
 *  (2) if it somehow slips past that read, the booking_slot UNIQUE(resource_id, slot_start)
 *      constraint rejects the INSERT with MySQL error 1062, which
 *      dao.BookingDao translates into this exception — the "layer 3" backstop.
 * Carries the next free slot (nullable — a courtesy lookup, not guaranteed) so the UI can suggest it.
 */
public class SlotConflictException extends BookingException {

    private final LocalDateTime suggestedNextFreeSlot;

    public SlotConflictException(String resourceName, LocalDateTime requestedStart, LocalDateTime suggestedNextFreeSlot) {
        super(buildMessage(resourceName, requestedStart, suggestedNextFreeSlot), 409);
        this.suggestedNextFreeSlot = suggestedNextFreeSlot;
    }

    private static String buildMessage(String resourceName, LocalDateTime requestedStart, LocalDateTime next) {
        String base = "That slot on " + resourceName + " starting " + requestedStart + " was just taken.";
        return next != null ? base + " The next free slot starts at " + next + "." : base;
    }

    public LocalDateTime getSuggestedNextFreeSlot() { return suggestedNextFreeSlot; }
}
