-- ============================================================================
-- Apparat — MVP schema (MySQL 8, InnoDB)
--
-- This is the polished-core subset of the 13-table design in
-- docs/PROJECT_BLUEPRINT_CORRECTED.md. Tables intentionally NOT built for the
-- MVP (certifications, resource_certifications, user_certifications,
-- waitlist_entries, notifications) are documented as future work in the
-- README rather than built with no UI behind them — see
-- ARCHITECTURE_DECISIONS.md and PROJECT_BLUEPRINT_CORRECTED.md §36 (Do not
-- overengineer).
--
-- Tables in this MVP: users, resources, bookings, booking_slot,
-- maintenance_windows, quota_usage, background_jobs, audit_log.  (8 tables)
-- ============================================================================

DROP DATABASE IF EXISTS apparat;
CREATE DATABASE apparat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE apparat;

-- ----------------------------------------------------------------------------
-- users
-- Single-table role hierarchy (STUDENT/FACULTY/TECHNICIAN/ADMIN), matching
-- the Java User -> Student/Faculty/Technician/Admin inheritance tree.
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(120) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,   -- PBKDF2WithHmacSHA256, salted (see util.PasswordUtil)
    full_name       VARCHAR(120) NOT NULL,
    role            ENUM('STUDENT','FACULTY','TECHNICIAN','ADMIN') NOT NULL,
    department      VARCHAR(80) NULL,
    active          TINYINT(1) NOT NULL DEFAULT 1,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE INDEX idx_users_role ON users(role);

-- ----------------------------------------------------------------------------
-- resources
-- MVP scope: one concrete Resource subtype (Instrument). resource_type is
-- kept as a column (not a discriminator across many tables) so the schema
-- can grow LabRoom/ConsumableKit later without a migration shape change —
-- see PROJECT_BLUEPRINT_CORRECTED.md's single-table-inheritance rationale.
-- ----------------------------------------------------------------------------
CREATE TABLE resources (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                    VARCHAR(30) NOT NULL UNIQUE,
    name                    VARCHAR(120) NOT NULL,
    resource_type           ENUM('INSTRUMENT') NOT NULL DEFAULT 'INSTRUMENT',
    category                VARCHAR(60) NOT NULL,
    location                VARCHAR(120) NOT NULL,
    description             VARCHAR(500) NULL,
    custodian_id            BIGINT NULL,
    status                  ENUM('AVAILABLE','MAINTENANCE','INACTIVE') NOT NULL DEFAULT 'AVAILABLE',
    open_time               TIME NOT NULL DEFAULT '09:00:00',
    close_time              TIME NOT NULL DEFAULT '18:00:00',
    slot_minutes            INT NOT NULL DEFAULT 30,      -- atomic slot size for this resource
    min_slot_minutes        INT NOT NULL DEFAULT 30,
    max_slot_minutes        INT NOT NULL DEFAULT 240,
    buffer_minutes          INT NOT NULL DEFAULT 0,       -- cooldown/warm-up, generates buffer slots
    requires_certification  TINYINT(1) NOT NULL DEFAULT 0,   -- displayed only in the MVP; not enforced (documented limitation)
    requires_approval       TINYINT(1) NOT NULL DEFAULT 0,   -- drives BookingPolicy selection (Standard vs Supervised)
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resource_custodian FOREIGN KEY (custodian_id) REFERENCES users(id),
    CONSTRAINT chk_resource_hours CHECK (close_time > open_time),
    CONSTRAINT chk_resource_slot_minutes CHECK (slot_minutes > 0),
    CONSTRAINT chk_resource_min_max CHECK (min_slot_minutes > 0 AND max_slot_minutes >= min_slot_minutes)
) ENGINE=InnoDB;

CREATE INDEX idx_resources_type_status ON resources(resource_type, status);
CREATE INDEX idx_resources_category ON resources(category);

-- ----------------------------------------------------------------------------
-- bookings
-- ----------------------------------------------------------------------------
CREATE TABLE bookings (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id     BIGINT NOT NULL,
    requester_id    BIGINT NOT NULL,
    start_at        DATETIME NOT NULL,
    end_at          DATETIME NOT NULL,
    status          ENUM('PENDING','APPROVED','REJECTED','CANCELLED','COMPLETED') NOT NULL DEFAULT 'PENDING',
    purpose         VARCHAR(255) NULL,
    approved_by     BIGINT NULL,
    approved_at     DATETIME NULL,
    cancel_reason   VARCHAR(255) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_resource   FOREIGN KEY (resource_id)  REFERENCES resources(id),
    CONSTRAINT fk_booking_requester  FOREIGN KEY (requester_id) REFERENCES users(id),
    CONSTRAINT fk_booking_approver   FOREIGN KEY (approved_by)  REFERENCES users(id),
    CONSTRAINT chk_booking_interval  CHECK (end_at > start_at)
) ENGINE=InnoDB;

CREATE INDEX idx_booking_resource_time ON bookings(resource_id, start_at, end_at);
CREATE INDEX idx_booking_user_status   ON bookings(requester_id, status);
CREATE INDEX idx_booking_status_start  ON bookings(status, start_at);

-- ----------------------------------------------------------------------------
-- booking_slot — DERIVED OCCUPANCY STRUCTURE, not an independent business
-- entity. Exists solely so the database can enforce
-- UNIQUE(resource_id, slot_start), which is the layer-3 backstop described
-- in PROJECT_BLUEPRINT_CORRECTED.md §18.3 / §19.4-19.6. See
-- PasswordUtil-style comment discipline: every non-obvious table here says
-- why it exists.
--
-- Lifecycle (see §19.5 of the corrected blueprint):
--   inserted   -> when a booking enters PENDING or APPROVED (an occupying status)
--   retained   -> on COMPLETED (always in the past; cannot collide with a future request)
--   deleted    -> on CANCELLED, REJECTED (same transaction as the status change,
--                 so the slot becomes bookable again)
-- ----------------------------------------------------------------------------
CREATE TABLE booking_slot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id      BIGINT NOT NULL,
    resource_id     BIGINT NOT NULL,
    slot_start      DATETIME NOT NULL,
    is_buffer       TINYINT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_slot_booking  FOREIGN KEY (booking_id)  REFERENCES bookings(id) ON DELETE CASCADE,
    CONSTRAINT fk_slot_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
    CONSTRAINT uq_slot UNIQUE (resource_id, slot_start)   -- THE layer-3 correctness guarantee
) ENGINE=InnoDB;

CREATE INDEX idx_slot_booking ON booking_slot(booking_id);

-- ----------------------------------------------------------------------------
-- maintenance_windows
-- ----------------------------------------------------------------------------
CREATE TABLE maintenance_windows (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id     BIGINT NOT NULL,
    start_at        DATETIME NOT NULL,
    end_at          DATETIME NOT NULL,
    reason          VARCHAR(255) NULL,
    created_by      BIGINT NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_maint_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
    CONSTRAINT fk_maint_creator  FOREIGN KEY (created_by)  REFERENCES users(id),
    CONSTRAINT chk_maint_interval CHECK (end_at > start_at)
) ENGINE=InnoDB;

CREATE INDEX idx_maint_resource_time ON maintenance_windows(resource_id, start_at, end_at);

-- ----------------------------------------------------------------------------
-- quota_usage — composite PK avoids a surrogate key and makes the atomic
-- UPDATE ... WHERE used_minutes + ? <= limit_minutes (ARCHITECTURE_DECISIONS
-- ADR-11) a single indexed statement.
-- ----------------------------------------------------------------------------
CREATE TABLE quota_usage (
    user_id         BIGINT NOT NULL,
    iso_year        SMALLINT NOT NULL,
    iso_week        TINYINT NOT NULL,
    used_minutes    INT NOT NULL DEFAULT 0,
    limit_minutes   INT NOT NULL,
    PRIMARY KEY (user_id, iso_year, iso_week),
    CONSTRAINT fk_quota_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_quota_nonneg CHECK (used_minutes >= 0 AND limit_minutes >= 0)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- background_jobs — durable outbox table (PROJECT_BLUEPRINT_CORRECTED.md
-- §17.2). A row is written in the SAME transaction as the domain change it
-- follows from, so there is no crash window between "booking committed" and
-- "job intent recorded." This table is authoritative; the serialized
-- worker-state checkpoint (jobs/checkpoint.ser) is a disposable local aid
-- layered on top of it — see job.Job and ARCHITECTURE_DECISIONS.md ADR-9/10.
-- ----------------------------------------------------------------------------
CREATE TABLE background_jobs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_type        ENUM('SEND_REMINDER','GENERATE_REPORT') NOT NULL,
    payload         TEXT NOT NULL,
    status          ENUM('PENDING','RUNNING','COMPLETE','FAILED') NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    available_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at       DATETIME NULL,
    completed_at    DATETIME NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE INDEX idx_jobs_status_available ON background_jobs(status, available_at);

-- ----------------------------------------------------------------------------
-- audit_log
-- ----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id        BIGINT NULL,
    action          VARCHAR(60) NOT NULL,
    entity_type     VARCHAR(40) NULL,
    entity_id       BIGINT NULL,
    details         VARCHAR(500) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE INDEX idx_audit_entity  ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_log(created_at);
