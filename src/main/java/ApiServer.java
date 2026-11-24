package com.crisisflow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ApiServer {

    private static final Set<String> STATIC_TYPES = Set.of(
        ".html", ".css", ".js", ".png", ".svg", ".ico", ".json");

    private final HttpServer server;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final ScheduledExecutorService sweeper =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-allocator");
            t.setDaemon(true);
            return t;
        });

    public ApiServer(int port) throws IOException {
        String key = System.getenv("CRISISFLOW_API_KEY");
        apiKey = key == null || key.isBlank() ? null : key;

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/api/emergencies", ex -> guarded(ex, this::handleEmergencies));
        server.createContext("/api/resources", ex -> guarded(ex, this::handleResources));
        server.createContext("/api/resource-types", ex -> guarded(ex, this::handleResourceTypes));
        server.createContext("/api/allocations", ex -> guarded(ex, this::handleAllocations));
        server.createContext("/api/allocate-pending", ex -> guarded(ex, this::handleAllocate));
        server.createContext("/api/emergency-status", ex -> guarded(ex, this::handleEmergencyStatus));
        server.createContext("/api/resource-status", ex -> guarded(ex, this::handleResourceStatus));
        server.createContext("/api/routes", ex -> guarded(ex, this::handleRoutes));
        server.createContext("/api/tracking", ex -> guarded(ex, this::handleTracking));
        server.createContext("/api/notifications", ex -> guarded(ex, this::handleNotifications));
        server.createContext("/api/metrics", ex -> guarded(ex, this::handleMetrics));
        server.createContext("/api/priority", ex -> guarded(ex, this::handlePriority));
        server.createContext("/api/reassignment-opportunities", ex -> guarded(ex, this::handleReassignmentOpportunities));
        server.createContext("/api/reassign", ex -> guarded(ex, this::handleReassign));
        server.createContext("/", this::handleStatic);
    }

    public void start() {
        server.start();

        int seconds = 30;
        try {
            String v = System.getenv("CRISISFLOW_AUTO_INTERVAL");
            if (v != null && !v.isBlank()) {
                seconds = Math.max(5, Integer.parseInt(v.trim()));
            }
        } catch (NumberFormatException ignored) {
            // keep default
        }
        // Safety net: even if a trigger was missed, waiting emergencies are
        // retried regularly without anyone pressing a button.
        sweeper.scheduleWithFixedDelay(
            Allocation::runQuietly, 5, seconds, TimeUnit.SECONDS);
    }

    public void stop() {
        sweeper.shutdownNow();
        server.stop(1);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    /** Auth check + uniform error handling around every API handler. */
    private void guarded(HttpExchange exchange, Handler handler)
            throws IOException {
        try {
            if (apiKey != null
                    && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String given = exchange.getRequestHeaders().getFirst("X-API-Key");
                if (given == null || !MessageDigest.isEqual(
                        given.getBytes(StandardCharsets.UTF_8),
                        apiKey.getBytes(StandardCharsets.UTF_8))) {
                    sendJson(exchange, 401,
                        Map.of("error", "API key required."));
                    return;
                }
            }
            handler.handle(exchange);
        } catch (Exception e) {
            sendError(exchange, e);
        }
    }

    /** "/api/resources/12" with context "/api/resources" -> 12, or null. */
    private Integer idFromPath(HttpExchange exchange, String base) {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > base.length()
            ? path.substring(base.length()) : "";
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(rest);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid id in URL.");
        }
    }

    private boolean hasQueryFlag(HttpExchange exchange, String name) {
        String q = exchange.getRequestURI().getQuery();
        if (q == null) {
            return false;
        }
        for (String part : q.split("&")) {
            if (part.equalsIgnoreCase(name + "=true")) {
                return true;
            }
        }
        return false;
    }

    private static boolean is(HttpExchange ex, String method) {
        return method.equalsIgnoreCase(ex.getRequestMethod());
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handleEmergencies(HttpExchange exchange) throws Exception {
        if (is(exchange, "GET")) {
            sendJson(exchange, 200, getEmergencies());
            return;
        }

        if (is(exchange, "POST")) {
            JsonObject body = readBody(exchange);

            int id = Emergency.register(
                Json.requireString(body, "disasterType", 50),
                Json.requireString(body, "location", 200),
                Json.requireDouble(body, "latitude"),
                Json.requireDouble(body, "longitude"),
                Json.requireInt(body, "peopleAffected"),
                Json.optionalString(body, "description", 2000)
            );

            NotificationService.create(id, null, "REGISTERED", "Emergency #" + id + " registered.");
            // Give it resources right away - no button press needed.
            Allocation.runQuietly();

            sendJson(exchange, 201, mapOf(
                "message", "Emergency registered successfully",
                "emergencyId", id,
                "allocationStatus", allocationStatusOf(id)
            ));
            return;
        }

        if (is(exchange, "DELETE")) {
            Integer id = idFromPath(exchange, "/api/emergencies");
            if (id == null) {
                throw new IllegalArgumentException("Emergency id is required.");
            }
            Emergency.delete(id);
            Allocation.runQuietly(); // freed units go to waiting emergencies
            sendJson(exchange, 200, Map.of("message", "Emergency deleted"));
            return;
        }

        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private void handleResources(HttpExchange exchange) throws Exception {
        if (is(exchange, "GET")) {
            sendJson(exchange, 200, getResources());
            return;
        }

        if (is(exchange, "POST")) {
            JsonObject body = readBody(exchange);

            int id = Resource.addResource(
                Json.requireString(body, "resourceName", 100),
                Json.requireInt(body, "typeId"),
                Json.requireInt(body, "capacity"),
                Json.requireString(body, "location", 200),
                Json.requireDouble(body, "latitude"),
                Json.requireDouble(body, "longitude")
            );

            // A new unit may be exactly what a waiting emergency needs.
            Allocation.runQuietly();

            sendJson(exchange, 201, mapOf(
                "message", "Resource added successfully",
                "resourceId", id
            ));
            return;
        }

        if (is(exchange, "DELETE")) {
            Integer id = idFromPath(exchange, "/api/resources");
            if (id == null) {
                throw new IllegalArgumentException("Resource id is required.");
            }

            List<Integer> affected =
                Resource.delete(id, hasQueryFlag(exchange, "force"));

            // Re-assign the emergencies that just lost a unit.
            Allocation.runQuietly();

            sendJson(exchange, 200, mapOf(
                "message", affected.isEmpty()
                    ? "Resource deleted"
                    : "Resource deleted; affected emergencies were re-queued "
                        + "for automatic reassignment",
                "affectedEmergencyIds", affected
            ));
            return;
        }

        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private void handleResourceTypes(HttpExchange exchange) throws Exception {
        if (!is(exchange, "GET")) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        List<Map<String, Object>> types = new ArrayList<>();
        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "SELECT type_id, type_name FROM resource_types ORDER BY type_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                types.add(mapOf("typeId", rs.getInt(1), "typeName", rs.getString(2)));
            }
        }
        sendJson(exchange, 200, types);
    }

    private void handleAllocations(HttpExchange exchange) throws Exception {
        if (!is(exchange, "GET")) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        List<Map<String, Object>> allocations = Allocation.listAllocations();
        List<Map<String, Object>> unmet = List.of();
        try {
            unmet = Allocation.listUnmet();
        } catch (Exception e) {
            // The assignments list is still useful without the shortfall list.
            System.err.println("Could not compute unmet needs: " + e);
            e.printStackTrace();
        }
        sendJson(exchange, 200, mapOf(
            "allocations", allocations,
            "unmet", unmet
        ));
    }

    private void handleAllocate(HttpExchange exchange) throws Exception {
        if (!is(exchange, "POST")) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        Allocation.Summary s = Allocation.runAutoAllocation();

        sendJson(exchange, 200, mapOf(
            "message", "Automatic allocation completed",
            "allocatedCount", s.resourcesAssigned(),
            "emergenciesUpdated", s.emergenciesUpdated()
        ));
    }

    private void handleEmergencyStatus(HttpExchange exchange) throws Exception {
        if (!is(exchange, "POST")) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        JsonObject body = readBody(exchange);
        int id = Json.requireInt(body, "emergencyId");
        String status = Json.requireString(body, "status", 30);
        String note = Json.optionalString(body, "note", 500);

        Emergency.updateResponseStatus(id, status, note);
        if ("Resolved".equalsIgnoreCase(status)) NotificationService.create(id, null, "COMPLETED", "Emergency #" + id + " response completed.");
        if ("Cancelled".equalsIgnoreCase(status)) NotificationService.create(id, null, "CANCELLED", "Emergency #" + id + " was cancelled.");
        if ("Responding".equalsIgnoreCase(status)) NotificationService.create(id, null, "RESPONSE_STARTED", "Response started for emergency #" + id + ".");
        Allocation.runQuietly(); // released units go to the next in line

        sendJson(exchange, 200, Map.of("message", "Emergency status updated"));
    }

    private void handleResourceStatus(HttpExchange exchange) throws Exception {
        if (!is(exchange, "POST")) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        JsonObject body = readBody(exchange);
        int id = Json.requireInt(body, "resourceId");
        String status = Json.requireString(body, "status", 30);

        Resource.setStatus(id, status);
        if ("Available".equals(status)) {
            Allocation.runQuietly();
        }

        sendJson(exchange, 200, Map.of("message", "Resource status updated"));
    }

    private void handleRoutes(HttpExchange ex) throws Exception {
        if (!is(ex, "GET")) { sendJson(ex,405,Map.of("error","Method not allowed")); return; }
        Map<String,String> q = new java.util.HashMap<>();
        String query=ex.getRequestURI().getRawQuery();
        if(query!=null) for(String part:query.split("&")){String[] kv=part.split("=",2); if(kv.length==2) q.put(java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
        try {
            double fl=Double.parseDouble(q.get("fromLat")), fo=Double.parseDouble(q.get("fromLon")), tl=Double.parseDouble(q.get("toLat")), to=Double.parseDouble(q.get("toLon"));
            sendJson(ex,200,RouteService.route(fl,fo,tl,to));
        } catch (NumberFormatException | NullPointerException e) { sendJson(ex,400,Map.of("error","Required numeric query parameters: fromLat, fromLon, toLat, toLon")); }
    }

    private void handleTracking(HttpExchange ex) throws Exception {
        if (is(ex,"POST")) {
            JsonObject b=readBody(ex); int id=Json.requireInt(b,"resourceId"); double lat=Json.requireDouble(b,"latitude"), lon=Json.requireDouble(b,"longitude");
            if(!Double.isFinite(lat)||lat < -90||lat>90||!Double.isFinite(lon)||lon < -180||lon>180) throw new IllegalArgumentException("Coordinates out of range");
            try(Connection c=Database.getConnection()) {
                try(PreparedStatement p=c.prepareStatement("SELECT resource_id FROM resources WHERE resource_id=? AND is_deleted=0")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()){sendJson(ex,404,Map.of("error","Resource not found"));return;}}}
                try(PreparedStatement p=c.prepareStatement("INSERT INTO resource_locations(resource_id,latitude,longitude,tracking_status) VALUES(?,?,?,'ACTIVE')")){p.setInt(1,id);p.setDouble(2,lat);p.setDouble(3,lon);p.executeUpdate();}
                try(PreparedStatement p=c.prepareStatement("UPDATE resources SET latitude=?,longitude=? WHERE resource_id=?")){p.setDouble(1,lat);p.setDouble(2,lon);p.setInt(3,id);p.executeUpdate();}
            }
            sendJson(ex,201,Map.of("message","Location accepted","resourceId",id,"trackingStatus","ACTIVE")); return;
        }
        if (is(ex,"GET")) {
            List<Map<String,Object>> rows=new ArrayList<>();
            try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT l.resource_id,r.resource_name,l.latitude,l.longitude,l.reported_at,l.tracking_status FROM resource_locations l JOIN resources r ON r.resource_id=l.resource_id JOIN (SELECT resource_id,MAX(location_id) id FROM resource_locations GROUP BY resource_id) x ON x.id=l.location_id WHERE r.is_deleted=0 ORDER BY l.reported_at DESC");ResultSet rs=p.executeQuery()) { while(rs.next()){rows.add(mapOf("resourceId",rs.getInt(1),"resourceName",rs.getString(2),"latitude",rs.getDouble(3),"longitude",rs.getDouble(4),"reportedAt",rs.getTimestamp(5).toInstant().toString(),"trackingStatus",rs.getString(6)));} }
            sendJson(ex,200,rows); return;
        }
        sendJson(ex,405,Map.of("error","Method not allowed"));
    }

    private void handleNotifications(HttpExchange ex) throws Exception {
        if(is(ex,"GET")){ List<Map<String,Object>> ns=NotificationService.list(); long unread=ns.stream().filter(n->Boolean.FALSE.equals(n.get("isRead"))).count(); sendJson(ex,200,mapOf("unreadCount",unread,"notifications",ns)); return; }
        if(is(ex,"POST")){ JsonObject b=readBody(ex); int id=Json.requireInt(b,"notificationId"); try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("UPDATE notifications SET is_read=1 WHERE notification_id=?")){p.setInt(1,id); if(p.executeUpdate()==0){sendJson(ex,404,Map.of("error","Notification not found"));return;}} sendJson(ex,200,Map.of("message","Notification marked read")); return; }
        sendJson(ex,405,Map.of("error","Method not allowed"));
    }

    private void handleMetrics(HttpExchange ex) throws Exception {
        if(!is(ex,"GET")){sendJson(ex,405,Map.of("error","Method not allowed"));return;}
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT emergency_id, response_status, reported_at, first_allocated_at, response_started_at, arrived_at, resolved_at, TIMESTAMPDIFF(SECOND,reported_at,CURRENT_TIMESTAMP) reported_seconds, CASE WHEN first_allocated_at IS NULL THEN NULL ELSE TIMESTAMPDIFF(SECOND,first_allocated_at,CURRENT_TIMESTAMP) END allocated_seconds FROM emergencies WHERE is_deleted=0 ORDER BY reported_at DESC");ResultSet r=p.executeQuery()){
            List<Map<String,Object>> rows=new ArrayList<>(); while(r.next()){rows.add(mapOf("emergencyId",r.getInt(1),"responseStatus",r.getString(2),"reportedAt",r.getTimestamp(3)==null?null:r.getTimestamp(3).toInstant().toString(),"firstAllocatedAt",r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant().toString(),"responseStartedAt",r.getTimestamp(5)==null?null:r.getTimestamp(5).toInstant().toString(),"arrivedAt",r.getTimestamp(6)==null?null:r.getTimestamp(6).toInstant().toString(),"resolvedAt",r.getTimestamp(7)==null?null:r.getTimestamp(7).toInstant().toString(),"reportedSeconds",r.getLong(8),"sinceAllocationSeconds",r.getObject(9)==null?null:r.getLong(9)));} sendJson(ex,200,rows);
        }
    }

    private void handlePriority(HttpExchange ex) throws Exception {
        if(!is(ex,"POST")){sendJson(ex,405,Map.of("error","Method not allowed"));return;}
        JsonObject b=readBody(ex); int id=Json.requireInt(b,"emergencyId");
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT disaster_type,people_affected,description,TIMESTAMPDIFF(MINUTE,reported_at,CURRENT_TIMESTAMP) FROM emergencies WHERE emergency_id=? AND is_deleted=0")){p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()){sendJson(ex,404,Map.of("error","Emergency not found"));return;} PriorityEngine.Result score=PriorityEngine.assess(r.getString(1),r.getInt(2),r.getString(3),r.getInt(4),null); String reasons=String.join("; ",score.reasons()); try(PreparedStatement u=c.prepareStatement("UPDATE emergencies SET priority_score=?,priority_reason=? WHERE emergency_id=?")){u.setInt(1,score.score());u.setString(2,reasons);u.setInt(3,id);u.executeUpdate();} sendJson(ex,200,mapOf("emergencyId",id,"priority",score.priority(),"score",score.score(),"manualReview",score.manualReview(),"reasons",score.reasons(),"method","transparent rule-based score; not machine learning"));}}
    }

    private void handleReassignmentOpportunities(HttpExchange ex) throws Exception {
        if(!is(ex,"GET")){sendJson(ex,405,Map.of("error","Method not allowed"));return;}
        List<ProximityReassignment.Opportunity> opportunities = ProximityReassignment.findOpportunities();
        List<Map<String,Object>> result = new ArrayList<>();
        for(ProximityReassignment.Opportunity opp : opportunities) {
            result.add(mapOf(
                "currentEmergencyId", opp.currentEmergencyId(),
                "suggestedEmergencyId", opp.suggestedEmergencyId(),
                "resourceId", opp.resourceId(),
                "resourceName", opp.resourceName(),
                "resourceType", opp.resourceType(),
                "currentDistanceKm", opp.currentDistanceKm(),
                "suggestedDistanceKm", opp.suggestedDistanceKm(),
                "timeSavingsMinutes", opp.timeSavingsMinutes(),
                "currentPriority", opp.currentPriority(),
                "suggestedPriority", opp.suggestedPriority(),
                "reason", opp.reason()
            ));
        }
        sendJson(ex,200,mapOf("opportunities",result,"count",result.size()));
    }

    private void handleReassign(HttpExchange ex) throws Exception {
        if(!is(ex,"POST")){sendJson(ex,405,Map.of("error","Method not allowed"));return;}
        JsonObject b=readBody(ex);
        int resourceId = Json.requireInt(b,"resourceId");
        int fromEmergencyId = Json.requireInt(b,"fromEmergencyId");
        int toEmergencyId = Json.requireInt(b,"toEmergencyId");
        String reason = Json.optionalString(b,"reason",500);
        if(reason.isEmpty()) reason = "Proximity-based reassignment";
        
        boolean success = ProximityReassignment.performReassignment(
            resourceId, fromEmergencyId, toEmergencyId, reason);
        
        if(!success) {
            sendJson(ex,409,Map.of("error","Resource is no longer assigned to source emergency"));
            return;
        }
        
        // Trigger reallocation for both emergencies
        Allocation.runQuietly();
        
        // Create notifications
        try {
            NotificationService.create(fromEmergencyId, resourceId, "RESOURCE_REASSIGNED",
                String.format("Resource #%d reassigned to emergency #%d (proximity-based)",
                    resourceId, toEmergencyId));
            NotificationService.create(toEmergencyId, resourceId, "RESOURCE_ASSIGNED",
                String.format("Resource #%d reassigned from emergency #%d (closer proximity)",
                    resourceId, fromEmergencyId));
        } catch(Exception ignored) {}
        
        sendJson(ex,200,mapOf(
            "message","Resource reassigned successfully",
            "resourceId",resourceId,
            "fromEmergencyId",fromEmergencyId,
            "toEmergencyId",toEmergencyId
        ));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    private String allocationStatusOf(int emergencyId) throws SQLException {
        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 "SELECT allocation_status FROM emergencies WHERE emergency_id = ?")) {
            ps.setInt(1, emergencyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "Pending";
            }
        }
    }

    private List<Map<String, Object>> getEmergencies() throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();

        String sql = """
            SELECT emergency_id, disaster_type, location,
                   latitude, longitude, people_affected,
                   description, priority, allocation_status,
                   response_status, reported_at, first_allocated_at,
                   response_started_at, arrived_at, resolved_at, cancelled_at,
                   priority_score, priority_reason
            FROM emergencies
            WHERE is_deleted = 0
            ORDER BY reported_at DESC, emergency_id DESC
            """;

        String assignedSql = """
            SELECT a.emergency_id, a.allocation_id, a.resource_id,
                   r.resource_name, rt.type_name, a.distance_km, a.eta_minutes
            FROM allocations a
            JOIN resources r ON r.resource_id = a.resource_id
            JOIN resource_types rt ON rt.type_id = r.type_id
            WHERE a.status = 'Active'
            ORDER BY a.allocation_id
            """;

        try (Connection con = Database.getConnection()) {
            Map<Integer, List<Map<String, Object>>> assigned = new LinkedHashMap<>();

            try (PreparedStatement ps = con.prepareStatement(assignedSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assigned.computeIfAbsent(rs.getInt("emergency_id"),
                        k -> new ArrayList<>()).add(mapOf(
                            "allocationId", rs.getInt("allocation_id"),
                            "resourceId", rs.getInt("resource_id"),
                            "resourceName", rs.getString("resource_name"),
                            "typeName", rs.getString("type_name"),
                            "distanceKm", Allocation.nullableDouble(rs, "distance_km"),
                            "etaMinutes", rs.getObject("eta_minutes") == null
                                ? null : rs.getInt("eta_minutes")));
                }
            }

            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("emergency_id");
                    String desc = rs.getString("description");
                    result.add(mapOf(
                        "emergencyId", id,
                        "disasterType", rs.getString("disaster_type"),
                        "location", rs.getString("location"),
                        "latitude", Allocation.nullableDouble(rs, "latitude"),
                        "longitude", Allocation.nullableDouble(rs, "longitude"),
                        "peopleAffected", rs.getInt("people_affected"),
                        "description", desc == null ? "" : desc,
                        "priority", rs.getString("priority"),
                        "allocationStatus", rs.getString("allocation_status"),
                        "responseStatus", rs.getString("response_status"),
                        "reportedAt", rs.getTimestamp("reported_at").toString(),
                        "firstAllocatedAt", rs.getTimestamp("first_allocated_at") == null ? null : rs.getTimestamp("first_allocated_at").toString(),
                        "responseStartedAt", rs.getTimestamp("response_started_at") == null ? null : rs.getTimestamp("response_started_at").toString(),
                        "arrivedAt", rs.getTimestamp("arrived_at") == null ? null : rs.getTimestamp("arrived_at").toString(),
                        "resolvedAt", rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toString(),
                        "cancelledAt", rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toString(),
                        "priorityScore", rs.getObject("priority_score") == null ? null : rs.getInt("priority_score"),
                        "priorityReason", rs.getString("priority_reason"),
                        "assigned", assigned.getOrDefault(id, List.of())
                    ));
                }
            }
        }

        return result;
    }

    private List<Map<String, Object>> getResources() throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();

        String sql = """
            SELECT r.resource_id, r.resource_name, r.type_id,
                   rt.type_name, r.capacity, r.location,
                   r.latitude, r.longitude, r.status,
                   (SELECT a.emergency_id FROM allocations a
                    WHERE a.resource_id = r.resource_id AND a.status = 'Active'
                    LIMIT 1) AS assigned_to
            FROM resources r
            JOIN resource_types rt ON r.type_id = rt.type_id
            WHERE r.is_deleted = 0
            ORDER BY r.resource_id
            """;

        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                result.add(mapOf(
                    "resourceId", rs.getInt("resource_id"),
                    "resourceName", rs.getString("resource_name"),
                    "typeId", rs.getInt("type_id"),
                    "typeName", rs.getString("type_name"),
                    "capacity", rs.getInt("capacity"),
                    "location", rs.getString("location"),
                    "latitude", Allocation.nullableDouble(rs, "latitude"),
                    "longitude", Allocation.nullableDouble(rs, "longitude"),
                    "status", rs.getString("status"),
                    "assignedTo", rs.getObject("assigned_to") == null
                        ? null : rs.getInt("assigned_to")
                ));
            }
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Request / response helpers
    // ------------------------------------------------------------------

    private JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] raw = input.readNBytes(64 * 1024 + 1);
            if (raw.length > 64 * 1024) {
                throw new IllegalArgumentException("Request body too large.");
            }
            String body = new String(raw, StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                throw new IllegalArgumentException("Request body is required.");
            }
            try {
                JsonObject obj = gson.fromJson(body, JsonObject.class);
                if (obj == null) {
                    throw new IllegalArgumentException("Request body is required.");
                }
                return obj;
            } catch (JsonParseException e) {
                throw new IllegalArgumentException("Request body is not valid JSON.");
            }
        }
    }

    private void sendError(HttpExchange exchange, Exception e)
            throws IOException {

        Map<String, Object> payload = new LinkedHashMap<>();
        int status;

        if (e instanceof ApiException api) {
            status = api.getStatus();
            payload.put("error", api.getMessage());
            payload.putAll(api.getExtra());
        } else if (e instanceof IllegalArgumentException) {
            status = 400;
            payload.put("error", e.getMessage() == null
                ? "Invalid request." : e.getMessage());
        } else {
            // Details stay in the server log, not in the response.
            e.printStackTrace();
            status = 500;
            payload.put("error", "Unexpected server error. Please try again.");
            // Set CRISISFLOW_DEBUG=1 while developing to see the cause here.
            if ("1".equals(System.getenv("CRISISFLOW_DEBUG"))) {
                payload.put("detail", e.toString());
            }
        }

        try {
            sendJson(exchange, status, payload);
        } catch (IOException ignored) {
            // client went away
        }
    }

    private void sendJson(
            HttpExchange exchange, int status, Object data)
            throws IOException {

        byte[] bytes = gson.toJson(data).getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (!is(exchange, "GET")) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        if (path.equals("/")) {
            path = "/index.html";
        }

        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot).toLowerCase();

        if (path.contains("..") || path.contains("\\") || path.contains("\0")
                || path.contains("//") || !STATIC_TYPES.contains(ext)) {
            sendJson(exchange, 404, Map.of("error", "File not found"));
            return;
        }

        try (InputStream input = getClass().getResourceAsStream(
                "/static" + path)) {

            if (input == null) {
                sendJson(exchange, 404, Map.of("error", "File not found"));
                return;
            }

            byte[] bytes = input.readAllBytes();
            String contentType = switch (ext) {
                case ".html" -> "text/html; charset=UTF-8";
                case ".css" -> "text/css; charset=UTF-8";
                case ".js" -> "application/javascript; charset=UTF-8";
                case ".json" -> "application/json; charset=UTF-8";
                case ".svg" -> "image/svg+xml";
                case ".png" -> "image/png";
                case ".ico" -> "image/x-icon";
                default -> "application/octet-stream";
            };

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
