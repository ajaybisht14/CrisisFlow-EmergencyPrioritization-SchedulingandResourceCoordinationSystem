# Local Development and Environment Setup Guide

This guide describes how to configure your workstation, provision the local database, and run CrisisFlow.

## Prerequisites

| Dependency | Minimum Version | Verification Command |
|---|---|---|
| **Java JDK** | 17 LTS (or 21 LTS) | `java -version` |
| **Apache Maven** | 3.8.0+ | `mvn -version` |
| **MySQL Server** | 8.0.1+ | `mysql --version` |
| **Git** | 2.30+ | `git --version` |

---

## 1. Database Provisioning

1. Start your local MySQL server service.
2. Load the base schema, tables, default resource categories, and priority rules:
   ```bash
   mysql -u root -p < crisisflow.sql
   ```
3. *(Optional)* Load demo resources and test incidents for local testing:
   ```bash
   mysql -u root -p crisisflow_v2 < seed_demo.sql
   ```

---

## 2. Environment Variables Configuration

CrisisFlow is configured via standard environment variables:

| Variable | Default Value | Description |
|---|---|---|
| `CRISISFLOW_DB_USER` | *None (Required)* | MySQL username |
| `CRISISFLOW_DB_PASSWORD` | `""` (empty) | MySQL user password |
| `CRISISFLOW_DB_HOST` | `localhost` | Hostname or IP of the MySQL server |
| `CRISISFLOW_DB_PORT` | `3306` | MySQL port |
| `CRISISFLOW_DB_NAME` | `crisisflow_v2` | Target database name |
| `CRISISFLOW_DB_POOL` | `10` | Maximum JDBC connection pool size |
| `CRISISFLOW_PORT` | `8080` | Port for the HTTP API and dashboard |
| `CRISISFLOW_AUTO_INTERVAL` | `30` | Seconds between autonomous allocation cycles (minimum 5) |
| `CRISISFLOW_API_KEY` | *Unset* | Optional secret key. If set, mutating requests require header `X-API-Key` |

---

## 3. Starting the Application

### PowerShell (Windows)

```powershell
$env:CRISISFLOW_DB_USER="root"
$env:CRISISFLOW_DB_PASSWORD="yourpassword"
mvn clean compile exec:java
```

### Bash / Zsh (Linux & macOS)

```bash
export CRISISFLOW_DB_USER=root
export CRISISFLOW_DB_PASSWORD="yourpassword"
mvn clean compile exec:java
```

When started, the terminal outputs:
```
CrisisFlow is running.
Dashboard: http://localhost:8080/
Map:       http://localhost:8080/map.html
```

---

## 4. IDE Configuration

### Visual Studio Code
- Install the **Extension Pack for Java** (Microsoft).
- Open the project root directory.
- Create or update `.vscode/launch.json` with your environment variables under the `"env"` block.

### IntelliJ IDEA
- Open the project directory as a Maven project.
- In **Run/Debug Configurations**, create an **Application** configuration:
  - Main class: `com.crisisflow.Main`
  - Environment variables: `CRISISFLOW_DB_USER=root;CRISISFLOW_DB_PASSWORD=...`
