# CrisisFlow

Emergency management dashboard: report emergencies, keep an inventory of response
resources, and let the system **assign the nearest suitable resources automatically**.

## Run (VS Code / terminal)

1. Install JDK 17+, Maven, MySQL 8 (needs 8.0.1+), VS Code + "Extension Pack for Java".
2. In MySQL run `crisisflow.sql` (creates DB `crisisflow_v2`, tables, rules, sample resources).
   Optional demo data: `seed_demo.sql` (8 more resources, 3 sample emergencies).
3. Set credentials and start:

   Windows PowerShell:
       $env:CRISISFLOW_DB_USER="root"; $env:CRISISFLOW_DB_PASSWORD="yourpassword"
       mvn compile exec:java
   macOS/Linux:
       export CRISISFLOW_DB_USER=root CRISISFLOW_DB_PASSWORD=yourpassword
       mvn compile exec:java

4. Open http://localhost:8080/ (map: http://localhost:8080/map.html)

Or with Docker: `docker compose up --build` (starts MySQL + the app with demo data).

Tests: `mvn test`

Old databases are upgraded automatically on startup (new columns/tables are added).

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `CRISISFLOW_DB_USER` | – (required) | MySQL user |
| `CRISISFLOW_DB_PASSWORD` | empty | MySQL password |
| `CRISISFLOW_DB_HOST` / `_PORT` / `_NAME` | localhost / 3306 / crisisflow_v2 | Database location |
| `CRISISFLOW_DB_POOL` | 10 | Max pooled connections |
| `CRISISFLOW_PORT` | 8080 | HTTP port |
| `CRISISFLOW_AUTO_INTERVAL` | 30 | Seconds between background allocation sweeps |
| `CRISISFLOW_API_KEY` | unset | If set, every non-GET API call needs header `X-API-Key`. The dashboard asks for it once per browser session. |

## How automatic allocation works

Resources are assigned with no button press whenever:
an emergency is reported · a resource is added · a resource becomes Available ·
an emergency is resolved / cancelled / deleted · a resource is deleted · every 30 s (safety sweep).
(The "Re-run allocation now" button is only a fallback.)

For each waiting emergency, most urgent first (CRITICAL → LOW, then oldest):

1. **What it needs** comes from table `response_requirements` (edit rows to change the rules):

   | Emergency | Sends |
   |---|---|
   | Flood | Rescue Team, Medical Team (if ≥ 10 people) |
   | Earthquake | Rescue Team, Medical Team, Ambulance |
   | Fire | Fire Unit, Ambulance |
   | Landslide | Rescue Team, Medical Team (if ≥ 10 people) |
   | Major Accident | Ambulance, Medical Team (if ≥ 10 people), + Fire Unit if the description mentions "fire" |

2. **How many units of a type**: `capacity` = people one unit can serve. Units are added
   until the affected people are covered, up to a cap by priority
   (LOW 1, MEDIUM 2, HIGH 3, CRITICAL 5 per type).
3. **Which units**: the nearest available one (Haversine distance), then larger capacity.
   Units without coordinates are chosen last.
4. Everything for one emergency is assigned in one DB transaction with row locks.
5. Status: `Allocated` (all needs met), `Partial` (some), `Pending` (none). Partial/Pending
   emergencies are retried automatically.

Distance and a rough ETA (straight line at 40 km/h – an estimate, not GPS) are stored per assignment.

## Deleting

* **Resource**: `DELETE /api/resources/{id}`. If it is assigned, the API answers 409 and the UI asks
  "Delete anyway and reassign?" (`?force=true`). The emergency is re-queued and another unit is
  assigned automatically.
* **Emergency**: `DELETE /api/emergencies/{id}` frees its resources for others.
* Both are soft deletes (`is_deleted`), so allocation history stays intact.

## API

| Method & path | Purpose |
|---|---|
| `GET/POST /api/emergencies` | list (with assigned resources) / register |
| `DELETE /api/emergencies/{id}` | delete |
| `GET/POST /api/resources` | list / add |
| `DELETE /api/resources/{id}[?force=true]` | delete |
| `GET /api/resource-types` | resource types |
| `GET /api/allocations` | `{allocations, unmet}` – assignments and what waiting emergencies still need |
| `POST /api/allocate-pending` | run allocation now |
| `POST /api/emergency-status` | `{emergencyId, status, note?}` – Reported/Responding/Resolved/Cancelled |
| `POST /api/resource-status` | `{resourceId, status}` – Available/Unavailable |

Place-name search uses OpenStreetMap Nominatim, so the browser needs internet.

## Enhanced routing, tracking, priorities and notifications

This enhanced version adds comprehensive response management features with database schema support for lifecycle timestamps, location tracking history, in-app notifications, priority reassessment, and route visualization. All existing tables and data are retained. Startup adds missing columns/tables with `CREATE TABLE IF NOT EXISTS` and additive column checks; a reference migration is at `db/migrations/V2__response_tracking_notifications.sql`.

### **Feature 1: Road routing and route visualization**

`GET /api/routes?fromLat=30.32&fromLon=78.04&toLat=30.37&toLon=78.08`

- Uses OSRM (Open Source Routing Machine) for road-network routing with real geometry
- Returns kilometers, duration in minutes, GeoJSON coordinates for map display, provider name, and update time
- **Note:** The public OSRM demo service is best-effort and does **not** provide live traffic data
- The Live Map displays road routes as blue lines between resources and assigned emergencies
- Dashed lines indicate when routing service is unavailable (falls back to straight-line approximation)
- Configure `CRISISFLOW_ROUTING_URL` environment variable for production OSRM instance

### **Feature 2: Emergency prioritization based on severity and traffic**

`POST /api/priority` with `{"emergencyId":1}`

- Transparent **rule-based scoring system** (not machine learning) that considers:
  - Baseline CrisisFlow priority rules (based on disaster type and people affected)
  - Immediate life-threat indicators in description (trapped, casualties, critical injuries, etc.)
  - Number of people affected (escalation at 20+ and 50+)
  - Time spent waiting (escalation at 30 and 60 minutes)
  - Long estimated response times (escalation at 45+ minutes)
  - Disaster types with escalation potential (earthquake, fire, landslide)
- Returns priority recommendation (CRITICAL/HIGH/MEDIUM/LOW), numerical score (0-100), manual review flag, and detailed explanation of scoring reasons
- **Safety-first design:** Immediate threats to life automatically escalate to CRITICAL even when baseline priority is low
- Traffic data is **not** used for safety-critical decisions because default provider has no live traffic
- Priority scores and reasons are persisted in the database (`priority_score`, `priority_reason` columns)
- Dashboard displays "Priority Alerts" section showing high-priority emergencies with reassessment option

### **Feature 3: Live resource tracking**

`POST /api/tracking` with `{"resourceId":1,"latitude":30.3165,"longitude":78.0322}`

- Accepts authenticated GPS location updates for resources
- Stores timestamped location in `resource_locations` table (tracking history)
- Updates resource's current position in `resources` table for map display
- Validates coordinates are within valid ranges (-90 to 90 lat, -180 to 180 lon)
- `GET /api/tracking` returns latest location per resource
- **Important:** This endpoint provides the ingestion point for real GPS data
- The application does **not** fabricate GPS coordinates or simulate tracking
- Device/driver software or mobile app must send genuine location updates
- When `CRISISFLOW_API_KEY` is set, tracking endpoints require `X-API-Key` header
- Live Map shows resource markers at their latest reported positions

### **Feature 4: Traffic-aware ETA and route-based travel time**

- Allocation engine stores route-based distance and ETA when assigning resources
- Database columns: `distance_km` (decimal), `eta_minutes` (integer) in `allocations` table
- Current implementation: Uses road-network distance from OSRM routing service
- Duration calculation: Based on OSRM's road-network travel time estimate
- **Traffic limitation:** Public OSRM demo does **not** provide real-time traffic data
- `trafficAware: false` flag in API responses clearly indicates no live traffic
- `trafficLabel` field explains: "Traffic data unavailable — road-network estimate, not live traffic"
- For production: Configure `CRISISFLOW_ROUTING_URL` to point to routing provider with traffic support
- ETA is recalculated when resource positions are updated via tracking endpoint
- Fallback: If routing fails, uses Haversine (straight-line) distance at 40 km/h average speed

### **Feature 5: Completion notifications**

`GET /api/notifications` returns `{unreadCount, notifications}`

- Automatic notifications created for key events:
  - **REGISTERED:** Emergency registered in system
  - **RESPONSE_STARTED:** Status changed to "Responding"
  - **COMPLETED:** Emergency resolved
  - **CANCELLED:** Emergency cancelled
- Stored in `notifications` table with deduplication (`dedupe_key` prevents duplicates)
- Each notification includes: event type, message, emergency/resource IDs, read status, timestamp
- `POST /api/notifications` with `{"notificationId":1}` marks notification as read
- Dashboard shows notification badge with unread count in sidebar
- Dedicated Notifications section displays all events with filtering
- Live Map includes notification panel for real-time system events
- Email/SMS/push notifications are **not** configured (in-app only)

### **Feature 6: Emergency waiting-time calculation and metrics**

`GET /api/metrics`

- Calculates and displays for each emergency:
  - Time since reported (`reportedSeconds`)
  - Time since first allocation (`sinceAllocationSeconds`)
  - Complete lifecycle timestamps (reported, first allocated, response started, arrived, resolved, cancelled)
- Timestamps persisted in database:
  - `reported_at` - When emergency was registered
  - `first_allocated_at` - When first resource was assigned
  - `response_started_at` - When status changed to "Responding"
  - `arrived_at` - When status changed to "Arrived" (requires explicit confirmation)
  - `resolved_at` - When emergency was resolved
  - `cancelled_at` - When emergency was cancelled
- Reports section shows waiting time metrics table with elapsed time counters
- Dashboard Priority Alerts section highlights emergencies with extended waiting times
- **Important:** `arrived_at` is set only when operator/device explicitly confirms arrival status
- Passing of estimated ETA does **not** automatically mark emergency as "Arrived"
- Frontend displays live elapsed time without excessive database queries

### Enhanced status workflow

Accepted status values for `POST /api/emergency-status`:

- **Reported** - Initial status when emergency is registered
- **Responding** - Resources dispatched and en route (sets `response_started_at`)
- **Arrived** - Responders confirmed on-scene (sets `arrived_at`, requires explicit action)
- **Resolved** - Emergency handled, releases resources (sets `resolved_at`)
- **Cancelled** - Emergency cancelled, releases resources (sets `cancelled_at`)

### Configuration (environment variables)

All existing variables plus:

| Variable | Default | Meaning |
|---|---|---|
| `CRISISFLOW_ROUTING_URL` | https://router.project-osrm.org | OSRM-compatible routing service base URL. Public demo has no traffic data. |

### Windows PowerShell setup (complete)

```powershell
$env:CRISISFLOW_DB_HOST="localhost"
$env:CRISISFLOW_DB_PORT="3306"
$env:CRISISFLOW_DB_NAME="crisisflow_v2"
$env:CRISISFLOW_DB_USER="root"
$env:CRISISFLOW_DB_PASSWORD="your-local-password"
$env:CRISISFLOW_API_KEY="replace-with-a-long-random-secret"
$env:CRISISFLOW_ROUTING_URL="https://router.project-osrm.org"
mvn test
mvn compile exec:java
```

Copy `.env.example` as a reference only; this application currently reads environment variables directly and does not automatically load a `.env` file. Never put credentials in browser JavaScript or commit them.

### Complete API reference

| Method | Path | Purpose | Notes |
|---|---|---|---|
| GET | `/api/routes?fromLat=&fromLon=&toLat=&toLon=` | Road route geometry and duration | OSRM fallback, no live traffic by default |
| POST | `/api/tracking` | Ingest validated GPS coordinates for a resource | Requires X-API-Key if configured |
| GET | `/api/tracking` | Latest known location per resource | Returns tracking status and timestamp |
| GET | `/api/notifications` | Notifications and unread count | In-app notifications only |
| POST | `/api/notifications` | Mark notification read | Requires `notificationId` field |
| POST | `/api/priority` | Reassess priority using rule engine | Returns score, priority, reasons, manual review flag |
| GET | `/api/metrics` | Lifecycle timestamps and elapsed-time metrics | Active emergencies with waiting times |
| GET | `/api/reassignment-opportunities` | Find nearby emergency reassignment opportunities | Smart proximity-based suggestions |
| POST | `/api/reassign` | Reassign resource to closer emergency | Requires resourceId, fromEmergencyId, toEmergencyId |

### Error handling

- **400 Bad Request:** Invalid coordinates, missing required fields, malformed JSON
- **401 Unauthorized:** Missing or invalid API key when `CRISISFLOW_API_KEY` is configured
- **404 Not Found:** Resource, emergency, or notification ID does not exist
- **409 Conflict:** Resource is assigned and cannot be deleted without `force=true`
- **500 Internal Server Error:** Unexpected server errors, provider failures, database issues

All errors return JSON: `{ "error": "Human-readable message" }`

### Testing GPS tracking without real devices

For development/testing, you can send location updates using curl or the browser console:

```powershell
# Windows PowerShell (replace API key and coordinates)
$headers = @{"Content-Type"="application/json"; "X-API-Key"="your-api-key"}
$body = '{"resourceId":1,"latitude":30.3200,"longitude":78.0400}'
Invoke-RestMethod -Uri "http://localhost:8080/api/tracking" -Method POST -Headers $headers -Body $body
```

Browser console (if API key is stored):
```javascript
await fetch('/api/tracking', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-API-Key': sessionStorage.getItem('crisisflowKey')
  },
  body: JSON.stringify({resourceId: 1, latitude: 30.3200, longitude: 78.0400})
});
```

The Live Map will update to show the new resource position and recalculate routes.

### Important limitations and clarifications

1. **Traffic data:** The default public OSRM service does **not** provide real-time traffic. Route durations are road-network estimates only. For live traffic, configure `CRISISFLOW_ROUTING_URL` to point to a traffic-enabled routing provider (e.g., commercial Google Maps Routes API, TomTom, HERE, or self-hosted OSRM with traffic data).

2. **GPS tracking:** The application provides ingestion endpoints and database storage for GPS coordinates. It does **not** include mobile apps or GPS device integration. Real location updates must come from external devices/apps making authenticated API calls.

3. **Priority engine:** Uses transparent rule-based scoring, deliberately **not** represented as machine learning or AI. No trained model, no historical data analysis. Scoring rules are visible in `PriorityEngine.java` and can be customized.

4. **Arrival confirmation:** The `arrived_at` timestamp is set only when an operator or device explicitly changes the status to "Arrived". Estimated arrival time passing does **not** automatically mark arrival.

5. **Notifications:** Currently in-app only. Email, SMS, and push notifications require additional configuration and are not included in this implementation.

6. **Route caching:** Frontend caches route responses for 60 seconds to reduce API calls. Routes are recalculated when resource positions change.

7. **Deployment considerations:** For production deployment:
   - Set strong `CRISISFLOW_API_KEY` value
   - Configure TLS/HTTPS termination (reverse proxy like nginx or Caddy)
   - Use managed MySQL instance or configure proper backups
   - Point `CRISISFLOW_ROUTING_URL` to reliable routing service
   - Implement operator authentication layer (the built-in API key protects write operations but not read access)
   - Review and adjust priority scoring rules for your region's requirements
   - Set up real-time location ingestion from GPS devices or mobile apps

### Dashboard enhancements

The enhanced dashboard now includes:

- **Priority Alerts panel:** Shows high-priority emergencies (score ≥ 80) with reassessment option
- **Notification badge:** Unread count in sidebar, updates every 15 seconds
- **Notifications section:** Full event history with read/unread status and mark-read functionality
- **Response metrics table:** Waiting times, lifecycle timestamps for all active emergencies
- **Enhanced emergency rows:** Display priority scores and reasons when available
- **Live Map improvements:** Route visualization with blue lines, dashed fallback for unavailable routes, route details in tooltips (distance, duration, traffic status)

All enhancements are backward-compatible with existing CrisisFlow installations. The database migration runs automatically on startup.

## Feature 7: Smart Proximity-Based Reassignment 🆕

`GET /api/reassignment-opportunities`

The system now continuously monitors assigned resources and identifies opportunities to reassign them to closer emergencies, reducing response times and saving lives.

### How It Works:

1. **Continuous Monitoring:** System checks all active resource assignments
2. **Proximity Detection:** Identifies nearby emergencies within 10 km
3. **Time Calculation:** Calculates potential time savings for reassignment
4. **Priority Consideration:** Only suggests reassignment to equal or higher priority emergencies
5. **Smart Suggestions:** Displays opportunities in the Allocations section with one-click reassignment

### Reassignment Criteria:

- **Minimum time savings:** 5 minutes or more
- **Maximum proximity:** 10 km radius
- **Priority rule:** Only reassign to same or higher priority
- **Resource type match:** Nearby emergency must need that resource type
- **Status check:** Only reassigns resources in "Reported" or "Responding" status

### Example Scenario:

```
Ambulance A is assigned to Emergency #1 (15 km away)
Emergency #2 appears at 3 km from Ambulance A
Emergency #2 has CRITICAL priority (same as #1)
System suggests: "Reassign Ambulance A to Emergency #2 (saves ~12 minutes)"
```

### API Usage:

**Get opportunities:**
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/reassignment-opportunities"
```

**Perform reassignment:**
```powershell
$headers = @{"Content-Type"="application/json"; "X-API-Key"="your-key"}
$body = @{
    resourceId = 1
    fromEmergencyId = 5
    toEmergencyId = 8
    reason = "Proximity-based reassignment: saves 12 minutes"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/reassign" `
    -Method POST -Headers $headers -Body $body
```

### UI Features:

**Allocations Section:**
- 🎯 **Smart Reassignment Opportunities** panel
- Visual comparison: Current vs. Suggested emergency
- Shows distance, time savings, priority levels
- One-click reassignment with confirmation
- Automatic notification to both emergencies
- Dismiss option for operators who prefer current assignment

**Notifications:**
- `RESOURCE_REASSIGNED` - Sent to source emergency
- `RESOURCE_ASSIGNED` - Sent to target emergency
- Includes reason and emergency IDs

**Audit Trail:**
- All reassignments logged in `audit_log` table
- Includes reason, timestamp, and both emergency IDs
- Full traceability for operational review

### Benefits:

✅ **Faster Response Times:** Resources reach closer emergencies quicker
✅ **Better Resource Utilization:** Dynamic reallocation as situation changes
✅ **Priority-Aware:** Never sacrifices high-priority for low-priority
✅ **Transparent:** Clear reasoning shown to operators
✅ **Operator Control:** Suggestions, not automatic - human in the loop
✅ **Audit Trail:** Complete record of all reassignment decisions

### Configuration:

Constants in `ProximityReassignment.java`:
- `MIN_TIME_SAVINGS_MINUTES` - Default: 5 minutes
- `MAX_PROXIMITY_KM` - Default: 10 km

Adjust these values based on your region's geography and traffic patterns.

---

All enhancements are backward-compatible with existing CrisisFlow installations. The database migration runs automatically on startup.
