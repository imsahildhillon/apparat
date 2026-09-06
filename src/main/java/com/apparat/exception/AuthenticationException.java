package com.apparat.exception;

/** Bad credentials, or no session where one is required. HTTP 401. */
public class AuthenticationException extends ApparatException {
    public AuthenticationException(String message) {
        super(message, 401);
    }
}
