package com.apparat.exception;

/** Malformed or missing input caught before it ever reaches the database. HTTP 400. */
public class ValidationException extends ApparatException {
    public ValidationException(String message) {
        super(message, 400);
    }
}
