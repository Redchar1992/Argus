-- Infrastructure owns service schema creation; Flyway owns every table and index.
-- Keeping schemas separate prevents migration-history collisions between services.
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS tools;
CREATE SCHEMA IF NOT EXISTS cases;
