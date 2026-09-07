package com.apparat.concurrency;

import com.apparat.config.ConnectionFactory;
import com.apparat.dao.AuditDao;
import com.apparat.dao.BackgroundJobDao;
import com.apparat.dao.BookingDao;
import com.apparat.dao.BookingSlotDao;
import com.apparat.dao.MaintenanceDao;
import com.apparat.dao.QuotaDao;
import com.apparat.dao.ResourceDao;
import com.apparat.dao.UserDao;
import com.apparat.exception.SlotConflictException;
import com.apparat.model.User;
import com.apparat.model.dto.BookingRequest;
import com.apparat.model.dto.SessionUser;
import com.apparat.model.enums.Role;
import com.apparat.service.BookingService;
import com.apparat.util.LogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The central concurrency proof — CONCURRENCY_TEST_PLAN.md Tests 1-3. Real
 * integration tests against a real MySQL instance (InnoDB), not mocked:
 * the entire point is to prove the DATABASE-level guarantee, not the Java
 * code's intent. See BookingService's Javadoc for the mechanism under test.
 *
 * Requires a running MySQL reachable via app.properties. Guarded by the
 * "apparat.it" system property so a plain `mvn test` (no database available)
 * does not fail the build — run with `mvn test -Dapparat.it=true` once
 * MySQL is up and sql/schema.sql + a test user/resource are loaded. See
 * README "Running tests".
 */
@EnabledIfSystemProperty(named = "apparat.it", matches = "true")
class ConcurrentBookingTest {

    private static Long resourceId;
    private static List<Long> userIds;
    private static BookingService bookingService;
    private static UserDao userDao;

    @BeforeAll
    static void setUpOnce() throws Exception {
        ResourceDao resourceDao = new ResourceDao();
        BookingDao bookingDao = new BookingDao();
        BookingSlotDao bookingSlotDao = new BookingSlotDao();
        MaintenanceDao maintenanceDao = new MaintenanceDao();
        QuotaDao quotaDao = new QuotaDao();
        BackgroundJobDao backgroundJobDao = new BackgroundJobDao();
        AuditDao auditDao = new AuditDao();
        userDao = new UserDao();
        LogWriter logWriter = new LogWriter(Files.createTempDirectory("apparat-test-data"));
        bookingService = new BookingService(resourceDao, bookingDao, bookingSlotDao, maintenanceDao,
                quotaDao, backgroundJobDao, auditDao, logWriter);

        // A dedicated test resource, isolated from the seeded demo resources, with a
        // generous quota-holding set of test users so quota is never the limiting factor here
        // (quota concurrency has its own dedicated test).
        try (Connection con = ConnectionFactory.get()) {
            resourceId = insertTestResource(con);
            userIds = insertTestUsers(con, 55);
        }
    }

    @BeforeEach
    void clearBookingsForResource() throws SQLException {
        try (Connection con = ConnectionFactory.get(); Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM booking_slot WHERE resource_id = " + resourceId);
            st.executeUpdate("DELETE FROM bookings WHERE resource_id = " + resourceId);
        }
    }

    @Test
    void fiftyConcurrentIdenticalRequestsProduceExactlyOneWinner() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusMinutes(60);

        int threadCount = 50;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final SessionUser actor = sessionUserFor(userIds.get(i % userIds.size()));
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    User actorUser = userDao.findById(actor.getUserId()).orElseThrow();
                    bookingService.createBooking(new BookingRequest(resourceId, start, end, "concurrency test"), actor, actorUser);
                    successes.incrementAndGet();
                } catch (SlotConflictException e) {
                    conflicts.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(finished, "Not all threads finished within the timeout");
        if (!unexpected.isEmpty()) {
            unexpected.forEach(Throwable::printStackTrace);
        }
        assertTrue(unexpected.isEmpty(), "Unexpected exception types occurred: " + unexpected);

        assertEquals(1, successes.get(), "Exactly one booking must succeed");
        assertEquals(49, conflicts.get(), "Every other attempt must fail with SlotConflictException");

        // Database-state verification, not just in-process counters — see CONCURRENCY_TEST_PLAN.md Test 1.
        try (Connection con = ConnectionFactory.get()) {
            assertEquals(1, countActiveBookings(con, resourceId, start));
            assertEquals(2, countSlotRows(con, resourceId, start)); // 14:00 and 14:30 — a 60-minute booking at 30-minute slots
            assertEquals(1, countDistinctBookingIdsForSlots(con, resourceId, start));
            assertEquals(0, countOrphanedSlots(con));
        }
    }

    @Test
    void adjacentIntervalsBothSucceed() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime mid = start.plusMinutes(60);
        LocalDateTime end = mid.plusMinutes(60);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable taskA = bookingTask(sessionUserFor(userIds.get(0)), start, mid, ready, go, done, successes, unexpected);
        Runnable taskB = bookingTask(sessionUserFor(userIds.get(1)), mid, end, ready, go, done, successes, unexpected);
        pool.submit(taskA);
        pool.submit(taskB);

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), "Unexpected exceptions: " + unexpected);
        assertEquals(2, successes.get(), "Adjacent, non-overlapping bookings must BOTH succeed");
    }

    @Test
    void partiallyOverlappingIntervalsExactlyOneWins() throws Exception {
        LocalDateTime startA = LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endA = startA.plusMinutes(60);
        LocalDateTime startB = startA.plusMinutes(30);
        LocalDateTime endB = startB.plusMinutes(60);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(bookingTaskCounting(sessionUserFor(userIds.get(2)), startA, endA, ready, go, done, successes, conflicts, unexpected));
        pool.submit(bookingTaskCounting(sessionUserFor(userIds.get(3)), startB, endB, ready, go, done, successes, conflicts, unexpected));

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(unexpected.isEmpty(), "Unexpected exceptions: " + unexpected);
        assertEquals(1, successes.get(), "Exactly one of two genuinely overlapping bookings must succeed");
        assertEquals(1, conflicts.get());
    }

    // ---- helpers ----

    private Runnable bookingTask(SessionUser actor, LocalDateTime start, LocalDateTime end,
                                  CountDownLatch ready, CountDownLatch go, CountDownLatch done,
                                  AtomicInteger successes, List<Throwable> unexpected) {
        return () -> {
            ready.countDown();
            try {
                go.await();
                User actorUser = userDao.findById(actor.getUserId()).orElseThrow();
                bookingService.createBooking(new BookingRequest(resourceId, start, end, "adjacency test"), actor, actorUser);
                successes.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                done.countDown();
            }
        };
    }

    private Runnable bookingTaskCounting(SessionUser actor, LocalDateTime start, LocalDateTime end,
                                          CountDownLatch ready, CountDownLatch go, CountDownLatch done,
                                          AtomicInteger successes, AtomicInteger conflicts, List<Throwable> unexpected) {
        return () -> {
            ready.countDown();
            try {
                go.await();
                User actorUser = userDao.findById(actor.getUserId()).orElseThrow();
                bookingService.createBooking(new BookingRequest(resourceId, start, end, "overlap test"), actor, actorUser);
                successes.incrementAndGet();
            } catch (SlotConflictException e) {
                conflicts.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                done.countDown();
            }
        };
    }

    private SessionUser sessionUserFor(Long userId) {
        return new SessionUser(userId, "Test User " + userId, Role.STUDENT, "Test");
    }

    private static Long insertTestResource(Connection con) throws SQLException {
        String sql = "INSERT INTO resources (code, name, resource_type, category, location, status, "
                + "open_time, close_time, slot_minutes, min_slot_minutes, max_slot_minutes, buffer_minutes, "
                + "requires_certification, requires_approval) VALUES "
                + "('TEST-CONCURRENCY', 'Concurrency Test Resource', 'INSTRUMENT', 'Test', 'Test Lab', 'AVAILABLE', "
                + "'00:00:00', '23:30:00', 30, 30, 240, 0, 0, 0)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<Long> insertTestUsers(Connection con, int count) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "INSERT INTO users (email, password_hash, full_name, role, active) VALUES (?,?,?,?,1)";
        for (int i = 0; i < count; i++) {
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "concurrency.test." + System.nanoTime() + "." + i + "@apparat.test");
                ps.setString(2, "PBKDF2$1$AA==$AA==");
                ps.setString(3, "Concurrency Test User " + i);
                ps.setString(4, "STUDENT");
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private static int countActiveBookings(Connection con, Long resourceId, LocalDateTime start) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM bookings WHERE resource_id=? AND start_at=? AND status IN ('PENDING','APPROVED')")) {
            ps.setLong(1, resourceId);
            ps.setObject(2, start);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int countSlotRows(Connection con, Long resourceId, LocalDateTime start) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM booking_slot WHERE resource_id=? AND slot_start IN (?,?)")) {
            ps.setLong(1, resourceId);
            ps.setObject(2, start);
            ps.setObject(3, start.plusMinutes(30));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int countDistinctBookingIdsForSlots(Connection con, Long resourceId, LocalDateTime start) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(DISTINCT booking_id) FROM booking_slot WHERE resource_id=? AND slot_start IN (?,?)")) {
            ps.setLong(1, resourceId);
            ps.setObject(2, start);
            ps.setObject(3, start.plusMinutes(30));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int countOrphanedSlots(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM booking_slot bs LEFT JOIN bookings b ON bs.booking_id = b.id WHERE b.id IS NULL")) {
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
