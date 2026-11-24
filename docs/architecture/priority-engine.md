# Priority Calculation Engine Specification

The `PriorityEngine` is a deterministic, safety-oriented rule evaluation subsystem in CrisisFlow. It intentionally avoids black-box machine learning models to ensure that emergency triage decisions remain transparent, explainable, and auditable for emergency services personnel.

## Assessment Pipeline

The engine evaluates an incident via `PriorityEngine.assess(disasterType, people, description, waitMinutes, etaMinutes)` returning a `Result(priority, score, manualReview, reasons)`.

### 1. Baseline Severity Scoring

The engine first resolves the baseline priority category using the configured emergency rules (`Rules.calculatePriority`):

| Baseline Category | Initial Score | Description |
|---|---|---|
| `CRITICAL` | 80 | Catastrophic events, structural collapses, mass-casualty events |
| `HIGH` | 60 | Severe incidents with immediate hazard to multiple individuals |
| `MEDIUM` | 40 | Moderate scale emergencies requiring rapid standard response |
| `LOW` | 20 | Non-life-threatening or contained incidents |

### 2. Immediate Life-Threat Override

A regex pattern matches critical hazard indicators in the incident description:
```java
.*(trapped|life.threatening|critical injuries|casualt|unconscious|collapsed building).*
```

When an indicator is detected:
- The score increases by at least 25 points.
- The score is guaranteed to reach at least 80 (`Math.max(score + 25, 80)`), forcing the priority category to `CRITICAL`.
- A safety escalation rationale is added to the diagnostic reasons list.

### 3. Population Impact Modifier

The number of casualties or trapped individuals increases incident severity:
- **$\ge$ 50 people affected:** $+10$ points
- **$\ge$ 20 people affected:** $+5$ points

### 4. Wait-Time Starvation Escalation

To prevent lower-priority emergencies from languishing indefinitely while higher-priority calls arrive, a dynamic time penalty is applied based on elapsed wait time:
- **$\ge$ 60 minutes:** $+15$ points
- **$\ge$ 30 minutes:** $+8$ points

### 5. Extended Response Time (ETA) Factor

If the estimated travel time of the nearest available resource is prolonged:
- **ETA $\ge$ 45 minutes:** $+8$ points

### 6. Disaster Escalation Risk

Events with known rapid secondary hazards (e.g. aftershocks, fire spread, slope instability) receive a $+3$ point multiplier if the disaster type includes:
- `earthquake`
- `fire`
- `landslide`

### 7. Final Normalization and Manual Review Flag

1. **Score Cap:** Score is capped at 100 (`Math.min(100, score)`).
2. **Category Boundaries:**
   - Score $\ge 80 \implies$ `CRITICAL`
   - Score $\ge 60 \implies$ `HIGH`
   - Score $\ge 40 \implies$ `MEDIUM`
   - Score $< 40 \implies$ `LOW`
3. **Manual Review Flag:** Set to `true` whenever an immediate life-threat indicator matches OR the total score is $\ge 80$.
