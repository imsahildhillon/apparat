package com.apparat.exception;

/** The resource is under MAINTENANCE/INACTIVE, or the requested interval overlaps a maintenance window. */
public class ResourceUnavailableException extends BookingException {
    public ResourceUnavailableException(String message) {
        super(message, 409);
    }
}
