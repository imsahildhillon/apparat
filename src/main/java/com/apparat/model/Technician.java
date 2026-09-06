package com.apparat.model;

import com.apparat.model.enums.Role;

/** Technicians approve/reject bookings on resources that require approval, and manage maintenance windows. */
public class Technician extends User {
    public Technician() { super(Role.TECHNICIAN); }

    @Override public boolean canApprove(Booking booking) { return true; }

    @Override public int weeklyQuotaMinutes() { return 100_000; } // effectively unlimited; kept finite so the uniform atomic-UPDATE quota path still applies
}
