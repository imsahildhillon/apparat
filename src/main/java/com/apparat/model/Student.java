package com.apparat.model;

import com.apparat.model.enums.Role;

/** Students cannot approve anything, and are the only role subject to the weekly quota. */
public class Student extends User {
    public Student() { super(Role.STUDENT); }

    @Override public boolean canApprove(Booking booking) { return false; }

    @Override public int weeklyQuotaMinutes() { return 120; }
}
