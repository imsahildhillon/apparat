package com.apparat.util;

import com.apparat.exception.SlotAlignmentException;
import com.apparat.model.Instrument;
import com.apparat.model.Resource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the atomic-slot math flagged as the highest-risk new logic
 * in CORRECTIONS_LOG.md (Risk #7) / CONCURRENCY_TEST_PLAN.md §5. Pure, no
 * database, no threads — isolates slot-generation bugs from concurrency
 * noise.
 */
class DateUtilTest {

    private Resource resource(int slotMinutes, int bufferMinutes) {
        Resource r = new Instrument();
        r.setOpenTime(LocalTime.of(9, 0));
        r.setCloseTime(LocalTime.of(18, 0));
        r.setSlotMinutes(slotMinutes);
        r.setBufferMinutes(bufferMinutes);
        r.setMinSlotMinutes(slotMinutes);
        r.setMaxSlotMinutes(240);
        return r;
    }

    @Test
    void alignedTimesPass() {
        assertDoesNotThrow(() -> DateUtil.validateAlignment(
                LocalDateTime.of(2026, 10, 5, 9, 0),
                LocalDateTime.of(2026, 10, 5, 11, 0),
                resource(30, 0)));
    }

    @Test
    void misalignedStartIsRejectedNotRounded() {
        SlotAlignmentException ex = assertThrows(SlotAlignmentException.class, () -> DateUtil.validateAlignment(
                LocalDateTime.of(2026, 10, 5, 9, 15),
                LocalDateTime.of(2026, 10, 5, 10, 0),
                resource(30, 0)));
        assertEquals(30, ex.getSlotMinutes());
        assertEquals(LocalTime.of(9, 0), ex.getNearestBefore());
        assertEquals(LocalTime.of(9, 30), ex.getNearestAfter());
    }

    @Test
    void misalignedEndIsRejected() {
        assertThrows(SlotAlignmentException.class, () -> DateUtil.validateAlignment(
                LocalDateTime.of(2026, 10, 5, 9, 0),
                LocalDateTime.of(2026, 10, 5, 10, 45),
                resource(30, 0)));
    }

    @Test
    void occupiedSlotsExactMultiple() {
        List<LocalDateTime> slots = DateUtil.generateOccupiedSlots(
                LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 11, 30),
                30);
        assertEquals(List.of(
                LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 10, 30),
                LocalDateTime.of(2026, 10, 5, 11, 0)
        ), slots);
    }

    @Test
    void occupiedSlotsMinimumDuration() {
        List<LocalDateTime> slots = DateUtil.generateOccupiedSlots(
                LocalDateTime.of(2026, 10, 5, 10, 0),
                LocalDateTime.of(2026, 10, 5, 10, 30),
                30);
        assertEquals(1, slots.size());
        assertEquals(LocalDateTime.of(2026, 10, 5, 10, 0), slots.get(0));
    }

    @Test
    void bufferSlotsGeneratedAfterEnd() {
        List<LocalDateTime> buffer = DateUtil.generateBufferSlots(
                LocalDateTime.of(2026, 10, 5, 11, 0), 15, 30);
        // 15-minute cooldown rounds UP to one 30-minute buffer slot
        assertEquals(List.of(LocalDateTime.of(2026, 10, 5, 11, 0)), buffer);
    }

    @Test
    void noBufferSlotsWhenBufferIsZero() {
        assertTrue(DateUtil.generateBufferSlots(LocalDateTime.of(2026, 10, 5, 11, 0), 0, 30).isEmpty());
    }

    @Test
    void bufferSlotsSpanMultipleSlotsWhenNeeded() {
        List<LocalDateTime> buffer = DateUtil.generateBufferSlots(
                LocalDateTime.of(2026, 10, 5, 11, 0), 45, 30);
        assertEquals(List.of(
                LocalDateTime.of(2026, 10, 5, 11, 0),
                LocalDateTime.of(2026, 10, 5, 11, 30)
        ), buffer);
    }

    @Test
    void boundaryExactlyOnCloseTimeIsValid() {
        // 17:30-18:00 on a resource closing at 18:00 must be accepted (exclusive end, no slot generated AT 18:00)
        Resource r = resource(30, 0);
        assertDoesNotThrow(() -> DateUtil.validateAlignment(
                LocalDateTime.of(2026, 10, 5, 17, 30),
                LocalDateTime.of(2026, 10, 5, 18, 0), r));
        List<LocalDateTime> slots = DateUtil.generateOccupiedSlots(
                LocalDateTime.of(2026, 10, 5, 17, 30),
                LocalDateTime.of(2026, 10, 5, 18, 0), 30);
        assertEquals(List.of(LocalDateTime.of(2026, 10, 5, 17, 30)), slots);
    }
}
