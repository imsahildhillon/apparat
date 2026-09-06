# Architecture Decisions — Apparat

Short-form ADR set for the final, corrected architecture. Each entry: decision, alternatives considered, why this one won, and the honest limitation to state unprompted in a viva. See [PROJECT_BLUEPRINT_CORRECTED.md](PROJECT_BLUEPRINT_CORRECTED.md) for full detail and [CORRECTIONS_LOG.md](CORRECTIONS_LOG.md) for what changed and why.

---

### ADR-1 — Why Apparat (the domain)

**Decision:** build a shared-instrument booking/approval/utilization platform for university core labs, rather than a project from the overused management-system list.

**Alternatives considered:** shift-roster scheduling, document approval workflow, IT helpdesk ticketing (see the original blueprint's Pass 2–3 for the full elimination reasoning).

**Why this won:** it is the only candidate where contention for a shared, finite resource is the actual problem — which makes multithreading, transactions, and locking *load-bearing* rather than decorative. Every other candidate could be built correctly without real concurrency; Apparat cannot.

**Limitation to state unprompted:** the domain is well-trodden by commercial vendors (BookitLab, Ezbook, LabCollector); Apparat's contribution is depth of implementation and honest engineering, not novelty of concept.

---

### ADR-2 — Why Servlet + JSP, not a modern framework

**Decision:** implement MVC directly on the raw Servlet API and JSP/JSTL, with no Spring, no templating engine beyond JSTL, no SPA framework.

**Alternatives considered:** Spring MVC (hides the request lifecycle you're asked to demonstrate); a JS SPA + REST backend (contradicts the explicit JSP/Servlet course requirement); Spring Boot + Thymeleaf (still hides session/lifecycle management the course wants demonstrated).

**Why this won:** the course explicitly requires JSP/Servlet/session management as a graded section. Building MVC by hand — front-controller servlets, a filter chain, explicit session handling — demonstrates understanding of what a framework like Spring does *for* you, which is a stronger and more explainable position in a viva than "I called `@Controller` and it worked."

**Limitation to state unprompted:** JSP usage is declining industry-wide in favour of JS-rendered front ends (Jakarta EE Cloud Native Java Survey, 2024); Servlets remain foundational underneath modern frameworks. The honest framing: this project demonstrates the fundamentals a framework abstracts away, not a technology choice for a production system built today.

---

### ADR-3 — Why a layered architecture (Controller → Service → DAO → JDBC → MySQL)

**Decision:** strict layering with dependency flowing one direction only; no SQL in servlets; no `HttpServletRequest` in services; no business rules in DAOs.

**Alternatives considered:** a "fat servlet" design where servlets talk to JDBC directly (faster to write, fails the clean-architecture requirement and makes unit testing of business rules impossible without a servlet container); a generic active-record model where each entity manages its own persistence (blurs the model/DAO boundary and makes multi-table transactions awkward to reason about).

**Why this won:** it is the only structure where the service layer is unit-testable without a running servlet container (services take plain objects, not `HttpServletRequest`), and where a reviewer can `grep` for `import java.sql` in the controller package and find nothing — a concrete, checkable architectural claim rather than an assertion.

**Limitation to state unprompted:** strict layering adds boilerplate (DTOs, mapping) that a smaller script-style project wouldn't need; it is a deliberate trade of some verbosity for testability and separation of concerns, appropriate at this project's scale but not free.

---

### ADR-4 — Why `ReentrantLock` per resource, not a single global lock or `synchronized`

**Decision:** a `ConcurrentHashMap<Long, ReentrantLock>`, one lock per resource, acquired via `computeIfAbsent` and released in `finally`.

**Alternatives considered:** a single global lock around all booking creation (correct but serializes bookings on *every* resource behind one lock, killing parallelism for unrelated instruments); `synchronized` on the service instance (same global-serialization problem, since Tomcat typically uses one servlet/service instance for all requests); `synchronized` on a resource-keyed object without a concurrent map (races on *creating* the lock object itself).

**Why this won:** per-resource granularity lets bookings on different instruments proceed fully in parallel while only serializing genuine contention on the *same* instrument; `ReentrantLock` additionally offers `tryLock(timeout)`, so a request fails fast with a clear error instead of hanging indefinitely if something else is misbehaving; `ConcurrentHashMap.computeIfAbsent` is itself atomic, so two threads racing to create the lock for a not-yet-locked resource are guaranteed to end up sharing the same lock instance.

**Limitation to state unprompted:** this lock is JVM-local (ADR-5) — it is a throughput optimisation for a single running instance, not a correctness guarantee, and the architecture does not lean on it as one.

---

### ADR-5 — Why the application lock is not treated as sufficient on its own

**Decision:** the `ReentrantLock` is explicitly documented as layer 1 of three, with its JVM-local scope stated as a known, accepted limitation rather than glossed over.

**Alternatives considered:** presenting the lock as "the fix" for double booking (the original, corrected version of this document did this, and it doesn't survive the obvious follow-up question about running two application instances); a distributed lock (Redis/ZooKeeper) to make the application-level lock authoritative across instances (rejected — see ADR-9, this is genuine over-engineering for a single-node academic deployment and was explicitly excluded from scope).

**Why this won:** rather than hiding or solving a problem this project doesn't need to solve (multi-instance deployment), the corrected architecture states the limitation plainly and shows that correctness does not actually depend on it, because the database layers below are authoritative regardless of how many application instances exist. This turns a potential weak point into one of the strongest, most self-aware answers in the whole viva.

---

### ADR-6 — Why `SELECT ... FOR UPDATE` rather than raising the transaction isolation level

**Decision:** keep InnoDB's default `REPEATABLE READ` isolation and take an explicit pessimistic row lock (`SELECT ... FOR UPDATE`) on the target resource row inside the booking transaction.

**Alternatives considered:** `SERIALIZABLE` isolation for all transactions (would prevent the same race, but serializes far more broadly than needed — every transaction touching overlapping read sets across the *whole* database would be affected, not just concurrent bookings on the *same* resource, hurting throughput for no additional correctness benefit here); optimistic locking via a version column with retry-on-conflict (a reasonable alternative design, but for a booking request the user is actively waiting on, a short pessimistic lock that resolves in milliseconds is simpler to reason about and explain than a retry loop with backoff).

**Why this won:** `FOR UPDATE` gives exactly the guarantee needed — the specific resource row is locked for the duration of the check-then-act sequence — without broadening the locking scope to unrelated data.

**Limitation to state unprompted:** pessimistic locking has a throughput cost under heavy contention on the *same* resource (competing requests queue behind the lock rather than racing and retrying); acceptable and appropriate at the scale this project targets (a department's instrument catalogue, not a national reservation system).

---

### ADR-7 — Why `booking_slot` (atomic slots + unique constraint) as the final backstop

**Decision:** decompose every booking interval into fixed-size atomic slot rows and enforce `UNIQUE(resource_id, slot_start)`.

**Alternatives considered:** relying on the overlap query plus the transaction alone (leaves no database-enforced guarantee if a future code path — or a manual `INSERT` — bypasses the service layer's discipline); an exclusion constraint as available in PostgreSQL (`EXCLUDE USING gist`) — genuinely the more elegant relational solution to interval overlap, but MySQL, the required course database, has no equivalent; a generated/computed range type with a custom check (MySQL cannot express interval-exclusion in a `CHECK` constraint that references other rows, since `CHECK` constraints in MySQL cannot perform cross-row comparisons).

**Why this won:** it is the only technique available in MySQL that gives a database-*enforced*, not merely database-*checked*, guarantee against overlapping occupancy — the constraint is correct even if every layer of application discipline above it fails.

**Limitation to state unprompted:** this trades a small, deliberate redundancy (duplicating `resource_id` from `bookings`) and a bounded lifecycle-management responsibility (§19.5 of the corrected blueprint — slot rows must be deleted on cancellation/rejection/no-show) for a guarantee the relational model doesn't provide natively. If this project targeted PostgreSQL, an exclusion constraint would likely be the better-engineered choice; MySQL is the required database for the course, so this is the correct choice within that constraint, not a universal one.

---

### ADR-8 — Why the Service layer owns the transaction, never the DAO

**Decision:** `Connection.setAutoCommit(false)`/`commit()`/`rollback()` are called only in service methods; DAO methods invoked with a caller-supplied `Connection` never touch transaction state.

**Alternatives considered:** transaction-per-DAO-call (each DAO method commits its own work) — rejected because a single business operation (creating a booking) spans five tables, and partial commits across them would leave the database inconsistent on a mid-operation failure; a declarative transaction framework (e.g. Spring's `@Transactional`) — rejected as unnecessary machinery for a project whose explicit purpose is to demonstrate manual JDBC transaction management, and as scope creep beyond the required stack.

**Why this won:** the service is the only layer that knows the full boundary of "one business operation" — it is the natural and correct owner, and stating this as an explicit rule (rather than an implicit pattern in one example) makes it a checkable design constraint across the whole codebase.

---

### ADR-9 — Why a database outbox table (`background_jobs`), not Java serialization, is the durable job record

**Decision:** background work (notifications, report generation, CSV import) is represented as durable rows in a `background_jobs` table, written inside the same transaction as the domain change that triggers the work; a background worker pool polls and claims rows with a conditional `UPDATE`.

**Alternatives considered:** submitting work directly to an in-memory queue after commit, checkpointed periodically via Java serialization (the original design) — rejected because of the crash window between commit and enqueue that no amount of *later* checkpointing can retroactively close (see CORRECTIONS_LOG.md C7); a real message broker (RabbitMQ/Kafka) — rejected as explicit over-engineering for a single-node academic deployment, adds an entire piece of infrastructure the student would need to install, run, and explain, for a benefit (true distributed delivery guarantees) this project's scale does not need.

**Why this won:** the transactional outbox pattern gives the exact guarantee needed — the job's *intent* commits atomically with the domain change it belongs to — using only the database the project already requires, with no new infrastructure.

**Limitation to state unprompted:** polling has latency (bounded by the worker's poll interval, not instantaneous); a message broker would offer push-based delivery and horizontal scaling of workers, which is explicitly out of scope for this project's size and stated as a documented future-work item, not a gap in the current design.

---

### ADR-10 — Why Java serialization is still used, and why it is not treated as the outbox's replacement

**Decision:** the worker pool's in-memory job queue is periodically checkpointed to a local `.ser` file, purely to speed up a restarted worker's recovery; the `background_jobs` table remains authoritative regardless of whether this checkpoint exists.

**Alternatives considered:** dropping serialization from the project entirely and only using the database outbox (would satisfy correctness but would not fulfil the course's explicit serialization requirement, and would leave a genuinely defensible pedagogical use case unexplored); treating the serialized file as authoritative (the original design's implicit stance — rejected, see ADR-9 and CORRECTIONS_LOG.md C9, because it silently created two competing sources of truth).

**Why this won:** this is the version of "use serialization" that survives the question "what if this file is deleted or corrupted" with the answer "nothing important is lost" — which is precisely the property a *legitimate*, bounded use of serialization should have, as distinct from a use invented only to satisfy a checklist.

**Limitation to state unprompted:** Java native serialization is brittle across class-shape changes (mitigated with explicit `serialVersionUID`), not human-readable, not cross-language, and — for untrusted input generally, though not a live risk here since only self-written files are ever deserialized — a known remote-code-execution vector via gadget chains. State this risk unprompted; it is a strong, rare answer for a student project.

---

### ADR-11 — Why quota consumption is a single atomic `UPDATE`, not a Java read-modify-write

**Decision:** `UPDATE quota_usage SET used_minutes = used_minutes + ? WHERE ... AND used_minutes + ? <= limit_minutes`, with the affected-row count as the source of truth for whether the quota check passed.

**Alternatives considered:** read the current value in Java, compute the new value, write it back (the original design) — rejected as a textbook lost-update race, exactly the class of bug the rest of the architecture exists to prevent, just on a different table; locking the `quota_usage` row with `SELECT ... FOR UPDATE` before a read-modify-write in Java (would also be correct, but is strictly more code and one more round trip than folding the check into the `UPDATE`'s `WHERE` clause directly).

**Why this won:** it is the simplest correct mechanism — one round trip, no explicit lock statement needed, and the database itself is the single point of truth for both the increment and the limit check, evaluated atomically against the current committed row.

---

### ADR-12 — Why 30-minute atomic slots, and why misalignment is rejected rather than rounded

**Decision:** a configurable, default 30-minute atomic slot size; a booking request whose interval does not align to slot boundaries is rejected with a specific error naming the nearest valid alternatives.

**Alternatives considered:** silently rounding a misaligned request to the nearest valid interval (simpler UX in the moment, but creates a genuine discrepancy between what the user believes they booked and what the system actually reserved — a correctness-relevant, trust-eroding behaviour that could surface as a confusing conflict later); a finer slot size (e.g. 5 minutes) for more flexible booking (increases the number of `booking_slot` rows per booking proportionally, adds no real value for instrument-booking granularity, and complicates the buffer/cooldown slot generation for marginal benefit); per-resource slot sizes as a MUST-HAVE (a reasonable SHOULD-HAVE extension, deliberately deferred to keep the MUST-HAVE scope implementable in the available time).

**Why this won:** 30 minutes matches realistic instrument-booking granularity (most lab sessions are not scheduled to five-minute precision) and keeps the number of slot rows per booking small; explicit rejection keeps the user's understanding of what they booked and the database's actual state identical at all times, which the correction requirements explicitly prefer over silent rounding.

---

*Apparat remains the selected project. These architecture decisions harden its concurrency, persistence, session, and database design without changing the product idea, the feature set, or the required technology stack.*
