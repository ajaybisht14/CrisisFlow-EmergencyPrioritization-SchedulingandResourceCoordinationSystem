package com.crisisflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure allocation logic: no database, no HTTP. Given one emergency, the units
 * it already holds and the units currently available, it decides which
 * additional units to send. Easy to unit-test; {@link Allocation} applies the
 * resulting plan to MySQL inside a transaction.
 */
public final class Allocator {

    private Allocator() {}

    /** One row of the response_requirements table. */
    public record Requirement(String typeName, int minPeople) {}

    /** A resource (held by the emergency or available to be assigned). */
    public record Unit(int id, String typeName, int capacity,
                       Double latitude, Double longitude) {}

    public record Pick(Unit unit, Double distanceKm) {}

    /** "Still need N more units of this type". */
    public record Unmet(String typeName, int units) {}

    public record Plan(List<Pick> picks, List<Unmet> unmet, String status) {}

    private static final Pattern FIRE_WORD =
        Pattern.compile("\\bfire\\b", Pattern.CASE_INSENSITIVE);

    /** Max number of units of one type sent to one emergency. */
    public static int maxUnitsFor(String priority) {
        if (priority == null) {
            return 1;
        }
        return switch (priority.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    /** CRITICAL = 1 ... LOW = 4 (lower is more urgent). */
    public static int priorityRank(String priority) {
        if (priority == null) {
            return 4;
        }
        return switch (priority.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 1;
            case "HIGH" -> 2;
            case "MEDIUM" -> 3;
            default -> 4;
        };
    }

    /**
     * Resource types an emergency needs, in order, without duplicates.
     *
     * @param forType requirement rows configured for this disaster type
     */
    public static List<String> requiredTypes(
            String disasterType, String description, int people,
            List<Requirement> forType) {

        Set<String> types = new LinkedHashSet<>();
        for (Requirement r : forType) {
            if (people >= r.minPeople()) {
                types.add(r.typeName());
            }
        }
        if ("Major Accident".equals(disasterType)
                && description != null
                && FIRE_WORD.matcher(description).find()) {
            types.add("Fire Unit");
        }
        return new ArrayList<>(types);
    }

    /**
     * Decide which available units to send.
     *
     * @param held         units this emergency already holds
     * @param available    units free to assign right now
     * @param refCapacity  typical capacity per type, used only to estimate how
     *                     many more units are missing
     */
    public static Plan plan(
            String priority, int people, Double lat, Double lon,
            List<String> requiredTypes,
            List<Unit> held, List<Unit> available,
            Map<String, Integer> refCapacity) {

        int cap = maxUnitsFor(priority);
        List<Pick> picks = new ArrayList<>();
        List<Unmet> unmet = new ArrayList<>();
        Set<Integer> used = new HashSet<>();

        for (String type : requiredTypes) {
            int count = 0;
            int covered = 0;
            for (Unit h : held) {
                if (h.typeName().equals(type)) {
                    count++;
                    covered += Math.max(1, h.capacity());
                }
            }

            List<Unit> candidates = new ArrayList<>();
            for (Unit u : available) {
                if (u.typeName().equals(type) && !used.contains(u.id())) {
                    candidates.add(u);
                }
            }
            candidates.sort(
                Comparator.comparingDouble((Unit u) -> {
                    Double d = GeoUtil.haversineKm(
                        lat, lon, u.latitude(), u.longitude());
                    return d == null ? Double.MAX_VALUE : d;
                })
                .thenComparing(Comparator.comparingInt(Unit::capacity).reversed())
                .thenComparingInt(Unit::id));

            int remaining = people - covered;
            int i = 0;
            while ((remaining > 0 || count == 0)
                    && count < cap && i < candidates.size()) {
                Unit u = candidates.get(i++);
                used.add(u.id());
                picks.add(new Pick(u,
                    GeoUtil.haversineKm(lat, lon, u.latitude(), u.longitude())));
                count++;
                remaining -= Math.max(1, u.capacity());
            }

            boolean satisfied = count > 0 && (remaining <= 0 || count >= cap);
            if (!satisfied) {
                int ref = Math.max(1, refCapacity.getOrDefault(type, 1));
                int missing = Math.max(1, Math.min(cap - count,
                    (int) Math.ceil(Math.max(remaining, 1) / (double) ref)));
                unmet.add(new Unmet(type, missing));
            }
        }

        String status;
        if (unmet.isEmpty()) {
            status = "Allocated";
        } else if (!picks.isEmpty() || !held.isEmpty()) {
            status = "Partial";
        } else {
            status = "Pending";
        }
        return new Plan(picks, unmet, status);
    }
}
