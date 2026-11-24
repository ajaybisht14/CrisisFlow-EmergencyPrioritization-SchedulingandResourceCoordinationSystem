# Frontend Dashboard and Geospatial Map Integration

CrisisFlow delivers a clean web interface without complex frontend toolchains (no Node.js build pipeline required). Assets are served directly by `ApiServer.java` from classpath static resources.

## Interface Components

The web application is split into two complementary interfaces:

1. **Operations Dashboard (`/` or `index.html`):** Tabular management views for incident ingestion, queue prioritization, resource status updates, and allocation auditing.
2. **Geospatial Map (`/map.html`):** Real-time spatial visualizer rendering incident markers, unit positions, and navigation routes.

---

## 1. Operations Dashboard Architecture (`app.js`, `style.css`)

### Incident Ingestion & Priority Live Preview
- The emergency submission form binds directly to `/api/emergencies`.
- As the operator types incident details (number of people, disaster type, description), `app.js` can invoke `/api/priority` to display a real-time priority score preview and safety rationale.

### Auto-Polling & State Synchronization
- The UI runs periodic polling intervals (default every 5 seconds) against `/api/emergencies` and `/api/resources`.
- Dynamic DOM diffing or targeted table updates ensure smooth operator experience without screen flickering.

### Authentication Integration
- If `CRISISFLOW_API_KEY` is configured, the browser prompts for the key once per session and stores it in `sessionStorage`.
- Outgoing mutating requests (`POST`) attach the `X-API-Key` header automatically.

---

## 2. Geospatial Map Visualizer (`map.html`, `map.js`, `geo.js`)

The map relies on **Leaflet 1.9+** and **OpenStreetMap** tile layers.

### Incident Marker Styling

Incidents are rendered with distinct SVG pulsing pin markers color-coded by triage level:

| Priority Category | Hex Color | Visual Indicator |
|---|---|---|
| `CRITICAL` | `#DC2626` (Red) | High-contrast red circle with alert pulse animation |
| `HIGH` | `#EA580C` (Orange) | Orange marker with warning badge |
| `MEDIUM` | `#CA8A04` (Yellow) | Amber marker |
| `LOW` | `#2563EB` (Blue) | Standard blue marker |

### Resource Fleet Tracking
- Response units are represented by specialized icons indicating their capability:
  - **Ambulance:** Medical cross
  - **Rescue Team:** Search & rescue / life-preserver icon
  - **Medical Team:** Hospital / triage tent symbol
  - **Fire Unit:** Fire service emblem
- Hovering or clicking a resource displays its status (`Available`, `Dispatched`, `On Scene`), fuel/battery level, and assigned destination.

### Route Polylines & Direction Vectors
- Active allocations render a dashed colored polyline linking the responding unit to the incident scene.
- Line styling is animated for moving vehicles with real-time GPS telemetry from `response_tracking`.
