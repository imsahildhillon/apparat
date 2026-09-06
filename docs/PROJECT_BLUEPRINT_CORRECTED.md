# APPARAT — Project Blueprint (Corrected / Implementation-Ready)
### Shared-Instrument Scheduling & Utilization Platform for University Core Labs
*Engineering hardening pass over the original blueprint. Product idea, feature set, OOP architecture, and JSP/Servlet/JDBC/MySQL stack are unchanged. This pass corrects technical inconsistencies so every concurrency, persistence, session, and database claim is defensible in a viva.*
*Prepared: 2026-09-06 · Revision: corrected-v1*

> **What changed and why:** see [CORRECTIONS_LOG.md](CORRECTIONS_LOG.md) for a full changelog, [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) for the rationale behind each final decision, and [CONCURRENCY_TEST_PLAN.md](CONCURRENCY_TEST_PLAN.md) for the test cases that prove the concurrency claims in this document. Nothing here changes the selected project — Apparat remains the recommendation.

---

## 1. EXECUTIVE VERDICT

**BUILD THIS PROJECT: `Apparat` — a shared-resource (lab instrument / studio / equipment) booking, approval, and utilization platform.**

One line: *Apparat prevents double-booking of expensive shared equipment, enforces access rules and usage quotas, and turns booking history into utilization analytics that justify purchase decisions.*

Every required Java concept is demanded by the problem, not bolted onto it. Two people booking the same microscope at the same second is a genuine race condition, so multithreading, locking and database transactions solve a real problem instead of decorating one. This corrected version makes the exact mechanics of that guarantee — and the honest limits of each layer — precise enough to defend line-by-line in a viva.

---

## 2. RESEARCH FINDINGS

*(Unchanged from the original research pass — see the original [PROJECT_BLUEPRINT.md](PROJECT_BLUEPRINT.md) §2 for the full citations on the Java project landscape, Jakarta EE/Servlet-JSP relevance, the documented lab-scheduling problem, and what makes a student GitHub repository credible to recruiters. Nothing in the research findings required correction — the issues found were entirely in the technical design that followed from them.)*

---

## 3–11. CANDIDATES, ELIMINATION, TECHNICAL FIT, SCORECARD, WINNING PROJECT

*(Unchanged — see original document §3–§11. The project selection, elimination reasoning, and scorecard stand. Table-count references in the GitHub evaluation table are corrected below in place.)*

### GitHub evaluation (corrected row)

| Question | Apparat |
|---|---|
| Enough depth to hold attention 3+ minutes? | Yes: layered architecture, a **13-table normalized schema**, a documented three-layer concurrency defence, and a concurrency test |
| ER diagram showable? | Yes — **13 core relational tables** with real FKs and composite constraints |

---

## 12. OOP ARCHITECTURE & CLASS DESIGN

*(Base hierarchy unchanged — `Resource`, `User`, `BookingPolicy`, `Notifier`, `ReportExporter`, `Dao<T,ID>` all stand as originally designed. Two corrections below.)*

### 12.1 Correction — the job hierarchy now separates durable state from local checkpoint state

The original design treated the in-memory job queue as if it were the durable record of "work to do." That is corrected: **the database is the durable record; serialization is a local, bounded checkpoint of a worker's in-memory state.** See §17 for the full reliability model. The class hierarchy becomes:

```
<<interface>> Job    (Serializable, for the in-memory checkpoint only)
  + void execute(ServiceRegistry services)
  + JobType type()
        ^              ^              ^
 NotificationJob   ReportJob     CsvImportJob

BackgroundJobRecord   (plain model class — mirrors a row in the `background_jobs` table)
  - id, jobType, payload, status, attemptCount, availableAt, lockedAt,
    completedAt, errorMessage, createdAt
  + boolean isDue()  + boolean canRetry()
```

A `Job` (in-memory, `Serializable`) is constructed *from* a `BackgroundJobRecord` (durable, database-backed) when a worker claims it. The database row is authoritative; the in-memory `Job` object is disposable and only checkpointed to disk so a worker that restarts does not have to re-query for tasks it had already picked up. This distinction is explained in full in §17.

### 12.2 Correction — `SessionUser` design is now explicit

`SessionUser` (package `model.dto`, `implements Serializable`) is deliberately minimal:

```java
public final class SessionUser implements Serializable {
    private static final long serialVersionUID = 1L;
    private final long userId;
    private final String fullName;
    private final RoleType role;
    private final String department;   // nullable

    // constructor, getters only — no setters, immutable
}
```

It holds **identity and authorization context only**. It never holds a `Connection`, a DAO, a `Service`, a `Resource`, a `Booking` collection, or any mutable cache. See §22 for why, and for exactly what container behaviour `Serializable` does and does not buy the application.

---

## 13. REQUIREMENT → IMPLEMENTATION MAPPING (corrected row)

| Professor's requirement | Exact implementation in Apparat | Example class | Why it is genuinely needed |
|---|---|---|---|
| Schema design | **13-table** 3NF-oriented schema, with one deliberately-labelled derived occupancy structure (`booking_slot`) and one durable job/outbox table (`background_jobs`) | `schema.sql` | Prevents double booking at the DB level; makes background work crash-recoverable |

All other rows are unchanged from the original mapping table (§13 of the original document).

---

## 14. COLLECTION STRATEGY

Unchanged — see original §14. `ConcurrentHashMap<Long,ReentrantLock>`, `PriorityQueue<WaitlistEntry>`, `HashSet<String>` certifications, `TreeMap`/`NavigableMap` availability timeline, `EnumMap<BookingStatus,Integer>` dashboard counts, `LinkedBlockingQueue<Job>` producer/consumer hand-off (now explicitly scoped — see §17.4) all stand.

---

## 15. EXCEPTION STRATEGY

Unchanged in shape — see original §15 for the full `ApparatException` hierarchy. One addition:

```
ApparatException
  └── ...
        └── SlotAlignmentException   (400 — requested interval does not align to the resource's atomic slot size)
```

`SlotAlignmentException` carries the resource's configured slot size and the nearest valid start times, so the UI can suggest a correction rather than only rejecting.

---

## 16. FILE HANDLING (corrected)

| Use case | Direction | Format | Status |
|---|---|---|---|
| `app.properties` — DB URL, pool size, atomic slot size, no-show grace minutes, reminder lead time, thread-pool sizes | Read at startup | .properties | MUST HAVE |
| Bulk resource import (`resources.csv`) | Read | CSV | MUST HAVE |
| Bulk user/roster import | Read | CSV | MUST HAVE |
| Utilization / booking / no-show reports | Write | CSV + HTML | SHOULD HAVE |
| Audit log (`logs/audit-YYYY-MM-DD.log`) | Append, daily rotation | plain text | MUST HAVE |
| **Local worker-state checkpoint** (`jobs/checkpoint.ser`) | Write/read | Java serialization | MUST HAVE — see §17. This is a *local recovery aid*, not the durable job record. |
| Generated report artefacts (`reports/*.csv`) | Write then stream to browser | CSV | SHOULD HAVE |
| ~~Nightly database backup dump~~ | Write | .sql | **Moved to NICE TO HAVE.** A scheduled `mysqldump` invocation that is not actually implemented and verified reliably must not be claimed as working. If attempted, it is an admin-triggered on-demand action, not an unattended nightly job — see §35/§36. |
| Uploaded instrument photo / manual PDF (optional) | Write | binary | NICE TO HAVE |

Every remaining row has a legitimate, load-bearing purpose. Nothing here exists solely to check a "File Handling" box.

---

## 17. SERIALIZATION AND THE BACKGROUND-JOB RELIABILITY MODEL (corrected — the most important correction in this document)

### 17.1 The problem with the original design

The original blueprint proposed submitting a `NotificationJob` to the in-memory queue *after* the booking transaction committed, and separately claimed that periodically serializing that in-memory queue to `jobs/queue.ser` made this "crash-safe." Both claims were too strong:

1. **The gap between commit and enqueue is real.** If the JVM crashes in the instant after `con.commit()` returns but before `jobQueue.submit(...)` runs, the job is lost — the booking exists, but nobody is ever notified, and a waitlisted user is never promoted. No amount of *later* serialization checkpoints the queue can recover a job that was never enqueued.
2. **Claiming `Serializable` on `HttpSession` attributes "persists sessions across a Tomcat restart" overstates the JDK contract.** Whether a container persists, replicates, or discards session state across a restart is a **container/configuration decision** (Tomcat's `PersistentManager`/`StandardManager` on shutdown, session clustering, etc.), not something `Serializable` guarantees on its own. `Serializable` is a *precondition* for the container to do that if and when it is configured to.

### 17.2 The corrected model — a database outbox is the durable record

The database is the single source of truth for "work that must happen." The schema's `report_jobs` table (as originally scoped, report-generation only) is **generalized into `background_jobs`**, a general-purpose durable job/outbox table used for notifications, report generation, and CSV import alike. This does not add a table to the schema — it replaces `report_jobs` one-for-one, so the table count is unchanged at 13 (see §19).

**The write path (outbox pattern):**
```
Business transaction (e.g. createBooking)
  → write domain changes (booking, booking_slot rows, quota)
  → write a background_jobs row in the SAME transaction, status = PENDING
  → COMMIT                      -- domain change and the job intent commit or roll back together
  ↓
Background worker (separate thread, separate transaction)
  → polls background_jobs WHERE status = 'PENDING' AND available_at <= NOW()
  → claims a row: UPDATE background_jobs SET status='RUNNING', locked_at = NOW()
                  WHERE id = ? AND status = 'PENDING'   -- claim is itself a conditional update
  → executes the job
  → on success:  UPDATE ... SET status = 'COMPLETE', completed_at = NOW()
  → on failure:  UPDATE ... SET status = 'PENDING', attempt_count = attempt_count + 1,
                                available_at = NOW() + backoff(attempt_count), error_message = ?
                 (or 'FAILED' once attempt_count exceeds a configured maximum)
```
Because the job row is written **inside the same transaction as the booking**, there is no window in which the booking exists but the intent to notify does not — commit is atomic across both. If the worker itself crashes after claiming a row but before completing it, the row is left `RUNNING`; a `stalled-job reclaimer` (one more small responsibility of the `QueueCheckpointer` scheduled task) resets any `RUNNING` row whose `locked_at` is older than a timeout back to `PENDING`, so it is retried. This is the standard, honestly-scoped version of "crash-safe background work" for a single-node student deployment.

**`background_jobs` columns:** `id`, `job_type` (`NOTIFICATION`/`REPORT`/`CSV_IMPORT`), `payload` (TEXT — a small JSON/serialized parameter blob, e.g. `{"bookingId":123,"event":"CONFIRMED"}`), `status` (`PENDING`/`RUNNING`/`COMPLETE`/`FAILED`), `attempt_count`, `available_at`, `locked_at`, `completed_at`, `error_message`, `created_at`.

### 17.3 Where Java serialization still belongs, and why it is honest

Serialization is still required by the course, and it still has a genuine (if intentionally bounded) job here: **the worker pool's local in-memory job queue is periodically checkpointed to disk purely so a restarted worker does not have to cold-start with an empty picture of what it was mid-processing.** This is explicitly a **local recovery aid layered on top of the durable database record**, not a replacement for it:

```
DATABASE OUTBOX (background_jobs table)
  → authoritative durable job state. Survives any crash. Source of truth.

SERIALIZED QUEUE SNAPSHOT (jobs/checkpoint.ser)
  → local worker-state checkpoint. Bounded, single-JVM, educational demonstration
    of Java serialization. If lost, nothing is lost — the worker simply re-polls
    background_jobs on next tick and rebuilds its in-memory queue from the
    authoritative source.
```

Say this exact distinction in a viva: **"if I deleted `checkpoint.ser` entirely, the system would still be correct — it would just re-poll the database. If I deleted `background_jobs`, the system would lose real work."** That sentence is the whole point of the correction, and it is a strong, honest answer.

The `Serializable` object graph checkpointed is the small `Job` hierarchy from §12.1 (`serialVersionUID` declared explicitly on each class; `transient` on anything non-serializable). **In a real distributed deployment this local checkpoint would be dropped entirely and replaced by additional workers polling the same `background_jobs` table, or by a message broker — Java native serialization is not, and is not presented as, a production-grade distributed queue.** State this limitation unprompted; it is one of the strongest available answers in the whole project (see ARCHITECTURE_DECISIONS.md).

**Second, independent, and still-legitimate serialization use:** `SessionUser` (§12.2) implements `Serializable` so the application is **compatible with** container-managed session persistence or clustering *if and when the deployment enables it* (e.g. Tomcat's `PersistentManager`, or a session-replication setup in a multi-instance deployment). Apparat does not claim to configure or rely on that behaviour by default for its single-Tomcat-instance academic deployment; the correct claim is narrower: *the session objects are small and serialization-safe, which is a prerequisite, not a guarantee, for container-level persistence.*

### 17.4 Risks and limitations (unchanged from the original, restated for completeness)

- Explicit `serialVersionUID` on every serializable class, so a field addition does not throw `InvalidClassException` against an old checkpoint file.
- **Never deserialize untrusted input.** Apparat only ever deserializes `checkpoint.ser`, a file it wrote itself, in a directory only the application writes to — never a user upload, never network input. State the general deserialization-of-untrusted-data risk (gadget-chain remote code execution) even though this project is not exposed to it, to show the risk is understood, not merely avoided by accident.
- `transient` on any field holding a `Connection`, `Logger`, or other non-serializable/sensitive reference.

---

## 18. MULTITHREADING (corrected — responsibilities, races, and shutdown made explicit)

### 18.1 The three concurrency areas, with explicit responsibility

**A. Per-resource booking lock (`ReentrantLock` via `ConcurrentHashMap<Long, ReentrantLock>`, request threads)**
Purpose: reduce **in-process contention** and avoid two Tomcat request threads doing redundant overlap-check work against the same resource inside one JVM. This is a throughput/coordination optimisation, not the correctness guarantee — see §18.4 for the explicit statement of its limits.

**B. `ScheduledExecutorService` (`Executors.newScheduledThreadPool(2)`) — recurring maintenance jobs**
| Job | Period | Work |
|---|---|---|
| `NoShowSweeper` | every 60 s | `CONFIRMED` bookings past `start + grace` with no check-in → `NO_SHOW`, free the slot, promote waitlist, write a `background_jobs` notification row — one transaction. |
| `ReminderDispatcher` | every 5 min | Bookings starting within the reminder window, `reminder_sent = 0` → write a `background_jobs` notification row and set `reminder_sent = 1` **in the same transaction** (idempotency — see Race 4, §18.5). |
| `QueueCheckpointer` | every 60 s | (1) Serialize the worker pool's current in-memory queue to `jobs/checkpoint.ser` — the bounded local aid from §17.3; (2) reclaim stalled `RUNNING` rows in `background_jobs` older than the lock timeout, resetting them to `PENDING`. |

**C. `ExecutorService` fixed worker pool (`Executors.newFixedThreadPool(4)`) — job execution**
Polls `background_jobs` for `PENDING` rows (via a small internal `LinkedBlockingQueue<BackgroundJobRecord>` used strictly as the in-process hand-off between the polling loop and the worker threads — not as the durable queue itself), claims a row with the conditional `UPDATE` from §17.2, executes it (send notification / generate report / import CSV), and marks it `COMPLETE` or reschedules it on failure.

### 18.2 Thread lifecycle and shutdown

```
contextInitialized (AppLifecycleListener, on deploy)
  → create the ScheduledExecutorService and the ExecutorService
  → attempt to load jobs/checkpoint.ser (best-effort; absence is not an error — see §17.3)
  → schedule NoShowSweeper, ReminderDispatcher, QueueCheckpointer
  → start the worker pool's polling loop against background_jobs

contextDestroyed (on undeploy/shutdown)
  → stop scheduling new recurring runs (scheduledExecutor.shutdown())
  → stop accepting new work into the polling loop
  → workerPool.shutdown()
  → awaitTermination(20, SECONDS)
  → if not terminated: shutdownNow()
  → write a final checkpoint of whatever remains in the in-memory queue
  → release the JDBC connection pool
```

Threads must not outlive the web application: an un-shut-down `ExecutorService` pins its threads (and, through them, the whole web app's classloader) in memory after undeploy, which Tomcat logs as a leak and which prevents the classloader from being garbage collected on redeploy. This is a specific, checkable failure mode — demonstrate a clean redeploy with no leak warning as evidence.

### 18.3 Why the database, not the application lock, is the authoritative concurrency boundary

The `ReentrantLock` in area A is **JVM-local**. If Apparat were ever deployed as two Tomcat instances behind a load balancer, each instance would have its *own* `ConcurrentHashMap<Long, ReentrantLock>` — the two locks would not coordinate with each other at all, and two requests landing on different instances could both believe they held "the" lock for the same resource. The database guarantees (§19–§20: `SELECT ... FOR UPDATE` plus the `UNIQUE(resource_id, slot_start)` constraint) do **not** share this limitation, because MySQL is the one shared, authoritative point every instance talks to. **This is the corrected, three-layer framing, and no single layer is presented as sufficient by itself:**

| Layer | Mechanism | Scope | What it actually buys |
|---|---|---|---|
| 1 | `ReentrantLock` per resource | Single JVM | Reduces wasted work between concurrent request threads in one instance; not a correctness guarantee on its own |
| 2 | `SELECT ... FOR UPDATE` inside a transaction | All JVMs talking to this MySQL instance | Serializes the check-then-act sequence across every process, not just one |
| 3 | `UNIQUE(resource_id, slot_start)` on `booking_slot` | The database itself | Final integrity backstop — rejects a conflicting write even if layers 1 and 2 were somehow bypassed or raced |

**One sentence to have ready verbatim:** *"Application locking reduces in-process contention, database locking coordinates transactional access across every process talking to the database, and the unique atomic-slot constraint is the final integrity backstop."*

### 18.4 Formal race-condition catalogue

| # | Race | Why it happens | Mitigation | Transaction boundary / idempotency |
|---|---|---|---|---|
| **R1** | Two users book the same resource/time simultaneously | Both threads read "slot free" before either writes | Three-layer defence (§18.3): lock → `FOR UPDATE` → unique constraint on `booking_slot` | One transaction per `createBooking` call; a unique-key violation is caught and translated to `SlotConflictException` (§20.5) |
| **R2** | Two concurrent bookings both try to consume the same weekly quota | Read-modify-write in Java (`read used`, `add`, `write`) can lose an update if two threads interleave | **Never** read-then-write in Java. Use a single atomic conditional `UPDATE` (§21) and check `affectedRows` | Same transaction as the booking insert; if the conditional update affects 0 rows, treat it as `QuotaExceededException` and roll back |
| **R3** | Waitlist promotion runs concurrently with a manual cancellation of the same waitlist entry | Both paths call `WaitlistService.promoteNext`/`cancel` against the same row | Guard the state transition in SQL: `UPDATE waitlist_entries SET status='PROMOTED' WHERE id=? AND status='WAITING'`; check affected rows before proceeding | One transaction per promotion/cancellation; the `WHERE status='WAITING'` clause makes the operation idempotent — the loser sees 0 affected rows and simply does nothing further |
| **R4** | The reminder job runs twice for the same booking (e.g. after a worker restart re-polls a window it already handled) | A scheduled job is not naturally exactly-once | Set `reminder_sent = 1` **in the same transaction** as writing the `background_jobs` row, gated by `WHERE reminder_sent = 0` | Same transaction; the flag is the idempotency key |
| **R5** | `NoShowSweeper` marks a booking `NO_SHOW` at nearly the same instant the user actually checks in | Both paths race on the same booking's status | Guard the transition in SQL: `UPDATE bookings SET status='NO_SHOW' WHERE id=? AND status='CONFIRMED'` (sweeper) vs `UPDATE bookings SET status='CHECKED_IN', check_in_at=NOW() WHERE id=? AND status='CONFIRMED'` (check-in) — only one of the two conditional updates can succeed, because after the first commits the row is no longer `CONFIRMED` | Each is its own short transaction; the `WHERE status='CONFIRMED'` clause is the concurrency guard, not a lock held across both operations |

None of these are asserted safe by claim alone — each row states the specific SQL-level mechanism (a conditional `UPDATE` whose `WHERE` clause encodes the precondition, checked via affected-row count) that makes it safe. "Threads are safe" is never an acceptable answer on its own in this project; the mechanism must always be named.

### 18.5 Deadlock vs race condition (explicit distinction for the viva)

A **race condition** is an incorrect *result* caused by timing — two operations interleave in an order the code did not anticipate (R1–R5 above). A **deadlock** is a *liveness* failure — two or more threads/transactions each hold a resource the other needs and neither can proceed. Apparat's specific deadlock-avoidance discipline: locks are always acquired in the same order — **application `ReentrantLock` first, then the database transaction/row lock second, never the reverse** — and the `ReentrantLock` critical section is kept short (no file I/O, no notification sending, no network calls inside it), which bounds how long any other thread can be blocked waiting for it.

---

## 19. MYSQL DATABASE DESIGN (corrected — 13 tables, atomic-slot model, and honest normalisation claims)

### 19.1 Table count — corrected and final

**Apparat's schema is a 13-table normalized relational design.** (The original document's repeated "11-table" figure undercounted; the corrected, consistent figure used everywhere in this document and its companions is **13 core relational tables**, listed in §19.3.)

### 19.2 ER diagram (text, corrected)

```
   users 1───────< bookings >───────1 resources
     │  │              │                 │  │
     │  │              │ 1..*            │  │ 1
     │  │              ˅                 │  ˅
     │  │       booking_slot             │  maintenance_windows
     │  │  (derived occupancy rows;      │
     │  │   UNIQUE resource_id,          │
     │  │           slot_start)          │
     │  │                                 │
     │  └──< user_certifications >── certifications ──< resource_certifications >──┘
     │
     ├──< quota_usage >── (resource category, iso week)
     ├──< waitlist_entries >── resources
     ├──< notifications
     ├──< audit_log
     └──< background_jobs        (durable job/outbox — generalizes the original report_jobs)

   users.supervisor_id ──self-FK──> users.id   (supervisor relationship)
   resources.custodian_id ──FK──> users.id     (technician owns resource)
```

### 19.3 The 13 tables

1. `users` 2. `resources` 3. `bookings` 4. `booking_slot` 5. `maintenance_windows` 6. `certifications` 7. `user_certifications` 8. `resource_certifications` 9. `quota_usage` 10. `waitlist_entries` 11. `notifications` 12. `audit_log` 13. **`background_jobs`** *(replaces the original `report_jobs`; same slot in the schema, generalized scope — see §17.2)*.

`users`, `resources`, `maintenance_windows`, `certifications`, `user_certifications`, `resource_certifications`, `quota_usage`, `waitlist_entries`, `notifications`, `audit_log`, `background_jobs` are unchanged from the original design (see original §19.2 for full column lists) except where noted below.

### 19.4 The Atomic Booking Slot Model (new section — the core correction)

**Atomic slot size:** configurable per deployment, defaulting to **30 minutes**, stored in `app.properties` as `booking.slot.minutes=30`. A resource could in principle override this (e.g. a `LabRoom` at 60-minute granularity), but the MUST-HAVE scope uses one global slot size for every resource — per-resource overrides are a SHOULD-HAVE extension, not required for correctness.

**Valid booking start times:** a booking's `start_at` must fall exactly on a slot boundary relative to the resource's `open_time` (e.g. with a 30-minute slot size and `open_time = 09:00`, valid starts are 09:00, 09:30, 10:00, …).

**Minimum / maximum booking duration:** `min_slot_minutes` and `max_slot_minutes` on `resources` (already present in the original schema) must each be an exact multiple of the atomic slot size; this is enforced by a `CHECK` constraint plus service-level validation.

**Converting a booking into slots:** given a validated, aligned `[start_at, end_at)` interval and a slot size of *S* minutes, the booking occupies the slots `start_at, start_at + S, start_at + 2S, …, end_at − S`. For a 10:00–11:30 booking at 30-minute granularity:

| resource_id | slot_start |
|---|---|
| 7 | 10:00 |
| 7 | 10:30 |
| 7 | 11:00 |

**Buffer/cooldown interaction:** a resource's `buffer_minutes` (warm-up/cooldown, e.g. an `Instrument`'s post-use cooldown) is applied by additionally generating "buffer slots" immediately after `end_at` — occupying but not billable/quota-consuming — so the *next* booking cannot start before the cooldown elapses. These buffer slots are tagged `is_buffer = 1` on the `booking_slot` row (a new boolean column) so reporting can distinguish real usage from buffer time.

**Maintenance-window interaction:** maintenance windows do not generate `booking_slot` rows (they are not bookings); instead, the availability engine treats any slot inside an active maintenance window as unavailable at the *query* level (§19.6), and any attempt to `createBooking` over a slot inside a maintenance window is rejected during resource validation, before overlap/slot generation is attempted.

**Misalignment policy — explicit rejection, not silent rounding.** If a requested interval does not align to the resource's atomic slot boundaries (for example a request for 10:15–11:00 against a 30-minute-slot resource), Apparat **rejects the request** with `SlotAlignmentException`, which carries the configured slot size and the two nearest valid alternatives (10:00–11:00 or 10:30–11:00), rather than silently rounding the request to the nearest valid interval. **Silent rounding is rejected as a policy** because it would let a user believe they booked 10:15–11:00 when the system actually reserved a different interval — a correctness-relevant misrepresentation the user might not notice until a real conflict occurs at 11:00–11:15. Explicit rejection with suggested alternatives keeps the user's mental model and the database's actual state identical at all times. This is one policy, applied consistently everywhere a booking interval is accepted (the JSP form pre-populates only aligned times to make correct input the default in practice, while the server-side check remains authoritative).

### 19.5 `booking_slot` lifecycle (new section — was previously undocumented)

`booking_slot` is a **derived occupancy structure**, not an independent business entity — it exists purely to let the database enforce `UNIQUE(resource_id, slot_start)` over atomic units of time, since MySQL has no native interval-exclusion constraint. Its lifecycle is a deliberate design decision, not an accident of implementation:

| Booking event | `booking_slot` effect |
|---|---|
| `createBooking` succeeds (status becomes `PENDING_APPROVAL` or `CONFIRMED`) | Rows inserted for every atomic slot in `[start_at, end_at)` (plus buffer slots), in the same transaction as the booking insert |
| Approved (`PENDING_APPROVAL → CONFIRMED`) | No change — the rows already exist and already occupy the slots; approval does not re-touch `booking_slot` |
| **Cancelled** (`→ CANCELLED`) | Rows **deleted** in the same transaction as the status change — the slots must become bookable again for other users |
| **Rejected** (`→ REJECTED`) | Rows **deleted** in the same transaction — the request never truly occupied the resource from a scheduling standpoint once rejected |
| **No-show** (`→ NO_SHOW`, by the sweeper) | Rows **deleted** in the same transaction the sweeper uses to flip the status — the remaining, unused portion of the slot must be released; this is exactly what makes waitlist promotion possible (§18.1, `NoShowSweeper`) |
| **Checked in / Completed** (`→ CHECKED_IN → COMPLETED`) | Rows **retained** — a completed booking is always in the past by the time it is `COMPLETED`, so its slot rows can never collide with a new (necessarily future) booking request. Retaining them preserves historical booking integrity for utilization analytics (§19 original report queries) without any conflict-checking cost. |

**The deliberate rule, stated once and applied everywhere:** *only bookings in an occupying status (`PENDING_APPROVAL`, `CONFIRMED`, `CHECKED_IN`) hold live `booking_slot` rows; every transition out of an occupying status either leaves the rows in place because the booking is now safely in the past (`COMPLETED`), or deletes them in the same transaction because the slot must be released for someone else (`CANCELLED`, `REJECTED`, `NO_SHOW`).* This is why MySQL's ordinary (non-partial) `UNIQUE` constraint is sufficient here without needing a filtered/partial index: by the time a booking leaves an occupying status without being complete, its rows are gone from the table, so they cannot conflict with anything.

### 19.6 Normalisation — corrected and qualified claim

The schema's business entities (`users`, `resources`, `bookings`, `certifications`, `maintenance_windows`, `quota_usage`, `waitlist_entries`, `notifications`, `audit_log`, `background_jobs`) are designed to 3NF: no repeating groups, every non-key column depends on the whole key, no transitive dependencies.

`booking_slot`, however, is explicitly **not** evaluated against 3NF as a business entity, because it is not one. The correct framing, stated precisely: *`booking_slot` is a deliberate, integrity-oriented derived occupancy/indexing structure that duplicates `resource_id` from `bookings` specifically to enable a database-level uniqueness constraint over atomic resource-time units — a constraint MySQL cannot express as an interval-exclusion rule directly.* Calling this "denormalized" without qualification invites the (reasonable) follow-up "then why is your schema not in 3NF" — the corrected answer distinguishes **normalized business entities** from a **derived occupancy/indexing structure** that trades a small, well-understood, and lifecycle-managed redundancy for a hard correctness guarantee the relational model could not otherwise give for free.

### 19.7 Important queries (corrected)

*Overlap detection — retained exactly, now understood as one of three layers, not the whole guarantee:*
```sql
SELECT id FROM bookings
 WHERE resource_id = ?
   AND status IN ('PENDING_APPROVAL','CONFIRMED','CHECKED_IN')
   AND start_at < ?      -- new end
   AND end_at   > ?      -- new start
 LIMIT 1;
```

*Atomic-slot claim (the actual final guarantee, inside the transaction described in §20.2):*
```sql
INSERT INTO booking_slot (booking_id, resource_id, slot_start, is_buffer)
VALUES (?, ?, ?, 0), (?, ?, ?, 0), ...;   -- batched; a unique-key violation on any row
                                          -- aborts the whole batch and the surrounding transaction
```

*Atomic quota consumption (corrected — see §21):*
```sql
UPDATE quota_usage
   SET used_minutes = used_minutes + ?
 WHERE user_id = ? AND category = ? AND iso_year = ? AND iso_week = ?
   AND used_minutes + ? <= limit_minutes;
-- caller checks affected row count == 1; if 0, treat as QuotaExceededException
```

*Utilization %, no-show rate, peak-hour heatmap, idle resources, waitlist head* — unchanged from the original document (§19.4 of the original), written against `bookings`, not `booking_slot`, since analytics reason about business events, not the derived occupancy structure.

### 19.8 Indexing (corrected/clarified)

`idx_book_resource_time (resource_id, start_at, end_at)` on `bookings` for the overlap query; `idx_book_status_start` for the sweeper; **`uq_slot (resource_id, slot_start)` UNIQUE on `booking_slot`** — this is a unique index, not merely a performance index, and it is the layer-3 guarantee from §18.3; `idx_slot_booking (booking_id)` on `booking_slot` to make the cancellation/rejection/no-show delete in §19.5 an indexed operation rather than a scan; `idx_jobs_status_available (status, available_at)` on `background_jobs` so the worker poll in §17.2 is indexed.

### 19.9 Engine and isolation (corrected — application lock is not conflated with database concurrency safety)

InnoDB is required for foreign keys, row-level locking, and transactions. Default isolation `REPEATABLE READ` is used; the specific hazard in this project (lost updates / phantom bookings on the same resource) is addressed by pessimistic `SELECT ... FOR UPDATE` on the resource row *inside the transaction*, not by raising the isolation level to `SERIALIZABLE`, which would lock more broadly than necessary and reduce throughput across unrelated resources. **To be explicit about a point the original document blurred: the application-level `ReentrantLock` provides no database-level guarantee by itself — it is InnoDB's row locking, the transaction boundary, and the `booking_slot` unique constraint that are authoritative (§18.3).**

---

## 20. JDBC ARCHITECTURE (corrected — transaction ownership and connection handling made explicit)

### 20.1 Layering (unchanged)
```
Servlet (controller)  → no SQL, no business rules, only HTTP concerns
   ↓  passes a DTO/request object
Service               → business rules, transactions, locking, policy dispatch
   ↓  passes the existing Connection to DAOs when a transaction spans DAOs
DAO                   → SQL only, PreparedStatement, ResultSet→object mapping
   ↓
ConnectionFactory     → DataSource / DriverManager, config from app.properties
   ↓
MySQL (InnoDB)
```

**Rule, stated explicitly (this was previously implicit and is now a checked design rule):** a DAO method invoked with a caller-supplied `Connection` **never** calls `commit()`, `rollback()`, or changes `setAutoCommit`, and never closes that connection — the caller (always a service method) owns the transaction lifecycle end to end. A DAO method that opens its *own* connection (for standalone, single-statement operations outside any larger transaction) is a separate, clearly-named overload — e.g. `BookingDao.insert(Connection con, Booking b)` for transactional use vs `BookingDao.insert(Booking b)` for standalone use, the latter opening and closing its own connection internally.

### 20.2 The corrected `createBooking` transaction sequence

The full, corrected sequence — this replaces the original, less precise version:

1. Acquire the application-level per-resource `ReentrantLock` via `tryLock(2, SECONDS)` (layer 1, §18.3).
2. Open a JDBC connection; `setAutoCommit(false)`.
3. `SELECT ... FOR UPDATE` on the target resource row (layer 2).
4. Validate resource status (`AVAILABLE`, not `DECOMMISSIONED`).
5. Validate operating hours.
6. Validate slot alignment against the resource's atomic slot size (§19.4) — reject with `SlotAlignmentException` if misaligned.
7. Validate against active maintenance windows.
8. Resolve and evaluate the `BookingPolicy` for this resource (polymorphic dispatch).
9. Attempt atomic quota consumption via the conditional `UPDATE` (§19.7/§21); 0 affected rows → `QuotaExceededException`, rollback.
10. Overlap check against `bookings` (defence-in-depth read, §19.7).
11. Insert the `bookings` row.
12. Generate the atomic `booking_slot` rows for the interval, including buffer slots (§19.4).
13. Batch-insert the `booking_slot` rows (layer 3 — the unique constraint is the enforcement point).
14. Write the `background_jobs` outbox row for the resulting notification (§17.2), in the same transaction.
15. Write the `audit_log` row.
16. `commit()`.
17. Release the `ReentrantLock` (`finally`).
18. **Only after a successful commit**, nothing further needs to happen synchronously — the background worker pool discovers the new `background_jobs` row on its own polling cycle. There is no separate "submit to queue" call outside the transaction to forget.

If step 13's batch insert fails with a unique-key violation (MySQL error code 1062), the whole transaction is rolled back at step 16's position (i.e., `rollback()` is called instead of `commit()`), the DAO translates the `SQLException` (`getErrorCode() == 1062`) into `SlotConflictException`, and the service returns that to the controller — no partial booking, no orphaned `booking_slot` rows, and a useful message identifying the next free slot (computed from a follow-up read after rollback).

### 20.3 The corrected `BookingService.createBooking` code

Kept small by extracting the logically separate steps into private helper methods, per the correction requirement — this is the orchestration method, not a single monolithic block:

```java
public BookingResult createBooking(BookingRequest req, SessionUser actor)
        throws SlotConflictException, QuotaExceededException,
               CertificationRequiredException, SlotAlignmentException,
               ResourceUnavailableException, DataAccessException {

    ReentrantLock lock = locks.computeIfAbsent(req.getResourceId(), k -> new ReentrantLock());
    boolean locked = false;
    try {
        locked = lock.tryLock(2, TimeUnit.SECONDS);
        if (!locked) throw new DataAccessException("System busy for this resource, please retry.");

        try (Connection con = ConnectionFactory.get()) {
            con.setAutoCommit(false);
            try {
                Resource resource = resourceDao.findForUpdate(con, req.getResourceId());
                validateResource(resource, req);                       // steps 4–7
                BookingPolicy policy = policyFactory.forResource(resource);
                BookingDecision decision = policy.evaluate(req, ctx(con, actor, resource));

                consumeQuota(con, actor, resource, req);                // step 9, throws on 0 rows
                checkOverlap(con, resource, req);                       // step 10, defence-in-depth

                long bookingId = createBookingRecord(con, req, decision, resource);   // step 11
                List<LocalDateTime> slots = generateAtomicSlots(resource, req);       // step 12
                insertBookingSlots(con, bookingId, resource.getId(), slots);          // step 13

                jobDao.enqueue(con, BackgroundJobRecord.notification(bookingId, decision.status())); // 14
                auditDao.write(con, AuditEntry.of(actor, "BOOKING_CREATE", bookingId));               // 15

                con.commit();                                          // step 16
                return BookingResult.of(bookingId, decision.status());
            } catch (SQLException e) {
                con.rollback();
                throw handleBookingConflict(e, resource(req), req);    // translates 1062 -> SlotConflictException
            } finally {
                con.setAutoCommit(true);
            }
        }
    } catch (SQLException e) {
        throw new DataAccessException("Could not create booking", e);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DataAccessException("Interrupted while acquiring resource lock", e);
    } finally {
        if (locked) lock.unlock();                                     // step 17
    }
}
```

`validateResource`, `consumeQuota`, `checkOverlap`, `createBookingRecord`, `generateAtomicSlots`, `insertBookingSlots`, and `handleBookingConflict` are private methods on `BookingService`, each doing exactly the one thing its name says — this keeps the orchestration method readable while still showing every step required by the corrected design.

### 20.4 Connection pooling (unchanged, still optional-but-recommended)

Start with `ConnectionFactory` wrapping `DriverManager.getConnection(...)`; a Tomcat JNDI `DataSource` configured in `context.xml` is a recommended upgrade, not a prerequisite for a correct first implementation. The project must not become dependent on advanced deployment infrastructure before the basic `DriverManager`-based version works end to end.

### 20.5 `SQLException` handling (corrected — the 1062 translation is now the documented mechanism for layer-3 conflicts)

Caught in the DAO/service boundary, inspected by `getErrorCode()`: `1062` (duplicate key) on the `booking_slot` insert is the **expected, correctly-functioning signal** that layer 3 caught a race that layers 1 and 2 did not prevent — it is translated to `SlotConflictException`, not treated as an unexpected error. Any other `SQLException` is wrapped in `DataAccessException`, with the original cause preserved for logging and never shown to the user.

---

## 21. QUOTA DESIGN (corrected — atomic SQL update replaces read-modify-write)

The original design's risk: reading `used_minutes` in Java, adding the requested duration, and writing the sum back is a classic **lost update** under concurrent bookings by the same user in the same category/week — two concurrent requests can both read the same starting value and both write the same (wrong, too-low) sum, silently letting a user exceed quota.

**Corrected approach — a single atomic conditional `UPDATE`:**
```sql
UPDATE quota_usage
   SET used_minutes = used_minutes + ?
 WHERE user_id = ? AND category = ? AND iso_year = ? AND iso_week = ?
   AND used_minutes + ? <= limit_minutes;
```
The increment and the limit check happen in one indivisible statement, evaluated by MySQL against the current committed row — there is no read-then-decide gap in application code for another transaction to interleave into. The service checks `PreparedStatement.executeUpdate()`'s returned affected-row count: **1** means the quota was successfully consumed; **0** means either the row does not exist yet (first booking of the week — handled by an `INSERT ... ON DUPLICATE KEY UPDATE` variant seeding a zero-used row first) or the limit would have been exceeded, in which case the service throws `QuotaExceededException` and the surrounding transaction rolls back. This is why quota consumption is step 9 in §20.2, before the more expensive overlap/slot work — fail fast on the cheapest check.

---

## 22. SERVLET + JSP ARCHITECTURE

Unchanged from the original document (§21 of the original) — front-controller servlets, JSP+JSTL views under `/WEB-INF/jsp/`, POST-Redirect-GET, the ~14-servlet/~16-JSP inventory, and the MVC layering all stand as designed. No correction was required here; the issues in this pass were entirely in the concurrency/persistence/session layers beneath it.

---

## 23. SESSION MANAGEMENT (corrected — persistence claims narrowed, object design made explicit)

**Login.** Unchanged: validate credentials → **invalidate the old session and create a new one** to prevent session fixation → populate the `SessionUser` (§12.2) → `session.setMaxInactiveInterval(1800)`.

**What goes in the session — corrected and made exhaustive:** the `SessionUser` object only (userId, fullName, role, department), plus a CSRF token and a transient flash message. **What does not, restated with the corrected rationale:** password/password hash; any `Connection`; any DAO or `Service` reference; `Resource` or `Booking` objects or collections; any mutable cache. The reason is now stated precisely rather than generally: session state lives in server memory for the life of the session across every request that user makes, and — **only if and when the deployment enables container-managed session persistence** — may be serialized to disk or replicated by the container; large or DB-stale objects there would be both a memory liability and a correctness liability (stale data surviving past the point the database changed underneath it). Re-querying the database on each request for anything beyond identity is the deliberate, corrected default.

**Corrected persistence claim (see §17.3 for the full reasoning):** `SessionUser` implementing `Serializable` makes the session **compatible with** container-managed persistence/replication *if that is configured* — it is not, by itself, a claim that Tomcat persists sessions across a restart in Apparat's default single-instance academic deployment. If the project demonstrates this explicitly (a SHOULD-HAVE, not required for MUST-HAVE correctness), it does so by showing Tomcat's `PersistentManager` configuration in `context.xml` and verifying the behaviour — never by assertion alone.

**Validation, role checking, timeout, logout, unauthorized access prevention** — unchanged from the original document (§22 of the original): `AuthFilter` with `request.getSession(false)`, a path→role map plus a service-layer `user.canApprove(booking)` object-level check, 30-minute `setMaxInactiveInterval`, `session.invalidate()` plus `Cache-Control: no-store` on logout, JSPs under `WEB-INF` unreachable directly, and per-request ownership checks (IDOR protection) on every servlet.

---

## 24. UI DESIGN, SYSTEM ARCHITECTURE, PACKAGE STRUCTURE, PROJECT DIRECTORY

Unchanged from the original document (§23–§26) with two small, consistent edits carried through:

- Every occurrence of "11 tables" in diagrams and directory comments is corrected to **13 tables** (e.g. `schema.sql (DDL, all 13 tables, indexes, constraints)`; `DATABASE LAYER  MySQL 8 (InnoDB) · 13 tables`).
- The package tree's `scheduler` package gains `JobDao`-facing collaborators consistent with §17–§18: `SchedulerManager`, `Worker` (polls `background_jobs`), `NoShowSweeper`, `ReminderDispatcher`, `QueueCheckpointer`, and the `Job`/`BackgroundJobRecord` pair from §12.1. `report_jobs` references in the directory tree and package list become `background_jobs`/`BackgroundJobDao` throughout.

---

## 25. REQUIREMENT → IMPLEMENTATION MAPPING — full corrected table (supersedes §13 of the original)

| Requirement | Implementation | Class | Why |
|---|---|---|---|
| Multithreading | Three explicit concurrency areas (§18.1): per-resource lock, `ScheduledExecutorService` (3 jobs), `ExecutorService` worker pool draining a durable DB outbox | `SchedulerManager` | Contention and background work are real, not staged |
| Serialization | Bounded local worker-state checkpoint layered over a durable `background_jobs` table; `SessionUser` serialization-compatibility | `scheduler.Job`, `model.dto.SessionUser` | Honest, scoped use — never claimed as the durable record |
| Transactions | Multi-statement atomic operations: booking creation (§20.2–20.3), maintenance cascade, quota consumption (§21), waitlist promotion | `BookingService`, `MaintenanceService` | Multi-table atomicity is mandatory for correctness, not decorative |
| MySQL schema | **13-table** schema; `booking_slot` explicitly documented as a derived occupancy structure with a defined lifecycle (§19.5) | `schema.sql` | Consistent, defensible table count and design rationale everywhere |
| JDBC | Service-owned transactions; DAOs never commit/rollback a caller-supplied connection (§20.1) | `dao.*` | Clear, checkable ownership rule |

All remaining rows are unchanged from the original mapping table.

---

## 26. THE CENTRAL DEMO STORY (new/corrected section — makes the exact viva sentence and demo sequence explicit)

1. Show resource `SEM-01` (a scanning electron microscope, `Instrument`).
2. Show the availability grid: `14:00–15:00` is free.
3. Open two browser sessions; log in as User A and User B.
4. Submit an identical `14:00–15:00` booking from both, as close to simultaneously as practical.
5. Point out, narrating live: **"Both requests are competing for the per-resource `ReentrantLock` first — that's layer one, reducing wasted work inside this one server."**
6. **"The winner proceeds into a transaction that takes `SELECT ... FOR UPDATE` on the resource row — that's layer two, and it is what actually protects correctness if this were running on more than one server."**
7. **"The winner inserts booking_slot rows for 14:00, 14:30. The loser's insert hits the UNIQUE(resource_id, slot_start) constraint and fails — that's layer three, the final backstop."**
8. Show exactly one success (`CONFIRMED` or `PENDING_APPROVAL`) and one clean `SlotConflictException` message naming the next free slot.
9. Query `booking_slot` live in the MySQL client: `SELECT * FROM booking_slot WHERE resource_id = ? AND slot_start IN ('14:00','14:30')` — show exactly one `booking_id` owns both rows.
10. Run `ConcurrentBookingTest` (§CONCURRENCY_TEST_PLAN.md) in front of the examiner and report its actual output — see §28 on not fabricating results.

**The sentence to say out loud, verbatim, at step 6–7:** *"Application locking reduces in-process contention, database locking coordinates transactional access, and the unique atomic-slot constraint is the final integrity backstop."*

---

## 27. REVIEW 1 / REVIEW 3 PLANS

Unchanged from the original document (§27, §29) except that every reference to table count reads **13 tables**, and the Review 1 sequence diagram to bring is retitled to reflect the corrected flow: *"Create booking — lock, transaction, FOR UPDATE, atomic slots, unique-constraint backstop."*

---

## 28. REVIEW 2 PLAN (corrected — multithreading demo requirement made explicit and non-negotiable)

Review 2 must demonstrate, with the concept→file map from the original document's §28 carried forward and the following made explicit:

| Concept | What must be shown (not merely stated) |
|---|---|
| OOP / inheritance / polymorphism / interfaces / collections / custom exceptions / file handling | As originally scoped — live, from running code |
| Serialization | Show `checkpoint.ser` on disk, and explicitly demonstrate — by deleting it and restarting the worker — that the system is still correct because `background_jobs` is the authoritative record (§17.3) |
| **Multithreading — corrected minimum bar** | **Not** "we created a thread." Must show: (1) the concurrent booking race live or via `ConcurrentBookingTest`; (2) the resource lock; (3) the transaction and `FOR UPDATE`; (4) the database constraint catching a race; (5) at least one scheduled background job firing live (grace period turned down for the demo); (6) a clean, observable graceful shutdown (no leaked-thread warning in the Tomcat log on redeploy) |
| JDBC / MySQL / transaction handling | `PreparedStatement`, the transaction boundary in the service layer, a rollback demonstrated by forcing a failure mid-transaction and showing nothing was written |
| Servlet / JSP / session handling | Filter chain, zero SQL in servlets, zero scriptlets in JSPs, login/timeout/logout |

Working functionality expected by Review 2: all MUST-HAVE features from §36 (corrected), including the corrected atomic-slot booking path and the outbox-based background job model.

---

## 29. ROADMAP, INDIVIDUAL CONTRIBUTION

Unchanged in structure from the original document (§30–§31). Module B ("Booking Engine, Concurrency & Transactions") now explicitly includes ownership of: the atomic-slot generation logic (§19.4), the `booking_slot` lifecycle management (§19.5), the corrected `createBooking` orchestration (§20.3), and the quota atomic-update (§21). Module C ("Background Processing, Serialization & Notifications") now explicitly includes ownership of: the `background_jobs` outbox table and DAO, the worker poll/claim/complete cycle (§17.2), the stalled-job reclaimer, and the bounded serialization checkpoint (§17.3) — with the explicit expectation that this module's owner can answer "what happens if the notification worker crashes after commit" (§20.2/§17.2) fluently, since that is now a first-class, correctly-designed part of the architecture rather than an acknowledged gap.

---

## 30. IMPLEMENTATION COMPLEXITY (corrected — the corrections above do not raise the MUST-HAVE bar)

The corrections in this document are corrections to **how correctly the same feature set is built**, not additions to the feature set. Concretely, they do **not** introduce:

- External APIs, cloud infrastructure, Redis, Kafka, a message broker, microservices, a distributed lock service, or a complex authentication framework.
- Any AI/ML component.
- A second table beyond what was already planned — `background_jobs` **replaces** `report_jobs` in the same slot in the schema; `booking_slot` was already in the original design; the total table count changes only because the original document had miscounted, not because anything new was added.

The stack remains exactly: **Java, Servlet, JSP, MySQL, JDBC, HTML/CSS/JS**, with a clean layered architecture and the concurrency/persistence mechanics now specified precisely enough to implement without ambiguity. See §36 for the corrected MUST-HAVE/SHOULD-HAVE/NICE-TO-HAVE split.

---

## 31. GITHUB STRATEGY (corrected README section — see also CONCURRENCY_TEST_PLAN.md)

The README's centrepiece section, **"How Apparat prevents double booking,"** is corrected to show the actual three-layer flow and to state test results only once they are measured:

```
Browser
  → Servlet (BookingServlet)
  → BookingService.createBooking()
      → ReentrantLock (per resource)          [layer 1: in-process contention]
      → JDBC transaction begins
      → SELECT ... FOR UPDATE (resource row)  [layer 2: cross-process coordination]
      → overlap check (defence-in-depth read)
      → atomic slot generation
      → INSERT INTO booking_slot (...)         [layer 3: UNIQUE(resource_id, slot_start)]
      → COMMIT (or ROLLBACK + SlotConflictException on a 1062 duplicate-key error)
```

**ConcurrentBookingTest result:** the README must state the actual, measured output of the test once it has been run against a real build — for example: *"Expected result: exactly 1 successful booking and 49 rejected with `SlotConflictException`, out of 50 concurrent attempts against the same resource and interval. Actual measured result: [fill in after running `mvn test -Dtest=ConcurrentBookingTest`]."* **Do not pre-write a specific pass/fail count as if it were already observed** — see §28 (Do Not Invent Implementation Results) and CONCURRENCY_TEST_PLAN.md.

Every other element of the original GitHub strategy (repository name, description, screenshot list, commit-message style, demo credentials handling) is unchanged — see the original document §32.

---

## 32. VIVA QUESTIONS — corrected and extended set

*(The original 43 questions in §34 of the original document stand — see that document for Q1–Q43. The 15 questions below are new, added specifically to test the corrections in this pass, and are the ones most likely to actually be asked once an examiner reads a repo that documents its own concurrency model this precisely.)*

**Q44. Why isn't the `ReentrantLock` alone enough to prevent double booking?**
*Ideal:* It is JVM-local. It only coordinates threads inside one running instance of the application. If Apparat ever ran as two Tomcat instances, each would have its own, uncoordinated lock map, and two requests landing on different instances could both proceed as if they held the lock. The database is the one thing every instance shares, which is why the `SELECT ... FOR UPDATE` and the unique constraint, not the lock, are the layers that remain correct under horizontal scaling.
*Why asked:* to check whether the student understands the *scope* of an in-memory lock, not just that "locks prevent races."
*Mistake:* saying "the lock prevents the race" without qualifying which race and at what scope.

**Q45. Why do you additionally need a database uniqueness constraint if you already take `SELECT ... FOR UPDATE`?**
*Ideal:* `FOR UPDATE` protects the sequence of operations *within* a correctly-written transaction. It gives no protection against a bug elsewhere in the codebase that writes to `booking_slot` outside that discipline, a future code path that forgets to take the lock, or an operator running a manual `INSERT`. The unique constraint is enforced by the database engine itself regardless of which code path produced the write — it is the layer that is correct even if every application-level safeguard fails.

**Q46. Why does `booking_slot` exist instead of just relying on the overlap query on `bookings`?**
*Ideal:* The overlap query is a read-then-decide check — by itself it has a race window between the read and the later write. A `UNIQUE` constraint needs an actual column combination to be unique; MySQL cannot express "no overlapping interval" as a constraint directly. `booking_slot` materializes each booking into fixed atomic units so `UNIQUE(resource_id, slot_start)` becomes an ordinary, enforceable key.

**Q47. What happens if two application server instances run Apparat simultaneously?**
*Ideal:* The `ReentrantLock` layer stops providing any real coordination between them, as in Q44. Correctness still holds, because `SELECT ... FOR UPDATE` and the `booking_slot` unique constraint operate at the shared database and are unaffected by how many application instances exist. This is explicitly documented as a known limitation of the lock layer, not of the overall system.

**Q48. What happens if the database commit for a booking succeeds, but the process crashes before the notification is sent?**
*Ideal:* It cannot happen the way the question implies, because the notification's *intent* — the `background_jobs` row — is written in the **same transaction** as the booking. If the transaction commits, both the booking and the pending job exist; if it doesn't commit, neither does. A separate background worker polls `background_jobs` independently and will pick up the pending row whenever it next runs, even after a full process restart, because the row is durable in MySQL, not sitting only in application memory.

**Q49. Why is Java serialization not treated as a production-grade job queue here?**
*Ideal:* Native Java serialization has no concept of "a durable record another process can poll," no visibility into job status without deserializing the whole file, no retry/backoff semantics, and the object format is brittle across class changes (`serialVersionUID`). Apparat uses it only for a local, disposable in-memory checkpoint that speeds up a worker's own restart — the actual job record lives in the `background_jobs` table, which is what a production system would rely on (or a real message broker, in a larger deployment).

**Q50. Does making `HttpSession` attributes `Serializable` mean Tomcat persists them across a restart?**
*Ideal:* No, not by itself. Whether the container persists or replicates session state is a container/configuration decision (e.g. Tomcat's `PersistentManager`). `Serializable` is a necessary precondition for that to work if configured — Apparat keeps `SessionUser` small and serializable specifically so it is *compatible* with that configuration, without claiming the default deployment enables it.

**Q51. Why does the `Service` layer own the transaction rather than the `DAO`?**
*Ideal:* A single business operation (creating a booking) touches several tables via several DAOs (`bookings`, `booking_slot`, `quota_usage`, `background_jobs`, `audit_log`). If each DAO opened and committed its own transaction, a failure partway through would leave some tables updated and others not — an inconsistent state. The service is the natural owner because it is the only layer that knows the full scope of "one business operation."

**Q52. Why `SELECT ... FOR UPDATE` specifically, rather than just relying on the transaction's isolation level?**
*Ideal:* The default `REPEATABLE READ` isolation prevents a transaction from seeing another's uncommitted changes, but it does not by itself force two concurrent transactions reading the same resource row to serialize against each other for a check-then-act sequence. `FOR UPDATE` takes an explicit row-level exclusive lock, so a second transaction attempting the same `SELECT ... FOR UPDATE` on that row blocks until the first commits or rolls back — turning the check-then-act sequence into an atomic unit with respect to that row.

**Q53. Why is the unique constraint defined on atomic slots rather than directly on the booking's start/end interval?**
*Ideal:* MySQL's `UNIQUE` constraint enforces exact-value uniqueness on a column combination; it cannot express "no other row's interval overlaps mine" directly, because overlap is a range comparison, not an equality comparison. Decomposing every booking into fixed-size atomic slots converts the problem into ordinary equality: two bookings conflict exactly when they would both try to claim the same `(resource_id, slot_start)` pair, which the constraint can enforce natively.

**Q54. What happens if the `booking_slot` batch insert fails partway through — say, the third of four rows hits the unique-constraint violation?**
*Ideal:* The entire transaction is rolled back, including the earlier successfully-inserted `bookings` row and the first two `booking_slot` rows — nothing partial is left committed. The `SQLException`'s error code (1062) is inspected and translated into a `SlotConflictException` for the caller, along with a freshly-computed next-free-slot suggestion.

**Q55. Why is the quota update written as `used_minutes = used_minutes + ? ... AND used_minutes + ? <= limit_minutes` rather than reading the value in Java first?**
*Ideal:* A read-then-write in Java has a gap between the read and the write during which another transaction can commit its own update, causing a lost update — two concurrent bookings could both read "50 minutes used, 600 limit," both decide there's room, and both commit, silently exceeding the quota. The single `UPDATE ... WHERE ...` statement performs the check and the increment as one atomic operation evaluated against the current committed value, with no gap for another transaction to interleave into.

**Q56. What is the difference between a race condition and a deadlock, and does Apparat have either?**
*Ideal:* A race condition is a wrong *result* from unlucky timing (two threads interleaving in an unanticipated order); a deadlock is a *liveness* failure where two or more threads/transactions each hold something the other needs and neither can proceed. Apparat's design deliberately targets and closes five specific race conditions (R1–R5, catalogued in the design). Deadlock is avoided by always acquiring the application lock before the database lock, never the reverse, and by keeping the lock's critical section free of slow I/O.

**Q57. Exactly what happens during Tomcat shutdown in this project?**
*Ideal:* `contextDestroyed` stops the scheduled jobs from firing again, stops the worker pool from accepting new work, calls `shutdown()` on both executors, waits up to 20 seconds via `awaitTermination`, calls `shutdownNow()` if anything remains, writes a best-effort final checkpoint of the in-memory worker queue, and releases the connection pool. Nothing about correctness depends on this checkpoint succeeding, because `background_jobs` already holds the durable record.

**Q58. If adjacent bookings (10:00–11:00 and 11:00–12:00 on the same resource) are both allowed, doesn't your overlap condition contradict that?**
*Ideal:* No — the interval-overlap condition `A.start < B.end AND A.end > B.start` is a strict inequality on both sides by design, so two intervals that only touch at a shared boundary (one ending exactly when the other starts) do not satisfy it and are correctly treated as non-overlapping. This is tested explicitly (CONCURRENCY_TEST_PLAN.md, boundary test).

The **eleven questions most likely to determine whether a student actually understands this project**, updated for this pass: Q4, Q5, Q12, Q16, Q21/Q24, **Q44, Q45, Q48, Q49, Q52, Q55, Q56** (original Q26, Q29, Q32, Q34, Q36, Q43 remain strong and are now reinforced rather than replaced by the new set).

---

## 33. IMPLEMENTATION RISKS (corrected — two risks added, none removed)

*(Original risks 1–6 in §35 of the original document are unchanged. Two are added, reflecting the areas this pass hardened.)*

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| 7 | **Atomic-slot generation logic has an off-by-one or boundary bug** (e.g. an inclusive/exclusive mistake at the interval end, or a buffer slot double-counted) | High — this is now the single most load-bearing piece of new logic in the corrected design | Unit-test `generateAtomicSlots` in isolation before wiring it into `createBooking`; test exact boundary cases explicitly (a booking ending exactly on a slot boundary, a booking with zero buffer, a booking with the maximum allowed duration) — see CONCURRENCY_TEST_PLAN.md |
| 8 | **The background worker's poll-and-claim query is written as a plain `SELECT` followed by a separate `UPDATE`**, recreating exactly the lost-update problem this pass corrected elsewhere | Medium — would silently let two worker threads both process the same job | The claim must be a single conditional `UPDATE ... WHERE id = ? AND status = 'PENDING'` (§17.2), never a `SELECT` followed by a separate `UPDATE`; check affected rows before proceeding to execute the job |

---

## 34. MVP vs ADVANCED FEATURES (corrected)

**MUST HAVE** — unchanged in scope from the original document's §36, with the following corrected specifics folded in as part of what "done" means for items already on the list: booking creation uses the atomic-slot model (§19.4) and the corrected transaction sequence (§20.2–20.3); quota enforcement uses the atomic conditional update (§21); background notification delivery uses the `background_jobs` outbox (§17.2), not a bare in-memory queue; the concurrency test asserts the corrected boundary and conflict cases (CONCURRENCY_TEST_PLAN.md). This is **the same feature list**, built correctly — see §30.

**SHOULD HAVE / NICE TO HAVE** — unchanged from the original document, with one explicit move: **database backup dump moves from an implied MUST-HAVE-adjacent item to an explicit NICE TO HAVE**, and only as an admin-triggered on-demand `mysqldump` invocation, never claimed as an unattended, verified nightly job unless actually built and tested as such (§16).

---

## 35. FINAL RECOMMENDATION

**BUILD THIS PROJECT.** Apparat remains the selected project. The corrections in this document do not change the product idea, the feature set, the OOP architecture, or the JSP/Servlet/JDBC/MySQL stack — they make the concurrency, persistence, session, and database claims **technically defensible and implementation-ready**: a corrected, consistent 13-table schema; an atomic-slot booking model with an explicit misalignment policy; a three-layer concurrency defence with its JVM-local limitation stated up front; a durable database outbox underneath an honestly-scoped serialization checkpoint; an atomic quota update; a formal race-condition catalogue with named SQL-level mitigations; and a `booking_slot` lifecycle that is documented rather than assumed.

See CORRECTIONS_LOG.md for the full changelog, ARCHITECTURE_DECISIONS.md for why each final decision was made over its alternatives, and CONCURRENCY_TEST_PLAN.md for exactly how the central concurrency claim is proven rather than asserted.
