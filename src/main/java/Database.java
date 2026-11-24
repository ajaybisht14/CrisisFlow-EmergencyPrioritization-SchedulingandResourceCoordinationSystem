package com.crisisflow;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


public class Database {

    private static final BlockingQueue<Connection> IDLE =
        new LinkedBlockingQueue<>();
    private static final AtomicInteger OPEN = new AtomicInteger();

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String url() {
        return "jdbc:mysql://" + env("CRISISFLOW_DB_HOST", "localhost")
            + ":" + env("CRISISFLOW_DB_PORT", "3306")
            + "/" + env("CRISISFLOW_DB_NAME", "crisisflow_v2")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private static int maxPool() {
        try {
            return Math.max(2, Integer.parseInt(env("CRISISFLOW_DB_POOL", "10")));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    private static Connection openRaw() throws SQLException {
        String user = System.getenv("CRISISFLOW_DB_USER");
        String password = System.getenv("CRISISFLOW_DB_PASSWORD");

        if (user == null || user.isBlank()) {
            throw new SQLException(
                "Set CRISISFLOW_DB_USER (and CRISISFLOW_DB_PASSWORD) first.");
        }
        return DriverManager.getConnection(
            url(), user, password == null ? "" : password);
    }

    /** Borrow a connection. Closing it returns it to the pool. */
    public static Connection getConnection() throws SQLException {
        Connection raw;

        while ((raw = IDLE.poll()) != null) {
            if (isUsable(raw)) {
                return wrap(raw);
            }
            discard(raw);
        }

        if (OPEN.incrementAndGet() <= maxPool()) {
            try {
                return wrap(openRaw());
            } catch (SQLException | RuntimeException e) {
                OPEN.decrementAndGet();
                throw e;
            }
        }
        OPEN.decrementAndGet();

        try {
            raw = IDLE.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a connection.");
        }
        if (raw == null) {
            throw new SQLException("Database is busy. Please try again.");
        }
        if (isUsable(raw)) {
            return wrap(raw);
        }
        discard(raw);
        return getConnection();
    }

    private static boolean isUsable(Connection c) {
        try {
            return !c.isClosed() && c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void discard(Connection c) {
        OPEN.decrementAndGet();
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing more to do
        }
    }

    private static Connection wrap(Connection raw) {
        AtomicBoolean returned = new AtomicBoolean(false);

        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("close")
                    && method.getParameterCount() == 0) {
                if (returned.compareAndSet(false, true)) {
                    giveBack(raw);
                }
                return null;
            }
            if (method.getName().equals("isClosed")
                    && method.getParameterCount() == 0) {
                return returned.get() || raw.isClosed();
            }
            if (returned.get()) {
                throw new SQLException("Connection already closed.");
            }
            try {
                return method.invoke(raw, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };

        return (Connection) Proxy.newProxyInstance(
            Database.class.getClassLoader(),
            new Class<?>[] {Connection.class}, handler);
    }

    private static void giveBack(Connection raw) {
        try {
            if (!raw.getAutoCommit()) {
                raw.rollback();
                raw.setAutoCommit(true);
            }
            IDLE.offer(raw);
        } catch (SQLException e) {
            discard(raw);
        }
    }

    // ------------------------------------------------------------------
    // Schema: create/upgrade everything the app needs at startup.
    // ------------------------------------------------------------------

    private record Seed(String disaster, String resourceType, int minPeople) {}

    private static final List<Seed> DEFAULT_REQUIREMENTS = List.of(
        new Seed("Flood", "Rescue Team", 0),
        new Seed("Flood", "Medical Team", 10),
        new Seed("Earthquake", "Rescue Team", 0),
        new Seed("Earthquake", "Medical Team", 0),
        new Seed("Earthquake", "Ambulance", 0),
        new Seed("Fire", "Fire Unit", 0),
        new Seed("Fire", "Ambulance", 0),
        new Seed("Landslide", "Rescue Team", 0),
        new Seed("Landslide", "Medical Team", 10),
        new Seed("Major Accident", "Ambulance", 0),
        new Seed("Major Accident", "Medical Team", 10)
    );

    public static void ensureSchema() throws SQLException {
        try (Connection con = getConnection()) {

            try (Statement st = con.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS response_requirements (
                        requirement_id INT AUTO_INCREMENT PRIMARY KEY,
                        disaster_type VARCHAR(50) NOT NULL,
                        type_id INT NOT NULL,
                        min_people INT NOT NULL DEFAULT 0,
                        UNIQUE KEY uq_req (disaster_type, type_id),
                        FOREIGN KEY (type_id) REFERENCES resource_types(type_id)
                    )""");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS status_history (
                        history_id INT AUTO_INCREMENT PRIMARY KEY,
                        emergency_id INT NOT NULL,
                        old_status VARCHAR(30),
                        new_status VARCHAR(30) NOT NULL,
                        note VARCHAR(500),
                        changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )""");
                st.executeUpdate("INSERT IGNORE INTO resource_types (type_name) "
                    + "VALUES ('Ambulance'),('Rescue Team'),"
                    + "('Medical Team'),('Fire Unit')");
            }

            for (String table : new String[] {"emergencies", "resources"}) {
                addColumnIfMissing(con, table, "latitude", "DECIMAL(10,7)");
                addColumnIfMissing(con, table, "longitude", "DECIMAL(10,7)");
                addColumnIfMissing(con, table, "is_deleted",
                    "TINYINT(1) NOT NULL DEFAULT 0");
                addColumnIfMissing(con, table, "deleted_at", "TIMESTAMP NULL");
            }
            // Older/hand-made schemas may lack these timestamp columns.
            addColumnIfMissing(con, "allocations", "allocated_at",
                "TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP");
            addColumnIfMissing(con, "allocations", "released_at", "TIMESTAMP NULL");
            addColumnIfMissing(con, "emergencies", "reported_at",
                "TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP");
            addColumnIfMissing(con, "allocations", "distance_km", "DECIMAL(8,2) NULL");
            addColumnIfMissing(con, "allocations", "eta_minutes", "INT NULL");
            addColumnIfMissing(con, "emergencies", "first_allocated_at", "TIMESTAMP NULL");
            addColumnIfMissing(con, "emergencies", "response_started_at", "TIMESTAMP NULL");
            addColumnIfMissing(con, "emergencies", "arrived_at", "TIMESTAMP NULL");
            addColumnIfMissing(con, "emergencies", "resolved_at", "TIMESTAMP NULL");
            addColumnIfMissing(con, "emergencies", "cancelled_at", "TIMESTAMP NULL");
            addColumnIfMissing(con, "emergencies", "priority_score", "INT NULL");
            addColumnIfMissing(con, "emergencies", "priority_reason", "TEXT NULL");
            try (Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS resource_locations (location_id BIGINT AUTO_INCREMENT PRIMARY KEY, resource_id INT NOT NULL, latitude DECIMAL(10,7) NOT NULL, longitude DECIMAL(10,7) NOT NULL, reported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, tracking_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', INDEX idx_location_resource_time(resource_id,reported_at), CONSTRAINT fk_location_resource FOREIGN KEY(resource_id) REFERENCES resources(resource_id))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS notifications (notification_id BIGINT AUTO_INCREMENT PRIMARY KEY, emergency_id INT NULL, resource_id INT NULL, event_type VARCHAR(50) NOT NULL, message VARCHAR(1000) NOT NULL, dedupe_key VARCHAR(180) NOT NULL UNIQUE, is_read TINYINT(1) NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_notifications_read(is_read,created_at))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS audit_log (audit_id BIGINT AUTO_INCREMENT PRIMARY KEY, emergency_id INT NULL, resource_id INT NULL, action VARCHAR(80) NOT NULL, details TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            }

            allowPartialAllocationStatus(con);
            seedRequirements(con);
        }
    }




        for (String name : toWiden) {
            try (Statement st = con.createStatement()) {
                st.executeUpdate("ALTER TABLE emergencies DROP CHECK `" + name + "`");
                st.executeUpdate("ALTER TABLE emergencies ADD CONSTRAINT `" + name
                    + "` CHECK (allocation_status IN "
                    + "('Pending','Partial','Allocated','Released','Cancelled'))");
            }
            System.out.println("Widened CHECK constraint " + name
                + " to allow allocation_status 'Partial'.");
        }
    }

    private static void seedRequirements(Connection con) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM response_requirements")) {
            rs.next();
            if (rs.getInt(1) > 0) {
                return;
            }
        }

        String sql = "INSERT IGNORE INTO response_requirements "
            + "(disaster_type, type_id, min_people) "
            + "SELECT ?, type_id, ? FROM resource_types WHERE type_name = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (Seed s : DEFAULT_REQUIREMENTS) {
                ps.setString(1, s.disaster());
                ps.setInt(2, s.minPeople());
                ps.setString(3, s.resourceType());
                ps.executeUpdate();
            }
        }
        System.out.println("Seeded default response requirements.");
    }

    private static void addColumnIfMissing(
            Connection con, String table, String column, String definition)
            throws SQLException {
        if (columnExists(con, table, column)) {
            return;
        }
        try (Statement st = con.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table
                + " ADD COLUMN " + column + " " + definition);
        }
        System.out.println("Added missing column " + table + "." + column);
    }

    private static boolean columnExists(
            Connection con, String table, String column) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND column_name = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
