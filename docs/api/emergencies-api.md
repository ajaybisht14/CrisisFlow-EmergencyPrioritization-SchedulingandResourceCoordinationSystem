# Emergency Management API Reference

Endpoints for registering, querying, and updating emergency incidents in CrisisFlow.

## Endpoints Summary

| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/emergencies` | List and filter active emergencies | No |
| `POST` | `/api/emergencies` | Report a new emergency incident | Yes (`X-API-Key` if configured) |
| `POST` | `/api/emergency-status` | Update incident response status | Yes (`X-API-Key` if configured) |

---

## 1. List Emergencies (`GET /api/emergencies`)

Returns a list of registered emergencies with their current triage priorities, geographical coordinates, and allocated resource summary.

### Query Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `status` | string | *all* | Filter by `allocation_status` (`Pending`, `Partial`, `Allocated`) or `response_status`. |
| `priority` | string | *all* | Filter by priority category (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`). |
| `limit` | integer | 100 | Maximum number of records to return. |

### Sample Response (`200 OK`)

```json
[
  {
    "emergency_id": 101,
    "disaster_type": "Flood",
    "location": "Riverside Sector 4",
    "latitude": 28.6139,
    "longitude": 77.2090,
    "people_affected": 35,
    "description": "Rising water levels trapped elderly residents on ground floor",
    "priority": "HIGH",
    "allocation_status": "Allocated",
    "response_status": "Responding",
    "reported_at": "2026-11-24T18:30:00Z",
    "resources": [
      {
        "resource_id": 12,
        "resource_name": "Rescue Boat Alpha",
        "type_name": "Rescue Team",
        "eta_minutes": 14
      }
    ]
  }
]
```

---

## 2. Report Emergency (`POST /api/emergencies`)

Registers a new emergency incident and triggers the priority calculation and automatic allocation engines.

### Request Headers

```http
Content-Type: application/json
X-API-Key: <secret-token>   # Required if CRISISFLOW_API_KEY environment variable is set
```

### Request Payload

```json
{
  "disaster_type": "Earthquake",
  "location": "Central Market Block B",
  "latitude": 28.6280,
  "longitude": 77.2185,
  "people_affected": 15,
  "description": "Collapsed building facade with trapped pedestrians",
  "priority": "CRITICAL"
}
```

*Note: If `priority` is omitted, the `PriorityEngine` automatically scores and assigns priority based on disaster type, people affected, and keyword analysis of the description.*

### Sample Response (`201 Created`)

```json
{
  "success": true,
  "emergency_id": 102,
  "priority": "CRITICAL",
  "score": 85,
  "allocation_status": "Allocated",
  "allocated_units": 3
}
```

---

## 3. Update Incident Status (`POST /api/emergency-status`)

Transitions an emergency through response phases (`Reported`, `Responding`, `On Scene`, `Resolved`, `Closed`).

### Request Payload

```json
{
  "emergency_id": 101,
  "status": "Resolved",
  "note": "All individuals evacuated safely to community shelter."
}
```

*Lifecycle Note: When an emergency is marked `Resolved` or `Closed`, all currently assigned active resources are automatically released back to `Available` status.*
