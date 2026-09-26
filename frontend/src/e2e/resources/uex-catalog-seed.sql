INSERT INTO city (id, name, has_refinery, has_refinery_terminal)
VALUES ('11111111-1111-1111-1111-111111111111', 'E2E Refinery City', true, true)
ON CONFLICT (id) DO NOTHING;

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

INSERT INTO city (id, name, id_city, has_refinery)
VALUES ('66666666-6666-6666-6666-666666666666', 'E2E Personal Inventory City', 900001, false)
ON CONFLICT (id) DO NOTHING;
