package com.apparat.dao;

import com.apparat.model.BookingSlot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DAO for the booking_slot DERIVED OCCUPANCY table — see schema.sql's
 * comment on this table and PROJECT_BLUEPRINT_CORRECTED.md §19.4-19.6. Every
 * method here takes a caller-supplied Connection: booking_slot rows are
 * never written or deleted outside a transaction the Service layer owns,
 * because they must always change atomically together with the owning
 * booking's status (see service.BookingService).
 */
public class BookingSlotDao extends BaseDao {

    /**
     * Batch-inserts one row per atomic slot. If any row collides with an
     * existing (resource_id, slot_start) pair, MySQL raises error 1062 on
     * that statement in the batch and the whole batch throws
     * java.sql.BatchUpdateException — the caller (BookingService) catches
     * this, rolls back the whole transaction, and translates it into
     * SlotConflictException. This IS the layer-3 backstop
     * (PROJECT_BLUEPRINT_CORRECTED.md §18.3) — it is expected to fire under
     * a genuine race, not a bug.
     */
    public void insertBatch(Connection con, List<BookingSlot> slots) throws SQLException {
        String sql = "INSERT INTO booking_slot (booking_id, resource_id, slot_start, is_buffer) VALUES (?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (BookingSlot slot : slots) {
                ps.setLong(1, slot.getBookingId());
                ps.setLong(2, slot.getResourceId());
                ps.setObject(3, slot.getSlotStart());
                ps.setBoolean(4, slot.isBuffer());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Releases all slots held by a booking — called in the same transaction
     * as a status change to CANCELLED or REJECTED, so the slot becomes
     * bookable again for someone else (see BookingStatus lifecycle in
     * schema.sql). Deliberately NOT called for COMPLETED — a completed
     * booking is always in the past and its rows are retained for
     * historical/utilization reporting.
     */
    public void deleteByBookingId(Connection con, Long bookingId) throws SQLException {
        String sql = "DELETE FROM booking_slot WHERE booking_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, bookingId);
            ps.executeUpdate();
        }
    }

    /** Utilization-report helper: counts occupied (non-buffer) slot-minutes for a resource in a window. Used by service.ReportService — see dao.BookingSlotDao#totalOccupiedMinutes. */
    public int totalOccupiedMinutes(Connection con, Long resourceId, LocalDateTime from, LocalDateTime to, int slotMinutes) throws SQLException {
        String sql = "SELECT COUNT(*) FROM booking_slot bs JOIN bookings b ON bs.booking_id = b.id "
                + "WHERE bs.resource_id = ? AND bs.is_buffer = 0 AND bs.slot_start >= ? AND bs.slot_start < ? "
                + "AND b.status IN ('APPROVED','COMPLETED')";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, resourceId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) * slotMinutes;
            }
        }
    }
}
