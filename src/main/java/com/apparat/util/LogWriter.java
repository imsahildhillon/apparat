package com.apparat.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Appends application events (LOGIN, BOOKING_CREATED, BOOKING_APPROVED, ...)
 * to a daily-rotating plain-text audit log file — separate from and in
 * addition to the audit_log DATABASE table (dao.AuditDao). The file is a
 * simple, greppable operational log a technician could tail; the DB table is
 * the queryable, joinable record used by the admin audit screen. Both are
 * written from the same call site (service classes call util.LogWriter right
 * after dao.AuditDao#write) so neither can silently drift from the other's
 * intent, even though they are two separate mechanisms.
 */
public final class LogWriter {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logDir;

    public LogWriter(Path dataDir) {
        this.logDir = dataDir.resolve("logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create log directory: " + logDir, e);
        }
    }

    public synchronized void append(String event, Long actorId, String details) {
        LocalDateTime now = LocalDateTime.now();
        Path file = logDir.resolve("audit-" + now.toLocalDate().format(FILE_DATE) + ".log");
        String line = "[" + now.format(LINE_TS) + "] " + event
                + " actor=" + (actorId == null ? "-" : actorId)
                + (details == null || details.isBlank() ? "" : " " + details)
                + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A logging failure must never break the request it's logging — record to stderr and move on.
            System.err.println("LogWriter failed to write audit line: " + e.getMessage());
        }
    }
}
