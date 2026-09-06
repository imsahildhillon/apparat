package com.apparat.service;

import com.apparat.config.ConnectionFactory;
import com.apparat.dao.AuditDao;
import com.apparat.dao.BackgroundJobDao;
import com.apparat.dao.BookingDao;
import com.apparat.dao.BookingSlotDao;
import com.apparat.dao.QuotaDao;
import com.apparat.exception.AuthorizationException;
import com.apparat.exception.DataAccessException;
import com.apparat.exception.InvalidBookingException;
import com.apparat.exception.ResourceNotFoundException;
import com.apparat.model.AuditEntry;
import com.apparat.model.Booking;
import com.apparat.model.dto.SessionUser;
import com.apparat.model.enums.BookingStatus;
import com.apparat.model.enums.JobType;
import com.apparat.util.LogWriter;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * The approval half of the booking workflow — kept deliberately simple, not
 * a workflow engine (PROJECT_BLUEPRINT_CORRECTED.md §35): a PENDING booking
 * is either APPROVED or REJECTED by a Technician/Admin. Both transitions use
 * BookingDao's conditional UPDATE ... WHERE status = 'PENDING' so a booking
 * cannot be approved and rejected twice by two racing approvers — only one
 * UPDATE affects a row.
 */
public class ApprovalService {

    private final BookingDao bookingDao;
    private final BookingSlotDao bookingSlotDao;
    private final QuotaDao quotaDao;
    private final BackgroundJobDao backgroundJobDao;
    private final AuditDao auditDao;
    private final LogWriter logWriter;

    public ApprovalService(BookingDao bookingDao, BookingSlotDao bookingSlotDao, QuotaDao quotaDao,
                            BackgroundJobDao backgroundJobDao, AuditDao auditDao, LogWriter logWriter) {
        this.bookingDao = bookingDao;
        this.bookingSlotDao = bookingSlotDao;
        this.quotaDao = quotaDao;
        this.backgroundJobDao = backgroundJobDao;
        this.auditDao = auditDao;
        this.logWriter = logWriter;
    }

    public List<Booking> pending() {
        return bookingDao.findPendingApprovals();
    }

    public void approve(SessionUser actor, long bookingId) {
        requireApprover(actor);
        transition(actor, bookingId, BookingStatus.APPROVED, null, "BOOKING_APPROVED");
    }

    public void reject(SessionUser actor, long bookingId, String reason) {
        requireApprover(actor);
        if (reason == null || reason.isBlank()) {
            throw new InvalidBookingException("A rejection reason is required.");
        }
        transition(actor, bookingId, BookingStatus.REJECTED, reason, "BOOKING_REJECTED");
    }

    private void transition(SessionUser actor, long bookingId, BookingStatus target, String reason, String auditAction) {
        try (Connection con = ConnectionFactory.get()) {
            con.setAutoCommit(false);
            try {
                Booking booking = bookingDao.findById(con, bookingId)
                        .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));

                int updated = bookingDao.transitionStatus(con, bookingId, BookingStatus.PENDING, target, actor.getUserId(), reason);
                if (updated != 1) {
                    con.rollback();
                    throw new InvalidBookingException("This booking is no longer pending (someone else may have already acted on it).");
                }

                if (target == BookingStatus.REJECTED) {
                    // Release the slot (schema.sql lifecycle) and the quota this booking had provisionally consumed.
                    bookingSlotDao.deleteByBookingId(con, bookingId);
                    LocalDate day = booking.getStartAt().toLocalDate();
                    quotaDao.release(con, booking.getRequesterId(), QuotaDao.isoYear(day), QuotaDao.isoWeek(day),
                            (int) booking.durationMinutes());
                }

                String payload = "{\"bookingId\":" + bookingId + ",\"event\":\"" + target + "\"}";
                backgroundJobDao.enqueue(con, JobType.SEND_REMINDER, payload);
                auditDao.write(con, AuditEntry.of(actor.getUserId(), auditAction, "booking", bookingId, reason));

                con.commit();
                logWriter.append(auditAction, actor.getUserId(), "bookingId=" + bookingId);
            } catch (SQLException e) {
                con.rollback();
                throw new DataAccessException("Could not update booking approval state.", e);
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not update booking approval state.", e);
        }
    }

    private void requireApprover(SessionUser actor) {
        if (!actor.isApprover()) {
            throw new AuthorizationException("Only technicians and admins can approve or reject bookings.");
        }
    }
}
