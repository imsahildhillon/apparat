package com.apparat.dao;

import com.apparat.model.QuotaUsage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/**
 * Quota consumption is ONE atomic conditional SQL UPDATE — never a Java
 * read-then-write. See ARCHITECTURE_DECISIONS.md ADR-11 and
 * CORRECTIONS_LOG.md C12 for why: reading used_minutes in Java, adding in
 * Java, and writing back is a textbook lost-update race under concurrent
 * bookings by the same user in the same week.
 */
public class QuotaDao extends BaseDao {

    public java.util.Optional<QuotaUsage> find(Connection con, Long userId, int isoYear, int isoWeek) throws SQLException {
        String sql = "SELECT * FROM quota_usage WHERE user_id = ? AND iso_year = ? AND iso_week = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, isoYear);
            ps.setInt(3, isoWeek);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                QuotaUsage q = new QuotaUsage();
                q.setUserId(userId);
                q.setIsoYear(isoYear);
                q.setIsoWeek(isoWeek);
                q.setUsedMinutes(rs.getInt("used_minutes"));
                q.setLimitMinutes(rs.getInt("limit_minutes"));
                return java.util.Optional.of(q);
            }
        }
    }

    /** Ensures a quota row exists for this user/week (idempotent — INSERT IGNORE), seeded with the user's role-based weekly limit. Does not consume any minutes by itself. */
    public void ensureRowExists(Connection con, Long userId, int isoYear, int isoWeek, int limitMinutes) throws SQLException {
        String sql = "INSERT IGNORE INTO quota_usage (user_id, iso_year, iso_week, used_minutes, limit_minutes) VALUES (?,?,?,0,?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, isoYear);
            ps.setInt(3, isoWeek);
            ps.setInt(4, limitMinutes);
            ps.executeUpdate();
        }
    }

    /**
     * THE atomic quota consumption. The increment and the limit check happen
     * in one indivisible statement evaluated against the current committed
     * row — there is no read-then-decide gap in application code for another
     * concurrent transaction to interleave into. Returns true iff exactly
     * one row was updated (quota consumed); false means either no row
     * exists yet for this week (caller should ensureRowExists first) or
     * consuming these minutes would exceed the limit — either way, the
     * caller (service.BookingService) treats 0 as QuotaExceededException.
     */
    public boolean tryConsume(Connection con, Long userId, int isoYear, int isoWeek, int minutes) throws SQLException {
        String sql = "UPDATE quota_usage SET used_minutes = used_minutes + ? "
                + "WHERE user_id = ? AND iso_year = ? AND iso_week = ? AND used_minutes + ? <= limit_minutes";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, minutes);
            ps.setLong(2, userId);
            ps.setInt(3, isoYear);
            ps.setInt(4, isoWeek);
            ps.setInt(5, minutes);
            return ps.executeUpdate() == 1;
        }
    }

    /** Releases minutes back to the quota, e.g. on cancellation of an occupying booking. Also a single atomic UPDATE, floored at 0 by the CHECK constraint's intent (defensive GREATEST clamps it defensively at the SQL level too). */
    public void release(Connection con, Long userId, int isoYear, int isoWeek, int minutes) throws SQLException {
        String sql = "UPDATE quota_usage SET used_minutes = GREATEST(0, used_minutes - ?) "
                + "WHERE user_id = ? AND iso_year = ? AND iso_week = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, minutes);
            ps.setLong(2, userId);
            ps.setInt(3, isoYear);
            ps.setInt(4, isoWeek);
            ps.executeUpdate();
        }
    }

    /** ISO week fields for "now" — used consistently by service.BookingService so quota rows key off the same week definition schema.sql's seed data uses (WEEK(date, 3) in MySQL == ISO week). */
    public static int isoYear(LocalDate date) { return date.get(IsoFields.WEEK_BASED_YEAR); }
    public static int isoWeek(LocalDate date) { return date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR); }
}
