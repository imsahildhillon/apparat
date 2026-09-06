package com.apparat.exception;

/**
 * The atomic conditional UPDATE in dao.QuotaDao#consume affected 0 rows,
 * meaning either no quota row exists yet for this user/week, or consuming
 * this booking's minutes would exceed the weekly limit. See
 * ARCHITECTURE_DECISIONS.md ADR-11 for why this is a single SQL UPDATE and
 * never a Java read-then-write.
 */
public class QuotaExceededException extends BookingException {
    public QuotaExceededException(int requestedMinutes, int limitMinutes) {
        super("This booking needs " + requestedMinutes + " minutes, which would exceed your weekly quota of "
                + limitMinutes + " minutes.", 409);
    }
}
