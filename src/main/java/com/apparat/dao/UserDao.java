package com.apparat.dao;

import com.apparat.model.User;
import com.apparat.model.enums.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserDao extends BaseDao implements Dao<User, Long> {

    @Override
    public Optional<User> findById(Long id) {
        return withConnection(con -> findById(con, id));
    }

    public Optional<User> findById(Connection con, Long id) throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<User> findByEmail(String email) {
        return withConnection(con -> {
            String sql = "SELECT * FROM users WHERE email = ? AND active = 1";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<User> findAll() {
        return withConnection(con -> {
            List<User> users = new ArrayList<>();
            String sql = "SELECT * FROM users ORDER BY full_name";
            try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) users.add(mapRow(rs));
            }
            return users;
        });
    }

    public List<User> findByRole(Role role) {
        return withConnection(con -> {
            List<User> users = new ArrayList<>();
            String sql = "SELECT * FROM users WHERE role = ? ORDER BY full_name";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, role.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) users.add(mapRow(rs));
                }
            }
            return users;
        });
    }

    public Long insert(User user) {
        return withConnection(con -> insert(con, user));
    }

    public Long insert(Connection con, User user) throws SQLException {
        String sql = "INSERT INTO users (email, password_hash, full_name, role, department, active) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getEmail());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getFullName());
            ps.setString(4, user.getRole().name());
            ps.setString(5, user.getDepartment());
            ps.setBoolean(6, user.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Factory maps the ENUM column straight into the correct concrete
     * subtype via {@link User#forRole} — this is the one place a raw string
     * from the database becomes a polymorphic Java object; every caller
     * above this line works with User/Student/Faculty/Technician/Admin, never a role string.
     */
    private User mapRow(ResultSet rs) throws SQLException {
        Role role = Role.valueOf(rs.getString("role"));
        User user = User.forRole(role);
        user.setId(rs.getLong("id"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.setDepartment(rs.getString("department"));
        user.setActive(rs.getBoolean("active"));
        return user;
    }
}
