package com.crisisflow;

import java.util.Locale;
import java.util.Set;

public class Rules {

    public static final int MAX_PEOPLE = 1_000_000;

    private static final Set<String> VALID_TYPES = Set.of(
        "Flood", "Earthquake", "Fire",
        "Landslide", "Major Accident"
    );

    private static final Set<String> SEVERE_TYPES = Set.of(
        "Flood", "Earthquake", "Fire"
    );

    public static boolean isValidEmergencyType(String type) {
        return type != null
            && VALID_TYPES.contains(normalizeEmergencyType(type));
    }

    public static String normalizeEmergencyType(String type) {
        if (type == null) {
            return "";
        }

        String normalized = type.trim()
            .toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "flood" -> "Flood";
            case "earthquake" -> "Earthquake";
            case "fire" -> "Fire";
            case "landslide" -> "Landslide";
            case "major accident", "major_accident" -> "Major Accident";
            default -> type.trim();
        };
    }

    public static String calculatePriority(String type, int people) {
        if (people >= 50) {
            return "CRITICAL";
        }

        if (people >= 10) {
            return "HIGH";
        }

        if (SEVERE_TYPES.contains(normalizeEmergencyType(type))) {
            return "MEDIUM";
        }

        return "LOW";
    }

    public static boolean isValidCoordinate(
            double latitude, double longitude) {
        return Double.isFinite(latitude)
            && Double.isFinite(longitude)
            && latitude >= -90 && latitude <= 90
            && longitude >= -180 && longitude <= 180;
    }
}
