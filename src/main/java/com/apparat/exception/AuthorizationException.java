package com.apparat.exception;

/**
 * Authenticated, but not permitted to perform this action on this object.
 * Deliberately distinct from AuthenticationException (401 vs 403) — see
 * filter.AuthFilter for the path-level role check and service classes for
 * the object-level ownership check (e.g. a technician approving only their
 * own custodial resources) that this exception also covers.
 */
public class AuthorizationException extends ApparatException {
    public AuthorizationException(String message) {
        super(message, 403);
    }
}
