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

INSERT INTO location (id, version, created_at, updated_at, name, city_id, hidden)
VALUES ('0e2e0000-0000-4000-8000-000000008001', 0, now(), now(),
        'E2E Collection Transfer Alt Hub', '11111111-1111-1111-1111-111111111111', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO terminal (id, version, created_at, updated_at, name, type, is_available_live,
                      star_system_name, city_name, hidden, has_loading_dock, is_auto_load)
VALUES ('0e2e0000-0000-4000-8000-000000008101', 0, now(), now(),
        'E2E Matrix Trade Terminal', 'commodity', true, 'Stanton', 'E2E Refinery City',
        false, true, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO material (id, version, created_at, updated_at, id_commodity, name, type,
                      quantity_type, is_raw, is_refined, is_visible, source_systems)
VALUES ('0e2e0000-0000-4000-8000-000000008201', 0, now(), now(), 900801,
        'E2E Matrix Commodity Alpha', 'REFINED', 'SCU', 0, 1, true, 'UEX_ONLY'),
       ('0e2e0000-0000-4000-8000-000000008202', 0, now(), now(), 900802,
        'E2E Matrix Commodity Beta', 'REFINED', 'SCU', 0, 1, true, 'UEX_ONLY')
ON CONFLICT (id) DO NOTHING;

INSERT INTO material_price (id, version, created_at, updated_at, material_id, terminal_id,
                            price_buy, price_sell, status_buy, status_sell)
VALUES ('0e2e0000-0000-4000-8000-000000008301', 0, now(), now(),
        '0e2e0000-0000-4000-8000-000000008201', '0e2e0000-0000-4000-8000-000000008101',
        100, 120, true, true),
       ('0e2e0000-0000-4000-8000-000000008302', 0, now(), now(),
        '0e2e0000-0000-4000-8000-000000008202', '0e2e0000-0000-4000-8000-000000008101',
        200, 240, true, true)
ON CONFLICT (id) DO NOTHING;
