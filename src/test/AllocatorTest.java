package com.crisisflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crisisflow.Allocator.Plan;
import com.crisisflow.Allocator.Requirement;
import com.crisisflow.Allocator.Unit;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AllocatorTest {

    // Emergency at Dehradun clock tower
    private static final double LAT = 30.3255, LON = 78.0413;

    private static Unit unit(int id, String type, int cap, double lat, double lon) {
        return new Unit(id, type, cap, lat, lon);
    }

    private static final Map<String, Integer> REF = Map.of("Ambulance", 4, "Rescue Team", 10);

    @Test
    public void picksNearestResource() {
        Unit far = unit(1, "Ambulance", 4, 30.50, 78.20);
        Unit near = unit(2, "Ambulance", 4, 30.3260, 78.0420);
        Plan p = Allocator.plan("LOW", 2, LAT, LON, List.of("Ambulance"),
            List.of(), List.of(far, near), REF);
        assertEquals(1, p.picks().size());
        assertEquals(2, p.picks().get(0).unit().id());
        assertEquals("Allocated", p.status());
    }

    @Test
    public void sendsSeveralUnitsUntilPeopleAreCovered() {
        List<Unit> pool = List.of(
            unit(1, "Ambulance", 4, 30.33, 78.04),
            unit(2, "Ambulance", 4, 30.34, 78.04),
            unit(3, "Ambulance", 4, 30.35, 78.04),
            unit(4, "Ambulance", 4, 30.36, 78.04));
        Plan p = Allocator.plan("HIGH", 9, LAT, LON, List.of("Ambulance"),
            List.of(), pool, REF);
        assertEquals(3, p.picks().size()); // 3 x 4 = 12 >= 9
        assertEquals("Allocated", p.status());
    }

    @Test
    public void priorityCapsNumberOfUnits() {
        List<Unit> pool = List.of(
            unit(1, "Ambulance", 1, 30.33, 78.04),
            unit(2, "Ambulance", 1, 30.34, 78.04),
            unit(3, "Ambulance", 1, 30.35, 78.04));
        Plan p = Allocator.plan("LOW", 40, LAT, LON, List.of("Ambulance"),
            List.of(), pool, Map.of("Ambulance", 1));
        assertEquals(1, p.picks().size());
        assertEquals("Allocated", p.status()); // cap reached counts as satisfied
    }

    @Test
    public void partialWhenOneTypeIsMissing() {
        Plan p = Allocator.plan("HIGH", 12, LAT, LON,
            List.of("Fire Unit", "Ambulance"), List.of(),
            List.of(unit(1, "Fire Unit", 6, 30.33, 78.04)), REF);
        assertEquals(1, p.picks().size());
        assertEquals("Partial", p.status());
        // 12 people need more than one 6-person fire unit, and no ambulance exists
        assertTrue(p.unmet().stream().anyMatch(u -> u.typeName().equals("Ambulance")));
    }

    @Test
    public void pendingWhenNothingAvailable() {
        Plan p = Allocator.plan("MEDIUM", 5, LAT, LON, List.of("Rescue Team"),
            List.of(), List.of(), REF);
        assertTrue(p.picks().isEmpty());
        assertEquals("Pending", p.status());
        assertEquals(1, p.unmet().get(0).units());
    }

    @Test
    public void topsUpWhenAlreadyHoldingSomeUnits() {
        Unit held = unit(1, "Ambulance", 4, 30.33, 78.04);
        Unit free = unit(2, "Ambulance", 4, 30.34, 78.04);
        Plan p = Allocator.plan("HIGH", 7, LAT, LON, List.of("Ambulance"),
            List.of(held), List.of(free), REF);
        assertEquals(1, p.picks().size()); // 4 held + 4 new >= 7
        assertEquals("Allocated", p.status());
    }

    @Test
    public void alreadyCoveredEmergencyGetsNothingMore() {
        Unit held = unit(1, "Ambulance", 10, 30.33, 78.04);
        Plan p = Allocator.plan("HIGH", 7, LAT, LON, List.of("Ambulance"),
            List.of(held), List.of(unit(2, "Ambulance", 4, 30.34, 78.04)), REF);
        assertTrue(p.picks().isEmpty());
        assertEquals("Allocated", p.status());
    }

    @Test
    public void unknownCoordinatesAreChosenLast() {
        Unit noCoords = unit(1, "Ambulance", 4, 0, 0);
        Unit unknown = new Unit(2, "Ambulance", 4, null, null);
        Unit located = unit(3, "Ambulance", 4, 30.40, 78.10);
        Plan p = Allocator.plan("LOW", 1, LAT, LON, List.of("Ambulance"),
            List.of(), List.of(unknown, noCoords, located), REF);
        assertEquals(3, p.picks().get(0).unit().id());
    }

    @Test
    public void requirementsDependOnPeopleAndDescription() {
        List<Requirement> accident = List.of(
            new Requirement("Ambulance", 0), new Requirement("Medical Team", 10));
        assertEquals(List.of("Ambulance"),
            Allocator.requiredTypes("Major Accident", "two cars", 3, accident));
        assertEquals(List.of("Ambulance", "Medical Team"),
            Allocator.requiredTypes("Major Accident", "two cars", 12, accident));
        assertEquals(List.of("Ambulance", "Fire Unit"),
            Allocator.requiredTypes("Major Accident", "Truck on FIRE", 3, accident));
    }

    @Test
    public void priorityRankingOrdersCriticalFirst() {
        assertTrue(Allocator.priorityRank("CRITICAL") < Allocator.priorityRank("HIGH"));
        assertTrue(Allocator.priorityRank("HIGH") < Allocator.priorityRank("MEDIUM"));
        assertTrue(Allocator.priorityRank("MEDIUM") < Allocator.priorityRank("LOW"));
    }

    @Test
    public void haversineMatchesKnownDistance() {
        // one degree of longitude on the equator ~ 111.19 km
        assertEquals(111.19, GeoUtil.haversineKm(0.0, 0.0, 0.0, 1.0), 0.1);
        assertEquals(null, GeoUtil.haversineKm(null, 1.0, 0.0, 1.0));
        assertEquals(1, GeoUtil.etaMinutes(0.1));
        assertEquals(30, GeoUtil.etaMinutes(20));
    }

    @Test
    public void priorityRulesUnchanged() {
        assertEquals("CRITICAL", Rules.calculatePriority("Flood", 50));
        assertEquals("HIGH", Rules.calculatePriority("Landslide", 10));
        assertEquals("MEDIUM", Rules.calculatePriority("Fire", 3));
        assertEquals("LOW", Rules.calculatePriority("Landslide", 3));
        assertEquals("Major Accident", Rules.normalizeEmergencyType("major_accident"));
    }
}
