
CREATE DATABASE IF NOT EXISTS crisisflow_v2;
USE crisisflow_v2;

CREATE TABLE IF NOT EXISTS resource_types (
    type_id INT AUTO_INCREMENT PRIMARY KEY,
    type_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(200)
);

INSERT IGNORE INTO resource_types (type_name, description) VALUES
('Ambulance', 'Emergency patient transport'),
('Rescue Team', 'Disaster rescue operations'),
('Medical Team', 'Medical assistance'),
('Fire Unit', 'Fire response operations');

CREATE TABLE IF NOT EXISTS emergencies (
    emergency_id INT AUTO_INCREMENT PRIMARY KEY,
    disaster_type VARCHAR(50) NOT NULL,
    location VARCHAR(200) NOT NULL,
    people_affected INT NOT NULL,
    description VARCHAR(500),
    priority VARCHAR(20) NOT NULL,
    allocation_status VARCHAR(30) NOT NULL DEFAULT 'Pending',
    response_status VARCHAR(30) NOT NULL DEFAULT 'Reported',
    reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK (people_affected > 0)
);

CREATE TABLE IF NOT EXISTS resources (
    resource_id INT AUTO_INCREMENT PRIMARY KEY,
    resource_name VARCHAR(100) NOT NULL,
    type_id INT NOT NULL,
    capacity INT NOT NULL DEFAULT 1,
    location VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'Available',
    FOREIGN KEY (type_id) REFERENCES resource_types(type_id),
    CHECK (capacity > 0),
    CHECK (status IN ('Available', 'Reserved', 'Deployed', 'Out of Service'))
);

CREATE TABLE IF NOT EXISTS allocations (
    allocation_id INT AUTO_INCREMENT PRIMARY KEY,
    emergency_id INT NOT NULL,
    resource_id INT NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'Active',
    FOREIGN KEY (emergency_id) REFERENCES emergencies(emergency_id),
    FOREIGN KEY (resource_id) REFERENCES resources(resource_id)
);

CREATE TABLE IF NOT EXISTS emergency_history (
    history_id INT AUTO_INCREMENT PRIMARY KEY,
    emergency_id INT NOT NULL,
    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(300),
    FOREIGN KEY (emergency_id) REFERENCES emergencies(emergency_id)
);

CREATE TABLE IF NOT EXISTS activity_logs (
    log_id INT AUTO_INCREMENT PRIMARY KEY,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_emergency_priority
ON emergencies(priority);

CREATE INDEX idx_emergency_allocation
ON emergencies(allocation_status);

CREATE INDEX idx_resource_status
ON resources(status);

CREATE INDEX idx_allocation_emergency
ON allocations(emergency_id);

CREATE INDEX idx_history_emergency
ON emergency_history(emergency_id);