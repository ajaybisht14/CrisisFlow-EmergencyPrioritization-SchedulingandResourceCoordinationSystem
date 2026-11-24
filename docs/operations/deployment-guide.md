# Deployment and Containerization Guide

This document covers packaging, containerization, and orchestration procedures for running CrisisFlow in production environments.

## 1. Container Architecture

CrisisFlow can be containerized using a lightweight multi-stage Docker build that minimizes the final image size and strips build tools.

### Reference Multi-Stage `Dockerfile` Pattern

```dockerfile
# Stage 1: Build artifact
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime container
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S crisisflow && adduser -S crisisflow -G crisisflow
WORKDIR /app
COPY --from=builder /workspace/target/crisisflow-*.jar app.jar
USER crisisflow
EXPOSE 8080
ENV CRISISFLOW_PORT=8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
```

---

## 2. Docker Compose Orchestration

A multi-container setup coordinates the application with a persistent MySQL instance:

```yaml
version: '3.8'

services:
  db:
    image: mysql:8.0
    restart: always
    environment:
      MYSQL_ROOT_PASSWORD: secure_root_password
      MYSQL_DATABASE: crisisflow_v2
    volumes:
      - mysql_data:/var/lib/mysql
      - ./crisisflow.sql:/docker-entrypoint-initdb.d/01_schema.sql:ro
    ports:
      - "127.0.0.1:3306:3306"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  crisisflow:
    build: .
    restart: always
    depends_on:
      db:
        condition: service_healthy
    environment:
      CRISISFLOW_DB_HOST: db
      CRISISFLOW_DB_PORT: 3306
      CRISISFLOW_DB_NAME: crisisflow_v2
      CRISISFLOW_DB_USER: root
      CRISISFLOW_DB_PASSWORD: secure_root_password
      CRISISFLOW_PORT: 8080
      CRISISFLOW_AUTO_INTERVAL: 15
      CRISISFLOW_API_KEY: ${CRISISFLOW_API_KEY:-}
    ports:
      - "8080:8080"

volumes:
  mysql_data:
```

---

## 3. Deployment Commands

1. **Build and Launch:**
   ```bash
   docker compose up --build -d
   ```
2. **View Live Application Logs:**
   ```bash
   docker compose logs -f crisisflow
   ```
3. **Verify Container Health:**
   ```bash
   curl http://localhost:8080/api/metrics
   ```
4. **Shutdown and Clean Up:**
   ```bash
   docker compose down
   ```

---

## 4. Backup and Disaster Recovery

### Creating a Database Snapshot
```bash
docker exec -i $(docker compose ps -q db) \
  mysqldump -u root -psecure_root_password crisisflow_v2 > backup_$(date +%Y%m%d_%H%M%S).sql
```

### Restoring from Snapshot
```bash
docker exec -i $(docker compose ps -q db) \
  mysql -u root -psecure_root_password crisisflow_v2 < backup_20261124_120000.sql
```
