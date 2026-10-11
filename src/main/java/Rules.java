
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Rules {

    private static final List<String> SUPPORTED_TYPES =
            Arrays.asList(
                    "Flood",
                    "Earthquake",
                    "Fire",
                    "Accident",
                    "Landslide"
            );

    public static String normalizeEmergencyType(String type) {
        if (type == null) {
            return null;
        }

        for (String supported : SUPPORTED_TYPES) {
            if (supported.equalsIgnoreCase(type.trim())) {
                return supported;
            }
        }

        return null;
    }

    public static boolean isValidEmergencyType(String type) {
        return normalizeEmergencyType(type) != null;
    }

    public static int getPriorityRank(String priority) {
        if (priority == null) {
            return 5;
        }

        switch (priority.toUpperCase()) {
            case "CRITICAL": return 1;
            case "HIGH":     return 2;
            case "MEDIUM":   return 3;
            case "LOW":      return 4;
            default:         return 5;
        }
    }

    public static String calculatePriority(
            String emergencyType, int peopleAffected) {

        String type = normalizeEmergencyType(emergencyType);

        if (type == null || peopleAffected <= 0) {
            throw new IllegalArgumentException(
                    "Invalid emergency type or number of people affected."
            );
        }

        if (peopleAffected >= 50) {
            return "CRITICAL";
        }

        if (type.equals("Earthquake")
                || type.equals("Flood")
                || type.equals("Fire")) {
            return peopleAffected >= 10 ? "HIGH" : "MEDIUM";
        }

        return peopleAffected >= 10 ? "HIGH" : "LOW";
    }

    public static List<Integer> getRequiredResourceTypes(
            String emergencyType, int peopleAffected) {

        String type = normalizeEmergencyType(emergencyType);

        if (type == null || peopleAffected <= 0) {
            throw new IllegalArgumentException(
                    "Invalid emergency details."
            );
        }

        List<Integer> types = new ArrayList<>();

        switch (type) {
            case "Fire":
                types.add(4);
                types.add(1);
                break;

            case "Flood":
            case "Landslide":
            case "Earthquake":
                types.add(2);
                types.add(3);
                types.add(1);
                break;

            case "Accident":
                types.add(1);
                types.add(3);
                break;

            default:
                throw new IllegalArgumentException(
                        "Unsupported emergency type."
                );
        }

        if (peopleAffected >= 10 && !types.contains(3)) {
            types.add(3);
        }

        return types;
    }
}
