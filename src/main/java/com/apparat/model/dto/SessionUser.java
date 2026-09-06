package com.apparat.model.dto;

import com.apparat.model.enums.Role;

import java.io.Serializable;
import java.util.Objects;

/**
 * What lives in HttpSession. Deliberately minimal — identity and
 * authorization context only, never a Connection, DAO, Service, Resource, or
 * Booking collection. See PROJECT_BLUEPRINT_CORRECTED.md §12.2/§23 and
 * ARCHITECTURE_DECISIONS.md ADR-10.
 *
 * Implements Serializable so this class is COMPATIBLE with container-managed
 * session persistence/replication IF a deployment configures it — this is a
 * precondition, not a guarantee that Tomcat persists sessions by default.
 * Apparat's default local deployment does not configure or rely on that
 * behaviour. See CORRECTIONS_LOG.md C5.
 */
public final class SessionUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String fullName;
    private final Role role;
    private final String department;

    public SessionUser(long userId, String fullName, Role role, String department) {
        this.userId = userId;
        this.fullName = Objects.requireNonNull(fullName);
        this.role = Objects.requireNonNull(role);
        this.department = department;
    }

    public long getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public String getDepartment() { return department; }

    public boolean isApprover() { return role == Role.TECHNICIAN || role == Role.ADMIN; }
    public boolean isAdmin() { return role == Role.ADMIN; }
}
