package com.apparat.service;

import com.apparat.concurrency.ResourceLockManager;
import com.apparat.config.ConnectionFactory;
import com.apparat.dao.BackgroundJobDao;
import com.apparat.dao.AuditDao;
import com.apparat.dao.BookingDao;
import com.apparat.dao.BookingSlotDao;
import com.apparat.dao.MaintenanceDao;
import com.apparat.dao.QuotaDao;
import com.apparat.dao.ResourceDao;
import com.apparat.exception.DataAccessException;
import com.apparat.exception.QuotaExceededException;
import com.apparat.exception.ResourceUnavailableException;
import com.apparat.exception.SlotConflictException;
import com.apparat.model.AuditEntry;
import com.apparat.model.BackgroundJobRecord;
import com.apparat.model.Booking;
import com.apparat.model.BookingSlot;
import com.apparat.model.Resource;
import com.apparat.model.User;
import com.apparat.model.dto.BookingRequest;
import com.apparat.model.dto.BookingResult;
import com.apparat.model.dto.SessionUser;
import com.apparat.model.enums.BookingStatus;
import com.apparat.model.enums.JobType;
import com.apparat.model.enums.ResourceStatus;
import com.apparat.policy.BookingPolicy;
import com.apparat.policy.PolicyFactory;
import com.apparat.util.DateUtil;
import com.apparat.util.LogWriter;
import com.apparat.util.ValidationUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The heart of Apparat. #createBooking implements the corrected 18-step
 * transaction sequence from PROJECT_BLUEPRINT_CORRECTED.md §20.2/§20.3:
 * application lock -> transaction -> row lock -> validation -> policy ->
 * atomic quota consumption -> overlap check -> booking insert -> atomic
 * slot generation -> slot insert (the layer-3 backstop) -> outbox job
 * write -> audit write -> commit -> lock release.
 *
 * Kept readable by extracting each numbered step into a small named private
 * method rather than one long method — see CORRECTIONS_LOG.md C17.
 */
public class BookingService {

    private static final int MAX_ISOWEEK_SEARCH_DAYS = 7; // bound the next-free-slot courtesy search

    private final ResourceDao resourceDao;
    private final BookingDao bookingDao;
    private final BookingSlotDao bookingSlotDao;
    private final MaintenanceDao maintenanceDao;
    private final QuotaDao quotaDao;
    private final BackgroundJobDao backgroundJobDao;
    private final AuditDao auditDao;
    private final LogWriter logWriter;
    private final ResourceLockManager lockManager = ResourceLockManager.get();

    public BookingService(ResourceDao resourceDao, BookingDao bookingDao, BookingSlotDao bookingSlotDao,
                           MaintenanceDao maintenanceDao, QuotaDao quotaDao, BackgroundJobDao backgroundJobDao,
                           AuditDao auditDao, LogWriter logWriter) {
        this.resourceDao = resourceDao;
        this.bookingDao = bookingDao;
        this.bookingSlotDao = bookingSlotDao;
        this.maintenanceDao = maintenanceDao;
        this.quotaDao = quotaDao;
        this.backgroundJobDao = backgroundJobDao;
        this.auditDao = auditDao;
        this.logWriter = logWriter;
    }

    public BookingResult createBooking(BookingRequest req, SessionUser actor, User actorUser) {
        // STEP 1 — layer 1: per-resource application lock (JVM-local, a throughput
        // optimisation — see concurrency.ResourceLockManager's Javadoc for why this
        // alone is not the correctness guarantee).
        ReentrantLock lock = lockManager.lockFor(req.getResourceId());
        boolean locked;
        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataAccessException("Interrupted while acquiring the resource lock.", e);
        }
        if (!locked) {
            throw new DataAccessException("This resource is busy right now — please try again in a moment.");
        }

        try {
            return createBookingLocked(req, actor, actorUser);
        } finally {
            lock.unlock(); // STEP 17 — always released, success or failure
        }
    }

    private BookingResult createBookingLocked(BookingRequest req, SessionUser actor, User actorUser) {
        try (Connection con = ConnectionFactory.get()) {
            con.setAutoCommit(false); // STEP 2
            try {
                // STEP 3 — layer 2: SELECT ... FOR UPDATE row lock, authoritative across all JVMs.
                Resource resource = resourceDao.findForUpdate(con, req.getResourceId());

                validateResource(resource, con, req);                                  // STEPS 4-7
                BookingPolicy policy = PolicyFactory.forResource(resource);            // STEP 8 (polymorphic dispatch)
                consumeQuota(con, actor, actorUser, req);                              // STEP 9 — atomic conditional UPDATE
                checkOverlap(con, resource, req);                                      // STEP 10 — defence-in-depth read

                long bookingId = createBookingRecord(con, req, actor, policy);         // STEP 11
                List<BookingSlot> slots = generateAtomicSlots(resource, req, bookingId); // STEP 12
                insertBookingSlots(con, resource, req, slots);                         // STEP 13 — layer 3 backstop

                enqueueNotification(con, bookingId, policy.resolveInitialStatus());     // STEP 14 — outbox, same transaction
                writeAudit(con, actor, bookingId, resource);                            // STEP 15

                con.commit();                                                          // STEP 16
                logWriter.append("BOOKING_CREATED", actor.getUserId(),
                        "bookingId=" + bookingId + " resourceId=" + resource.getId() + " status=" + policy.name());
                return BookingResult.of(bookingId, policy.resolveInitialStatus());

            } catch (SQLException e) {
                con.rollback();
                throw handleBookingConflict(e, con, req);
            } catch (RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not create booking.", e);
        }
    }

    // ---- extracted steps ----

    private void validateResource(Resource resource, Connection con, BookingRequest req) throws SQLException {
        if (resource.getStatus() != ResourceStatus.AVAILABLE) {
            throw new ResourceUnavailableException("This resource is currently " + resource.getStatus() + " and cannot be booked.");
        }
        ValidationUtil.validateBookingInterval(req.getStartAt(), req.getEndAt(), resource);
        DateUtil.validateAlignment(req.getStartAt(), req.getEndAt(), resource); // throws SlotAlignmentException — see util.DateUtil
        if (maintenanceDao.hasOverlap(con, resource.getId(), req.getStartAt(), req.getEndAt())) {
            throw new ResourceUnavailableException("This resource has a scheduled maintenance window that overlaps your requested time.");
        }
    }

    private void consumeQuota(Connection con, SessionUser actor, User actorUser, BookingRequest req) throws SQLException {
        LocalDate today = LocalDate.now();
        int isoYear = QuotaDao.isoYear(today);
        int isoWeek = QuotaDao.isoWeek(today);
        int limit = actorUser.weeklyQuotaMinutes();
        quotaDao.ensureRowExists(con, actor.getUserId(), isoYear, isoWeek, limit);
        boolean consumed = quotaDao.tryConsume(con, actor.getUserId(), isoYear, isoWeek, (int) req.durationMinutes());
        if (!consumed) {
            throw new QuotaExceededException((int) req.durationMinutes(), limit);
        }
    }

    private void checkOverlap(Connection con, Resource resource, BookingRequest req) throws SQLException {
        if (bookingDao.hasOverlap(con, resource.getId(), req.getStartAt(), req.getEndAt())) {
            LocalDateTime searchLimit = req.getStartAt().plusDays(MAX_ISOWEEK_SEARCH_DAYS);
            LocalDateTime nextFree = bookingDao.nextFreeSlotAfter(con, resource.getId(), req.getEndAt(),
                    resource.getSlotMinutes(), searchLimit).orElse(null);
            throw new SlotConflictException(resource.getName(), req.getStartAt(), nextFree);
        }
    }

    private long createBookingRecord(Connection con, BookingRequest req, SessionUser actor, BookingPolicy policy) throws SQLException {
        Booking booking = new Booking();
        booking.setResourceId(req.getResourceId());
        booking.setRequesterId(actor.getUserId());
        booking.setStartAt(req.getStartAt());
        booking.setEndAt(req.getEndAt());
        booking.setStatus(policy.resolveInitialStatus());
        booking.setPurpose(req.getPurpose());
        return bookingDao.insert(con, booking);
    }

    /** STEP 12 — the atomic-slot conversion described in PROJECT_BLUEPRINT_CORRECTED.md §19.4. */
    private List<BookingSlot> generateAtomicSlots(Resource resource, BookingRequest req, long bookingId) {
        List<BookingSlot> slots = new ArrayList<>();
        for (LocalDateTime slotStart : DateUtil.generateOccupiedSlots(req.getStartAt(), req.getEndAt(), resource.getSlotMinutes())) {
            slots.add(new BookingSlot(bookingId, resource.getId(), slotStart, false));
        }
        for (LocalDateTime slotStart : DateUtil.generateBufferSlots(req.getEndAt(), resource.cooldownMinutes(), resource.getSlotMinutes())) {
            slots.add(new BookingSlot(bookingId, resource.getId(), slotStart, true));
        }
        return slots;
    }

    /**
     * STEP 13 — the layer-3 backstop. A duplicate-key failure here (MySQL
     * error 1062 on booking_slot's UNIQUE(resource_id, slot_start)) is the
     * expected, correctly-functioning signal that two transactions raced
     * past layers 1 and 2 — it propagates as a checked SQLException up to
     * #createBookingLocked's catch(SQLException) block, which rolls back and
     * calls #handleBookingConflict to translate it.
     */
    private void insertBookingSlots(Connection con, Resource resource, BookingRequest req, List<BookingSlot> slots) throws SQLException {
        bookingSlotDao.insertBatch(con, slots);
    }

    private void enqueueNotification(Connection con, long bookingId, BookingStatus initialStatus) throws SQLException {
        String payload = "{\"bookingId\":" + bookingId + ",\"event\":\"" + initialStatus + "\"}";
        backgroundJobDao.enqueue(con, JobType.SEND_REMINDER, payload);
    }

    private void writeAudit(Connection con, SessionUser actor, long bookingId, Resource resource) throws SQLException {
        auditDao.write(con, AuditEntry.of(actor.getUserId(), "BOOKING_CREATE", "booking", bookingId,
                "resource=" + resource.getCode()));
    }

    /**
     * Translates the rolled-back SQLException. Error 1062 on booking_slot
     * (checked via SQLState/vendor code) becomes SlotConflictException — the
     * layer-3 catch. Anything else is an unexpected database error.
     */
    private RuntimeException handleBookingConflict(SQLException e, Connection con, BookingRequest req) {
        if (e.getErrorCode() == 1062 || (e.getSQLState() != null && e.getSQLState().equals("23000"))) {
            // No courtesy next-free-slot lookup here deliberately — see BookingService Javadoc:
            // the transaction that just failed is not a safe place to run further reads before rollback settles.
            return new SlotConflictException(
                    "resource " + req.getResourceId(), req.getStartAt(), null);
        }
        return new DataAccessException("Could not create booking.", e);
    }

    // ---- cancellation ----

    /**
     * Cancels an occupying booking. Uses BookingDao#transitionStatus's
     * conditional UPDATE (WHERE status = current) so a concurrent
     * cancellation/approval race is resolved by whichever UPDATE actually
     * affects the row — see PROJECT_BLUEPRINT_CORRECTED.md §18.4 Race R3/R5
     * for the same pattern applied elsewhere.
     */
    public void cancelBooking(SessionUser actor, long bookingId, String reason) {
        try (Connection con = ConnectionFactory.get()) {
            con.setAutoCommit(false);
            try {
                Booking booking = bookingDao.findById(con, bookingId)
                        .orElseThrow(() -> new com.apparat.exception.ResourceNotFoundException("Booking", bookingId));
                if (!booking.getRequesterId().equals(actor.getUserId()) && !actor.isApprover()) {
                    throw new com.apparat.exception.AuthorizationException("You can only cancel your own bookings.");
                }
                BookingStatus from = booking.getStatus();
                int updated = bookingDao.transitionStatus(con, bookingId, from, BookingStatus.CANCELLED, null, reason);
                if (updated == 1) {
                    bookingSlotDao.deleteByBookingId(con, bookingId); // release the slots — see schema.sql lifecycle comment
                    if (from.isOccupying()) {
                        LocalDate day = booking.getStartAt().toLocalDate();
                        quotaDao.release(con, booking.getRequesterId(), QuotaDao.isoYear(day), QuotaDao.isoWeek(day),
                                (int) booking.durationMinutes());
                    }
                    auditDao.write(con, AuditEntry.of(actor.getUserId(), "BOOKING_CANCELLED", "booking", bookingId, reason));
                    con.commit();
                    logWriter.append("BOOKING_CANCELLED", actor.getUserId(), "bookingId=" + bookingId);
                } else {
                    con.rollback();
                    throw new com.apparat.exception.InvalidBookingException("This booking can no longer be cancelled (its status has already changed).");
                }
            } catch (SQLException e) {
                con.rollback();
                throw new DataAccessException("Could not cancel booking.", e);
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not cancel booking.", e);
        }
    }
}
