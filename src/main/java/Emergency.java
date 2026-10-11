
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;

public class Emergency {

    // Register a new emergency
    public static boolean register(
            String type,
            String location,
            int peopleAffected,
            String description,
            Scanner sc) {

        String normalizedType = Rules.normalizeEmergencyType(type);

        if (normalizedType == null) {
            System.out.println("Invalid emergency type.");
            return false;
        }

        if (location == null || location.trim().isEmpty()
                || peopleAffected <= 0) {
            System.out.println("Invalid emergency details.");
            return false;
        }

        String priority =
                Rules.calculatePriority(normalizedType, peopleAffected);

        String sql = "INSERT INTO emergencies "
                + "(disaster_type, location, people_affected, description, "
                + "priority, allocation_status, response_status) "
                + "VALUES (?, ?, ?, ?, ?, 'Pending', 'Reported')";

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, normalizedType);
            ps.setString(2, location.trim());
            ps.setInt(3, peopleAffected);
            ps.setString(4,
                    description == null ? "" : description.trim());
            ps.setString(5, priority);

            if (ps.executeUpdate() == 0) {
                System.out.println("Emergency registration failed.");
                return false;
            }

            int emergencyId;

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    System.out.println(
                            "Emergency saved, but ID was not returned.");
                    return false;
                }

                emergencyId = keys.getInt(1);
            }

            System.out.println("\nEmergency registered successfully!");
            System.out.println("Emergency ID: " + emergencyId);
            System.out.println("Calculated priority: " + priority);

            String answer;

            while (true) {
                System.out.print("Allocate resources now? (Y/N): ");
                answer = sc.nextLine().trim();

                if (answer.equalsIgnoreCase("Y")
                        || answer.equalsIgnoreCase("N")) {
                    break;
                }

                System.out.println("Please enter Y or N.");
            }

            if (answer.equalsIgnoreCase("Y")) {
                Allocation.allocateAutomatically(emergencyId);
            } else {
                System.out.println(
                        "Emergency saved. Resource allocation is pending.");
            }

            return true;

        } catch (SQLException e) {
            System.out.println("Could not register emergency: "
                    + e.getMessage());
            return false;
        }
    }

    // View all emergencies
    public static void viewEmergencies() {

        String sql = "SELECT emergency_id, disaster_type, location, "
                + "people_affected, priority, allocation_status, "
                + "response_status, reported_at "
                + "FROM emergencies ORDER BY reported_at DESC";

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("\n========== EMERGENCY LIST ==========");

            boolean found = false;

            while (rs.next()) {
                found = true;

                System.out.println("------------------------------------");
                System.out.println("ID: " + rs.getInt("emergency_id"));
                System.out.println("Type: " + rs.getString("disaster_type"));
                System.out.println("Location: " + rs.getString("location"));
                System.out.println("People affected: "
                        + rs.getInt("people_affected"));
                System.out.println("Priority: " + rs.getString("priority"));
                System.out.println("Allocation: "
                        + rs.getString("allocation_status"));
                System.out.println("Response status: "
                        + rs.getString("response_status"));
                System.out.println("Reported at: "
                        + rs.getTimestamp("reported_at"));
            }

            if (!found) {
                System.out.println("No emergencies found.");
            }

            System.out.println("====================================");

        } catch (SQLException e) {
            System.out.println("Could not retrieve emergencies: "
                    + e.getMessage());
        }
    }

    // Update an emergency's response status
    public static boolean updateResponseStatus(
            int emergencyId,
            String newStatus,
            String remarks) {

        if (emergencyId <= 0 || newStatus == null) {
            System.out.println("Invalid emergency details.");
            return false;
        }

        newStatus = newStatus.trim();

        if (!newStatus.equalsIgnoreCase("Reported")
                && !newStatus.equalsIgnoreCase("Responding")
                && !newStatus.equalsIgnoreCase("Resolved")
                && !newStatus.equalsIgnoreCase("Cancelled")) {
            System.out.println("Invalid response status.");
            return false;
        }

        newStatus = normalizeStatus(newStatus);

        String selectSql =
                "SELECT response_status FROM emergencies "
                + "WHERE emergency_id = ? FOR UPDATE";

        String updateSql =
                "UPDATE emergencies SET response_status = ? "
                + "WHERE emergency_id = ?";

        String historySql =
                "INSERT INTO emergency_history "
                + "(emergency_id, status, remarks) VALUES (?, ?, ?)";

        try (Connection con = Database.getConnection()) {

            con.setAutoCommit(false);

            try {
                String oldStatus;

                try (PreparedStatement ps =
                             con.prepareStatement(selectSql)) {

                    ps.setInt(1, emergencyId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            System.out.println("Emergency not found.");
                            con.rollback();
                            return false;
                        }

                        oldStatus = rs.getString("response_status");
                    }
                }

                if (oldStatus.equalsIgnoreCase("Resolved")
                        || oldStatus.equalsIgnoreCase("Cancelled")) {
                    System.out.println(
                            "This emergency is already closed.");
                    con.rollback();
                    return false;
                }

                try (PreparedStatement ps =
                             con.prepareStatement(updateSql)) {

                    ps.setString(1, newStatus);
                    ps.setInt(2, emergencyId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps =
                             con.prepareStatement(historySql)) {

                    ps.setInt(1, emergencyId);
                    ps.setString(2, newStatus);
                    ps.setString(3,
                            remarks == null ? "" : remarks.trim());
                    ps.executeUpdate();
                }

                con.commit();

            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }

            if (newStatus.equals("Resolved")
                    || newStatus.equals("Cancelled")) {
                Allocation.releaseResources(emergencyId);
            }

            System.out.println("Emergency status updated successfully.");
            return true;

        } catch (SQLException e) {
            System.out.println("Could not update emergency status: "
                    + e.getMessage());
            return false;
        }
    }

    private static String normalizeStatus(String status) {
        if (status.equalsIgnoreCase("Reported")) {
            return "Reported";
        }
        if (status.equalsIgnoreCase("Responding")) {
            return "Responding";
        }
        if (status.equalsIgnoreCase("Resolved")) {
            return "Resolved";
        }
        if (status.equalsIgnoreCase("Cancelled")) {
            return "Cancelled";
        }
        return status;
    }
}
