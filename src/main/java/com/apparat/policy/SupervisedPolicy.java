package com.apparat.policy;

import com.apparat.model.enums.BookingStatus;

/** Routes to PENDING, requiring a Technician/Admin to approve — used for resources with requires_approval = true (e.g. SEM-01, AFM-01 in the seed data). */
public class SupervisedPolicy implements BookingPolicy {
    @Override public BookingStatus resolveInitialStatus() { return BookingStatus.PENDING; }
    @Override public String name() { return "SUPERVISED"; }
}
