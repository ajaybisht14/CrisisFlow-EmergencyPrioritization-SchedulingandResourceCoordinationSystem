-- Optional demo data: more Dehradun resources and three sample emergencies.
-- Run AFTER crisisflow.sql:   mysql -u root -p < seed_demo.sql
-- Safe to run twice (resources are only inserted if the name is new).
-- Emergencies are only inserted when the emergencies table is empty.
USE crisisflow_v2;

INSERT INTO resources (resource_name, type_id, capacity, location, latitude, longitude, status)
SELECT d.name, t.type_id, d.cap, d.loc, d.lat, d.lng, 'Available'
FROM (
    SELECT 'Ambulance 2' AS name, 'Ambulance' AS tname, 4 AS cap, 'Doon Hospital' AS loc, 30.3199 AS lat, 78.0405 AS lng
    UNION ALL SELECT 'Ambulance 3', 'Ambulance', 4, 'Max Hospital, Malsi', 30.3573, 78.0660
    UNION ALL SELECT 'Ambulance 4', 'Ambulance', 4, 'Prem Nagar', 30.3350, 77.9640
    UNION ALL SELECT 'Rescue Team 2', 'Rescue Team', 10, 'Sahastradhara Road', 30.3780, 78.1040
    UNION ALL SELECT 'Rescue Team 3', 'Rescue Team', 12, 'Raiwala', 30.0330, 78.2260
    UNION ALL SELECT 'Medical Team 2', 'Medical Team', 8, 'Patel Nagar', 30.3190, 78.0110
    UNION ALL SELECT 'Fire Unit 2', 'Fire Unit', 6, 'Dalanwala Fire Station', 30.3290, 78.0530
    UNION ALL SELECT 'Fire Unit 3', 'Fire Unit', 6, 'Clement Town', 30.2670, 78.0000
) d
JOIN resource_types t ON t.type_name = d.tname
WHERE NOT EXISTS (SELECT 1 FROM resources r WHERE r.resource_name = d.name);

INSERT INTO emergencies
    (disaster_type, location, latitude, longitude, people_affected, description, priority)
SELECT * FROM (
    SELECT 'Fire' AS disaster_type, 'Paltan Bazaar, Dehradun' AS location, 30.3207 AS latitude, 78.0366 AS longitude, 14 AS people_affected, 'Shop fire spreading to neighbouring buildings' AS description, 'HIGH' AS priority
    UNION ALL SELECT 'Landslide', 'Mussoorie Road, Dehradun', 30.4000, 78.0900, 6, 'Road blocked, two vehicles buried', 'LOW'
    UNION ALL SELECT 'Flood', 'Rispana River bank, Dehradun', 30.3050, 78.0450, 60, 'Water entering homes along the river', 'CRITICAL'
) x
WHERE NOT EXISTS (SELECT 1 FROM emergencies);
