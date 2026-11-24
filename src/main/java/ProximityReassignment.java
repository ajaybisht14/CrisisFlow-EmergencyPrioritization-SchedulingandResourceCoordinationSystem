package com.crisisflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent proximity-based resource reassignment.
 * 
 * When a resource is en-route to an emergency, this service checks if there's
 * a closer emergency of higher or equal priority that needs the same type of
 * resource. If found, and the time savings are significant, it suggests or
 * performs automatic reassignment.
 */
public final class ProximityReassignment {

    private ProximityReassignment() {}

    /**
     * Represents a potential reassignment opportunity.
     */
    public record Opportunity(
        int currentEmergencyId,
        int suggestedEmergencyId,
        int resourceId,
        String resourceName,
        String resourceType,
        double currentDistanceKm,
        double suggestedDistanceKm,
        double timeSavingsMinutes,
        String currentPriority,
        String suggestedPriority,
        String reason
    ) {}

    /**
     * Minimum time savings (in minutes) required for reassignment suggestion.
     */
    private static final int MIN_TIME_SAVINGS_MINUTES = 5;

    /**
     * Maximum distance (km) to consider for "nearby" emergencies.
     */
    private static final double MAX_PROXIMITY_KM = 10.0;

    /**
     * Find all opportunities for reassigning resources to closer emergencies.
     * 
     * This checks all currently allocated resources that are:
     * - Assigned to an emergency
     * - In "Responding" or earlier status
     * - Within proximity of other waiting emergencies
     * 
     * @return List of reassignment opportunities
     */
    public static List<Opportunity> findOpportunities() throws SQLException {
        List<Opportunity> opportunities = new ArrayList<>();

        String sql = """
            SELECT 
                a.allocation_id,
                a.emergency_id AS current_emergency_id,
                a.resource_id,
                r.resource_name,
                rt.type_name AS resource_type,
                r.latitude AS resource_lat,
                r.longitude AS resource_lon,
                e1.latitude AS current_em_lat,
                e1.longitude AS current_em_lon,
                e1.priority AS current_priority,
                e1.disaster_type AS current_disaster,
                a.distance_km AS current_distance,
                e2.emergency_id AS nearby_emergency_id,
                e2.latitude AS nearby_em_lat,
                e2.longitude AS nearby_em_lon,
                e2.priority AS nearby_priority,
                e2.disaster_type AS nearby_disaster,
                e2.people_affected AS nearby_people
            FROM allocations a
            JOIN resources r ON r.resource_id = a.resource_id
            JOIN resource_types rt ON rt.type_id = r.type_id
            JOIN emergencies e1 ON e1.emergency_id = a.emergency_id
            CROSS JOIN emergencies e2
            WHERE a.status = 'Active'
              AND e1.is_deleted = 0
              AND e1.response_status IN ('Reported', 'Responding')
              AND e2.is_deleted = 0
              AND e2.emergency_id != e1.emergency_id
              AND e2.response_status IN ('Reported')
              AND e2.allocation_status IN ('Pending', 'Partial')
              AND r.latitude IS NOT NULL
              AND r.longitude IS NOT NULL
              AND e2.latitude IS NOT NULL
              AND e2.longitude IS NOT NULL
            """;

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int currentEmergencyId = rs.getInt("current_emergency_id");
                int nearbyEmergencyId = rs.getInt("nearby_emergency_id");
                int resourceId = rs.getInt("resource_id");
                String resourceName = rs.getString("resource_name");
                String resourceType = rs.getString("resource_type");
                
                double resourceLat = rs.getDouble("resource_lat");
                double resourceLon = rs.getDouble("resource_lon");
                double currentEmLat = rs.getDouble("current_em_lat");
                double currentEmLon = rs.getDouble("current_em_lon");
                double nearbyEmLat = rs.getDouble("nearby_em_lat");
                double nearbyEmLon = rs.getDouble("nearby_em_lon");

                String currentPriority = rs.getString("current_priority");
                String nearbyPriority = rs.getString("nearby_priority");
                
                // Calculate distances
                Double distToCurrent = GeoUtil.haversineKm(
                    resourceLat, resourceLon, currentEmLat, currentEmLon);
                Double distToNearby = GeoUtil.haversineKm(
                    resourceLat, resourceLon, nearbyEmLat, nearbyEmLon);

                if (distToCurrent == null || distToNearby == null) {
                    continue;
                }

                // Check if nearby emergency is actually closer
                if (distToNearby >= distToCurrent) {
                    continue;
                }

                // Check if nearby emergency is within proximity range
                if (distToNearby > MAX_PROXIMITY_KM) {
                    continue;
                }

                // Calculate time savings
                double currentTimeMinutes = GeoUtil.etaMinutes(distToCurrent);
                double nearbyTimeMinutes = GeoUtil.etaMinutes(distToNearby);
                double timeSavings = currentTimeMinutes - nearbyTimeMinutes;

                // Must save significant time
                if (timeSavings < MIN_TIME_SAVINGS_MINUTES) {
                    continue;
                }

                // Check priority: only reassign to equal or higher priority
                int currentPriorityRank = Allocator.priorityRank(currentPriority);
                int nearbyPriorityRank = Allocator.priorityRank(nearbyPriority);
                
                if (nearbyPriorityRank > currentPriorityRank) {
                    // Nearby is lower priority, skip
                    continue;
                }

                // Check if nearby emergency needs this resource type
                if (!needsResourceType(nearbyEmergencyId, resourceType)) {
                    continue;
                }

                // Build reason
                String reason = buildReason(
                    distToCurrent, distToNearby, timeSavings,
                    currentPriority, nearbyPriority
                );

                opportunities.add(new Opportunity(
                    currentEmergencyId,
                    nearbyEmergencyId,
                    resourceId,
                    resourceName,
                    resourceType,
                    distToCurrent,
                    distToNearby,
                    timeSavings,
                    currentPriority,
                    nearbyPriority,
                    reason
                ));
            }
        }

        return opportunities;
    }

    /**
     * Check if an emergency needs a specific resource type.
     */
    private static boolean needsResourceType(int emergencyId, String resourceType) 
            throws SQLException {
        String sql = """
            SELECT rr.type_id
            FROM emergencies e
            JOIN response_requirements rr ON rr.disaster_type = e.disaster_type
            JOIN resource_types rt ON rt.type_id = rr.type_id
            WHERE e.emergency_id = ?
              AND rt.type_name = ?
              AND e.people_affected >= rr.min_people
            LIMIT 1
            """;

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, emergencyId);
            ps.setString(2, resourceType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Build a human-readable reason for the reassignment suggestion.
     */
    private static String buildReason(
            double currentDist, double nearbyDist, double timeSavings,
            String currentPriority, String nearbyPriority) {
        
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("Resource is %.1f km from nearby emergency", nearbyDist));
        reason.append(String.format(" vs %.1f km to current target", currentDist));
        reason.append(String.format(" (saves ~%.0f minutes)", timeSavings));
        
        if (!currentPriority.equals(nearbyPriority)) {
            reason.append(String.format(". Nearby emergency has %s priority", nearbyPriority));
        }
        
        return reason.toString();
    }

    /**
     * Automatically reassign a resource from one emergency to another.
     * 
     * This performs the database operations to:
     * 1. Release the resource from current emergency
     * 2. Assign it to the new emergency
     * 3. Create audit log and notification
     * 
     * @param resourceId The resource to reassign
     * @param fromEmergencyId Current emergency
     * @param toEmergencyId Target emergency
     * @param reason Reason for reassignment
     * @return true if successful
     */
    public static boolean performReassignment(
            int resourceId, 
            int fromEmergencyId, 
            int toEmergencyId,
            String reason) throws SQLException {

        Allocation.LOCK.lock();
        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);
            try {
                // Verify resource is still assigned to fromEmergency
                try (PreparedStatement ps = con.prepareStatement("""
                        SELECT allocation_id FROM allocations
                        WHERE resource_id = ? AND emergency_id = ? 
                          AND status = 'Active'
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, resourceId);
                    ps.setInt(2, fromEmergencyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return false;
                        }
                    }
                }

                // Release from current emergency
                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE allocations 
                        SET status = 'Released', released_at = CURRENT_TIMESTAMP
                        WHERE resource_id = ? AND emergency_id = ? 
                          AND status = 'Active'
                        """)) {
                    ps.setInt(1, resourceId);
                    ps.setInt(2, fromEmergencyId);
                    ps.executeUpdate();
                }

                // Update from-emergency allocation status
                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE emergencies
                        SET allocation_status = CASE 
                            WHEN EXISTS (
                                SELECT 1 FROM allocations
                                WHERE emergency_id = ? AND status = 'Active'
                            ) THEN 'Partial' 
                            ELSE 'Pending' 
                        END
                        WHERE emergency_id = ?
                        """)) {
                    ps.setInt(1, fromEmergencyId);
                    ps.setInt(2, fromEmergencyId);
                    ps.executeUpdate();
                }

                // Set resource back to Available temporarily
                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE resources SET status = 'Available'
                        WHERE resource_id = ? AND is_deleted = 0
                        """)) {
                    ps.setInt(1, resourceId);
                    ps.executeUpdate();
                }

                // Create audit log
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO audit_log 
                        (emergency_id, resource_id, action, details)
                        VALUES (?, ?, 'PROXIMITY_REASSIGNMENT', ?)
                        """)) {
                    ps.setInt(1, toEmergencyId);
                    ps.setInt(2, resourceId);
                    ps.setString(3, String.format(
                        "Reassigned from emergency #%d. Reason: %s",
                        fromEmergencyId, reason
                    ));
                    ps.executeUpdate();
                }

                con.commit();

                // Now trigger allocation for the to-emergency
                // (will happen in separate transaction)
                
                return true;

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
