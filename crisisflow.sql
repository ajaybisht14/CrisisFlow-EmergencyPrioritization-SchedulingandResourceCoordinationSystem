CREATE DATABASE IF NOT EXISTS crisisflow_v2;
USE crisisflow_v2;

CREATE TABLE IF NOT EXISTS resource_types (
    type_id INT AUTO_INCREMENT PRIMARY KEY,
    type_name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS emergencies (
    emergency_id INT AUTO_INCREMENT PRIMARY KEY,
    disaster_type VARCHAR(50) NOT NULL,
    location VARCHAR(200) NOT NULL,
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    people_affected INT NOT NULL,
    description TEXT,
    priority VARCHAR(20) NOT NULL,
    allocation_status VARCHAR(30) DEFAULT 'Pending',
    response_status VARCHAR(30) DEFAULT 'Reported',
    reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL,
    INDEX idx_em_open (is_deleted, response_status, allocation_status)
);

CREATE TABLE IF NOT EXISTS resources (
    resource_id INT AUTO_INCREMENT PRIMARY KEY,
    resource_name VARCHAR(100) NOT NULL,
    type_id INT NOT NULL,
    capacity INT NOT NULL DEFAULT 1,
    location VARCHAR(200) NOT NULL,
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    status VARCHAR(30) NOT NULL DEFAULT 'Available',
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL,
    FOREIGN KEY (type_id) REFERENCES resource_types(type_id),
    INDEX idx_res_pool (is_deleted, status, type_id)
);

CREATE TABLE IF NOT EXISTS allocations (
    allocation_id INT AUTO_INCREMENT PRIMARY KEY,
    emergency_id INT NOT NULL,
    resource_id INT NOT NULL,
    allocated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL,
    status VARCHAR(30) DEFAULT 'Active',
    distance_km DECIMAL(8,2) NULL,
    eta_minutes INT NULL,
    INDEX idx_alloc_active (status, emergency_id, resource_id),
    FOREIGN KEY (emergency_id) REFERENCES emergencies(emergency_id),
    FOREIGN KEY (resource_id) REFERENCES resources(resource_id)
);

-- What each kind of emergency needs. Edit these rows to change the rules
-- (min_people = only send this type when at least that many are affected).
CREATE TABLE IF NOT EXISTS response_requirements (
    requirement_id INT AUTO_INCREMENT PRIMARY KEY,
    disaster_type VARCHAR(50) NOT NULL,
    type_id INT NOT NULL,
    min_people INT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_req (disaster_type, type_id),
    FOREIGN KEY (type_id) REFERENCES resource_types(type_id)
);

CREATE TABLE IF NOT EXISTS status_history (
    history_id INT AUTO_INCREMENT PRIMARY KEY,
    emergency_id INT NOT NULL,
    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    note VARCHAR(500),
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT IGNORE INTO resource_types (type_name)
VALUES
    ('Ambulance'),
    ('Rescue Team'),
    ('Medical Team'),
    ('Fire Unit');

INSERT IGNORE INTO response_requirements (disaster_type, type_id, min_people)
SELECT d.disaster, t.type_id, d.min_people
FROM (
    SELECT 'Flood' AS disaster, 'Rescue Team' AS tname, 0 AS min_people
    UNION ALL SELECT 'Flood', 'Medical Team', 10
    UNION ALL SELECT 'Earthquake', 'Rescue Team', 0
    UNION ALL SELECT 'Earthquake', 'Medical Team', 0
    UNION ALL SELECT 'Earthquake', 'Ambulance', 0
    UNION ALL SELECT 'Fire', 'Fire Unit', 0
    UNION ALL SELECT 'Fire', 'Ambulance', 0
    UNION ALL SELECT 'Landslide', 'Rescue Team', 0
    UNION ALL SELECT 'Landslide', 'Medical Team', 10
    UNION ALL SELECT 'Major Accident', 'Ambulance', 0
    UNION ALL SELECT 'Major Accident', 'Medical Team', 10
) d
JOIN resource_types t ON t.type_name = d.tname;

-- Sample resources for testing the application.
-- Coordinates are approximate sample locations in Dehradun.

INSERT INTO resources
    (resource_name, type_id, capacity, location,
     latitude, longitude, status)
SELECT
    'Ambulance 1', type_id, 4, 'Dehradun City',
    30.3165, 78.0322, 'Available'
FROM resource_types
WHERE type_name = 'Ambulance'
AND NOT EXISTS (
    SELECT 1 FROM resources WHERE resource_name = 'Ambulance 1'
);

INSERT INTO resources
    (resource_name, type_id, capacity, location,
     latitude, longitude, status)
SELECT
    'Rescue Team 1', type_id, 10, 'Rajpur Road',
    30.3700, 78.0800, 'Available'
FROM resource_types
WHERE type_name = 'Rescue Team'
AND NOT EXISTS (
    SELECT 1 FROM resources WHERE resource_name = 'Rescue Team 1'
);

INSERT INTO resources
    (resource_name, type_id, capacity, location,
     latitude, longitude, status)
SELECT
    'Medical Team 1', type_id, 8, 'Clock Tower',
    30.3255, 78.0413, 'Available'
FROM resource_types
WHERE type_name = 'Medical Team'
AND NOT EXISTS (
    SELECT 1 FROM resources WHERE resource_name = 'Medical Team 1'
);

INSERT INTO resources
    (resource_name, type_id, capacity, location,
     latitude, longitude, status)
SELECT
    'Fire Unit 1', type_id, 6, 'ISBT Dehradun',
    30.2860, 78.0100, 'Available'
FROM resource_types
WHERE type_name = 'Fire Unit'
AND NOT EXISTS (
    SELECT 1 FROM resources WHERE resource_name = 'Fire Unit 1'
);

-- If your tables were created by an older version of this file, the app adds
-- the missing columns/tables (latitude, longitude, is_deleted, distance_km,
-- eta_minutes, response_requirements, status_history) automatically on startup.
