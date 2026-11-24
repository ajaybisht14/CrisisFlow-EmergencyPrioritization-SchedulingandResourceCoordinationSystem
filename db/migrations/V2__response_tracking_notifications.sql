-- Incremental, non-destructive migration for existing crisisflow_v2 databases.
-- All statements are additive and safe to run repeatedly on MySQL 8.
CREATE TABLE IF NOT EXISTS resource_locations (
  location_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  resource_id INT NOT NULL,
  latitude DECIMAL(10,7) NOT NULL,
  longitude DECIMAL(10,7) NOT NULL,
  reported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  tracking_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  INDEX idx_location_resource_time(resource_id, reported_at),
  CONSTRAINT fk_location_resource FOREIGN KEY(resource_id) REFERENCES resources(resource_id)
);
CREATE TABLE IF NOT EXISTS notifications (
  notification_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  emergency_id INT NULL,
  resource_id INT NULL,
  event_type VARCHAR(50) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  dedupe_key VARCHAR(180) NOT NULL UNIQUE,
  is_read TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notifications_read(is_read,created_at)
);
CREATE TABLE IF NOT EXISTS audit_log (
  audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  emergency_id INT NULL,
  resource_id INT NULL,
  action VARCHAR(80) NOT NULL,
  details TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- The application also adds these missing columns at startup, for legacy schema compatibility.
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS first_allocated_at TIMESTAMP NULL;
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS response_started_at TIMESTAMP NULL;
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS arrived_at TIMESTAMP NULL;
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP NULL;
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP NULL;
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS priority_score INT NULL;
ALTER TABLE emergencies ADD COLUMN IF NOT EXISTS priority_reason TEXT NULL;
