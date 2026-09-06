package com.apparat.exception;

/** Structurally invalid booking request that isn't a slot-alignment problem specifically — e.g. end before start, duration outside min/max, outside operating hours. */
public class InvalidBookingException extends BookingException {
    public InvalidBookingException(String message) {
        super(message, 400);
    }
}
