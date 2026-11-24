# CrisisFlow System Architecture

This document outlines the architectural topology, core subsystems, threading model, and data flow of the CrisisFlow disaster management platform.

## High-Level Topology

CrisisFlow is designed as a modular, lightweight service without heavy framework dependencies, utilizing Java's built-in `com.sun.net.httpserver` alongside MySQL and Leaflet.

```
       +---------------------------------------------+
       |           Browser Client / Dashboard        |
       |     (HTML5, CSS3, Vanilla JS, Leaflet OSM)  |
       +---------------------------------------------+
                              | HTTP (JSON / REST)
                              v
       +---------------------------------------------+
       |             CrisisFlow ApiServer            |
       |      - Fixed Thread Pool (8 workers)        |
       |      - Route dispatching & Static Handler   |
       |      - API Key Authentication Guard         |
       +---------------------------------------------+
              |                      |
              v                      v
     +-----------------+    +-------------------------+
     | PriorityEngine  |    | Allocator & Assignment  |
     | (Rule Scoring)  |    | (Haversine & Quotas)    |
     +-----------------+    +-------------------------+
              |                      |
              |                      v
              |             +-------------------------+
              |             | Proximity Reassignment  |
              |             | (In-transit swaps)      |
              |             +-------------------------+
              \                      /
               \                    /
                v                  v
       +---------------------------------------------+
       |             Database Layer (JDBC)           |
       |      - Connection Pool (Configurable)       |
       |      - Transactional Commits & Rollbacks    |
       |      - Schema Auto-migration Check          |
       +---------------------------------------------+
                              |
                              v
       +---------------------------------------------+
       |              MySQL Database                 |
       |  (crisisflow_v2: emergencies, resources,    |
       |   allocations, tracking, notifications)     |
       +---------------------------------------------+
```

## Subsystem Details

### 1. HTTP Server & Request Handling (`ApiServer.java`)
- Listens on `CRISISFLOW_PORT` (default 8080).
- Handled via `com.sun.net.httpserver.HttpServer` backed by an 8-thread worker pool.
- Enforces an optional bearer/header authorization check (`X-API-Key`) for mutation routes.
- Serves static assets directly from classpath resources (`src/main/resource/`).

### 2. Autonomous Allocation Sweeper
- Uses a single-threaded daemon executor (`ScheduledExecutorService`).
- Sweeps every `CRISISFLOW_AUTO_INTERVAL` seconds (default 30 seconds, minimum 5 seconds).
- Invokes `Allocation.runQuietly()` to greedily pair unassigned or partially assigned emergencies with newly freed resources.

### 3. Spatial & Distance Computations (`GeoUtil.java`)
- Computes spherical distances between coordinate pairs `(lat1, lon1)` and `(lat2, lon2)` using the Haversine equation:
  $$d = 2r \arcsin\left(\sqrt{\sin^2\left(\frac{\Delta\phi}{2}\right) + \cos(\phi_1)\cos(\phi_2)\sin^2\left(\frac{\Delta\lambda}{2}\right)}\right)$$
- Estimates vehicle travel durations with speed calibration based on vehicle type and road conditions.

### 4. Persistence & Database Integrity (`Database.java`)
- Maintains transactional atomicity: allocating a resource updates both `emergencies.allocation_status` and `resources.status`, inserting into `allocations` inside a single JDBC transaction.
- Dynamic schema migration verifies and creates missing tables or indexes at startup.
