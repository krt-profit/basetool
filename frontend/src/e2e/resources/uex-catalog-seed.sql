-- UEX-catalog snapshot for the E2E flows.
--
-- Ship types and refinery-hosting locations are normally UEX-synced and cannot
-- be created through the admin REST API on a fresh DB. This deterministic
-- snapshot is applied by BackendSeeder.seedCatalog() over JDBC right after the
-- stack is healthy, so the Hangar and Refinery flows have stable reference data
-- regardless of whether the (network-dependent) UEX sync has run.
--
-- Idempotent via fixed UUIDs + ON CONFLICT. Only the columns that are NOT NULL
-- without a DB default must be supplied (verified against the live schema);
-- `version` is left null (read paths tolerate it) and other columns default.

-- Makes the linked location refinery-hosting. Since REQ-REFINERY-020 (V226) the
-- picker query LocationRepository.findLocationsWithRefinery() joins city /
-- space_station on the DERIVED has_refinery_terminal, no longer on UEX's raw
-- has_refinery claim -- so the raw flag alone no longer seeds a usable refinery.
--
-- Both are set, and a matching refinery terminal is seeded below, because the two
-- columns answer different questions and this fixture stands in for a UEX sweep
-- that never runs here:
--   * has_refinery mirrors the upstream claim (diagnostics only),
--   * has_refinery_terminal is the derived truth the picker and the order
--     create/update gate actually read.
-- V226 adds has_refinery_terminal as NOT NULL DEFAULT FALSE and deliberately does
-- NOT backfill it -- in prod the hourly UexUniverseSyncService sweep populates it.
-- The E2E stack has no such sweep: it runs the dev profile but the stacked
-- docker-compose.localtest.yml override sets KRT_UEX_SCHEDULER_ENABLED=false, and
-- the DB is ephemeral per run. So without this fixture nothing ever sets the flag
-- and every refinery flow finds an empty picker (the 10 timeouts of 2026-07-28).
INSERT INTO city (id, name, has_refinery, has_refinery_terminal)
VALUES ('11111111-1111-1111-1111-111111111111', 'E2E Refinery City', true, true)
ON CONFLICT (id) DO NOTHING;

-- The refinery terminal the flag above is derived FROM. Not strictly required for
-- the picker (which reads the parent flag), but it keeps the fixture internally
-- consistent: should a sweep ever run in an E2E stack,
-- UexUniverseSyncService.reconcileRefineryTerminalFlags() recomputes every parent
-- flag from these rows and would otherwise clear the city's flag again, silently
-- re-breaking the refinery flows. Matching mirrors that method exactly: type =
-- 'refinery', is_available_live = true, city_name = the city's name, and
-- space_station_name NULL so the city branch (not the station branch) matches.
INSERT INTO terminal (id, name, type, is_available_live, city_name, space_station_name)
VALUES ('77777777-7777-7777-7777-777777777777', 'E2E Refinery Terminal', 'refinery',
        true, 'E2E Refinery City', NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO location (id, name, city_id, hidden)
VALUES ('22222222-2222-2222-2222-222222222222', 'E2E Refinery Hub',
        '11111111-1111-1111-1111-111111111111', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO manufacturer (id, name, abbreviation, hidden)
VALUES ('33333333-3333-3333-3333-333333333333', 'E2E Manufacturer', 'E2EM', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ship_type (id, name, manufacturer_id, hidden)
VALUES ('44444444-4444-4444-4444-444444444444', 'E2E Ship Type',
        '33333333-3333-3333-3333-333333333333', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO refining_method (id, name)
VALUES ('55555555-5555-5555-5555-555555555555', 'E2E Refining Method')
ON CONFLICT (id) DO NOTHING;

-- A city carrying a numeric id_city, required by the Mein Inventar (personal
-- inventory) location typeahead: PersonalInventoryItemService.resolveLocationName
-- looks the location up by id_city / id_space_station, and the UEX location search
-- skips rows whose id_city is null. The refinery city above deliberately has no
-- id_city, so PersonalInventoryCrudE2eTest seeds this distinct one to select.
INSERT INTO city (id, name, id_city, has_refinery)
VALUES ('66666666-6666-6666-6666-666666666666', 'E2E Personal Inventory City', 900001, false)
ON CONFLICT (id) DO NOTHING;
