import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Resource {

    // Register a new resource
    public static void registerResource(
            String name,
            int typeId,
            int capacity,
            String location) {

        String sql = "INSERT INTO resources "
                + "(resource_name, type_id, capacity, location, status) "
                + "VALUES (?, ?, ?, ?, 'Available')";

        if (name == null || name.trim().isEmpty()
                || location == null || location.trim().isEmpty()
                || capacity <= 0) {
            System.out.println("Invalid resource details.");
            return;
        }

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, name.trim());
            ps.setInt(2, typeId);
            ps.setInt(3, capacity);
            ps.setString(4, location.trim());

            if (ps.executeUpdate() > 0) {
                System.out.println("Resource registered successfully!");
                System.out.println("Initial status: Available");
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not register resource: " + e.getMessage()
            );
        }
    }

    // Display all registered resources
    public static void viewResources() {

        String sql = "SELECT r.resource_id, r.resource_name, "
                + "rt.type_name, r.capacity, r.location, r.status "
                + "FROM resources r "
                + "JOIN resource_types rt ON r.type_id = rt.type_id "
                + "ORDER BY r.resource_id";

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("\n========== RESOURCE INVENTORY ===========");

            boolean found = false;

            while (rs.next()) {
                found = true;

                System.out.println("-------------------------------");
                System.out.println(
                        "Resource ID: " + rs.getInt("resource_id")
                );
                System.out.println(
                        "Name: " + rs.getString("resource_name")
                );
                System.out.println(
                        "Type: " + rs.getString("type_name")
                );
                System.out.println(
                        "Capacity: " + rs.getInt("capacity")
                );
                System.out.println(
                        "Location: " + rs.getString("location")
                );
                System.out.println(
                        "Status: " + rs.getString("status")
                );
            }

            if (!found) {
                System.out.println("No resources registered yet.");
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not retrieve resources: " + e.getMessage()
            );
        }
    }

    // Update resource status
    public static void updateResourceStatus(
            int resourceId,
            String newStatus) {

        if (newStatus == null
                || !(newStatus.equalsIgnoreCase("Available")
                || newStatus.equalsIgnoreCase("Out of Service"))) {

            System.out.println(
                    "Manual status changes are allowed only to "
                    + "'Available' or 'Out of Service'."
            );
            System.out.println(
                    "Reserved and Deployed statuses are managed by the system."
            );
            return;
        }

        String checkSql =
                "SELECT status FROM resources WHERE resource_id = ?";

        String updateSql =
                "UPDATE resources SET status = ? "
                + "WHERE resource_id = ?";

        try (Connection con = Database.getConnection()) {

            con.setAutoCommit(false);

            try {
                String currentStatus;

                try (PreparedStatement check =
                             con.prepareStatement(checkSql)) {

                    check.setInt(1, resourceId);

                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            System.out.println("Resource ID not found.");
                            con.rollback();
                            return;
                        }

                        currentStatus = rs.getString("status");
                    }
                }

                if ("Reserved".equalsIgnoreCase(currentStatus)
                        || "Deployed".equalsIgnoreCase(currentStatus)) {
                    System.out.println(
                            "Cannot manually change a reserved or deployed resource."
                    );
                    con.rollback();
                    return;
                }

                try (PreparedStatement update =
                             con.prepareStatement(updateSql)) {

                    update.setString(
                            1,
                            newStatus.equalsIgnoreCase("Available")
                                    ? "Available" : "Out of Service"
                    );
                    update.setInt(2, resourceId);
                    update.executeUpdate();
                }

                con.commit();
                System.out.println("Resource status updated successfully.");

            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not update resource status: " + e.getMessage()
            );
        }
    }
}
