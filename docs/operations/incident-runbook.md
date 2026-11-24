# Emergency Dispatcher Incident Response Runbook

This Standard Operating Procedure (SOP) guides Emergency Operations Center (EOC) operators and dispatch coordinators using CrisisFlow during disaster response operations.

```
+-------------------------------------------------------------------------------+
|                        INCIDENT RESPONSE LIFECYCLE                            |
|                                                                               |
|  [ Call / Report ] ---> [ Priority Assessment ] ---> [ Automated Allocation ] |
|                                   |                           |               |
|                                   v                           v               |
|                          { Manual Review? }           { Unmet Demands? }      |
|                                                               |               |
|                                                               v               |
|  [ Incident Resolved ] <--- [ On-Scene Ops ] <--- [ Units Dispatched ]        |
+-------------------------------------------------------------------------------+
```

---

## Phase 1: Incident Ingestion and Triage

1. **Intake Entry:**
   - Navigate to the **CrisisFlow Dashboard** (`http://localhost:8080/`).
   - Enter disaster type, specific location landmark, accurate GPS coordinates (or select on the map), estimated victim count, and narrative description.
2. **Review Priority Score:**
   - If the incident involves severe entrapment, structural failure, or active fires, ensure the priority is escalated to `CRITICAL`.
   - The `PriorityEngine` flags `manualReview: true` for any incident reaching score $\ge 80$.
3. **Dispatch Confirmation:**
   - Submit the incident. The system immediately attempts automatic assignment.

---

## Phase 2: Managing Resource Allocation States

After submission, monitor the `allocation_status` column:

### Case A: Status is `Allocated`
- All required units (ambulances, rescue teams, fire units) have been successfully assigned.
- Open `/map.html` to track the en-route units and their estimated time of arrival (ETA).

### Case B: Status is `Partial`
- Some required units were assigned, but specific assets were depleted (e.g. all ambulances in use).
- **Dispatcher Actions:**
  1. Check the `Unmet` requirements listed in the incident card.
  2. Request mutual aid or reserve units from neighboring administrative zones.
  3. Register new available mutual aid units via `/api/resources`.
  4. Click **Run Allocation Sweeper** (`POST /api/allocate-pending`) to dispatch newly registered units.

### Case C: Status is `Pending`
- Zero units of the required types were currently available.
- Incident remains at the top of the queue sorted by priority.
- If wait time exceeds 30 minutes, the engine automatically adds penalty score points to prevent starvation.

---

## Phase 3: In-Transit Proximity Reassignment

When a critical life-threatening emergency occurs while units are en route to an earlier incident:
1. Check the **Reassignment Suggestions** panel on the dashboard.
2. If `ProximityReassignment` identifies that an en-route unit is passing within 10 km of a higher-priority scene and saves $\ge 5$ minutes:
   - Click **Approve Reassignment**.
   - The unit's navigation is updated to the critical scene.
   - The original incident is placed back into the allocation queue for the next available unit.

---

## Phase 4: Incident De-escalation and Fleet Release

1. Once field teams report that all victims are treated or evacuated:
   - Update the emergency response status to `Resolved`.
2. **Automated Teardown:**
   - CrisisFlow automatically sets the allocation record status to `Released`.
   - All associated resources transition back to `Available`.
   - Units immediately become candidates for any waiting incidents in the queue.
