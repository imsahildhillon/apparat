package com.apparat.dao;

import com.apparat.model.Booking;
import com.apparat.model.enums.BookingStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BookingDao extends BaseDao implements Dao<Booking, Long> {

    @Override
    public Optional<Booking> findById(Long id) {
        return withConnection(con -> findById(con, id));
    }

    public Optional<Booking> findById(Connection con, Long id) throws SQLException {
        String sql = "SELECT * FROM bookings WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public List<Booking> findAll() {
        return withConnection(con -> queryList(con, "SELECT * FROM bookings ORDER BY start_at DESC", ps -> {}));
    }

    public List<Booking> findByRequester(Long requesterId) {
        return withConnection(con -> queryList(con,
                "SELECT * FROM bookings WHERE requester_id = ? ORDER BY start_at DESC",
                ps -> ps.setLong(1, requesterId)));
    }

    public List<Booking> findPendingApprovals() {
        return withConnection(con -> queryList(con,
                "SELECT * FROM bookings WHERE status = 'PENDING' ORDER BY created_at",
                ps -> {}));
    }

    public List<Booking> findByResourceAndWindow(Long resourceId, LocalDateTime from, LocalDateTime to) {
        return withConnection(con -> queryList(con,
                "SELECT * FROM bookings WHERE resource_id = ? AND status IN ('PENDING','APPROVED') "
                + "AND start_at < ? AND end_at > ? ORDER BY start_at",
                ps -> { ps.setLong(1, resourceId); ps.setObject(2, to); ps.setObject(3, from); }));
    }

    /**
     * Defence-in-depth read: layer 2's overlap check inside the transaction,
     * BEFORE the layer-3 booking_slot insert is attempted (see
     * service.BookingService#createBooking and
     * PROJECT_BLUEPRINT_CORRECTED.md §19.7 / §20.2 step 10). This does not
     * replace the unique constraint — it exists so most conflicts are caught
     * with a clear, fast, non-exceptional check before the more expensive
     * slot-generation work runs.
     *
     * Strict interval-overlap condition: A.start < B.end AND A.end > B.start —
     * both sides strict, so adjacent bookings that only touch at a shared
     * boundary are correctly NOT reported as conflicting.
     */
    public boolean hasOverlap(Connection con, Long resourceId, LocalDateTime start, LocalDateTime end) throws SQLException {
        String sql = "SELECT 1 FROM bookings WHERE resource_id = ? AND status IN ('PENDING','APPROVED') "
                + "AND start_at < ? AND end_at > ? LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, resourceId);
            ps.setObject(2, end);
            ps.setObject(3, start);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Optional<LocalDateTime> nextFreeSlotAfter(Connection con, Long resourceId, LocalDateTime after, int slotMinutes, LocalDateTime searchLimit) throws SQLException {
        LocalDateTime candidate = after;
        while (candidate.isBefore(searchLimit)) {
            LocalDateTime candidateEnd = candidate.plusMinutes(slotMinutes);
            if (!hasOverlap(con, resourceId, candidate, candidateEnd)) {
                return Optional.of(candidate);
            }
            candidate = candidate.plusMinutes(slotMinutes);
        }
        return Optional.empty();
    }

    public Long insert(Connection con, Booking booking) throws SQLException {
        String sql = "INSERT INTO bookings (resource_id, requester_id, start_at, end_at, status, purpose) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, booking.getResourceId());
            ps.setLong(2, booking.getRequesterId());
            ps.setObject(3, booking.getStartAt());
            ps.setObject(4, booking.getEndAt());
            ps.setString(5, booking.getStatus().name());
            ps.setString(6, booking.getPurpose());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Conditional status-transition update — the WHERE clause encodes the
     * precondition, and the caller checks the affected-row count rather than
     * trusting the call "succeeded." This is the exact mechanism that closes
     * Race R3/R5 in PROJECT_BLUEPRINT_CORRECTED.md §18.4 (waitlist
     * promotion vs cancellation; no-show sweep vs check-in) — a losing
     * concurrent caller sees 0 affected rows and does nothing further,
     * rather than both callers believing they won.
     */
    public int transitionStatus(Connection con, Long bookingId, BookingStatus from, BookingStatus to,
                                 Long approvedBy, String cancelReason) throws SQLException {
        String sql = "UPDATE bookings SET status = ?, approved_by = ?, approved_at = ?, cancel_reason = ? "
                + "WHERE id = ? AND status = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, to.name());
            if (approvedBy != null) ps.setLong(2, approvedBy); else ps.setNull(2, java.sql.Types.BIGINT);
            ps.setObject(3, (to == BookingStatus.APPROVED) ? LocalDateTime.now() : null);
            ps.setString(4, cancelReason);
            ps.setLong(5, bookingId);
            ps.setString(6, from.name());
            return ps.executeUpdate();
        }
    }

    private List<Booking> queryList(Connection con, String sql, SqlConsumer binder) throws SQLException {
        List<Booking> bookings = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) bookings.add(mapRow(rs));
            }
        }
        return bookings;
    }

    private Booking mapRow(ResultSet rs) throws SQLException {
        Booking b = new Booking();
        b.setId(rs.getLong("id"));
        b.setResourceId(rs.getLong("resource_id"));
        b.setRequesterId(rs.getLong("requester_id"));
        b.setStartAt(rs.getObject("start_at", LocalDateTime.class));
        // setEndAt validates end > start; safe since the DB CHECK constraint already guarantees it
        b.setEndAt(rs.getObject("end_at", LocalDateTime.class));
        b.setStatus(BookingStatus.valueOf(rs.getString("status")));
        b.setPurpose(rs.getString("purpose"));
        long approvedBy = rs.getLong("approved_by");
        b.setApprovedBy(rs.wasNull() ? null : approvedBy);
        b.setApprovedAt(rs.getObject("approved_at", LocalDateTime.class));
        b.setCancelReason(rs.getString("cancel_reason"));
        b.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return b;
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
