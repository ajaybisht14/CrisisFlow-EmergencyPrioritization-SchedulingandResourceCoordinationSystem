package com.crisisflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OSRM road routing fallback. OSRM public demo does not provide live traffic. */
public final class RouteService {
    private RouteService() {}
    public static Map<String,Object> route(double fromLat,double fromLon,double toLat,double toLon) throws Exception {
        validate(fromLat,fromLon); validate(toLat,toLon);
        String base = System.getenv().getOrDefault("CRISISFLOW_ROUTING_URL", "https://router.project-osrm.org");
        String url = base.replaceAll("/$", "") + "/route/v1/driving/" + fromLon + "," + fromLat + ";" + toLon + "," + toLat + "?overview=full&geometries=geojson&steps=false";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Routing provider returned HTTP " + res.statusCode());
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!"Ok".equals(root.get("code").getAsString()) || root.getAsJsonArray("routes").isEmpty()) throw new IllegalStateException("No road route found");
        JsonObject r = root.getAsJsonArray("routes").get(0).getAsJsonObject();
        JsonObject geom = r.getAsJsonObject("geometry");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("distanceKm", Math.round(r.get("distance").getAsDouble()/10.0)/100.0);
        out.put("durationMinutes", Math.max(1, Math.round(r.get("duration").getAsDouble()/60.0)));
        out.put("trafficAware", false); out.put("provider", "OSRM");
        out.put("trafficLabel", "Traffic data unavailable — road-network estimate, not live traffic");
        out.put("updatedAt", Instant.now().toString()); out.put("geometry", geom.getAsJsonArray("coordinates"));
        return out;
    }
    private static void validate(double lat,double lon) { if (!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat>90||lon < -180||lon>180) throw new IllegalArgumentException("Latitude/longitude out of range"); }
}
