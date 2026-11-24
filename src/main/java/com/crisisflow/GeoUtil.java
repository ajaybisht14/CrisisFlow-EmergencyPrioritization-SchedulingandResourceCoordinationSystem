package com.crisisflow;

public final class GeoUtil {

    private static final double EARTH_RADIUS_KM = 6371.0088;
    /** Assumed average road speed used for ETA estimates. */
    public static final double AVERAGE_SPEED_KMH = 40.0;

    private GeoUtil() {}

    /** Great-circle distance in km, or null if either point is unknown/invalid. */
    public static Double haversineKm(
            Double lat1, Double lon1, Double lat2, Double lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null
                || !Rules.isValidCoordinate(lat1, lon1)
                || !Rules.isValidCoordinate(lat2, lon2)) {
            return null;
        }
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Rough travel time in whole minutes (at least 1). Estimate only. */
    public static int etaMinutes(double km) {
        return Math.max(1, (int) Math.ceil(km / AVERAGE_SPEED_KMH * 60));
    }
}
