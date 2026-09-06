# Corrections Log — Apparat Blueprint Hardening Pass

Engineering changelog for the review that produced [PROJECT_BLUEPRINT_CORRECTED.md](PROJECT_BLUEPRINT_CORRECTED.md). Each entry: issue → original problem → correction → reason → sections affected. Nothing here changes the selected project, the feature set, or the technology stack — see the final statement at the end of this log.

---

### C1 — Table count was wrong throughout the document

**Original problem:** the blueprint repeatedly stated "11-table schema," but the schema as actually designed (§19 of the original) listed 13 distinct tables: `users`, `resources`, `bookings`, `booking_slot`, `maintenance_windows`, `certifications`, `user_certifications`, `resource_certifications`, `quota_usage`, `waitlist_entries`, `notifications`, `audit_log`, `report_jobs`.

**Correction:** every occurrence of "11-table"/"11 tables" is replaced with "**13-table**"/"**13 tables**" (or "13 core relational tables"), using one consistent phrase throughout. The schema itself was not reduced — the correction is arithmetic, not a design change.

**Reason:** a table-count mismatch between the prose and the actual DDL is exactly the kind of inconsistency an examiner catches in thirty seconds and that undermines trust in every other claim in the document.

**Sections affected:** GitHub evaluation table, requirement-mapping table, system architecture diagram, package/directory tree comments, Review 1 plan, project report structure §12, MUST-HAVE feature description, feasibility statement, final-recommendation professor-review paragraph — all corrected in `PROJECT_BLUEPRINT_CORRECTED.md` §3–§37 (renumbered as the corrected §3–§35).

---

### C2 — The double-booking guarantee needed a rigorous, explicit slot model

**Original problem:** the design relied on interval-overlap detection, a per-resource lock, `SELECT ... FOR UPDATE`, and a `booking_slot` uniqueness table, but never defined how a booking interval becomes concrete slot rows, what slot size is used, how misaligned requests are handled, or how buffer/cooldown time interacts with slots. The mechanism was gestured at, not specified.

**Correction:** a new **Atomic Booking Slot Model** section (§19.4) defines a configurable atomic slot size (default 30 minutes), the alignment rule for valid start times, min/max duration as multiples of the slot size, the exact conversion of an interval into slot rows, buffer-slot generation, maintenance-window interaction, and an explicit **rejection-over-rounding** misalignment policy with the reasoning for choosing rejection.

**Reason:** without a concrete conversion rule, "the database enforces uniqueness on atomic slots" was an aspiration, not an implementable mechanism — and silent rounding (the alternative to rejection) would let the system's actual reserved interval diverge from what the user believes they booked, a correctness-relevant misrepresentation.

**Sections affected:** §19.4 (new), §20.2 (transaction sequence now includes slot-alignment validation and slot generation as explicit steps), §20.3 (`generateAtomicSlots` extracted as a named method), Exception Strategy (new `SlotAlignmentException`).

---

### C3 — Booking creation didn't actually generate or insert `booking_slot` rows

**Original problem:** the original `createBooking` example inserted a `bookings` row and updated quota, but never inserted into `booking_slot` — so the unique constraint the design relied on for its "final backstop" had nothing to check against in the worked example.

**Correction:** the corrected transaction sequence (§20.2) and the corrected `createBooking` code (§20.3) explicitly generate atomic slots and batch-insert them into `booking_slot` inside the same transaction, with a duplicate-key (`1062`) failure translated into `SlotConflictException` and a full rollback — no partial booking, no orphaned slot rows.

**Reason:** this was the single largest gap between the claimed guarantee and the actual code shown; without it, the "three-layer defence" was really only two layers with the third asserted but not built.

**Sections affected:** §20.2, §20.3, §20.5 (SQLException translation now documents the 1062 case explicitly), Concurrency test plan (verifies no orphaned `booking_slot` rows after a failed transaction).

---

### C4 — The three concurrency layers were not clearly distinguished, and the lock's scope was overstated

**Original problem:** the `ReentrantLock`, the database transaction/`FOR UPDATE`, and the unique constraint were described together as "the fix" without stating which layer actually provides the correctness guarantee versus which one is an optimisation, or that the `ReentrantLock` is JVM-local and would not coordinate multiple application server instances.

**Correction:** §18.3 now names each layer, its exact scope, and what it actually buys, in a table, plus an explicit statement of the `ReentrantLock`'s JVM-local limitation and why the database layers remain authoritative regardless of deployment topology. The single sentence *"Application locking reduces in-process contention, database locking coordinates transactional access, and the unique atomic-slot constraint is the final integrity backstop"* is introduced as the canonical answer, repeated at the point it's needed (demo script, viva questions, README).

**Reason:** a viva examiner's most likely challenge to this design is "what if you ran two servers" — the original document had no answer prepared; the corrected document turns that into one of its strongest points instead of an exposed weakness.

**Sections affected:** §18.3 (new), §26 (demo script narrates the layers explicitly), viva Q44/Q45/Q47, ARCHITECTURE_DECISIONS.md.

---

### C5 — Serialization claims about `HttpSession` persistence were overstated

**Original problem:** the original document stated that making session attributes `Serializable` "persists sessions across a Tomcat restart," presenting a JDK precondition as if it were a guaranteed, always-on container behaviour.

**Correction:** §17.3 and §23 now state the accurate, narrower claim: `Serializable` is a *precondition* for container-managed session persistence/replication, not a guarantee that it happens by default. Apparat's default single-instance deployment does not configure or claim this behaviour; if demonstrated, it must be shown via an actual `PersistentManager` configuration and verified, not asserted.

**Reason:** the original claim would not survive a single follow-up question ("show me where Tomcat is configured to do that") — the corrected claim is exactly as strong as what the project can actually demonstrate.

**Sections affected:** §17.3, §23 (Session Management), viva Q50.

---

### C6 — `SessionUser` design was underspecified

**Original problem:** the original document said sessions should hold "identity/context, not application state" but did not give a concrete class design or state the immutability/size discipline explicitly.

**Correction:** §12.2 gives the exact `SessionUser` field list (userId, fullName, role, department), makes it immutable with no setters, requires an explicit `serialVersionUID`, and restates precisely what must never be placed in session (password/hash, `Connection`, DAO, `Service`, `Resource`/`Booking` objects or collections, mutable caches) with the corrected rationale tied to §17.3's persistence claim.

**Reason:** turns a general principle into a checkable, implementable class.

**Sections affected:** §12.2, §23.

---

### C7 — Post-commit background job submission had an unaddressed crash window

**Original problem:** the original design submitted a `NotificationJob` to an in-memory queue *after* the booking transaction committed. A crash between commit and submission silently loses the job — a real user-facing failure (a waitlisted user never promoted, an approval never notified) that periodic serialization of the queue cannot retroactively recover, since the job was never enqueued in the first place.

**Correction:** the reliability model is redesigned around a **database outbox pattern** (§17.2): the job's *intent* is written as a row in a new durable table, `background_jobs`, inside the **same transaction** as the domain change (the booking). A separate background worker polls this table and claims rows with a conditional `UPDATE`, never a plain `SELECT` followed by a separate `UPDATE` (which would reintroduce the same class of race — flagged explicitly as Risk #8). Stalled `RUNNING` rows are reclaimed by a periodic sweep.

**Reason:** this is the correction with the most real engineering weight in the whole pass — it closes an actual data-loss window, and it does so with a well-known, teachable pattern (transactional outbox) rather than by adding infrastructure.

**Sections affected:** §17.1, §17.2 (new), §12.1 (`BackgroundJobRecord` model), §19.3 (schema — see C8), §20.2/§20.3 (job row written inside `createBooking`'s transaction), §33 (Risk #8).

---

### C8 — `report_jobs` generalized into `background_jobs` instead of adding a new table

**Original problem:** the outbox correction (C7) needed a durable job table, and the original schema only had a narrow `report_jobs` table scoped to report generation.

**Correction:** `report_jobs` is **generalized in place** into `background_jobs` (columns: id, job_type, payload, status, attempt_count, available_at, locked_at, completed_at, error_message, created_at), used for notifications, report generation, and CSV import alike. This replaces one table with one table — the total schema count does not change because of this correction (it changes only because of C1's arithmetic fix).

**Reason:** the instructions explicitly asked to consider this generalization and to avoid unnecessarily inflating the schema; reusing the existing table's slot satisfies the outbox requirement with no net addition.

**Sections affected:** §17.2, §19.3, package structure (`BackgroundJobDao` replaces `ReportJobDao`), directory tree.

---

### C9 — Serialization's role needed to be redefined relative to the new durable outbox

**Original problem:** once `background_jobs` becomes the authoritative durable record (C7/C8), the original claim that the serialized queue itself was "crash-safe" needed to be corrected to avoid implying two competing sources of truth.

**Correction:** §17.3 explicitly establishes a hierarchy — `background_jobs` (authoritative, durable) versus the serialized checkpoint (local, disposable, bounded, single-JVM, purely a restart-convenience/course-demonstration mechanism). The canonical test sentence is introduced: *"If I deleted `checkpoint.ser` entirely, the system would still be correct... If I deleted `background_jobs`, the system would lose real work."*

**Reason:** this reframes serialization from "the reliability mechanism" (indefensible once challenged) to "an honest, bounded, still-required course demonstration layered on top of the real mechanism" (fully defensible), which is exactly the intent of the correction requirements.

**Sections affected:** §17.3, §17.4, viva Q49.

---

### C10 — File handling included an implied nightly backup that was never actually built or verified

**Original problem:** the feature list mentioned "nightly DB backup dump" among file-handling use cases without distinguishing it from features that are actually implemented and demoable.

**Correction:** moved explicitly to NICE TO HAVE, and rescoped to an **admin-triggered on-demand** `mysqldump` invocation if attempted at all — never claimed as an unattended, verified nightly job unless it is actually built and tested as such.

**Reason:** an unattended scheduled job that "generates data an examiner could ask to see the last output of" is a specific, checkable claim; if it isn't really running, it must not be described as if it were.

**Sections affected:** §16, §34 (MVP vs Advanced).

---

### C11 — Multithreading responsibilities were described together without explicit per-area ownership or a formal race-condition catalogue

**Original problem:** the three concurrency areas (lock, scheduled jobs, worker pool) were described, but shutdown behaviour, exact responsibility split, and the specific races each mechanism closes were not laid out as a checkable list — "threads are safe" was implied rather than demonstrated per-race.

**Correction:** §18.1 gives each area's exact responsibility; §18.2 makes the `contextInitialized`/`contextDestroyed` lifecycle explicit including the stalled-job reclaimer; §18.4 is a new formal race-condition catalogue (R1–R5) each with a named SQL-level mitigation and idempotency mechanism, not a general assurance.

**Reason:** "why is this thread-safe" must always have a specific, named answer in this project — a general claim is not defensible under a follow-up question naming a specific interleaving.

**Sections affected:** §18.1–§18.5 (new/expanded), viva Q56.

---

### C12 — Quota update was a read-modify-write, vulnerable to lost updates

**Original problem:** the original quota logic implied reading `used_minutes`, adding in Java, and writing back — a textbook lost-update race under concurrent bookings by the same user.

**Correction:** §21 replaces this with a single atomic conditional `UPDATE ... SET used_minutes = used_minutes + ? WHERE ... AND used_minutes + ? <= limit_minutes`, checked via affected-row count, moved to run early in the transaction (step 9, before the more expensive overlap/slot work) as a fail-fast optimisation.

**Reason:** this is the same class of bug the whole booking-conflict design exists to prevent, just on a different table — leaving it unfixed would have been a glaring inconsistency for any examiner who understood the rest of the design.

**Sections affected:** §19.7 (query), §20.2 (step 9), §21 (new dedicated section), viva Q55.

---

### C13 — `booking_slot` lifecycle (creation, retention, deletion) was undocumented

**Original problem:** the original design never stated what happens to `booking_slot` rows on cancellation, rejection, no-show, or completion — leaving open the serious question of whether a cancelled slot would remain permanently unbookable (since a naive "never delete" policy would starve future bookings against a `UNIQUE` constraint that can't distinguish active from historical occupancy).

**Correction:** §19.5 states the deliberate lifecycle: rows are inserted when a booking enters an occupying status; retained on `COMPLETED` (safe, because completed bookings are always in the past and cannot collide with a necessarily-future new request); **deleted in the same transaction** as the status change for `CANCELLED`, `REJECTED`, and `NO_SHOW` (because those slots must become bookable again). This also resolves why an ordinary, non-partial `UNIQUE` index is sufficient without needing MySQL features it doesn't have (partial/filtered unique indexes).

**Reason:** without this, the schema had a latent correctness bug serious enough to make the whole feature (cancel a booking, expect the slot to free up) not actually work — this was the single most important schema-level gap to close.

**Sections affected:** §19.5 (new), §19.8 (index for the lifecycle delete), viva Q46.

---

### C14 — The 3NF claim didn't account for `booking_slot`'s deliberate redundancy

**Original problem:** the schema was called 3NF while `booking_slot` intentionally duplicates `resource_id` from `bookings`, without qualifying that this is a distinct kind of structure rather than a violation to explain away.

**Correction:** §19.6 distinguishes **normalized business entities** (evaluated against 3NF as originally) from `booking_slot` as a **deliberate, integrity-oriented derived occupancy/indexing structure**, explaining precisely why the redundancy exists (enabling a native `UNIQUE` constraint over what is really an interval-exclusion requirement MySQL can't express directly) rather than either ignoring the redundancy or overclaiming/underclaiming normalization.

**Reason:** "why does your 3NF schema have a duplicate column" is a real trap question; the corrected framing turns it into a demonstration of understanding the limits of the relational model rather than an inconsistency to be caught out on.

**Sections affected:** §19.6, viva Q46/Q53.

---

### C15 — Isolation/locking explanation conflated the application lock with database-level concurrency safety

**Original problem:** the original MySQL section recommended `SELECT ... FOR UPDATE` correctly but did not explicitly separate what the `ReentrantLock` contributes from what InnoDB's row locking contributes, leaving room for the (incorrect) inference that the application lock itself provided cross-process safety.

**Correction:** §19.9 states explicitly that the `ReentrantLock` provides no database-level guarantee by itself, and that InnoDB row locking, the transaction boundary, and the `booking_slot` constraint are what's authoritative — consistent with, and cross-referenced to, §18.3.

**Sections affected:** §19.9.

---

### C16 — JDBC transaction-ownership rule was implicit rather than a stated, checkable design rule

**Original problem:** the original document showed the service owning transactions in its one example but never stated as a rule that DAOs must never commit/rollback/close a caller-supplied connection.

**Correction:** §20.1 states this as an explicit rule, and distinguishes the two DAO method shapes (transactional overload taking a `Connection`, vs. standalone overload managing its own).

**Sections affected:** §20.1.

---

### C17 — `createBooking` needed to be decomposed rather than left as one large method

**Original problem:** with all of C2/C3/C7/C12's additions folded in, a single monolithic `createBooking` method would become unreadable and undefendable as "clean class design."

**Correction:** §20.3 extracts `validateResource`, `consumeQuota`, `checkOverlap`, `createBookingRecord`, `generateAtomicSlots`, `insertBookingSlots`, and `handleBookingConflict` as named private methods, keeping the orchestration method as a readable sequence of calls corresponding 1:1 to the 18-step transaction sequence in §20.2.

**Sections affected:** §20.2, §20.3.

---

### C18 — Concurrency test needed to be strengthened and given explicit boundary cases

**Original problem:** the original `ConcurrentBookingTest` description was a single scenario (50 threads, one conflict) without database-state verification or boundary-condition coverage (adjacent, non-overlapping intervals; partially-overlapping intervals).

**Correction:** see [CONCURRENCY_TEST_PLAN.md](CONCURRENCY_TEST_PLAN.md) — a dedicated test plan with the 50-thread conflict test (now also asserting exactly one active booking and no orphaned `booking_slot` rows), an adjacent-interval test (10:00–11:00 vs 11:00–12:00, both must succeed), and a partial-overlap test (10:00–11:00 vs 10:30–11:30, exactly one must succeed).

**Sections affected:** §CONCURRENCY_TEST_PLAN.md (new file), §26 (demo script), viva Q58.

---

### C19 — Results must not be pre-written before the test is actually run

**Original problem:** none in the original document specifically, but the correction requirements flagged this as a discipline to enforce going forward, since the corrected document now describes test behaviour in enough detail that it would be easy to accidentally state a specific pass/fail count as already observed.

**Correction:** §28 (Do Not Invent Implementation Results) and the GitHub README section (§31) are written using **"expected result"** language with a placeholder for the actual measured output, explicitly instructing that the real number replaces the placeholder only after the test is implemented and run.

**Sections affected:** §31, CONCURRENCY_TEST_PLAN.md.

---

## Final summary

**Corrected architecture:**
Application lock (in-process contention, JVM-local)
**+** database pessimistic locking (`SELECT ... FOR UPDATE`, cross-process authoritative)
**+** atomic booking slots (30-minute default, explicit alignment policy, rejection over rounding)
**+** database uniqueness (`UNIQUE(resource_id, slot_start)`, the final backstop)
**+** transactional atomic quota update (single conditional `UPDATE`, no read-modify-write)
**+** durable background-job/outbox table (`background_jobs`, written in the same transaction as the domain change)
**+** serialized local worker checkpoint (bounded, disposable, honestly scoped beneath the outbox — still fulfils the serialization requirement)
**+** Servlet/JSP MVC (unchanged)
**+** JDBC DAO/service layering with an explicit transaction-ownership rule (unchanged in shape, now stated as a rule)

**Apparat remains the selected project. These corrections do not change the product idea; they make its concurrency, persistence, session, and database claims technically defensible and implementation-ready.**
