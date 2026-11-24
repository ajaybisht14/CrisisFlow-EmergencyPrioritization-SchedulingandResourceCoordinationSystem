package com.crisisflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Resource {

    public static int addResource(
            String name,
            int typeId,
            int capacity,
            String location,
            double latitude,
            double longitude) throws SQLException {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource name is required.");
        }

        if (capacity <= 0 || capacity > 100_000) {
            throw new IllegalArgumentException(
                "Capacity must be between 1 and 100000.");
        }

        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Location is required.");
        }

        if (!Rules.isValidCoordinate(latitude, longitude)) {
            throw new IllegalArgumentException("Invalid coordinates.");
        }

        String sql = """
            INSERT INTO resources
            (resource_name, type_id, capacity, location,
             latitude, longitude, status)
            VALUES (?, ?, ?, ?, ?, ?, 'Available')
            """;

        try (Connection con = Database.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT 1 FROM resource_types WHERE type_id = ?")) {
                ps.setInt(1, typeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Invalid resource type.");
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement(sql,
                     PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, name.trim());
                ps.setInt(2, typeId);
                ps.setInt(3, capacity);
                ps.setString(4, location.trim());
                ps.setDouble(5, latitude);
                ps.setDouble(6, longitude);

                ps.executeUpdate();

                try (var keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
        }

        throw new SQLException("Could not add resource.");
    }

    /** Mark a resource Available / Unavailable (never while it is Reserved). */
    public static void setStatus(int resourceId, String status)
            throws SQLException {

        if (!List.of("Available", "Unavailable").contains(status)) {
            throw new IllegalArgumentException("Invalid resource status.");
        }

        String sql = """
            UPDATE resources SET status = ?
            WHERE resource_id = ? AND is_deleted = 0
              AND status <> 'Reserved'
            """;

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, resourceId);
            if (ps.executeUpdate() == 0) {
                throw new ApiException(409,
                    "Resource not found or currently reserved.");
            }
        }
    }

    /**
     * Soft-delete a resource.
     *
     * If it is assigned to an emergency this fails with HTTP 409 unless
     * {@code force} is true; then the assignment is released and the
     * emergency goes back to the allocation queue.
     *
     * @return ids of emergencies that lost this resource (to be re-served)
     */
    public static List<Integer> delete(int resourceId, boolean force)
            throws SQLException {

        Allocation.LOCK.lock();
        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement("""
                        SELECT resource_id FROM resources
                        WHERE resource_id = ? AND is_deleted = 0
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, resourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ApiException(404, "Resource not found.");
                        }
                    }
                }

                Set<Integer> affected = new LinkedHashSet<>();
                try (PreparedStatement ps = con.prepareStatement("""
                        SELECT emergency_id FROM allocations
                        WHERE resource_id = ? AND status = 'Active'
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, resourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            affected.add(rs.getInt(1));
                        }
                    }
                }

                if (!affected.isEmpty() && !force) {
                    throw new ApiException(409,
                        "Resource is assigned to emergency #"
                        + affected.iterator().next()
                        + (affected.size() > 1 ? " (and others)" : "")
                        + ". Delete anyway to release it and reassign.",
                        Map.of("emergencyIds", new ArrayList<>(affected),
                               "canForce", true));
                }

                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE allocations
                        SET status = 'Released', released_at = CURRENT_TIMESTAMP
                        WHERE resource_id = ? AND status = 'Active'
                        """)) {
                    ps.setInt(1, resourceId);
                    ps.executeUpdate();
                }

                // Emergencies that lost this unit: Partial if they still hold
                // others, otherwise back to Pending.
                for (int emergencyId : affected) {
                    try (PreparedStatement ps = con.prepareStatement("""
                            UPDATE emergencies
                            SET allocation_status = CASE WHEN EXISTS (
                                SELECT 1 FROM allocations
                                WHERE emergency_id = ? AND status = 'Active')
                                THEN 'Partial' ELSE 'Pending' END
                            WHERE emergency_id = ?
                            """)) {
                        ps.setInt(1, emergencyId);
                        ps.setInt(2, emergencyId);
                        ps.executeUpdate();
                    }
                }

                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE resources
                        SET is_deleted = 1, deleted_at = CURRENT_TIMESTAMP,
                            status = 'Unavailable'
                        WHERE resource_id = ?
                        """)) {
                    ps.setInt(1, resourceId);
                    ps.executeUpdate();
                }

                con.commit();
                return new ArrayList<>(affected);
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } finally {
            Allocation.LOCK.unlock();
        }
    }
}
