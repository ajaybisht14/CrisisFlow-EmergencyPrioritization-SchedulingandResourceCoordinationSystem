# Resource Management and Tracking API Reference

Endpoints for managing emergency response units, vehicle telemetry, and fleet operational status in CrisisFlow.

## Endpoints Summary

| Method | Endpoint | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/resources` | List all resources with status and coordinates | No |
| `POST` | `/api/resources` | Register a new emergency resource | Yes (`X-API-Key` if configured) |
| `GET` | `/api/resource-types` | List standard resource classifications | No |
| `POST` | `/api/resource-status` | Manually update resource availability | Yes (`X-API-Key` if configured) |
| `POST` | `/api/tracking` | Ingest real-time GPS coordinates for a unit | Yes (`X-API-Key` if configured) |
| `GET` | `/api/tracking` | Retrieve telemetry history for a resource | No |

---

## 1. List Resources (`GET /api/resources`)

Retrieves the current inventory of response assets.

### Query Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `status` | string | *all* | Filter by status (`Available`, `Dispatched`, `On Scene`, `Maintenance`). |
| `type` | string | *all* | Filter by resource type name (`Ambulance`, `Rescue Team`, etc.). |

### Sample Response (`200 OK`)

```json
[
  {
    "resource_id": 5,
    "resource_name": "Ambulance Unit 104",
    "type_id": 1,
    "type_name": "Ambulance",
    "capacity": 2,
    "location": "City General Hospital",
    "latitude": 28.6120,
    "longitude": 77.2105,
    "status": "Available"
  }
]
```

---

## 2. Register Resource (`POST /api/resources`)

Adds an asset to the operational resource pool.

### Request Payload

```json
{
  "resource_name": "Heavy Rescue Truck 3",
  "type_id": 2,
  "capacity": 6,
  "location": "District Fire Station",
  "latitude": 28.6304,
  "longitude": 77.2177,
  "status": "Available"
}
```

---

## 3. Update Resource Status (`POST /api/resource-status`)

Updates vehicle readiness or takes a unit offline for maintenance.

### Request Payload

```json
{
  "resource_id": 5,
  "status": "Maintenance"
}
```

---

## 4. Ingest GPS Telemetry (`POST /api/tracking`)

Ingests live coordinate updates from vehicle transponders, mobile MDTs, or driver companion applications.

### Request Payload

```json
{
  "resource_id": 5,
  "latitude": 28.6185,
  "longitude": 77.2142,
  "speed_kmh": 42.5,
  "heading_deg": 185.0
}
```

*Effects:*
1. Updates the `latitude` and `longitude` fields in the `resources` table.
2. Inserts an immutable row into `response_tracking` with timestamp and speed.
3. Recalculates remaining ETA to the destination emergency.
