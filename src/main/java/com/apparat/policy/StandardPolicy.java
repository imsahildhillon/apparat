package com.apparat.policy;

import com.apparat.model.enums.BookingStatus;

/** Auto-confirms — used for resources with requires_approval = false (e.g. HPLC-01 in the seed data). */
public class StandardPolicy implements BookingPolicy {
    @Override public BookingStatus resolveInitialStatus() { return BookingStatus.APPROVED; }
    @Override public String name() { return "STANDARD"; }
}
