# Apparat

**A concurrency-safe scheduling and utilization platform for shared university laboratory instruments.**

Java · Servlet 6.0 (Jakarta) · JSP/JSTL · JDBC · MySQL 8 (InnoDB) — no Spring, no ORM, no frontend framework. MVC built by hand on the raw Servlet API, deliberately, to demonstrate the request lifecycle, session management, and layered architecture that a framework like Spring would otherwise hide.

> Full design rationale: [docs/PROJECT_BLUEPRINT_CORRECTED.md](docs/PROJECT_BLUEPRINT_CORRECTED.md) · [docs/ARCHITECTURE_DECISIONS.md](docs/ARCHITECTURE_DECISIONS.md) · [docs/CORRECTIONS_LOG.md](docs/CORRECTIONS_LOG.md) · [docs/CONCURRENCY_TEST_PLAN.md](docs/CONCURRENCY_TEST_PLAN.md)

---

## What is Apparat?

University departments share expensive instruments (electron microscopes, chromatographs, diffractometers) across many students, faculty, and projects. Coordination today happens over spreadsheets, WhatsApp groups, and paper sign-up sheets — which produces double bookings, unqualified access, and zero usage evidence for the next purchase decision.

Apparat is the single system of record: browse real-time availability, book a conflict-free slot, route bookings through an approval policy where required, and see actual utilization from real booking data.

## Why concurrency matters here

Two people booking the same instrument at the same time is a genuine race condition, not a hypothetical one. Apparat closes it with three independent, explicitly-scoped layers rather than one:

```
Browser
  -> Servlet (BookingCreateServlet)
  -> BookingService.createBooking()
       -> ReentrantLock (per resource)          [layer 1: in-process contention, JVM-local]
       -> JDBC transaction begins
       -> SELECT ... FOR UPDATE (resource row)  [layer 2: cross-process coordination]
       -> overlap check (defence-in-depth read)
       -> atomic slot generation (30-min slots)
       -> INSERT INTO booking_slot (...)         [layer 3: UNIQUE(resource_id, slot_start)]
       -> COMMIT (or ROLLBACK + SlotConflictException on a MySQL 1062 duplicate-key error)
```

**Application locking reduces in-process contention, database locking coordinates transactional access across every process talking to the database, and the unique atomic-slot constraint is the final integrity backstop.** No single layer is presented as sufficient by itself — see [ARCHITECTURE_DECISIONS.md](docs/ARCHITECTURE_DECISIONS.md) ADR-4/5 for why, including the explicitly-stated limitation that the `ReentrantLock` alone would not coordinate two application server instances.

### Proof, not assertion

`src/test/java/com/apparat/concurrency/ConcurrentBookingTest` fires 50 concurrent booking attempts at the identical resource/interval using `CountDownLatch` + `ExecutorService`, against a real MySQL instance (not mocked).

**Measured result (2026-09-06, MySQL 5.7.24/InnoDB):** 50 threads submitted → **exactly 1 success, exactly 49 `SlotConflictException`**, 0 unexpected exceptions. Verified in the database afterward: exactly 1 active booking, exactly 2 `booking_slot` rows (both owned by the same winning `booking_id`), 0 orphaned slot rows. Reproduced on a second run. Also covered: adjacent, non-overlapping bookings (both must succeed — tests the strict `<`/`>` interval-overlap inequality) and partially-overlapping bookings (exactly one must win). Full detail and the exact commands to reproduce: [docs/CONCURRENCY_TEST_PLAN.md](docs/CONCURRENCY_TEST_PLAN.md).

---

## Features

**Core (built and manually verified end-to-end against a real MySQL + Tomcat deployment):**
- Login/logout, salted PBKDF2 password hashing, 30-minute session timeout, session-fixation prevention, role-based access (`STUDENT`/`FACULTY`/`TECHNICIAN`/`ADMIN`)
- Resource catalogue with search/filter, per-resource availability grid (day view, 30-minute atomic slots)
- Booking creation with server-side alignment validation (misaligned times are **rejected with suggested alternatives, never silently rounded**), maintenance-window conflict checking, atomic quota enforcement
- Approval workflow (`StandardPolicy` auto-confirms; `SupervisedPolicy` routes to Technician/Admin) via a `BookingPolicy` interface selected per resource
- Booking cancellation with slot release and quota release, guarded by conditional `UPDATE ... WHERE status = ...`
- Maintenance scheduling (Technician/Admin) that blocks new bookings on that resource
- Resource utilization report computed from real `booking_slot` occupancy, with CSV export
- Durable background-job outbox (`background_jobs` table) for notifications and reports, drained by a real `ExecutorService` worker pool polling on a `ScheduledExecutorService`
- Audit trail: both a database table (`audit_log`) and a daily-rotating file (`data/logs/audit-YYYY-MM-DD.log`)
- CSV resource import (`src/main/resources/resources.csv`, `util.CsvUtil`)

## Architecture

```
JSP (JSTL, WEB-INF/jsp/, no scriptlets except tiny nav chrome)
  v
Servlet (controller — no SQL, no business rules; ~14 classes in com.apparat.servlet)
  v
Service (business rules, transaction boundary, locking, policy dispatch)
  v
DAO (SQL only — PreparedStatement, try-with-resources; a DAO never commits a caller-owned transaction)
  v
JDBC (config.ConnectionFactory)
  v
MySQL 8 (InnoDB)
```

Dependency direction is strictly downward. Servlets never import `java.sql.*`. See [docs/PROJECT_BLUEPRINT_CORRECTED.md](docs/PROJECT_BLUEPRINT_CORRECTED.md) §24 for the full layered diagram including the background/threading layer.

## OOP concepts demonstrated

| Concept | Where |
|---|---|
| Inheritance | `User` → `Student`/`Faculty`/`Technician`/`Admin` (polymorphic `canApprove`, `weeklyQuotaMinutes`); `Resource` → `Instrument` |
| Polymorphism | `BookingPolicy` (`StandardPolicy`/`SupervisedPolicy`) selected at runtime by `PolicyFactory`; `Job` (`NotificationJob`/`ReportJob`) built by `Job.from(BackgroundJobRecord)` |
| Interfaces | `Dao<T,ID>`, `BookingPolicy`, `Job` |
| Abstraction | `BaseDao` owns connection-handling discipline; `User`/`Resource` are abstract with concrete behaviour subtypes only specialise |
| Encapsulation | Private fields, validating setters (e.g. `Booking.setEndAt` rejects `end <= start`); `SessionUser` is immutable |
| Custom exceptions | `ApparatException` hierarchy — `AuthenticationException`, `AuthorizationException`, `ValidationException`, `SlotAlignmentException`, `SlotConflictException`, `QuotaExceededException`, `ResourceUnavailableException`, `ResourceNotFoundException`, `InvalidBookingException`, `DataAccessException` |
| Collections | `ConcurrentHashMap<Long,ReentrantLock>` (per-resource locks), `TreeMap`/`NavigableMap` (availability grid), `EnumMap` (slot-state tally), `HashSet`/`List` throughout DAOs |
| File handling | `app.properties` config, CSV import/export, daily audit log, serialization checkpoint |
| Serialization | `job.Job` (bounded, disposable worker checkpoint — see below); `SessionUser` (session-persistence compatibility) |
| Multithreading | Per-resource `ReentrantLock`; `ScheduledExecutorService` (poll + checkpoint/reclaim); `ExecutorService` worker pool draining the job outbox |

## Database design

**8 tables** for this MVP core (a deliberate subset of the 13-table full design in the corrected blueprint — certifications, waitlist, and notification tables are documented as future work rather than built with no UI behind them): `users`, `resources`, `bookings`, `booking_slot`, `maintenance_windows`, `quota_usage`, `background_jobs`, `audit_log`.

`booking_slot` is a **derived occupancy structure**, not a business entity — it exists purely so MySQL can enforce `UNIQUE(resource_id, slot_start)`, since MySQL has no native interval-exclusion constraint. Full schema, lifecycle rules (when slot rows are inserted/retained/deleted), and the atomic-slot conversion algorithm: [docs/PROJECT_BLUEPRINT_CORRECTED.md](docs/PROJECT_BLUEPRINT_CORRECTED.md) §19. DDL: [sql/schema.sql](sql/schema.sql).

## Serialization strategy — honest and bounded

`background_jobs` (a MySQL table) is the **authoritative durable record** of pending work — a job row is written in the *same transaction* as the domain change that triggers it (the outbox pattern), so there is no crash window between "booking committed" and "notification guaranteed to eventually fire."

`job.Job` is `Serializable` and periodically checkpointed to `data/jobs/checkpoint.ser`, but this is deliberately scoped as a **local, disposable worker-state aid, not the source of truth**: `job.SchedulerManager` loads it on startup purely for diagnostic logging and never re-executes from it — if the file is deleted, nothing important is lost, because the worker simply re-polls `background_jobs`. This is the honest, bounded use of Java serialization the course requires, distinct from treating it as a production message queue. See [docs/PROJECT_BLUEPRINT_CORRECTED.md](docs/PROJECT_BLUEPRINT_CORRECTED.md) §17.

## Transaction strategy

The **Service** layer owns every transaction boundary — a DAO invoked with a caller-supplied `Connection` never calls `commit()`/`rollback()`/`setAutoCommit`. `BookingService.createBooking` is the reference example: lock → `setAutoCommit(false)` → `SELECT ... FOR UPDATE` → validate → consume quota (atomic `UPDATE`) → check overlap → insert booking → generate + insert atomic slots → enqueue outbox job → write audit → `commit()`, with a single `catch (SQLException)` that rolls back and translates a MySQL 1062 duplicate-key error into `SlotConflictException`.

---

## Setup instructions

**Prerequisites:** JDK 21, Maven 3.9+, MySQL 8 (InnoDB), a Jakarta Servlet 6.0+ container (Tomcat 10.1+ or 11 — see "Tomcat version" below).

```bash
git clone <this repo>
cd apparat

# 1. Database
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/seed.sql   # demo data — see "Demo credentials"

# 2. Configuration (never commit real credentials)
cp src/main/resources/app.properties.example src/main/resources/app.properties
#   edit db.url / db.user / db.password to match your local MySQL

# 3. Build
mvn clean package
#   -> target/apparat.war

# 4. Deploy to Tomcat
cp target/apparat.war $CATALINA_HOME/webapps/
#   start Tomcat, then open http://localhost:8080/apparat/login
```

### Tomcat version

This project targets **Jakarta Servlet 6.0 (Tomcat 10.1+/11)** — chosen once and used consistently; nothing in the codebase mixes `javax.servlet.*` and `jakarta.servlet.*`. If you only have Tomcat 9 available, either upgrade Tomcat or run the [Jakarta EE migration tool](https://tomcat.apache.org/download-migration.cgi) against the built WAR — do not attempt to mix API generations by hand.

### Database setup

`sql/schema.sql` creates the `apparat` database and all 8 tables with foreign keys, the `booking_slot` unique constraint, and indexes. `sql/seed.sql` loads 8 demo users (2 per role), the 4 named instruments, one active maintenance window, and a couple of demo bookings. Both are idempotent from a clean state (`schema.sql` starts with `DROP DATABASE IF EXISTS apparat`).

### Demo credentials

**Local/development database only — never use these values, or this password, against a real deployment.** All seeded accounts share the password `Demo@123` (hashed with `util.PasswordUtil`'s PBKDF2WithHmacSHA256 scheme, not stored in plaintext anywhere):

| Email | Role |
|---|---|
| `admin@apparat.edu` | Admin |
| `tech.mehta@apparat.edu`, `tech.iyer@apparat.edu` | Technician |
| `prof.kapoor@apparat.edu`, `prof.singh@apparat.edu` | Faculty |
| `student.arjun@apparat.edu`, `student.meera@apparat.edu`, `student.kabir@apparat.edu` | Student |

`app.properties` (real credentials) is gitignored and never committed.

### Running tests

```bash
# Pure unit tests (no database needed) — slot-alignment math, atomic-slot generation
mvn test -Dtest=DateUtilTest

# The concurrency integration suite — needs a running MySQL with the schema loaded.
# Creates and cleans up its own isolated test resource/users; does not touch demo data.
mvn test -Dapparat.it=true -Dtest=ConcurrentBookingTest
```

Integration tests are gated behind `-Dapparat.it=true` specifically so a plain `mvn test` never fails in an environment without a database — see the `@EnabledIfSystemProperty` guard in the test class.

---

## Known limitations

- The application-level `ReentrantLock` is JVM-local; it does not coordinate across multiple application server instances (the database layers remain correct regardless — see the concurrency section above).
- No real email/SMS transport — `NotificationJob` writes to the audit log rather than sending mail, deliberately, to avoid building notification infrastructure the MVP doesn't need.
- `ConnectionFactory` uses `DriverManager` directly rather than a pooled `DataSource`; documented in the corrected blueprint as an acceptable, non-blocking simplification for this scale (§20.4).
- Certification requirements are displayed on a resource but not enforced (no certification-grant workflow in this MVP scope).
- Single global atomic slot size per deployment (`app.properties`), not per-resource, though the schema already supports the latter.

## Future enhancements

REST API + SPA client · per-resource slot-size overrides · certification grant/enforcement workflow · waitlist with `PriorityQueue` promotion on cancellation/no-show · distributed lock (e.g. Redis) if ever deployed across multiple instances · Tomcat JNDI connection pool · real email/SMS notification transport.

---

## Project structure

```
apparat/
├── pom.xml
├── README.md
├── docs/                                  design & correction documents
├── sql/                                   schema.sql, seed.sql
├── src/main/java/com/apparat/
│   ├── model/            User hierarchy, Resource hierarchy, Booking, ...
│   │   ├── dto/           SessionUser, BookingRequest, BookingResult
│   │   └── enums/          Role, BookingStatus, ResourceStatus, JobType, JobStatus
│   ├── dao/               Dao<T,ID>, BaseDao, and 8 concrete DAOs
│   ├── service/            AuthService, BookingService, ApprovalService, ...
│   ├── policy/             BookingPolicy + Standard/Supervised + PolicyFactory
│   ├── concurrency/        ResourceLockManager (layer 1)
│   ├── job/                 Job hierarchy, JobContext, SchedulerManager (layers B/C)
│   ├── exception/           ApparatException hierarchy
│   ├── filter/                AuthFilter, CharacterEncodingFilter
│   ├── listener/               AppLifecycleListener (composition root + thread lifecycle)
│   ├── servlet/                 ~14 controller classes
│   └── util/                     PasswordUtil, ValidationUtil, DateUtil, CsvUtil, LogWriter
├── src/main/resources/     app.properties.example, resources.csv
├── src/main/webapp/
│   ├── css/app.css          hand-written design system
│   ├── js/
│   ├── index.jsp
│   └── WEB-INF/
│       ├── web.xml           session-config, error-pages (mappings are annotation-driven)
│       └── jsp/               layout/, resources/, bookings/, reports/, ...
└── src/test/java/com/apparat/
    ├── util/DateUtilTest.java
    └── concurrency/ConcurrentBookingTest.java
```
