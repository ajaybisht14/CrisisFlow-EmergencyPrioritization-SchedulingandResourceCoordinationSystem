# CrisisFlow Documentation Suite

Welcome to the CrisisFlow documentation library. This documentation suite provides detailed specifications, architectural designs, API references, operational runbooks, and developer guides for the CrisisFlow Emergency Prioritization, Scheduling, and Resource Coordination System.

## Documentation Index

### Architecture & Engine Specifications
- [System Overview](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/architecture/system-overview.md): High-level system architecture, client-server model, background workers, and technology stack.
- [Priority Calculation Engine](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/architecture/priority-engine.md): Multi-factor priority scoring, life-threat safety overrides, and wait-time escalation rules.
- [Resource Allocation Model](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/architecture/allocation-model.md): Requirement resolution, capacity quotas, and Haversine nearest-neighbor dispatching.
- [Proximity Reassignment Guide](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/architecture/proximity-reassignment.md): In-transit dynamic resource reassignment based on real-time spatial proximity.

### API Reference
- [Emergency Management Endpoints](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/api/emergencies-api.md): REST endpoints for incident creation, listing, status transitions, and queries.
- [Resource Management & Telemetry](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/api/resources-api.md): Endpoints for resource inventory, availability status, and GPS telemetry updates.
- [Operational & Route Endpoints](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/api/operations-api.md): Endpoints for allocations, route generation, notifications, system metrics, and reassignment.

### Database & Schemas
- [Database Schema Guide](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/database/schema-guide.md): Relational tables, foreign keys, indexes, and schema evolution.

### Developer & Contributor Guides
- [Local Setup Guide](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/development/setup-guide.md): Prerequisites, database provisioning, environment variables, and build instructions.
- [Frontend & Map Integration](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/frontend/ui-and-maps.md): Web dashboard layout, Leaflet and OpenStreetMap visualizer, and live polling mechanisms.
- [Testing & Quality Assurance Guide](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/development/testing-guide.md): Unit tests, test suites, edge case verification, and manual E2E workflows.

### Operations & Deployment
- [Security & Access Control Guide](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/operations/security-guide.md): API key authorization, input validation, SQL safety, and deployment security practices.
- [Deployment & Containerization](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/operations/deployment-guide.md): Container packaging, Docker Compose configurations, and production operations.
- [Incident Response Runbook](file:///c:/Users/h/Desktop/pbl/CrisisFlow-EmergencyPrioritization-SchedulingandResourceCoordinationSystem/docs/operations/incident-runbook.md): Standard operating procedures for dispatchers, priority escalation, and manual overrides.

## Quick Reference Summary

| Topic | Relevant File/Component |
|---|---|
| Core HTTP Server | `com.crisisflow.ApiServer` |
| Background Sweeper | `com.crisisflow.Allocation.runQuietly` |
| Scoring Engine | `com.crisisflow.PriorityEngine` |
| Spatial Dispatcher | `com.crisisflow.Allocator` |
| Reassignment | `com.crisisflow.ProximityReassignment` |
| Database Layer | `com.crisisflow.Database` |
| Web UI | `src/main/resource/index.html`, `map.html` |
