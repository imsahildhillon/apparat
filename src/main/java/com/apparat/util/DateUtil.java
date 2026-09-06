package com.apparat.util;

import com.apparat.exception.SlotAlignmentException;
import com.apparat.model.Resource;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The atomic-slot math described in PROJECT_BLUEPRINT_CORRECTED.md §19.4 and
 * flagged as the highest-risk new logic in CORRECTIONS_LOG.md (Risk #7) /
 * CONCURRENCY_TEST_PLAN.md §5. Kept as small, pure, static functions
 * specifically so they can be unit-tested in isolation from the database and
 * from concurrency — see test.util.DateUtilTest.
 */
public final class DateUtil {

    private DateUtil() { }

    /**
     * True if {@code time} falls exactly on a slot boundary relative to
     * {@code resourceOpenTime} and the resource's slot size — e.g. with a
     * 30-minute slot size and open_time 09:00, valid times are 09:00, 09:30,
     * 10:00, ... 09:15 is not.
     */
    public static boolean isAligned(LocalTime time, LocalTime resourceOpenTime, int slotMinutes) {
        long minutesSinceOpen = ChronoUnit.MINUTES.between(resourceOpenTime, time);
        return minutesSinceOpen >= 0 && minutesSinceOpen % slotMinutes == 0;
    }

    /**
     * Validates that both start and end align to the resource's slot grid.
     * Throws SlotAlignmentException naming the nearest valid alternatives
     * rather than silently rounding — see PROJECT_BLUEPRINT_CORRECTED.md
     * §19.4 for why rejection was chosen over rounding.
     */
    public static void validateAlignment(LocalDateTime start, LocalDateTime end, Resource resource) {
        int slotMinutes = resource.getSlotMinutes();
        LocalTime openTime = resource.getOpenTime();
        if (!isAligned(start.toLocalTime(), openTime, slotMinutes) || !isAligned(end.toLocalTime(), openTime, slotMinutes)) {
            LocalTime misaligned = !isAligned(start.toLocalTime(), openTime, slotMinutes) ? start.toLocalTime() : end.toLocalTime();
            LocalTime before = roundDown(misaligned, openTime, slotMinutes);
            LocalTime after = before.plusMinutes(slotMinutes);
            throw new SlotAlignmentException(slotMinutes, before, after);
        }
    }

    private static LocalTime roundDown(LocalTime time, LocalTime openTime, int slotMinutes) {
        long minutesSinceOpen = ChronoUnit.MINUTES.between(openTime, time);
        long flooredSlots = Math.floorDiv(minutesSinceOpen, slotMinutes);
        return openTime.plusMinutes(flooredSlots * slotMinutes);
    }

    /**
     * Converts a validated, aligned [start, end) interval into the list of
     * atomic slot-start timestamps it occupies, e.g. 10:00-11:30 at 30-minute
     * granularity -> [10:00, 10:30, 11:00]. Does NOT include buffer slots —
     * see #generateBufferSlots.
     */
    public static List<LocalDateTime> generateOccupiedSlots(LocalDateTime start, LocalDateTime end, int slotMinutes) {
        List<LocalDateTime> slots = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(end)) {
            slots.add(cursor);
            cursor = cursor.plusMinutes(slotMinutes);
        }
        return slots;
    }

    /**
     * Cooldown/warm-up slots generated immediately after {@code end}, tagged
     * separately (is_buffer=1) so reporting can distinguish real usage from
     * buffer time, and so the next booking cannot start before the cooldown
     * elapses (see PROJECT_BLUEPRINT_CORRECTED.md §19.4).
     */
    public static List<LocalDateTime> generateBufferSlots(LocalDateTime end, int bufferMinutes, int slotMinutes) {
        List<LocalDateTime> slots = new ArrayList<>();
        if (bufferMinutes <= 0) return slots;
        int bufferSlotCount = (int) Math.ceil((double) bufferMinutes / slotMinutes);
        LocalDateTime cursor = end;
        for (int i = 0; i < bufferSlotCount; i++) {
            slots.add(cursor);
            cursor = cursor.plusMinutes(slotMinutes);
        }
        return slots;
    }
}
