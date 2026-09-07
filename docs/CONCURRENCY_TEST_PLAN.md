# Concurrency Test Plan — Apparat

Test plan for proving, not asserting, the double-booking guarantee described in [PROJECT_BLUEPRINT_CORRECTED.md](PROJECT_BLUEPRINT_CORRECTED.md) §18–§20. **No result in this document is a measured result until the corresponding test is actually implemented and run** — see the "Result" field convention below, and PROJECT_BLUEPRINT_CORRECTED.md §28 (Do Not Invent Implementation Results).

**Result field convention used throughout this document:**
> `Result: NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`
Replace this line with the actual observed output (e.g. exact success/failure counts, exception types thrown, timing) the first time the test is executed, and keep it updated if the test is re-run after a code change.

---

## 1. Test environment and setup

- MySQL 8 (InnoDB) test schema, created fresh from `database/schema.sql` for each test class run (or wrapped in a transaction per test and rolled back — pick one strategy and state it in the test class's Javadoc).
- A dedicated test resource seeded before each test: `SEM-01`, `resource_type = INSTRUMENT`, `open_time = 09:00`, `close_time = 18:00`, atomic slot size `30` minutes, `buffer_minutes = 0` (kept at zero specifically so slot-count assertions in these tests are not complicated by buffer slots — buffer interaction is covered separately in the Atomic Slot Generation unit tests, §5).
- A dedicated test requester user seeded before each test, with a weekly quota large enough that quota exhaustion is never the limiting factor in a concurrency test (quota logic has its own, separate test — §6).
- `BookingService` wired against the real `ConnectionFactory` pointed at the test schema — **these are integration tests against a real MySQL instance, not mocked**, because the entire point is to prove the database-level guarantee, not the Java code's intent.
- Each test resets the relevant rows in `bookings` and `booking_slot` for the test resource before it runs, so tests do not depend on execution order.

---

## 2. Test 1 — Fifty concurrent identical requests (the core conflict test)

**Class:** `ConcurrentBookingTest#fiftyConcurrentIdenticalRequests`

**Scenario:** 50 threads each attempt to book `SEM-01` for the identical interval `2026-10-05 14:00–15:00` (two 30-minute atomic slots: `14:00`, `14:30`).

**Mechanics:**
```java
int threadCount = 50;
CountDownLatch ready = new CountDownLatch(threadCount);
CountDownLatch start = new CountDownLatch(1);
CountDownLatch done  = new CountDownLatch(threadCount);
AtomicInteger successes = new AtomicInteger();
AtomicInteger conflicts = new AtomicInteger();
List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

ExecutorService pool = Executors.newFixedThreadPool(threadCount);
for (int i = 0; i < threadCount; i++) {
    final SessionUser actor = testUsers.get(i % testUsers.size()); // distinct users, same target slot
    pool.submit(() -> {
        ready.countDown();
        try {
            start.await();                       // all threads release together
            bookingService.createBooking(sameRequest(), actor);
            successes.incrementAndGet();
        } catch (SlotConflictException e) {
            conflicts.incrementAndGet();
        } catch (Throwable t) {
            unexpected.add(t);                    // anything else is a test failure, not an expected outcome
        } finally {
            done.countDown();
        }
    });
}
ready.await();
start.countDown();                                // fire all 50 as close to simultaneously as the JVM allows
done.await(30, TimeUnit.SECONDS);
pool.shutdown();
```

**Assertions:**
1. `successes.get() == 1` — exactly one booking succeeds.
2. `conflicts.get() == 49` — every other attempt fails specifically with `SlotConflictException`, not a generic error.
3. `unexpected.isEmpty()` — no unclassified exception (e.g. a `DataAccessException` masking a bug) occurred.
4. **Database-state verification, not just in-process counters:**
   `SELECT COUNT(*) FROM bookings WHERE resource_id = ? AND start_at = '2026-10-05 14:00:00' AND status IN ('PENDING_APPROVAL','CONFIRMED','CHECKED_IN')` → exactly **1**.
5. **Slot verification:** `SELECT COUNT(*) FROM booking_slot WHERE resource_id = ? AND slot_start IN ('2026-10-05 14:00:00','2026-10-05 14:30:00')` → exactly **2** (both slots owned by the single winning `booking_id` — verify via `SELECT DISTINCT booking_id FROM booking_slot WHERE ...` returning exactly one id).
6. **No orphaned slot rows:** `SELECT COUNT(*) FROM booking_slot bs LEFT JOIN bookings b ON bs.booking_id = b.id WHERE b.id IS NULL` → **0**, confirming that every rolled-back attempt left no partial `booking_slot` rows behind (proves the transaction boundary in PROJECT_BLUEPRINT_CORRECTED.md §20.2 step 13/16 is correctly all-or-nothing).

**Purpose:** this is the test referenced throughout the corrected blueprint (§18.5, §26 demo script, §31 README) as the proof of the three-layer defence. It is deliberately run against the real database so that even if the application-level `ReentrantLock` were disabled or buggy, assertions 4–6 would still catch a correctness failure — the test is validating the *outcome*, not the mechanism, which is the correct thing to validate.

**Result: RUN — 2026-09-06, against MySQL 5.7.24 (InnoDB) on this development machine, via `mvn test -Dapparat.it=true -Dtest=ConcurrentBookingTest`.**
Implemented as `src/test/java/com/apparat/concurrency/ConcurrentBookingTest#fiftyConcurrentIdenticalRequestsProduceExactlyOneWinner`, run against an isolated `TEST-CONCURRENCY` resource and 55 dedicated test users (not the demo seed data) so the run is repeatable and side-effect-free. **Measured: 50 threads submitted, exactly 1 success, exactly 49 `SlotConflictException`, 0 unexpected exceptions.** Database verification after the run: exactly 1 active booking on the resource for that slot; exactly 2 `booking_slot` rows (14:00 and 14:30, for the 60-minute booking at 30-minute granularity); both rows owned by the same single `booking_id`; 0 orphaned `booking_slot` rows anywhere in the table. Reproduced on a second independent run (0.66s elapsed) with an identical outcome. Test data was deleted after each run; the demo seed data (8 users, 4 resources, 3 bookings) was verified unaffected.

---

## 3. Test 2 — Adjacent, non-overlapping intervals (boundary condition, both must succeed)

**Class:** `ConcurrentBookingTest#adjacentIntervalsBothSucceed`

**Scenario:** Thread A books `SEM-01` for `10:00–11:00`; Thread B concurrently books `SEM-01` for `11:00–12:00`. These intervals share exactly one boundary instant (`11:00`) and must **not** be treated as overlapping.

**Mechanics:** same `CountDownLatch` synchronization pattern as Test 1, but with two threads and two distinct requests.

**Assertions:**
1. Both threads succeed — no `SlotConflictException` on either side.
2. `SELECT COUNT(*) FROM bookings WHERE resource_id = ? AND status IN (...)` → **2**.
3. `booking_slot` contains rows for `10:00, 10:30` (owned by A's booking) and `11:00, 11:30` (owned by B's booking) — four rows total, two distinct `booking_id` values, no overlap between the two sets.

**Purpose:** directly tests the strict-inequality form of the overlap condition (`A.start < B.end AND A.end > B.start`, PROJECT_BLUEPRINT_CORRECTED.md §19.7) — a common off-by-one mistake is to use `<=`/`>=`, which would incorrectly reject adjacent bookings that only touch at a shared boundary. This is viva question Q58 made executable.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 4. Test 3 — Partially overlapping intervals (exactly one must succeed)

**Class:** `ConcurrentBookingTest#partiallyOverlappingIntervalsOneWins`

**Scenario:** Thread A books `10:00–11:00`; Thread B concurrently books `10:30–11:30` — a genuine 30-minute overlap (the `10:30` slot is contested), not an edge case like Test 2.

**Assertions:**
1. Exactly one of the two threads succeeds; the other receives `SlotConflictException`.
2. `SELECT COUNT(*) FROM bookings WHERE resource_id = ? AND status IN (...)` → **1**.
3. If A wins: `booking_slot` contains `10:00, 10:30` only, owned by A. If B wins: `booking_slot` contains `10:30, 11:00` only, owned by B. Either outcome is a pass — the test does not assert *which* thread wins (that is a legitimate race outcome), only that exactly one does and the database is left in a consistent state either way.

**Purpose:** distinguishes "genuinely overlapping" from "merely adjacent" (Test 2) as two different, both-necessary boundary cases — a design that passes only one of these two tests has a real bug in exactly one direction of the inequality.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 5. Unit tests — atomic slot generation (isolated from concurrency, from Risk #7 in CORRECTIONS_LOG.md)

**Class:** `AtomicSlotGenerationTest` (pure unit tests against `generateAtomicSlots`, no database, no threads)

| Case | Input | Expected slots |
|---|---|---|
| Exact multiple of slot size | `10:00–11:30`, 30-min slots, 0 buffer | `10:00, 10:30, 11:00` |
| Minimum duration | `10:00–10:30`, 30-min slots | `10:00` |
| Maximum allowed duration | `09:00–13:00` on a resource with `max_slot_minutes = 240` | 8 slots, `09:00` through `12:30` |
| With buffer/cooldown | `10:00–11:00`, 30-min slots, `buffer_minutes = 15` (rounds up to one 30-min buffer slot) | `10:00, 10:30` (occupied, `is_buffer=0`) + `11:00` (`is_buffer=1`) |
| Misaligned start | `10:15–11:00` on a resource with 30-min slots | `SlotAlignmentException` thrown, carrying suggested alternatives `10:00–11:00` and `10:30–11:00` — **no slots generated** |
| Misaligned end | `10:00–10:45` on a resource with 30-min slots | `SlotAlignmentException` thrown |
| Boundary exactly on close time | `close_time = 18:00`, booking `17:30–18:00` | `17:30` — valid, does not attempt to generate a slot at `18:00` (exclusive end) |

**Purpose:** isolates the highest-risk new logic (Risk #7, CORRECTIONS_LOG.md) from the concurrency machinery so an off-by-one bug here is caught by a fast, deterministic unit test rather than only surfacing as a confusing concurrency-test failure.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 6. Test 4 — Concurrent quota consumption (Race R2)

**Class:** `ConcurrentQuotaTest#concurrentBookingsRespectQuotaLimit`

**Scenario:** a requester with `limit_minutes = 60` remaining in the current week attempts two concurrent 60-minute bookings on two *different*, non-conflicting resources (so Test 1's slot-uniqueness mechanism is deliberately not the thing under test — only the quota path is exercised).

**Assertions:**
1. Exactly one booking succeeds; the other fails with `QuotaExceededException`.
2. `SELECT used_minutes FROM quota_usage WHERE user_id = ? AND ...` → exactly `60`, never `120` (which would indicate the lost-update bug the atomic `UPDATE` in PROJECT_BLUEPRINT_CORRECTED.md §21 was designed to prevent) and never `0` (which would indicate the successful booking's consumption was lost).

**Purpose:** directly tests Race R2 (PROJECT_BLUEPRINT_CORRECTED.md §18.4) and the ADR-11 atomic-update decision.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 7. Test 5 — Waitlist promotion vs. cancellation race (Race R3)

**Class:** `ConcurrentWaitlistTest#promotionAndCancellationRaceIsIdempotent`

**Scenario:** a single `WAITING` waitlist entry; one thread calls `WaitlistService.promoteNext(resourceId)` (as the `NoShowSweeper` would) while another thread concurrently calls `WaitlistService.cancel(entryId)` (as the user manually cancelling their own waitlist spot would).

**Assertions:**
1. The waitlist entry ends in exactly one terminal state (`PROMOTED` or `CANCELLED`), never both, and never left `WAITING`.
2. Whichever operation "loses" the race observes zero affected rows from its conditional `UPDATE ... WHERE status = 'WAITING'` and takes no further action (no booking created from a promotion that lost the race; no error surfaced to the user beyond an accurate "this spot was already resolved" message).

**Purpose:** tests Race R3 and its `WHERE status = 'WAITING'` idempotency guard directly.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 8. Test 6 — No-show sweep vs. manual check-in race (Race R5)

**Class:** `ConcurrentCheckInTest#sweepAndCheckInRaceLeavesConsistentState`

**Scenario:** a `CONFIRMED` booking whose grace period has just elapsed; one thread runs the `NoShowSweeper`'s transition logic (`UPDATE bookings SET status='NO_SHOW' WHERE id=? AND status='CONFIRMED'`) while another thread concurrently performs a manual check-in (`UPDATE bookings SET status='CHECKED_IN', check_in_at=NOW() WHERE id=? AND status='CONFIRMED'`).

**Assertions:**
1. The booking ends in exactly one of `NO_SHOW` or `CHECKED_IN`, never both, and the affected-row count on the losing update is 0.
2. If the sweeper wins: the slot is released and, if a waitlist entry exists, promoted — verify via the same mechanism as Test 5.
3. If check-in wins: no waitlist promotion occurs and the booking proceeds normally toward `COMPLETED`.

**Purpose:** tests Race R5 directly, and confirms the two operations' conditional `UPDATE ... WHERE status = 'CONFIRMED'` clauses are mutually exclusive by construction, not by luck.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 9. Test 7 — Reminder job idempotency under a simulated restart (Race R4)

**Class:** `ReminderDispatcherTest#reminderNotSentTwiceAcrossRestart`

**Scenario:** `ReminderDispatcher` runs once, correctly sending a reminder for a booking and setting `reminder_sent = 1` in the same transaction as the `background_jobs` insert. The scheduler is then invoked a second time (simulating a restart re-polling the same time window) without any state reset.

**Assertions:**
1. After the first run: exactly one `background_jobs` row of type `NOTIFICATION` exists for this booking, and `reminder_sent = 1`.
2. After the second run: still exactly one such row — the second run's query (`WHERE reminder_sent = 0`) excludes the booking, so no duplicate is created.

**Purpose:** tests Race R4 and demonstrates the specific idempotency mechanism (a flag set in the same transaction as the side effect it guards) rather than merely asserting "it won't double-send."

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 10. Test 8 — Background job claim is race-free (Risk #8, CORRECTIONS_LOG.md)

**Class:** `ConcurrentJobClaimTest#twoWorkersClaimingSameJobExactlyOneWins`

**Scenario:** a single `PENDING` row in `background_jobs`; two worker threads concurrently attempt the claim operation (`UPDATE background_jobs SET status='RUNNING', locked_at=NOW() WHERE id=? AND status='PENDING'`).

**Assertions:**
1. Exactly one `UPDATE` reports 1 affected row; the other reports 0.
2. The job is executed exactly once (instrument the job's `execute()` with a counter for the test; assert it was called exactly once).

**Purpose:** directly guards against Risk #8 — a worker implementation that mistakenly uses a plain `SELECT` followed by a separate `UPDATE` to claim work would fail this test by allowing both workers to execute the job, which is exactly the class of bug this test exists to catch before it reaches the demo.

**Result:** `NOT YET RUN — fill in after executing against a real build. Do not pre-fill a specific pass/fail count.`

---

## 11. Summary table (fill in as tests are implemented and run)

| Test | Race/behaviour covered | Status | Result |
|---|---|---|---|
| Test 1 — 50 concurrent identical requests | R1 (double booking) | Not yet implemented | NOT YET RUN |
| Test 2 — Adjacent intervals | Boundary correctness of overlap condition | Not yet implemented | NOT YET RUN |
| Test 3 — Partial overlap | R1 (genuine overlap) | Not yet implemented | NOT YET RUN |
| §5 — Atomic slot generation (unit) | Slot math, alignment policy | Not yet implemented | NOT YET RUN |
| Test 4 — Concurrent quota | R2 (lost update) | Not yet implemented | NOT YET RUN |
| Test 5 — Waitlist promotion vs. cancel | R3 | Not yet implemented | NOT YET RUN |
| Test 6 — Sweep vs. check-in | R5 | Not yet implemented | NOT YET RUN |
| Test 7 — Reminder idempotency | R4 | Not yet implemented | NOT YET RUN |
| Test 8 — Job claim race | Outbox worker correctness | Not yet implemented | NOT YET RUN |

**Update this table, and the "Result" line in each section above, the first time each test is actually implemented and run. Never state a specific count, timing, or pass/fail outcome anywhere in this document, the corrected blueprint, or the GitHub README before that has happened.**
