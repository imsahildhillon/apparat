package com.apparat.model;

import com.apparat.model.enums.Role;

/**
 * Faculty book resources like students but are not part of the approval
 * workflow in this MVP (the build brief's simplified approval workflow is
 * Technician/Admin approve — see BookingException-throwing code in
 * service.BookingService and PROJECT_BLUEPRINT_CORRECTED.md's SupervisedPolicy
 * for the fuller design this simplifies). Given a generous but still-real
 * quota rather than special-cased as unlimited, so the same atomic quota
 * UPDATE path (dao.QuotaDao#consume) applies uniformly to every role.
 */
public class Faculty extends User {
    public Faculty() { super(Role.FACULTY); }

    @Override public boolean canApprove(Booking booking) { return false; }

    @Override public int weeklyQuotaMinutes() { return 480; }
}
