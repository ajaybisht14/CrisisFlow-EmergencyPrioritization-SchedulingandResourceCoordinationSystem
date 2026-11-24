# Operational and Route Endpoints API Reference

API endpoints supporting allocation orchestration, route visualization, dispatcher notifications, system telemetry, and reassignment control.

## Endpoints Summary

| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/allocations` | List active and historical resource allocations | No |
| `POST` | `/api/allocate-pending` | Trigger an immediate batch allocation cycle | Yes (`X-API-Key` if configured) |
| `GET` | `/api/routes` | Compute route geometry and turn points between resource and scene | No |
| `GET` | `/api/notifications` | Fetch dispatch alerts and status event log | No |
| `GET` | `/api/metrics` | Retrieve operational metrics and KPI summaries | No |
| `POST` | `/api/priority` | Dry-run priority scoring simulation | No |
| `GET` | `/api/reassignment-opportunities` | Query available proximity reassignment suggestions | No |
| `POST` | `/api/reassign` | Execute a proposed proximity reassignment | Yes (`X-API-Key` if configured) |

---

## 1. Trigger Batch Allocation (`POST /api/allocate-pending`)

Forces an immediate execution of `Allocation.runQuietly()`. Useful when multiple new resources have come online and the operator does not wish to wait for the periodic background sweeper.

### Sample Response (`200 OK`)

```json
{
  "success": true,
  "allocated_count": 4,
  "unmet_count": 1,
  "timestamp": "2026-11-24T20:45:10Z"
}
```

---

## 2. Route Navigation (`GET /api/routes?allocation_id=45`)

Calculates navigational geometry between the assigned resource's current GPS location and the emergency scene.

### Sample Response (`200 OK`)

```json
{
  "allocation_id": 45,
  "resource_id": 8,
  "emergency_id": 102,
  "distance_km": 4.2,
  "eta_minutes": 9,
  "origin": { "lat": 28.6120, "lng": 77.2105 },
  "destination": { "lat": 28.6280, "lng": 77.2185 },
  "waypoints": [
    [28.6120, 77.2105],
    [28.6190, 77.2140],
    [28.6280, 77.2185]
  ]
}
```

---

## 3. System Metrics (`GET /api/metrics`)

Returns real-time operational statistics for command staff dashboards:

```json
{
  "active_emergencies": 7,
  "pending_emergencies": 2,
  "critical_emergencies": 3,
  "available_resources": 14,
  "deployed_resources": 9,
  "fleet_utilization_percent": 39.1,
  "avg_response_eta_minutes": 11.4
}
```

---

## 4. Proximity Reassignment (`GET` & `POST`)

### Fetch Opportunities (`GET /api/reassignment-opportunities`)

```json
[
  {
    "resource_id": 14,
    "resource_name": "Rescue Team Delta",
    "current_emergency_id": 98,
    "current_priority": "MEDIUM",
    "suggested_emergency_id": 103,
    "suggested_priority": "CRITICAL",
    "current_distance_km": 7.8,
    "suggested_distance_km": 1.9,
    "time_savings_minutes": 8.8,
    "reason": "Closer critical incident (life-threat reported)"
  }
]
```

### Apply Reassignment (`POST /api/reassign`)

```json
{
  "resource_id": 14,
  "old_emergency_id": 98,
  "new_emergency_id": 103
}
```
