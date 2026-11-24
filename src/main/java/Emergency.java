package com.crisisflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

public class Emergency {

    private static final Set<String> CLOSED = Set.of("Resolved", "Cancelled");

    public static int register(
            String type,
            String location,
            double latitude,
            double longitude,
            int people,
            String description) throws SQLException {

        type = Rules.normalizeEmergencyType(type);

        if (!Rules.isValidEmergencyType(type)) {
            throw new IllegalArgumentException("Invalid emergency type.");
        }

        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Location is required.");
        }

        if (people <= 0 || people > Rules.MAX_PEOPLE) {
            throw new IllegalArgumentException(
                "People affected must be between 1 and " + Rules.MAX_PEOPLE + ".");
        }

        if (!Rules.isValidCoordinate(latitude, longitude)) {
            throw new IllegalArgumentException("Invalid coordinates.");
        }

        String priority = Rules.calculatePriority(type, people);

        String sql = """
            INSERT INTO emergencies
            (disaster_type, location, latitude, longitude,
             people_affected, description, priority,
             allocation_status, response_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'Pending', 'Reported')
            """;

        try (Connection con = Database.getConnection();
             PreparedStatement ps =
                 con.prepareStatement(sql,
                     PreparedStatement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, type);
            ps.setString(2, location.trim());
            ps.setDouble(3, latitude);
            ps.setDouble(4, longitude);
            ps.setInt(5, people);
            ps.setString(6, description == null ? "" : description.trim());
            ps.setString(7, priority);

            ps.executeUpdate();

            try (var keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }

        throw new SQLException("Could not register emergency.");
    }

    /**
     * Change the response status. Resolving or cancelling frees the
     * emergency's resources; reopening puts it back in the allocation queue.
     */
    public static void updateResponseStatus(
            int emergencyId, String status, String notes)
            throws SQLException {

        Set<String> allowed = Set.of(
            "Reported", "Responding", "Arrived", "Resolved", "Cancelled"
        );

        if (status == null || !allowed.contains(status)) {
            throw new IllegalArgumentException("Invalid response status.");
        }

        Allocation.LOCK.lock();
        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);
            try {
                String old;
                try (PreparedStatement ps = con.prepareStatement("""
                        SELECT response_status FROM emergencies
                        WHERE emergency_id = ? AND is_deleted = 0
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, emergencyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ApiException(404, "Emergency not found.");
                        }
                        old = rs.getString(1);
                    }
                }

                String lifecycleSql = "UPDATE emergencies SET response_status = ?, "
                    + "response_started_at = CASE WHEN ? = 'Responding' AND response_started_at IS NULL THEN CURRENT_TIMESTAMP ELSE response_started_at END, "
                    + "arrived_at = CASE WHEN ? = 'Arrived' AND arrived_at IS NULL THEN CURRENT_TIMESTAMP ELSE arrived_at END, "
                    + "resolved_at = CASE WHEN ? = 'Resolved' THEN CURRENT_TIMESTAMP ELSE resolved_at END, "
                    + "cancelled_at = CASE WHEN ? = 'Cancelled' THEN CURRENT_TIMESTAMP ELSE cancelled_at END "
                    + "WHERE emergency_id = ?";
                try (PreparedStatement ps = con.prepareStatement(lifecycleSql)) {
                    ps.setString(1, status); ps.setString(2, status); ps.setString(3, status);
                    ps.setString(4, status); ps.setString(5, status); ps.setInt(6, emergencyId);
                    ps.executeUpdate();
                }

                if (CLOSED.contains(status)) {
                    Allocation.releaseInTx(con, emergencyId);
                } else if (CLOSED.contains(old)) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE emergencies SET allocation_status = 'Pending' "
                            + "WHERE emergency_id = ?")) {
                        ps.setInt(1, emergencyId);
                        ps.executeUpdate();
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO status_history "
                        + "(emergency_id, old_status, new_status, note) "
                        + "VALUES (?, ?, ?, ?)")) {
                    ps.setInt(1, emergencyId);
                    ps.setString(2, old);
                    ps.setString(3, status);
                    ps.setString(4, notes == null || notes.isBlank()
                        ? null : notes.trim());
                    ps.executeUpdate();
                }

                con.commit();
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

    /** Soft-delete: frees its resources, keeps history. */
    public static void delete(int emergencyId) throws SQLException {
        Allocation.LOCK.lock();
        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement("""
                        SELECT emergency_id FROM emergencies
                        WHERE emergency_id = ? AND is_deleted = 0
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, emergencyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ApiException(404, "Emergency not found.");
                        }
                    }
                }

                Allocation.releaseInTx(con, emergencyId);

                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE emergencies
                        SET is_deleted = 1, deleted_at = CURRENT_TIMESTAMP
                        WHERE emergency_id = ?
                        """)) {
                    ps.setInt(1, emergencyId);
                    ps.executeUpdate();
                }
                con.commit();
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
