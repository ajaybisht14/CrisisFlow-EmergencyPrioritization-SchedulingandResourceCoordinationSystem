package com.crisisflow;

import static org.junit.Assert.*;
import org.junit.Test;

public class PriorityEngineTest {
    @Test public void immediateLifeThreatEscalatesSmallIncident() {
        PriorityEngine.Result r = PriorityEngine.assess("Major Accident", 2, "people trapped, critical injuries", 3, 10);
        assertEquals("CRITICAL", r.priority());
        assertTrue(r.manualReview());
        assertTrue(r.reasons().stream().anyMatch(x -> x.contains("life-threat")));
    }
    @Test public void longWaitRaisesScore() {
        PriorityEngine.Result early = PriorityEngine.assess("Fire", 3, "smoke visible", 1, 10);
        PriorityEngine.Result late = PriorityEngine.assess("Fire", 3, "smoke visible", 65, 10);
        assertTrue(late.score() > early.score());
    }
}
