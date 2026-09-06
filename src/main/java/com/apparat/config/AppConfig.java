package com.apparat.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads app.properties once at startup (see listener.AppLifecycleListener).
 * File-based configuration rather than hard-coded values specifically so
 * database credentials never live in source code (grading requirement +
 * plain good practice) and so the atomic slot size / thread-pool sizes /
 * grace periods can be tuned for a live demo without a rebuild — see
 * PROJECT_BLUEPRINT_CORRECTED.md §16.
 */
public final class AppConfig {

    private static volatile AppConfig instance;

    private final Properties props = new Properties();

    private AppConfig() {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "app.properties not found on classpath. Copy src/main/resources/app.properties.example "
                        + "to src/main/resources/app.properties and fill in local values.");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load app.properties", e);
        }
    }

    public static AppConfig get() {
        AppConfig local = instance;
        if (local == null) {
            synchronized (AppConfig.class) {
                local = instance;
                if (local == null) {
                    instance = local = new AppConfig();
                }
            }
        }
        return local;
    }

    public String dbUrl() { return require("db.url"); }
    public String dbUser() { return require("db.user"); }
    public String dbPassword() { return props.getProperty("db.password", ""); }

    public int defaultSlotMinutes() { return intProp("booking.default.slot.minutes", 30); }
    public int reminderLeadMinutes() { return intProp("booking.reminder.lead.minutes", 60); }
    public int jobWorkerPoolSize() { return intProp("job.worker.pool.size", 4); }
    public int jobSchedulerPoolSize() { return intProp("job.scheduler.pool.size", 2); }
    public int jobPollIntervalSeconds() { return intProp("job.poll.interval.seconds", 5); }

    public Path dataDir() { return Path.of(props.getProperty("data.dir", "./data")); }

    private String require(String key) {
        String value = props.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing required property: " + key);
        return value;
    }

    private int intProp(String key, int fallback) {
        String value = props.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }
}
