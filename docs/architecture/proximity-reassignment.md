# Proximity-Based Resource Reassignment Mechanism

CrisisFlow implements an opportunistic reassignment service (`ProximityReassignment.java`) that identifies when an en-route resource can be diverted to save lives or significantly reduce critical response latency.

## Motivation & Problem Statement

In standard dispatch systems, resources are locked to an incident once dispatched. However, in dynamic disaster scenarios:
1. A new `CRITICAL` emergency may be reported within minutes after an ambulance was dispatched to a distant `MEDIUM` incident.
2. An en-route resource may pass directly beside a newly reported high-urgency incident while traveling to its original assignment.

Refusing to divert units in these scenarios leads to avoidable delays. `ProximityReassignment` balances responsiveness and stability by providing algorithmic recommendations and automated redirection.

## Reassignment Criteria

An opportunity is recognized only when all of the following conditions hold:

| Criterion | Constraint | Purpose |
|---|---|---|
| **Resource State** | Status is `Dispatched` or `Responding` | Units already on-scene (`On Scene`) cannot be pulled away. |
| **Max Radius** | Distance $\le 10.0$ km | Constrains evaluation to the local geographic operational area. |
| **Priority Ranking** | `priorityRank(target) <= priorityRank(source)` | Unit is only diverted to an incident of equal or higher priority. |
| **Minimum Time Savings** | $\Delta t \ge 5$ minutes | Prevents thrashing and minor course corrections. |
| **Resource Capability** | Target incident requires the resource's type | Ensures operational fit. |

## Time Savings Evaluation Formula

Let:
- $d_{\text{current}}$ be the distance from the resource's current GPS position to its currently assigned incident.
- $d_{\text{suggested}}$ be the distance from the resource's current GPS position to the waiting incident.
- $v_{\text{avg}}$ be the average response vehicle velocity (e.g. 40 km/h in urban response).

$$\Delta t = \frac{d_{\text{current}} - d_{\text{suggested}}}{v_{\text{avg}}} \times 60 \text{ minutes}$$

If $\Delta t \ge 5.0\text{ min}$ and $d_{\text{suggested}} \le 10.0\text{ km}$, an `Opportunity` record is generated.

## Reassignment Transaction Lifecycle

When a reassignment is executed via `/api/reassign`:
1. The current active allocation in table `allocations` is marked `status = 'Released'` with `released_at = CURRENT_TIMESTAMP`.
2. A new allocation record is inserted with `status = 'Active'` connecting the resource to the new emergency.
3. The previous emergency status is set back to `Partial` or `Pending` if requirements become unmet.
4. An automated notification is logged in `notifications` notifying the dispatch operator and response team of the route change.
