package com.crisisflow;

import java.util.ArrayList;
import java.util.List;

/** Transparent safety-oriented rule score; deliberately not represented as ML. */
public final class PriorityEngine {
    private PriorityEngine() {}
    public record Result(String priority, int score, boolean manualReview, List<String> reasons) {}
    public static Result assess(String disasterType, int people, String description, int waitMinutes, Integer etaMinutes) {
        String d = (disasterType == null ? "" : disasterType).toLowerCase();
        String desc = (description == null ? "" : description).toLowerCase();
        int score = switch (Rules.calculatePriority(disasterType, people)) {
            case "CRITICAL" -> 80; case "HIGH" -> 60; case "MEDIUM" -> 40; default -> 20;
        };
        List<String> reasons = new ArrayList<>();
        reasons.add("Baseline from existing CrisisFlow priority rules");
        boolean immediateLifeThreat = desc.matches(".*(trapped|life.threatening|critical injuries|casualt|unconscious|collapsed building).*");
        if (immediateLifeThreat) {
            // Safety override: an immediate threat to life must reach CRITICAL even
            // when the baseline priority and number of people affected are low.
            score = Math.max(score + 25, 80);
            reasons.add("Immediate life-threat indicator in description; safety escalation to CRITICAL");
        }
        if (people >= 50) { score += 10; reasons.add("50 or more people affected"); }
        else if (people >= 20) { score += 5; reasons.add("20 or more people affected"); }
        if (waitMinutes >= 60) { score += 15; reasons.add("Waiting over 60 minutes"); }
        else if (waitMinutes >= 30) { score += 8; reasons.add("Waiting over 30 minutes"); }
        if (etaMinutes != null && etaMinutes >= 45) { score += 8; reasons.add("Long estimated response time"); }
        if (d.contains("earthquake") || d.contains("fire") || d.contains("landslide")) { score += 3; reasons.add("Disaster type has escalation potential"); }
        score = Math.min(100, score);
        String priority = score >= 80 ? "CRITICAL" : score >= 60 ? "HIGH" : score >= 40 ? "MEDIUM" : "LOW";
        boolean review = desc.matches(".*(trapped|life.threatening|critical injuries|casualt|unconscious|collapsed building).*" ) || score >= 80;
        return new Result(priority, score, review, List.copyOf(reasons));
    }
}
