package com.apparat.model.enums;

/** A resource under MAINTENANCE or INACTIVE must never be bookable — enforced in service.BookingService. */
public enum ResourceStatus {
    AVAILABLE,
    MAINTENANCE,
    INACTIVE
}
