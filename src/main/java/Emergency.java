import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Emergency {

    // Register a new emergency
    public static boolean register(
            String type,
            String location,
            int peopleAffected,
            String description) {

        String priority = Rules.calculatePriority(type, peopleAffected);

        String sql = "INSERT INTO emergencies "
                + "(disaster_type, location, people_affected, description, "
                + "priority, allocation_status, response_status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (
            Connection con = Database.getConnection();
            PreparedStatement ps = con.prepareStatement(sql)
        ) {
            ps.setString(1, type);
            ps.setString(2, location);
            ps.setInt(3, peopleAffected);
            ps.setString(4, description);
            ps.setString(5, priority);
            ps.setString(6, "Pending");
            ps.setString(7, "Reported");

            int rows = ps.executeUpdate();

            if (rows > 0) {
                System.out.println("Emergency registered successfully!");
                System.out.println("Calculated priority: " + priority);
                return true;
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not register emergency: " + e.getMessage()
            );
        }

        return false;
    }

    // Display all registered emergencies
    public static void viewEmergencies() {

        String sql = "SELECT emergency_id, disaster_type, location, "
                + "people_affected, priority, allocation_status, "
                + "response_status, reported_at "
                + "FROM emergencies ORDER BY "
                + "CASE priority "
                + "WHEN 'CRITICAL' THEN 1 "
                + "WHEN 'HIGH' THEN 2 "
                + "WHEN 'MEDIUM' THEN 3 "
                + "ELSE 4 END, reported_at";

        try (
            Connection con = Database.getConnection();
            PreparedStatement ps = con.prepareStatement(sql);
            ResultSet rs = ps.executeQuery()
        ) {
            System.out.println("\n========== EMERGENCY RECORDS ==========");

            boolean found = false;

            while (rs.next()) {
                found = true;

                System.out.println("\n------------------------------");
                System.out.println("Emergency ID: "
                        + rs.getInt("emergency_id"));
                System.out.println("Type: "
                        + rs.getString("disaster_type"));
                System.out.println("Location: "
                        + rs.getString("location"));
                System.out.println("People Affected: "
                        + rs.getInt("people_affected"));
                System.out.println("Priority: "
                        + rs.getString("priority"));
                System.out.println("Allocation: "
                        + rs.getString("allocation_status"));
                System.out.println("Response: "
                        + rs.getString("response_status"));
                System.out.println("Reported At: "
                        + rs.getTimestamp("reported_at"));
            }

            if (!found) {
                System.out.println("No emergencies registered yet.");
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not retrieve emergencies: " + e.getMessage()
            );
        }
    }

    // Update response status and record the change in history
    public static void updateResponseStatus(
            int emergencyId,
            String newStatus,
            String notes) {

        if (newStatus == null
                || !(newStatus.equalsIgnoreCase("Reported")
                || newStatus.equalsIgnoreCase("Responding")
                || newStatus.equalsIgnoreCase("Resolved")
                || newStatus.equalsIgnoreCase("Cancelled"))) {

            System.out.println(
                    "Invalid status. Use Reported, Responding, "
                    + "Resolved, or Cancelled."
            );
            return;
        }

        String selectSql =
                "SELECT response_status FROM emergencies "
                + "WHERE emergency_id = ?";

        String updateSql =
                "UPDATE emergencies SET response_status = ?, "
                + "allocation_status = CASE "
                + "WHEN ? = 'Resolved' OR ? = 'Cancelled' "
                + "THEN 'Completed' ELSE allocation_status END "
                + "WHERE emergency_id = ?";

        String historySql =
                "INSERT INTO emergency_history "
                + "(emergency_id, old_status, new_status, notes) "
                + "VALUES (?, ?, ?, ?)";

        try (Connection con = Database.getConnection()) {

            con.setAutoCommit(false);

            try {
                String oldStatus;

                try (PreparedStatement ps =
                             con.prepareStatement(selectSql)) {

                    ps.setInt(1, emergencyId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            System.out.println("Emergency ID not found.");
                            con.rollback();
                            return;
                        }

                        oldStatus = rs.getString("response_status");
                    }
                }

                String normalizedStatus =
                        newStatus.substring(0, 1).toUpperCase()
                        + newStatus.substring(1).toLowerCase();

                try (PreparedStatement ps =
                             con.prepareStatement(updateSql)) {

                    ps.setString(1, normalizedStatus);
                    ps.setString(2, normalizedStatus);
                    ps.setString(3, normalizedStatus);
                    ps.setInt(4, emergencyId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps =
                             con.prepareStatement(historySql)) {

                    ps.setInt(1, emergencyId);
                    ps.setString(2, oldStatus);
                    ps.setString(3, normalizedStatus);
                    ps.setString(4, notes == null ? "" : notes);
                    ps.executeUpdate();
                }

                con.commit();

            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not update emergency status: " + e.getMessage()
            );
            return;
        }

        System.out.println("Emergency response status updated successfully.");

        if (newStatus.equalsIgnoreCase("Resolved")
                || newStatus.equalsIgnoreCase("Cancelled")) {
            Allocation.releaseResources(emergencyId);
        }
    }
}