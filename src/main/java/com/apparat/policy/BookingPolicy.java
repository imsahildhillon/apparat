package com.apparat.policy;

import com.apparat.model.enums.BookingStatus;

/**
 * Pluggable behaviour: what status a new booking request starts in, decided
 * per resource. This is the polymorphism behind the "approval workflow" —
 * service.BookingService calls policy.resolveInitialStatus() without an
 * if/else on resource type; PolicyFactory decides which implementation to
 * hand back based on Resource#isRequiresApproval(). Two implementations
 * (Standard, Supervised) is deliberately the whole MVP set — see
 * PROJECT_BLUEPRINT_CORRECTED.md's fuller CertificationPolicy/
 * QuotaLimitedPolicy design for the extension points this leaves open
 * without changing any caller.
 */
public interface BookingPolicy {
    BookingStatus resolveInitialStatus();
    String name();
}
