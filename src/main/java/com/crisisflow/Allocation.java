package com.crisisflow;

import com.crisisflow.Allocator.Pick;
import com.crisisflow.Allocator.Plan;
import com.crisisflow.Allocator.Requirement;
import com.crisisflow.Allocator.Unit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Applies the decisions of {@link Allocator} to the database.
 *
 * Every operation that changes who holds which resource takes {@link #LOCK},
 * so automatic runs, manual runs, releases and deletes never interleave
 * inside this server. Row locks (SELECT ... FOR UPDATE) protect the data too.
 */
public class Allocation {

    /** Held while resources are being assigned, released or deleted. */
    public static final ReentrantLock LOCK = new ReentrantLock();

    public record Summary(int emergenciesUpdated, int resourcesAssigned) {}

    private static final String OPEN_FILTER =
        "is_deleted = 0 AND response_status NOT IN ('Resolved', 'Cancelled')";

    // ------------------------------------------------------------------
    // Automatic allocation
    // ------------------------------------------------------------------

    /**
     * Give available resources to every waiting emergency (CRITICAL first,
     * then oldest first). Safe to call at any time and as often as needed.
     */
    public static Summary runAutoAllocation() throws SQLException {
        LOCK.lock();
        try {
            Map<String, List<Requirement>> requirements;
            Map<String, Integer> refCapacity;
            List<Integer> waiting = new ArrayList<>();

            try (Connection con = Database.getConnection()) {
                requirements = loadRequirements(con);
                refCapacity = loadReferenceCapacity(con);

                String sql = """
                    SELECT emergency_id
                    FROM emergencies
                    WHERE %s AND allocation_status IN ('Pending', 'Partial')
                    ORDER BY
                        CASE priority
                            WHEN 'CRITICAL' THEN 1
                            WHEN 'HIGH' THEN 2
                            WHEN 'MEDIUM' THEN 3
                            ELSE 4
                        END,
                        reported_at ASC, emergency_id ASC
                    """.formatted(OPEN_FILTER);

                try (PreparedStatement ps = con.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        waiting.add(rs.getInt(1));
                    }
                }
            }

            int updated = 0;
            int assigned = 0;
            for (int id : waiting) {
                int n = allocateOne(id, requirements, refCapacity);
                if (n > 0) {
                    updated++;
                    assigned += n;
                }
            }
            return new Summary(updated, assigned);
        } finally {
            LOCK.unlock();
        }
    }

    /** Backwards-compatible name used by the old endpoint. */
    public static int allocatePendingAutomatically() throws SQLException {
        return runAutoAllocation().resourcesAssigned();
    }

    /** Never throws: used after other changes so they are not rolled back. */
    public static void runQuietly() {
        try {
            runAutoAllocation();
        } catch (Exception e) {
            System.err.println("Automatic allocation failed: " + e);
        }
    }

    private static int allocateOne(
            int emergencyId,
            Map<String, List<Requirement>> requirements,
            Map<String, Integer> refCapacity) throws SQLException {

        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);
            try {
                String disasterType, description, priority, allocStatus;
                int people;
                Double lat, lon;

                try (PreparedStatement ps = con.prepareStatement("""
                        SELECT disaster_type, description, people_affected,
                               priority, latitude, longitude, allocation_status
                        FROM emergencies
                        WHERE emergency_id = ? AND %s
                        FOR UPDATE
                        """.formatted(OPEN_FILTER))) {
                    ps.setInt(1, emergencyId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return 0;
                        }
                        disasterType = rs.getString("disaster_type");
                        description = rs.getString("description");
                        people = rs.getInt("people_affected");
                        priority = rs.getString("priority");
                        lat = nullableDouble(rs, "latitude");
                        lon = nullableDouble(rs, "longitude");
                        allocStatus = rs.getString("allocation_status");
                    }
                }

                if (!"Pending".equals(allocStatus)
                        && !"Partial".equals(allocStatus)) {
                    con.rollback();
                    return 0;
                }

                List<String> needed = Allocator.requiredTypes(
                    disasterType, description, people,
                    requirements.getOrDefault(disasterType, List.of()));
                if (needed.isEmpty()) {
                    needed = List.of("Rescue Team"); // nothing configured
                }

                List<Unit> held = loadHeld(con, emergencyId);
                List<Unit> available = loadAvailable(con);

                Plan plan = Allocator.plan(priority, people, lat, lon,
                    needed, held, available, refCapacity);

                for (Pick pick : plan.picks()) {
                    reserve(con, emergencyId, pick);
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE emergencies SET allocation_status = ?, "
                        + "first_allocated_at = CASE WHEN ? > 0 AND first_allocated_at IS NULL THEN CURRENT_TIMESTAMP ELSE first_allocated_at END "
                        + "WHERE emergency_id = ?")) {
                    ps.setString(1, plan.status());
                    ps.setInt(2, plan.picks().size());
                    ps.setInt(3, emergencyId);
                    ps.executeUpdate();
                }

                con.commit();
                return plan.picks().size();

            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    private static void reserve(Connection con, int emergencyId, Pick pick)
            throws SQLException {

        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE resources SET status = 'Reserved'
                WHERE resource_id = ? AND status = 'Available'
                  AND is_deleted = 0
                """)) {
            ps.setInt(1, pick.unit().id());
            if (ps.executeUpdate() != 1) {
                throw new SQLException(
                    "Resource " + pick.unit().id() + " is no longer available.");
            }
        }

        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO allocations
                (emergency_id, resource_id, status, distance_km, eta_minutes)
                VALUES (?, ?, 'Active', ?, ?)
                """)) {
            ps.setInt(1, emergencyId);
            ps.setInt(2, pick.unit().id());
            Double km = pick.distanceKm();
            if (km == null) {
                ps.setNull(3, Types.DECIMAL);
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setDouble(3, Math.round(km * 100.0) / 100.0);
                ps.setInt(4, GeoUtil.etaMinutes(km));
            }
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Releasing
    // ------------------------------------------------------------------

    /** Free every resource held by an emergency (resolved / cancelled). */
    public static void releaseResourcesForEmergency(int emergencyId)
            throws SQLException {

        LOCK.lock();
        try (Connection con = Database.getConnection()) {
            con.setAutoCommit(false);
            try {
                releaseInTx(con, emergencyId);
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** Same, inside a transaction the caller controls. */
    static int releaseInTx(Connection con, int emergencyId)
            throws SQLException {

        List<Integer> resourceIds = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT resource_id FROM allocations
                WHERE emergency_id = ? AND status = 'Active'
                FOR UPDATE
                """)) {
            ps.setInt(1, emergencyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resourceIds.add(rs.getInt(1));
                }
            }
        }

        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE allocations
                SET status = 'Released', released_at = CURRENT_TIMESTAMP
                WHERE emergency_id = ? AND status = 'Active'
                """)) {
            ps.setInt(1, emergencyId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE resources SET status = 'Available'
                WHERE resource_id = ? AND status = 'Reserved'
                  AND is_deleted = 0
                """)) {
            for (int rid : resourceIds) {
                ps.setInt(1, rid);
                ps.executeUpdate();
            }
        }

        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE emergencies SET allocation_status = 'Released'
                WHERE emergency_id = ?
                """)) {
            ps.setInt(1, emergencyId);
            ps.executeUpdate();
        }
        return resourceIds.size();
    }

    // ------------------------------------------------------------------
    // Read models for the UI
    // ------------------------------------------------------------------

    /** Active allocations first, then the most recent history. */
    public static List<Map<String, Object>> listAllocations()
            throws SQLException {

        String sql = """
            SELECT a.allocation_id, a.emergency_id, e.disaster_type,
                   e.location AS emergency_location, e.priority,
                   e.latitude AS e_lat, e.longitude AS e_lng,
                   a.resource_id, r.resource_name, rt.type_name,
                   r.latitude AS r_lat, r.longitude AS r_lng,
                   a.status, a.allocated_at, a.released_at,
                   a.distance_km, a.eta_minutes
            FROM allocations a
            JOIN emergencies e ON e.emergency_id = a.emergency_id
            JOIN resources r ON r.resource_id = a.resource_id
            JOIN resource_types rt ON rt.type_id = r.type_id
            ORDER BY (a.status = 'Active') DESC, a.allocated_at DESC,
                     a.allocation_id DESC
            LIMIT 300
            """;

        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("allocationId", rs.getInt("allocation_id"));
                m.put("emergencyId", rs.getInt("emergency_id"));
                m.put("disasterType", rs.getString("disaster_type"));
                m.put("emergencyLocation", rs.getString("emergency_location"));
                m.put("priority", rs.getString("priority"));
                m.put("emergencyLatitude", nullableDouble(rs, "e_lat"));
                m.put("emergencyLongitude", nullableDouble(rs, "e_lng"));
                m.put("resourceId", rs.getInt("resource_id"));
                m.put("resourceName", rs.getString("resource_name"));
                m.put("typeName", rs.getString("type_name"));
                m.put("resourceLatitude", nullableDouble(rs, "r_lat"));
                m.put("resourceLongitude", nullableDouble(rs, "r_lng"));
                m.put("status", rs.getString("status"));
                m.put("allocatedAt", String.valueOf(rs.getTimestamp("allocated_at")));
                m.put("releasedAt", rs.getTimestamp("released_at") == null
                    ? null : rs.getTimestamp("released_at").toString());
                m.put("distanceKm", nullableDouble(rs, "distance_km"));
                m.put("etaMinutes", rs.getObject("eta_minutes") == null
                    ? null : rs.getInt("eta_minutes"));
                out.add(m);
            }
        }
        return out;
    }

    /** For every waiting emergency: which resource types are still missing. */
    public static List<Map<String, Object>> listUnmet() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();

        try (Connection con = Database.getConnection()) {
            Map<String, List<Requirement>> requirements = loadRequirements(con);
            Map<String, Integer> refCapacity = loadReferenceCapacity(con);

            String sql = """
                SELECT emergency_id, disaster_type, location, description,
                       people_affected, priority, latitude, longitude,
                       allocation_status
                FROM emergencies
                WHERE %s AND allocation_status IN ('Pending', 'Partial')
                ORDER BY
                    CASE priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2
                                  WHEN 'MEDIUM' THEN 3 ELSE 4 END,
                    reported_at ASC
                """.formatted(OPEN_FILTER);

            // Read the waiting emergencies first, then plan each one, so no
            // second query runs while this result set is still open.
            record Waiting(int id, String type, String location,
                           String description, int people, String priority,
                           Double lat, Double lon, String allocStatus) {}

            List<Waiting> waiting = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    waiting.add(new Waiting(
                        rs.getInt("emergency_id"),
                        rs.getString("disaster_type"),
                        rs.getString("location"),
                        rs.getString("description"),
                        rs.getInt("people_affected"),
                        rs.getString("priority"),
                        nullableDouble(rs, "latitude"),
                        nullableDouble(rs, "longitude"),
                        rs.getString("allocation_status")));
                }
            }

            for (Waiting w : waiting) {
                List<String> needed = Allocator.requiredTypes(
                    w.type(), w.description(), w.people(),
                    requirements.getOrDefault(w.type(), List.of()));
                if (needed.isEmpty()) {
                    needed = List.of("Rescue Team");
                }

                // Plan against an empty pool: shows the true shortfall.
                Plan plan = Allocator.plan(w.priority(), w.people(),
                    w.lat(), w.lon(), needed,
                    loadHeld(con, w.id()), List.of(), refCapacity);

                List<Map<String, Object>> needs = new ArrayList<>();
                for (Allocator.Unmet u : plan.unmet()) {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("typeName", u.typeName());
                    n.put("units", u.units());
                    needs.add(n);
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("emergencyId", w.id());
                m.put("disasterType", w.type());
                m.put("location", w.location());
                m.put("priority", w.priority());
                m.put("allocationStatus", w.allocStatus());
                m.put("needs", needs);
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Loaders
    // ------------------------------------------------------------------

    private static Map<String, List<Requirement>> loadRequirements(
            Connection con) throws SQLException {

        Map<String, List<Requirement>> map = new HashMap<>();
        String sql = """
            SELECT q.disaster_type, rt.type_name, q.min_people
            FROM response_requirements q
            JOIN resource_types rt ON rt.type_id = q.type_id
            ORDER BY q.requirement_id
            """;
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new Requirement(rs.getString(2), rs.getInt(3)));
            }
        }
        return map;
    }

    /** Largest capacity per resource type among all non-deleted resources. */
    private static Map<String, Integer> loadReferenceCapacity(Connection con)
            throws SQLException {

        Map<String, Integer> map = new HashMap<>();
        String sql = """
            SELECT rt.type_name, MAX(r.capacity)
            FROM resources r
            JOIN resource_types rt ON rt.type_id = r.type_id
            WHERE r.is_deleted = 0
            GROUP BY rt.type_name
            """;
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getInt(2));
            }
        }
        return map;
    }

    private static List<Unit> loadHeld(Connection con, int emergencyId)
            throws SQLException {

        String sql = """
            SELECT r.resource_id, rt.type_name, r.capacity,
                   r.latitude, r.longitude
            FROM allocations a
            JOIN resources r ON r.resource_id = a.resource_id
            JOIN resource_types rt ON rt.type_id = r.type_id
            WHERE a.emergency_id = ? AND a.status = 'Active'
            """;
        List<Unit> units = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, emergencyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    units.add(toUnit(rs));
                }
            }
        }
        return units;
    }

    private static List<Unit> loadAvailable(Connection con) throws SQLException {
        String sql = """
            SELECT r.resource_id, rt.type_name, r.capacity,
                   r.latitude, r.longitude
            FROM resources r
            JOIN resource_types rt ON rt.type_id = r.type_id
            WHERE r.status = 'Available' AND r.is_deleted = 0
            ORDER BY r.resource_id
            FOR UPDATE OF r
            """;
        List<Unit> units = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                units.add(toUnit(rs));
            }
        }
        return units;
    }

    private static Unit toUnit(ResultSet rs) throws SQLException {
        return new Unit(rs.getInt("resource_id"), rs.getString("type_name"),
            rs.getInt("capacity"),
            nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"));
    }

    static Double nullableDouble(ResultSet rs, String column)
            throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
