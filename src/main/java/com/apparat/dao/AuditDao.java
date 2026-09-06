package com.apparat.dao;

import com.apparat.model.AuditEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AuditDao extends BaseDao {

    public void write(Connection con, AuditEntry entry) throws SQLException {
        String sql = "INSERT INTO audit_log (actor_id, action, entity_type, entity_id, details) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            if (entry.getActorId() != null) ps.setLong(1, entry.getActorId()); else ps.setNull(1, java.sql.Types.BIGINT);
            ps.setString(2, entry.getAction());
            ps.setString(3, entry.getEntityType());
            if (entry.getEntityId() != null) ps.setLong(4, entry.getEntityId()); else ps.setNull(4, java.sql.Types.BIGINT);
            ps.setString(5, entry.getDetails());
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> recent(int limit) {
        return withConnection(con -> {
            List<AuditEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ?";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) entries.add(mapRow(rs));
                }
            }
            return entries;
        });
    }

    private AuditEntry mapRow(ResultSet rs) throws SQLException {
        long actorId = rs.getLong("actor_id");
        AuditEntry e = AuditEntry.of(rs.wasNull() ? null : actorId, rs.getString("action"),
                rs.getString("entity_type"), rs.getLong("entity_id"), rs.getString("details"));
        e.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return e;
    }
}
