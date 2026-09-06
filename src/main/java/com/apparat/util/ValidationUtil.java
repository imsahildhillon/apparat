package com.apparat.util;

import com.apparat.exception.InvalidBookingException;
import com.apparat.exception.ValidationException;
import com.apparat.model.Resource;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** Server-side validation — the authoritative validation layer. Client-side HTML5 validation in the JSPs is a convenience only; every rule here is re-checked server-side because client validation is trivially bypassable. */
public final class ValidationUtil {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}$");

    private ValidationUtil() { }

    public static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(fieldName + " is required.");
        }
    }

    public static void validateEmail(String email) {
        requireNonBlank(email, "Email");
        if (!EMAIL.matcher(email).matches()) {
            throw new ValidationException("Please enter a valid email address.");
        }
    }

    public static void validatePasswordStrength(char[] password) {
        if (password == null || password.length < 8) {
            throw new ValidationException("Password must be at least 8 characters.");
        }
    }

    /** Structural checks that are not alignment (see util.DateUtil#validateAlignment for that): end after start, within min/max duration, within operating hours, not in the past. */
    public static void validateBookingInterval(LocalDateTime start, LocalDateTime end, Resource resource) {
        if (start == null || end == null) {
            throw new InvalidBookingException("Start and end time are required.");
        }
        if (!end.isAfter(start)) {
            throw new InvalidBookingException("End time must be after start time.");
        }
        if (start.isBefore(LocalDateTime.now())) {
            throw new InvalidBookingException("Cannot book a time in the past.");
        }
        long minutes = java.time.Duration.between(start, end).toMinutes();
        if (minutes < resource.getMinSlotMinutes()) {
            throw new InvalidBookingException("Minimum booking duration for this resource is "
                    + resource.getMinSlotMinutes() + " minutes.");
        }
        if (minutes > resource.getMaxSlotMinutes()) {
            throw new InvalidBookingException("Maximum booking duration for this resource is "
                    + resource.getMaxSlotMinutes() + " minutes.");
        }
        if (!resource.isWithinOperatingHours(start.toLocalTime(), end.toLocalTime())) {
            throw new InvalidBookingException("Requested time is outside this resource's operating hours ("
                    + resource.getOpenTime() + "-" + resource.getCloseTime() + ").");
        }
    }
}
