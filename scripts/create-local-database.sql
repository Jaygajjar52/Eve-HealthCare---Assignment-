-- ===========================================================================
--  Creates the local database and role for running this project outside Docker.
--
--  Run this ONCE, connected as a PostgreSQL superuser (usually "postgres").
--  Nothing in the project needs changing afterwards, because application.yml
--  already defaults to exactly these values:
--
--      url      jdbc:postgresql://localhost:5432/evediagnostics
--      user     eve
--      password eve_password
--
--  How to run it
--  ------------
--  With psql (you will be asked for the postgres superuser password):
--      psql -U postgres -f scripts/create-local-database.sql
--
--  Or in pgAdmin: connect as postgres, then Tools > Query Tool, and paste this.
--
--  Note on security: these are development credentials. They are deliberately
--  weak so the project runs with no setup, and must never be used anywhere real.
-- ===========================================================================

-- 1. The application role.
--
--    CREATE ROLE has no "IF NOT EXISTS", so this block is what makes the script
--    safe to run twice: it only creates the role when it is missing, and resets
--    the password when it already exists (which fixes a typo'd password).
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eve') THEN
        CREATE ROLE eve LOGIN PASSWORD 'eve_password';
        RAISE NOTICE 'Created role "eve"';
    ELSE
        ALTER ROLE eve WITH LOGIN PASSWORD 'eve_password';
        RAISE NOTICE 'Role "eve" already existed, password reset';
    END IF;
END
$do$;

-- 2. The database.
--
--    CREATE DATABASE cannot run inside a transaction block, which is why this
--    statement is deliberately not wrapped in the DO block above. If it fails
--    with "database already exists", that is fine: the database is already set
--    up and you can ignore it.
CREATE DATABASE evediagnostics OWNER eve;

-- 3. Confirm it worked. The expected rows are: eve | evediagnostics
SELECT rolname AS role FROM pg_roles WHERE rolname = 'eve';
SELECT datname AS database FROM pg_database WHERE datname = 'evediagnostics';

-- ===========================================================================
--  Nothing else to do. Start the application and it will:
--    * connect with these credentials
--    * run the four Flyway migrations, creating the schema and seed data
--    * serve Swagger UI on http://localhost:8080
--
--  To start from scratch instead, drop the database and re-run this script:
--    DROP DATABASE evediagnostics;
-- ===========================================================================