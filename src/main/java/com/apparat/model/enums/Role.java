package com.apparat.model.enums;

/**
 * The four user roles. Kept as an enum (not a string column compared with
 * {@code .equals}) specifically so role comparisons are typo-proof and so
 * {@code switch} over role is exhaustive-checkable by the compiler — see
 * model.User#canApprove for the polymorphic method that actually varies
 * behaviour per role.
 */
public enum Role {
    STUDENT,
    FACULTY,
    TECHNICIAN,
    ADMIN
}
