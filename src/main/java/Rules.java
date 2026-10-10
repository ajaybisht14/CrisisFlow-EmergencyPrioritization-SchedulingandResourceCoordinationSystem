
import java.util.ArrayList;
import java.util.List;

public class Rules {

    public static String calculatePriority(
            String emergencyType,
            int peopleAffected) {

        if (peopleAffected >= 50) {
            return "CRITICAL";
        }

        if (emergencyType.equalsIgnoreCase("Earthquake")
                || emergencyType.equalsIgnoreCase("Flood")
                || emergencyType.equalsIgnoreCase("Fire")) {

            if (peopleAffected >= 10) {
                return "HIGH";
            }

            return "MEDIUM";
        }

        if (peopleAffected >= 10) {
            return "HIGH";
        }

        return "LOW";
    }

    public static List<Integer> getRequiredResourceTypes(
            String emergencyType,
            int peopleAffected) {

        List<Integer> types = new ArrayList<>();

        if (emergencyType.equalsIgnoreCase("Fire")) {
            types.add(4); // Fire Unit
            types.add(1); // Ambulance
        } else if (emergencyType.equalsIgnoreCase("Flood")
                || emergencyType.equalsIgnoreCase("Landslide")) {
            types.add(2); // Rescue Team
            types.add(3); // Medical Team
            types.add(1); // Ambulance
        } else if (emergencyType.equalsIgnoreCase("Earthquake")) {
            types.add(2); // Rescue Team
            types.add(3); // Medical Team
            types.add(1); // Ambulance
        } else if (emergencyType.equalsIgnoreCase("Accident")) {
            types.add(1); // Ambulance
            types.add(3); // Medical Team
        } else {
            types.add(1); // Default: Ambulance
        }

        if (peopleAffected >= 10 && !types.contains(3)) {
            types.add(3); // Add Medical Team for larger incidents
        }

        return types;
    }
}