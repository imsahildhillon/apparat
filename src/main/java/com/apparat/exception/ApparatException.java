package com.apparat.exception;

/**
 * Root of Apparat's exception hierarchy. Unchecked (extends RuntimeException)
 * deliberately: these propagate from DAO -> service -> servlet, and forcing a
 * {@code throws} clause through every intermediate layer (which never
 * recovers from them, only the servlet layer's catch block does) would add
 * ceremony without benefit. See ARCHITECTURE_DECISIONS.md and
 * PROJECT_BLUEPRINT_CORRECTED.md's exception-strategy section for the full
 * checked-vs-unchecked reasoning.
 *
 * Carries a user-safe message (never a raw SQL error or stack trace) plus an
 * HTTP-style status code the servlet layer can use to pick a response code.
 */
public abstract class ApparatException extends RuntimeException {

    private final int statusCode;

    protected ApparatException(String userMessage, int statusCode) {
        super(userMessage);
        this.statusCode = statusCode;
    }

    protected ApparatException(String userMessage, int statusCode, Throwable cause) {
        super(userMessage, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
