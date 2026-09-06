package com.apparat.model;

import com.apparat.model.enums.Role;

/** Admins can approve anything and manage users/resources/reports. */
public class Admin extends User {
    public Admin() { super(Role.ADMIN); }

    @Override public boolean canApprove(Booking booking) { return true; }

    @Override public int weeklyQuotaMinutes() { return 100_000; } // see Technician for why this is finite, not a special-cased skip
}
