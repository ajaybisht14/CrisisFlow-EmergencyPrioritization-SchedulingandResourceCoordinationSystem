# Security Model and Access Control Guide

This document describes the security architecture, authentication mechanisms, input handling safeguards, and operational hardening practices implemented in CrisisFlow.

## Threat Model and Surface Analysis

CrisisFlow manages critical emergency response assets and incident coordination. Primary security concerns include:
1. **Unauthorized Dispatch Tampering:** Preventing unauthorized actors from altering unit allocations or diverting first responders.
2. **Denial of Service / Flooding:** Preventing malicious incident injection that could exhaust available ambulances or rescue teams.
3. **Information Disclosure:** Ensuring sensitive caller details and geolocation data are protected during transport.

---

## 1. Authentication & API Key Enforcement

CrisisFlow implements an API key authentication guard inside `ApiServer.java`.

### Configuration
Set the secret key via environment variable:
```bash
export CRISISFLOW_API_KEY="cfl_live_9f83a8b27c104e1d9082a9d82e1c98"
```

### Authorization Scope

| HTTP Method | Authentication Requirement | Purpose |
|---|---|---|
| `GET` | **Public** (No key required) | Read-only telemetry, status monitoring, and dashboards. |
| `POST` / `PUT` / `DELETE` | **Guarded** (Requires `X-API-Key`) | Mutating endpoints (reporting incidents, dispatching, reassignment). |

### Constant-Time Token Comparison
To mitigate timing side-channel attacks, key validation uses constant-time byte comparisons via `MessageDigest.isEqual()`:

```java
boolean valid = MessageDigest.isEqual(
    clientKey.getBytes(StandardCharsets.UTF_8),
    expectedKey.getBytes(StandardCharsets.UTF_8)
);
```

If unauthorized, the server responds immediately with HTTP `401 Unauthorized`:
```json
{
  "error": "Unauthorized: valid X-API-Key header required."
}
```

---

## 2. SQL Injection Defense

All database interactions throughout `Database.java`, `Allocation.java`, and `ProximityReassignment.java` strictly use parameterized queries via `java.sql.PreparedStatement`:

```java
// Safe: parameterized placeholders
String sql = "SELECT * FROM emergencies WHERE priority = ? AND is_deleted = 0";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setString(1, priority);
    ...
}
```

Dynamic SQL string concatenation is forbidden throughout the codebase.

---

## 3. Path Traversal & Static Asset Confinement

To protect against directory traversal (`../../etc/passwd`):
1. **Extension Whitelisting:** Static requests are filtered against an allowlist of known safe extensions (`.html`, `.css`, `.js`, `.png`, `.svg`, `.ico`, `.json`).
2. **Classpath Isolation:** Static files are served strictly from the isolated JAR classpath (`src/main/resource/`), preventing arbitrary host filesystem access.

---

## 4. Production Hardening Checklist

When deploying to staging or production environments:
- [ ] **TLS Termination:** Place CrisisFlow behind a reverse proxy (e.g. Nginx, Caddy, or Traefik) providing HTTPS certificates.
- [ ] **Network Segmentation:** MySQL should bind to a private network or internal Docker bridge; do not expose port 3306 to the public internet.
- [ ] **Rate Limiting:** Configure reverse proxy rate limiting (e.g. 50 req/sec per IP) on `/api/emergencies` to defend against automated spamming.
- [ ] **Key Rotation:** Rotate `CRISISFLOW_API_KEY` periodically and never commit secrets to version control.
