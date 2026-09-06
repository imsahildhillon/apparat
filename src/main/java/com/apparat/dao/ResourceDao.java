package com.apparat.dao;

import com.apparat.model.Instrument;
import com.apparat.model.Resource;
import com.apparat.model.enums.ResourceStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ResourceDao extends BaseDao implements Dao<Resource, Long> {

    @Override
    public Optional<Resource> findById(Long id) {
        return withConnection(con -> {
            String sql = "SELECT * FROM resources WHERE id = ?";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Locks the resource row for the duration of the caller's transaction —
     * layer 2 of the three-layer concurrency defence
     * (PROJECT_BLUEPRINT_CORRECTED.md §18.3). Only ever called from inside a
     * transaction the SERVICE owns (con.setAutoCommit(false) already called
     * by the caller); this method never commits or closes con.
     */
    public Resource findForUpdate(Connection con, Long id) throws SQLException {
        String sql = "SELECT * FROM resources WHERE id = ? FOR UPDATE";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new com.apparat.exception.ResourceNotFoundException("Resource", id);
                return mapRow(rs);
            }
        }
    }

    @Override
    public List<Resource> findAll() {
        return withConnection(con -> {
            List<Resource> resources = new ArrayList<>();
            String sql = "SELECT * FROM resources ORDER BY name";
            try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) resources.add(mapRow(rs));
            }
            return resources;
        });
    }

    public List<Resource> search(String category, ResourceStatus status, String query) {
        return withConnection(con -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM resources WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (category != null && !category.isBlank()) {
                sql.append(" AND category = ?");
                params.add(category);
            }
            if (status != null) {
                sql.append(" AND status = ?");
                params.add(status.name());
            }
            if (query != null && !query.isBlank()) {
                sql.append(" AND (name LIKE ? OR code LIKE ?)");
                params.add("%" + query + "%");
                params.add("%" + query + "%");
            }
            sql.append(" ORDER BY name");
            List<Resource> resources = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) resources.add(mapRow(rs));
                }
            }
            return resources;
        });
    }

    public void updateStatus(Long resourceId, ResourceStatus status) {
        withConnection(con -> {
            String sql = "UPDATE resources SET status = ? WHERE id = ?";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, status.name());
                ps.setLong(2, resourceId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Long insert(Resource resource) {
        return withConnection(con -> {
            String sql = "INSERT INTO resources (code, name, resource_type, category, location, description, "
                    + "custodian_id, status, open_time, close_time, slot_minutes, min_slot_minutes, "
                    + "max_slot_minutes, buffer_minutes, requires_certification, requires_approval) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, resource.getCode());
                ps.setString(2, resource.getName());
                ps.setString(3, "INSTRUMENT");
                ps.setString(4, resource.getCategory());
                ps.setString(5, resource.getLocation());
                ps.setString(6, resource.getDescription());
                if (resource.getCustodianId() != null) ps.setLong(7, resource.getCustodianId()); else ps.setNull(7, java.sql.Types.BIGINT);
                ps.setString(8, resource.getStatus().name());
                ps.setObject(9, resource.getOpenTime());
                ps.setObject(10, resource.getCloseTime());
                ps.setInt(11, resource.getSlotMinutes());
                ps.setInt(12, resource.getMinSlotMinutes());
                ps.setInt(13, resource.getMaxSlotMinutes());
                ps.setInt(14, resource.getBufferMinutes());
                ps.setBoolean(15, resource.isRequiresCertification());
                ps.setBoolean(16, resource.isRequiresApproval());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Only Instrument exists as a concrete subtype in this MVP — see model.Instrument's Javadoc. Ready to switch on resource_type once a second subtype is added. */
    private Resource mapRow(ResultSet rs) throws SQLException {
        Resource resource = new Instrument();
        resource.setId(rs.getLong("id"));
        resource.setCode(rs.getString("code"));
        resource.setName(rs.getString("name"));
        resource.setCategory(rs.getString("category"));
        resource.setLocation(rs.getString("location"));
        resource.setDescription(rs.getString("description"));
        long custodianId = rs.getLong("custodian_id");
        resource.setCustodianId(rs.wasNull() ? null : custodianId);
        resource.setStatus(ResourceStatus.valueOf(rs.getString("status")));
        resource.setOpenTime(rs.getObject("open_time", LocalTime.class));
        resource.setCloseTime(rs.getObject("close_time", LocalTime.class));
        resource.setSlotMinutes(rs.getInt("slot_minutes"));
        resource.setMinSlotMinutes(rs.getInt("min_slot_minutes"));
        resource.setMaxSlotMinutes(rs.getInt("max_slot_minutes"));
        resource.setBufferMinutes(rs.getInt("buffer_minutes"));
        resource.setRequiresCertification(rs.getBoolean("requires_certification"));
        resource.setRequiresApproval(rs.getBoolean("requires_approval"));
        return resource;
    }
}
