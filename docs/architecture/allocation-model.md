# Resource Allocation Model and Lifecycle

This document describes the pure decision model implemented in `Allocator.java` and its transactional execution via `Allocation.java`.

## Allocation Principles

1. **Deterministic Nearest-Neighbor Selection:** Candidates are sorted strictly by Haversine distance relative to the emergency coordinates.
2. **Priority-Tiered Quotas:** The maximum number of response units dispatched for each resource type depends on the incident's priority level.
3. **Decoupled Architecture:** `Allocator.java` executes with zero database or network dependencies, making it directly unit-testable.

## Unit Capacity & Priority Quotas

The maximum units of any single type that an incident can request is determined by `Allocator.maxUnitsFor(priority)`:

| Priority Level | Priority Rank | Max Units per Type |
|---|---|---|
| `CRITICAL` | 1 | 5 units |
| `HIGH` | 2 | 3 units |
| `MEDIUM` | 3 | 2 units |
| `LOW` | 4 | 1 unit |

## Dispatch Decision Algorithm

```
For each required resource type (from response_requirements):
    1. Count existing allocated units held by the incident.
    2. Needed = min(required_quota, maxUnitsFor(priority)) - existing_units.
    3. If Needed <= 0: continue to next type.
    4. Find candidate available resources matching type.
    5. Sort candidates by distance = Haversine(unit_coords, emergency_coords).
    6. Take the top (closest) N units where N = min(candidates.size(), Needed).
    7. Record Picks for selected units.
    8. If candidates.size() < Needed:
           Record Unmet requirement for the difference.
```

### Result Status Classification

- **`Allocated`**: All required resources across all needed types have been successfully assigned.
- **`Partial`**: At least one resource was assigned, but one or more unmet requirements remain.
- **`Pending`**: No available resources could be assigned at this time.

## Resource Lifecycle State Machine

Resources transition through standardized lifecycle states:

```
[ Available ] ----(Dispatch)----> [ Dispatched ]
      ^                                  |
      |                            (Arrive on Scene)
      |                                  v
  (Release) <---------------------- [ On Scene ]
```

1. **`Available`**: Unit is idle at station or base and ready for new tasking.
2. **`Dispatched`**: Unit is actively en route to the designated emergency location.
3. **`On Scene`**: Unit has arrived at the emergency site and is performing emergency operations.
4. **`Released`**: Unit operations are complete; unit is reset to `Available`.
