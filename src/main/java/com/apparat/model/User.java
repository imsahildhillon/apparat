package com.apparat.model;

import com.apparat.model.enums.Role;

/**
 * Abstract base of the role hierarchy: User -> Student/Faculty/Technician/Admin.
 * Encapsulation: fields are private; passwordHash has no getter that leaks it —
 * only matchesPassword(rawPassword, hasher) is exposed (see util.PasswordUtil).
 * Polymorphism: canApprove(Booking) and weeklyQuotaMinutes() vary genuinely by
 * subtype — see each subclass. This is the alternative to scattering
 * `if (role.equals("ADMIN"))` checks through the service layer.
 */
public abstract class User {

    private Long id;
    private String email;
    private String passwordHash;
    private String fullName;
    private final Role role;
    private String department;
    private boolean active = true;

    protected User(Role role) {
        this.role = role;
    }

    /** Whether this user may approve the given booking. Overridden per role — see Student/Faculty/Technician/Admin. */
    public abstract boolean canApprove(Booking booking);

    /** Default weekly quota in minutes for this role. STUDENT is quota-limited; the rest are not (see subclasses). */
    public abstract int weeklyQuotaMinutes();

    // --- encapsulated accessors ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) {
        if (email == null || !email.contains("@")) throw new IllegalArgumentException("Invalid email");
        this.email = email;
    }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Role getRole() { return role; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /**
     * Factory: builds the correct concrete subtype from a role read out of
     * the database. Centralises the role->class mapping in one place instead
     * of scattering it through DAOs — see dao.UserDao#mapRow.
     */
    public static User forRole(Role role) {
        return switch (role) {
            case STUDENT -> new Student();
            case FACULTY -> new Faculty();
            case TECHNICIAN -> new Technician();
            case ADMIN -> new Admin();
        };
    }
}
