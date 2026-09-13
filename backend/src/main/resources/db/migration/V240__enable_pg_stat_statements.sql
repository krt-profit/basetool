-- =====================================================================
-- V240 - pg_stat_statements, so "which query burns the CPU" is a
--        measurement rather than a reconstruction (REQ-DATA-016)
-- =====================================================================
-- On 2026-09-13 db-backend was the most CFS-throttled container in the
-- stack (161 s in 29 h, ~229 ms average stall per throttle event) while
-- averaging 1.6 % of its own CPU quota. Diagnosing that meant inferring
-- the hot path from pg_stat_user_tables scan counters and their ratios,
-- because pg_stat_statements was not installed. The inference held up
-- -- it matched the documented miss cost of the authorities cache
-- almost exactly -- but it was still a reconstruction, and the next
-- performance question should not have to repeat it.
--
-- WHY THIS IS A MIGRATION AND NOT A HOST STEP. An init script in
-- docker-entrypoint-initdb.d only runs on a FRESH data directory, so it
-- would never reach the production volume, which has existed since
-- 2026-04. A one-off manual CREATE EXTENSION would reach production and
-- nothing else: every fresh dev, test and e2e stack would silently lack
-- it, and the gap would only be discovered the next time someone needed
-- the data. Flyway is the one path that is versioned, automatic and
-- identical everywhere.
--
-- THE EXTENSION ALONE COLLECTS NOTHING. pg_stat_statements needs its
-- library loaded at server start; the view exists after this migration
-- but errors on SELECT until `shared_preload_libraries` names it. That
-- half is in docker-compose.yml's db-backend `command:` and takes
-- effect on the next container recreate, which is a separate act from
-- this migration deliberately -- the two cannot be applied atomically
-- and the ordering between them does not matter.
--
-- WHY THIS MIGRATION CANNOT FAIL A DEPLOY. CREATE EXTENSION needs
-- superuser and needs the contrib .so present. Both hold on
-- postgres:18-alpine under POSTGRES_USER, but neither is guaranteed on
-- a differently-provisioned database, and pg_stat_statements is a
-- DIAGNOSTIC: it must never be the reason the backend refuses to start.
-- So the failure is caught and downgraded to a WARNING. The cost of
-- that choice is that a missing extension is silent unless the log is
-- read -- which is the correct trade for something that observes the
-- system rather than running it.
--
-- NO SCHEMA OBJECT IS CREATED OR TOUCHED HERE, so ddl-auto=validate is
-- unaffected and there is nothing for a JPA entity to drift against.
-- =====================================================================

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
    RAISE WARNING 'V240: pg_stat_statements is available. It reports nothing until shared_preload_libraries names it (docker-compose.yml, db-backend command).';
EXCEPTION
    WHEN OTHERS THEN
        -- WARNING and not INFO: PostgreSQL never writes INFO to the server
        -- log, so an INFO here would be invisible in exactly the situation
        -- the message exists for. Same reasoning as V239.
        RAISE WARNING 'V240: could not create pg_stat_statements (%). Query-level statistics stay unavailable; this is diagnostic only and deliberately does not fail the migration.', SQLERRM;
END
$$;
