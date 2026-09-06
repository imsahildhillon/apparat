package com.apparat.dao;

import com.apparat.model.MaintenanceWindow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MaintenanceDao extends BaseDao {

    public List<MaintenanceWindow> findByResource(Long resourceId) {
        return withConnection(con -> {
            List<MaintenanceWindow> windows = new ArrayList<>();
            String sql = "SELECT * FROM maintenance_windows WHERE resource_id = ? ORDER BY start_at DESC";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, resourceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) windows.add(mapRow(rs));
                }
            }
            return windows;
        });
    }

    /** Used during booking validation to reject a request overlapping an active maintenance window — see service.BookingService step 7. */
    public boolean hasOverlap(Connection con, Long resourceId, LocalDateTime start, LocalDateTime end) throws SQLException {
        String sql = "SELECT 1 FROM maintenance_windows WHERE resource_id = ? AND start_at < ? AND end_at > ? LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, resourceId);
            ps.setObject(2, end);
            ps.setObject(3, start);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Long insert(MaintenanceWindow window) {
        return withConnection(con -> {
            String sql = "INSERT INTO maintenance_windows (resource_id, start_at, end_at, reason, created_by) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, window.getResourceId());
                ps.setObject(2, window.getStartAt());
                ps.setObject(3, window.getEndAt());
                ps.setString(4, window.getReason());
                ps.setLong(5, window.getCreatedBy());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    private MaintenanceWindow mapRow(ResultSet rs) throws SQLException {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setId(rs.getLong("id"));
        w.setResourceId(rs.getLong("resource_id"));
        w.setStartAt(rs.getObject("start_at", LocalDateTime.class));
        w.setEndAt(rs.getObject("end_at", LocalDateTime.class));
        w.setReason(rs.getString("reason"));
        w.setCreatedBy(rs.getLong("created_by"));
        w.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return w;
    }
}
