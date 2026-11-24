# Database Schema and Entity-Relationship Guide

CrisisFlow stores relational data in MySQL (`crisisflow_v2`). This document provides comprehensive table schemas, indexing strategies, foreign key constraints, and entity relationships.

## Entity Relationship Overview

```
 [resource_types] 1 --- * [resources] 1 --- * [allocations] * --- 1 [emergencies]
         |                                          |                      |
         | 1                                        | 1                    | 1
         *                                          *                      *
 [response_requirements]                   [response_tracking]      [status_history]
                                                                           |
                                                                           | 1
                                                                           *
                                                                    [notifications]
```

---

## Table Definitions

### 1. `resource_types`
Enumerates standardized operational capabilities.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `type_id` | `INT` | PK, Auto Increment | Unique identifier |
| `type_name` | `VARCHAR(50)` | UNIQUE, NOT NULL | Descriptive name (e.g. `Ambulance`, `Rescue Team`, `Medical Team`, `Fire Unit`) |

---

### 2. `emergencies`
Stores reported crisis events, current triage priority, and coordination status.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `emergency_id` | `INT` | PK, Auto Increment | Primary key |
| `disaster_type` | `VARCHAR(50)` | NOT NULL | Type of disaster (Flood, Fire, Earthquake, etc.) |
| `location` | `VARCHAR(200)` | NOT NULL | Human-readable address or landmark |
| `latitude` | `DECIMAL(10,7)` | NULL | GPS latitude |
| `longitude` | `DECIMAL(10,7)` | NULL | GPS longitude |
| `people_affected`| `INT` | NOT NULL | Estimated victims or threatened persons |
| `description` | `TEXT` | NULL | Narrative details, injuries, damage notes |
| `priority` | `VARCHAR(20)` | NOT NULL | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `allocation_status`| `VARCHAR(30)` | Default `'Pending'` | `Pending`, `Partial`, `Allocated` |
| `response_status`| `VARCHAR(30)` | Default `'Reported'` | `Reported`, `Responding`, `On Scene`, `Resolved`, `Closed` |
| `reported_at` | `TIMESTAMP` | Default `CURRENT_TIMESTAMP` | Ingestion timestamp |
| `is_deleted` | `TINYINT(1)` | Default `0` | Soft-delete flag |
| `deleted_at` | `TIMESTAMP` | NULL | Soft-deletion timestamp |

*Composite Index:* `idx_em_open (is_deleted, response_status, allocation_status)` accelerates pending dispatch queries.

---

### 3. `resources`
Maintains fleet units, stations, personnel teams, and real-time locations.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `resource_id` | `INT` | PK, Auto Increment | Primary key |
| `resource_name` | `VARCHAR(100)` | NOT NULL | Vehicle / team designation |
| `type_id` | `INT` | FK -> `resource_types(type_id)` | Resource capability |
| `capacity` | `INT` | Default `1` | Crew or passenger capacity |
| `location` | `VARCHAR(200)` | NOT NULL | Station name or last known address |
| `latitude` | `DECIMAL(10,7)` | NULL | Current GPS latitude |
| `longitude` | `DECIMAL(10,7)` | NULL | Current GPS longitude |
| `status` | `VARCHAR(30)` | Default `'Available'` | `Available`, `Dispatched`, `On Scene`, `Maintenance` |
| `is_deleted` | `TINYINT(1)` | Default `0` | Soft-delete flag |

*Composite Index:* `idx_res_pool (is_deleted, status, type_id)` optimizes search for available units by type.

---

### 4. `allocations`
Cross-reference table recording dispatch pairings between emergencies and units.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `allocation_id` | `INT` | PK, Auto Increment | Primary key |
| `emergency_id` | `INT` | FK -> `emergencies(emergency_id)` | Associated incident |
| `resource_id` | `INT` | FK -> `resources(resource_id)` | Dispatched asset |
| `allocated_at` | `TIMESTAMP` | Default `CURRENT_TIMESTAMP` | Dispatch time |
| `released_at` | `TIMESTAMP` | NULL | Release/completion timestamp |
| `status` | `VARCHAR(30)` | Default `'Active'` | `Active`, `Released`, `Reassigned` |
| `distance_km` | `DECIMAL(8,2)` | NULL | Initial dispatch distance |
| `eta_minutes` | `INT` | NULL | Estimated travel time |

*Composite Index:* `idx_alloc_active (status, emergency_id, resource_id)` optimizes active assignment checks.

---

### 5. `response_requirements`
Configures which types of response assets are required for each disaster category.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `requirement_id`| `INT` | PK, Auto Increment | Primary key |
| `disaster_type` | `VARCHAR(50)` | NOT NULL | Target disaster type |
| `type_id` | `INT` | FK -> `resource_types(type_id)` | Required asset type |
| `min_people` | `INT` | Default `0` | Threshold of affected victims required to trigger this requirement |

*Unique Key:* `uq_req (disaster_type, type_id)` prevents conflicting duplicate rules.

---

### 6. Supporting Operational Tables

- **`status_history`**: Audit trail of every lifecycle status transition for an emergency.
- **`response_tracking`**: Chronological log of GPS coordinates, vehicle velocities, and compass headings.
- **`notifications`**: Dispatch alert history and outbound communication logs.
