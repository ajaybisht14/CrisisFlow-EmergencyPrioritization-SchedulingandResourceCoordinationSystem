import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Allocation {

    private static final int TIMEOUT_MINUTES = 10;
    private static ScheduledExecutorService scheduler;
    public static synchronized void startAutoReleaseScheduler() {

        if(scheduler != null&& !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r,"CrisisFlow-Resource-Release");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                Allocation::releaseExpiredResources,
                30,
                30,
                TimeUnit.SECONDS
        );
        System.out.println(
                "Automatic resource release enabled: "
                + TIMEOUT_MINUTES + "-minute timeout."
        );
    }
    // Assign one available resource to an emergency
    public static boolean assignResource(int emergencyId, int typeId) {

        String findResource =
                "SELECT resource_id FROM resources "
                + "WHERE type_id = ? AND status = 'Available' "
                + "ORDER BY resource_id LIMIT 1 FOR UPDATE";

        String insertAllocation =
                "INSERT INTO allocations "
                + "(emergency_id, resource_id, status) "
                + "VALUES (?, ?, 'Active')";

        String updateResource =
                "UPDATE resources SET status = 'Reserved' "
                + "WHERE resource_id = ? AND status = 'Available'";

        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);

            try (
                PreparedStatement find = con.prepareStatement(findResource);
                PreparedStatement insert = con.prepareStatement(insertAllocation);
                PreparedStatement update = con.prepareStatement(updateResource)
            ) {
                find.setInt(1, typeId);

                int resourceId;

                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        con.rollback();
                        System.out.println(
                                "No available resource found for type ID "
                                + typeId
                        );
                        return false;
                    }

                    resourceId = rs.getInt("resource_id");
                }

                insert.setInt(1, emergencyId);
                insert.setInt(2, resourceId);
                insert.executeUpdate();

                update.setInt(1, resourceId);

                if (update.executeUpdate() != 1) {
                    con.rollback();
                    System.out.println(
                            "Resource availability changed. Try again."
                    );
                    return false;
                }

                con.commit();

                System.out.println(
                        "Resource " + resourceId
                        + " assigned to emergency " + emergencyId
                );

                return true;

            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            System.out.println("Allocation failed: " + e.getMessage());
            return false;
        }
    }

    // Automatically allocate resources using predefined rules
    public static void allocateAutomatically(int emergencyId) {

        String checkSql =
                "SELECT allocation_status FROM emergencies "
                + "WHERE emergency_id = ?";

        try (Connection con = Database.getConnection();
             PreparedStatement check = con.prepareStatement(checkSql)) {

            check.setInt(1, emergencyId);

            try (ResultSet rs = check.executeQuery()) {

                if (!rs.next()) {
                    System.out.println("Emergency ID not found.");
                    return;
                }

                String status = rs.getString("allocation_status");

                if ("Allocated".equalsIgnoreCase(status)
                        || "Partially Allocated".equalsIgnoreCase(status)) {

                    System.out.println(
                            "This emergency has already been allocated."
                    );
                    System.out.println("Current status: " + status);
                    return;
                }
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not check allocation status: " + e.getMessage()
            );
            return;
        }

        String sql =
                "SELECT disaster_type, people_affected "
                + "FROM emergencies WHERE emergency_id = ?";

        String updateStatus =
                "UPDATE emergencies SET allocation_status = ? "
                + "WHERE emergency_id = ?";

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, emergencyId);

            String emergencyType;
            int peopleAffected;

            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    System.out.println("Emergency ID not found.");
                    return;
                }

                emergencyType = rs.getString("disaster_type");
                peopleAffected = rs.getInt("people_affected");
            }

            List<Integer> requiredTypes =
                    Rules.getRequiredResourceTypes(
                            emergencyType, peopleAffected
                    );

            int assigned = 0;
            int shortages = 0;

            System.out.println(
                    "\nStarting automatic resource allocation..."
            );

            for (int typeId : requiredTypes) {

                System.out.println(
                        "Checking resource type ID " + typeId
                );

                if (assignResource(emergencyId, typeId)) {
                    assigned++;
                } else {
                    shortages++;
                    System.out.println(
                            "Shortage detected for resource type ID "
                            + typeId
                    );
                }
            }

            String status;

            if (shortages == 0) {
                status = "Allocated";
            } else if (assigned > 0) {
                status = "Partially Allocated";
            } else {
                status = "Pending";
            }
            try (Connection statusCon = Database.getConnection();
                 PreparedStatement update =
                         statusCon.prepareStatement(updateStatus)) {

                update.setString(1, status);
                update.setInt(2, emergencyId);
                update.executeUpdate();
            }

            System.out.println("\nAutomatic allocation finished.");
            System.out.println("Resources assigned: " + assigned);
            System.out.println("Resource shortages: " + shortages);
            System.out.println("Allocation status: " + status);

        } catch (SQLException e) {
            System.out.println(
                    "Automatic allocation failed: " + e.getMessage()
            );
        }
    }

    // Release expired allocations automatically
    private static void releaseExpiredResources() {

        String releaseResourcesSql =
                "UPDATE resources r "
                + "JOIN allocations a ON r.resource_id = a.resource_id "
                + "SET r.status = 'Available' "
                + "WHERE a.status = 'Active' "
                + "AND a.assigned_at <= DATE_SUB(CURRENT_TIMESTAMP, "
                + "INTERVAL " + TIMEOUT_MINUTES + " MINUTE) "
                + "AND r.status IN ('Reserved', 'Deployed')";

        String releaseAllocationsSql =
                "UPDATE allocations "
                + "SET status = 'Released', "
                + "released_at = CURRENT_TIMESTAMP "
                + "WHERE status = 'Active' "
                + "AND assigned_at <= DATE_SUB(CURRENT_TIMESTAMP, "
                + "INTERVAL " + TIMEOUT_MINUTES + " MINUTE)";

        String updateEmergencySql =
                "UPDATE emergencies e "
                + "SET e.allocation_status = 'Pending' "
                + "WHERE e.response_status NOT IN ('Resolved', 'Cancelled') "
                + "AND e.allocation_status IN "
                + "('Allocated', 'Partially Allocated') "
                + "AND NOT EXISTS ("
                + "SELECT 1 FROM allocations a "
                + "WHERE a.emergency_id = e.emergency_id "
                + "AND a.status = 'Active')";

        try (Connection con = Database.getConnection()) {

            con.setAutoCommit(false);

            try {
                int resourcesReleased;
                int allocationsReleased;

                try (PreparedStatement ps =
                             con.prepareStatement(releaseResourcesSql)) {
                    resourcesReleased = ps.executeUpdate();
                }

                try (PreparedStatement ps =
                             con.prepareStatement(releaseAllocationsSql)) {
                    allocationsReleased = ps.executeUpdate();
                }

                try (PreparedStatement ps =
                             con.prepareStatement(updateEmergencySql)) {
                    ps.executeUpdate();
                }

                con.commit();

                if (allocationsReleased > 0) {
                    System.out.println(
                            "\n[Automatic Release] Expired allocations: "
                            + allocationsReleased
                    );
                    System.out.println(
                            "Resources made available: " + resourcesReleased
                    );
                }

            } catch (SQLException e) {
                con.rollback();
                System.err.println(
                        "Automatic release failed: " + e.getMessage()
                );
            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            System.err.println(
                    "Automatic release could not connect to the database: "
                    + e.getMessage()
            );
        }
    }

    // Manually release all active resources for an emergency
    public static void releaseResources(int emergencyId) {

        String resourceSql =
                "UPDATE resources r "
                + "JOIN allocations a ON r.resource_id = a.resource_id "
                + "SET r.status = 'Available' "
                + "WHERE a.emergency_id = ? "
                + "AND a.status = 'Active' "
                + "AND r.status IN ('Reserved', 'Deployed')";

        String allocationSql =
                "UPDATE allocations "
                + "SET status = 'Released', "
                + "released_at = CURRENT_TIMESTAMP "
                + "WHERE emergency_id = ? AND status = 'Active'";

        try (Connection con = Database.getConnection()) {

            con.setAutoCommit(false);

            try {
                int resourcesReleased;
                int allocationsReleased;

                try (PreparedStatement ps =
                             con.prepareStatement(resourceSql)) {
                    ps.setInt(1, emergencyId);
                    resourcesReleased = ps.executeUpdate();
                }

                try (PreparedStatement ps =
                             con.prepareStatement(allocationSql)) {
                    ps.setInt(1, emergencyId);
                    allocationsReleased = ps.executeUpdate();
                }

                con.commit();

                System.out.println(
                        "Allocations released: " + allocationsReleased
                );
                System.out.println(
                        "Resources made available: " + resourcesReleased
                );

            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            System.out.println(
                    "Could not release resources: " + e.getMessage()
            );
        }
    }
    
public static void allocatePendingAutomatically() {

    String sql =
            "SELECT emergency_id FROM emergencies "
            + "WHERE allocation_status = 'Pending' "
            + "AND response_status IN ('Reported', 'Responding') "
            + "ORDER BY CASE priority "
            + "WHEN 'CRITICAL' THEN 1 "
            + "WHEN 'HIGH' THEN 2 "
            + "WHEN 'MEDIUM' THEN 3 "
            + "ELSE 4 END, reported_at ASC, emergency_id ASC";

    java.util.List<Integer> pendingIds = new java.util.ArrayList<>();

    try (Connection con = Database.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        while (rs.next()) {
            pendingIds.add(rs.getInt("emergency_id"));
        }

    } catch (SQLException e) {
        System.out.println("Could not retrieve pending emergencies: "
                + e.getMessage());
        return;
    }

    if (pendingIds.isEmpty()) {
        System.out.println("No pending emergencies need allocation.");
        return;
    }

    for (int emergencyId : pendingIds) {
        System.out.println("\nProcessing pending emergency...");
        allocateAutomatically(emergencyId);
    }
}

}
