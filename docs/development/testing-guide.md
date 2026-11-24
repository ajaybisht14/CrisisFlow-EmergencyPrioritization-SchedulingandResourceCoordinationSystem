# Testing Guide and Quality Assurance Checklist

This document details automated test execution, unit test suites, edge-case coverage, and manual end-to-end verification workflows for CrisisFlow.

## 1. Automated Test Execution

Tests are implemented using standard JUnit and run with Maven:

```bash
mvn test
```

To run a specific test class:
```bash
mvn test -Dtest=AllocatorTest
mvn test -Dtest=PriorityEngineTest
```

---

## 2. Test Suites Overview

### `AllocatorTest`
Verifies pure allocation decisions without mock databases or external network latency:
- **Nearest-Neighbor Verification:** Ensures that given three available ambulances at distances 12 km, 3 km, and 8 km, the unit at 3 km is selected first.
- **Quota Enforcements:** Confirms an incident marked `CRITICAL` receives up to 5 units of a required type, while a `LOW` incident receives at most 1 unit.
- **Partial Allocation Handling:** Validates that when only 1 unit is available but 3 are needed, 1 pick is made and an `Unmet` entry with count 2 is recorded.
- **Idempotency:** Re-running the allocator on an already fully serviced emergency yields zero redundant picks.

### `PriorityEngineTest`
Verifies deterministic triage scoring rules:
- **Life-Threat Override:** Asserts that descriptions containing phrases like `"trapped in collapsed building"` immediately elevate any incident to `CRITICAL` (score $\ge 80$).
- **Wait Time Escalation:** Confirms that after 60 minutes of unserviced waiting, a `MEDIUM` emergency gains 15 penalty points.
- **Manual Review Flag:** Verifies `manualReview` is true for critical events.

---

## 3. Manual End-to-End Verification Checklist

Perform these steps before releasing a version or staging a deployment:

| Step | Action | Expected Result | Pass/Fail |
|---|---|---|---|
| **1. Startup Check** | Start app with `mvn exec:java` | Console outputs dashboard & map URLs, schema auto-migration succeeds | [ ] |
| **2. Seed Data** | Load `seed_demo.sql` | 8 resources and 3 demo emergencies appear in MySQL | [ ] |
| **3. UI Load** | Navigate to `http://localhost:8080/` | Dashboard renders without console JS errors | [ ] |
| **4. Incident Submission** | Submit incident with "Gas leak with trapped workers" | Priority auto-scores `CRITICAL`, status enters `Allocated` or `Partial` | [ ] |
| **5. Live Map View** | Open `http://localhost:8080/map.html` | Red pulsating marker appears at incident coordinates | [ ] |
| **6. Dispatch Verification** | Check assigned unit status | Resource switches from `Available` to `Dispatched` | [ ] |
| **7. Incident Resolution** | Set incident status to `Resolved` | Associated resources are released back to `Available` | [ ] |
| **8. Security Enforcement** | Set `CRISISFLOW_API_KEY` and call `POST /api/emergencies` without header | API returns `401 Unauthorized` | [ ] |
