package com.apparat.exception;

/**
 * Wraps a java.sql.SQLException at the DAO boundary. The service layer never
 * imports java.sql.* — this is the translation point (see dao.BaseDao). The
 * original SQLException is preserved as the cause for logging; its message
 * (which may include SQL fragments) is never surfaced to the user — the
 * message on this exception is always a generic, user-safe string.
 */
public class DataAccessException extends ApparatException {
    public DataAccessException(String message, Throwable cause) {
        super(message, 500, cause);
    }
    public DataAccessException(String message) {
        super(message, 500);
    }
}
