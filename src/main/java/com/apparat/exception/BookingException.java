package com.apparat.exception;

/** Common supertype for every booking-workflow-specific failure, so a servlet can catch just this one type if it only cares that "the booking failed" and not why. */
public abstract class BookingException extends ApparatException {
    protected BookingException(String message, int statusCode) {
        super(message, statusCode);
    }
}
