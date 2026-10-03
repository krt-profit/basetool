INSERT INTO app_user (id, version, created_at, updated_at, username, email, display_name, in_keycloak,
                      enabled_in_keycloak, join_date, approval_status, approved_at, rsi_handle)
VALUES ('5a4d0000-0000-4000-8000-000000000001', 0, now(), now(), 'sandbox-member',
        'sandbox-member@example.invalid', 'Sandbox Member', true, true, current_date, 'ACTIVE', now(),
        'Sandbox_Member'),
       ('5a4d0000-0000-4000-8000-000000000002', 0, now(), now(), 'sandbox-member-2',
        'sandbox-member-2@example.invalid', 'Sandbox Member 2', true, true, current_date, 'ACTIVE',
        now(), NULL),
       ('5a4d0000-0000-4000-8000-000000000003', 0, now(), now(), 'sandbox-admin',
        'sandbox-admin@example.invalid', 'Sandbox Admin', true, true, current_date, 'ACTIVE', now(),
        NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES ('5a4d0000-0000-4000-8000-000000000001'::uuid, 'KRT_MEMBER'),
             ('5a4d0000-0000-4000-8000-000000000002'::uuid, 'KRT_MEMBER'),
             ('5a4d0000-0000-4000-8000-000000000003'::uuid, 'KRT_MEMBER'),
             ('5a4d0000-0000-4000-8000-000000000003'::uuid, 'OFFICER'),
             ('5a4d0000-0000-4000-8000-000000000003'::uuid, 'ADMIN')) AS u (id, code)
JOIN role r ON r.code = u.code
ON CONFLICT DO NOTHING;

INSERT INTO terms_acceptance (id, user_id, terms_version, accepted_at)
SELECT gen_random_uuid(), u.id, :'terms_version', now()
FROM app_user u
WHERE u.id IN ('5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000000002',
               '5a4d0000-0000-4000-8000-000000000003')
ON CONFLICT DO NOTHING;

INSERT INTO org_unit (id, version, created_at, updated_at, kind, name, shorthand, description,
                      active, is_promotion_enabled, is_profit_eligible)
VALUES ('5a4d0000-0000-4000-8000-000000000101', 0, now(), now(), 'SQUADRON', 'Sandbox Squadron',
        'SBX', 'The second squadron of the local exchange sandbox', true, false, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO org_unit_membership (user_id, org_unit_id, kind)
VALUES ('5a4d0000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'SQUADRON'),
       ('5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000000101', 'SQUADRON'),
       ('5a4d0000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000001', 'SQUADRON'),
       ('5a4d0000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000001', 'SQUADRON')
ON CONFLICT DO NOTHING;

INSERT INTO app_user (id, version, created_at, updated_at, username, email, display_name, in_keycloak,
                      enabled_in_keycloak, join_date, approval_status, approved_at, rsi_handle)
SELECT format('5a4d0000-0000-4000-8000-0000000002%s', lpad(n::text, 2, '0'))::uuid, 0, now(), now(),
       format('sandbox-load-%s', lpad(n::text, 2, '0')),
       format('sandbox-load-%s@example.invalid', lpad(n::text, 2, '0')),
       format('Sandbox Load %s', lpad(n::text, 2, '0')), true, true, current_date, 'ACTIVE', now(), NULL
FROM generate_series(1, 16) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM app_user u
JOIN role r ON r.code = 'KRT_MEMBER'
WHERE u.username LIKE 'sandbox-load-%'
ON CONFLICT DO NOTHING;

INSERT INTO terms_acceptance (id, user_id, terms_version, accepted_at)
SELECT gen_random_uuid(), u.id, :'terms_version', now()
FROM app_user u
WHERE u.username LIKE 'sandbox-load-%'
ON CONFLICT DO NOTHING;

INSERT INTO org_unit_membership (user_id, org_unit_id, kind)
SELECT u.id, '00000000-0000-0000-0000-000000000001', 'SQUADRON'
FROM app_user u
WHERE u.username LIKE 'sandbox-load-%'
ON CONFLICT DO NOTHING;

INSERT INTO manufacturer (id, version, created_at, updated_at, name, abbreviation, hidden)
VALUES ('5a4d0000-0000-4000-8000-000000001001', 0, now(), now(), 'Sandbox Manufacturer', 'SBXM', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO city (id, version, created_at, updated_at, id_city, name, code, star_system_name,
                  planet_name, is_available, is_available_live, is_visible)
VALUES ('5a4d0000-0000-4000-8000-000000001101', 0, now(), now(), 990001, 'Sandbox City', 'SBXC',
        'Sandbox System', 'Sandbox Prime', true, true, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO space_station (id, version, created_at, updated_at, id_space_station, name, code,
                           star_system_name, is_available, is_available_live, is_visible)
VALUES ('5a4d0000-0000-4000-8000-000000001102', 0, now(), now(), 990002, 'Sandbox Station', 'SBXS',
        'Sandbox System', true, true, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO location (id, version, created_at, updated_at, name, description, city_id,
                      space_station_id, hidden)
VALUES ('5a4d0000-0000-4000-8000-000000001201', 0, now(), now(), 'Sandbox City Hangar',
        'A Lager location in Sandbox City', '5a4d0000-0000-4000-8000-000000001101', NULL, false),
       ('5a4d0000-0000-4000-8000-000000001202', 0, now(), now(), 'Sandbox Station Storage',
        'A Lager location on Sandbox Station', NULL, '5a4d0000-0000-4000-8000-000000001102', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ship_type (id, version, created_at, updated_at, name, manufacturer_id, hidden, scu,
                       uex_vehicle_id, class_name, name_full)
VALUES ('5a4d0000-0000-4000-8000-000000001301', 0, now(), now(), 'Sandbox Hauler',
        '5a4d0000-0000-4000-8000-000000001001', false, 64, 990101, 'SBXM_Hauler',
        'Sandbox Manufacturer Hauler'),
       ('5a4d0000-0000-4000-8000-000000001302', 0, now(), now(), 'Sandbox Miner',
        '5a4d0000-0000-4000-8000-000000001001', false, 32, 990102, 'SBXM_Miner',
        'Sandbox Manufacturer Miner')
ON CONFLICT (id) DO NOTHING;

INSERT INTO material (id, version, created_at, updated_at, name, type, quantity_type, code,
                      id_commodity, refined_material_id, is_raw, is_refined, is_visible, scwiki_key)
VALUES ('5a4d0000-0000-4000-8000-000000001402', 0, now(), now(), 'Sandbox Metal', 'REFINED', 'SCU',
        'SBXMET', NULL, NULL, 0, 1, true, 'sbx_metal'),
       ('5a4d0000-0000-4000-8000-000000001401', 0, now(), now(), 'Sandbox Ore (Raw)', 'RAW', 'SCU',
        'SBXORE', NULL, '5a4d0000-0000-4000-8000-000000001402', 1, 0, true, 'sbx_ore_raw'),
       ('5a4d0000-0000-4000-8000-000000001403', 0, now(), now(), 'Sandbox Trade Goods', 'NO_REFINE',
        'SCU', 'SBXTRD', 990201, NULL, 0, 0, true, 'sbx_trade_goods'),
       ('5a4d0000-0000-4000-8000-000000001404', 0, now(), now(), 'Sandbox Component', 'NO_REFINE',
        'PIECE', 'SBXCMP', NULL, NULL, 0, 0, true, 'sbx_component')
ON CONFLICT (id) DO NOTHING;

INSERT INTO game_item (id, name, manufacturer_id, kind, source_systems, class_name, uex_item_id)
VALUES ('5a4d0000-0000-4000-8000-000000001501', 'Sandbox Rifle',
        '5a4d0000-0000-4000-8000-000000001001', 'WEAPON', 'UEX_ONLY', 'sbxm_rifle_01', 990301),
       ('5a4d0000-0000-4000-8000-000000001502', 'Sandbox Helmet',
        '5a4d0000-0000-4000-8000-000000001001', 'ARMOR', 'UEX_ONLY', 'sbxm_helmet_01', 990302)
ON CONFLICT (id) DO NOTHING;

INSERT INTO blueprint (id, scwiki_uuid, scwiki_key, output_item_id, output_name, craft_time_seconds,
                       is_available_by_default, ingredient_count)
VALUES ('5a4d0000-0000-4000-8000-000000001601', '5a4d0000-0000-4000-8000-000000001611',
        'BP_CRAFT_SBXM_RIFLE_01', '5a4d0000-0000-4000-8000-000000001501', 'Sandbox Rifle', 120,
        false, 1),
       ('5a4d0000-0000-4000-8000-000000001602', '5a4d0000-0000-4000-8000-000000001612',
        'BP_CRAFT_SBXM_HELMET_01', '5a4d0000-0000-4000-8000-000000001502', 'Sandbox Helmet', 90,
        false, 1),
       ('5a4d0000-0000-4000-8000-000000001603', '5a4d0000-0000-4000-8000-000000001613',
        'BP_CRAFT_SBXM_KNIFE_01', NULL, 'Sandbox Knife', 30, true, 0),
       ('5a4d0000-0000-4000-8000-000000001631', '5a4d0000-0000-4000-8000-000000001641', NULL, NULL,
        'S-38 Magazine (20 cap)', 20, true, 0),
       ('5a4d0000-0000-4000-8000-000000001632', '5a4d0000-0000-4000-8000-000000001642', NULL, NULL,
        'P4-AR Magazine (40 cap)', 20, true, 0),
       ('5a4d0000-0000-4000-8000-000000001633', '5a4d0000-0000-4000-8000-000000001643', NULL, NULL,
        'Field Recon Suit Arms', 60, true, 0),
       ('5a4d0000-0000-4000-8000-000000001634', '5a4d0000-0000-4000-8000-000000001644', NULL, NULL,
        'Field Recon Suit Core', 60, true, 0),
       ('5a4d0000-0000-4000-8000-000000001635', '5a4d0000-0000-4000-8000-000000001645', NULL, NULL,
        'Field Recon Suit Helmet', 60, true, 0),
       ('5a4d0000-0000-4000-8000-000000001636', '5a4d0000-0000-4000-8000-000000001646', NULL, NULL,
        'Field Recon Suit Legs', 60, true, 0),
       ('5a4d0000-0000-4000-8000-000000001637', '5a4d0000-0000-4000-8000-000000001647', NULL, NULL,
        'S-38 Pistol', 60, true, 0),
       ('5a4d0000-0000-4000-8000-000000001638', '5a4d0000-0000-4000-8000-000000001648', NULL, NULL,
        'P4-AR Rifle', 90, true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO blueprint_ingredient (id, blueprint_id, order_index, kind, material_id, quantity_scu,
                                  min_quality)
VALUES ('5a4d0000-0000-4000-8000-000000001621', '5a4d0000-0000-4000-8000-000000001601', 0,
        'RESOURCE', '5a4d0000-0000-4000-8000-000000001402', 0.5, 500),
       ('5a4d0000-0000-4000-8000-000000001622', '5a4d0000-0000-4000-8000-000000001602', 0,
        'RESOURCE', '5a4d0000-0000-4000-8000-000000001402', 0.2, NULL)
ON CONFLICT (id) DO NOTHING;

INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name, output_item_id,
                                acquired_at, note, source)
VALUES ('5a4d0000-0000-4000-8000-000000001801', '5a4d0000-0000-4000-8000-000000000001',
        'sandbox rifle', 'Sandbox Rifle', '5a4d0000-0000-4000-8000-000000001501', now(),
        'Seeded by the sandbox', 'MANUAL'),
       ('5a4d0000-0000-4000-8000-000000001802', '5a4d0000-0000-4000-8000-000000000001',
        'sandbox knife', 'Sandbox Knife', NULL, now(), NULL, 'LOG'),
       ('5a4d0000-0000-4000-8000-000000001803', '5a4d0000-0000-4000-8000-000000000002',
        'sandbox helmet', 'Sandbox Helmet', '5a4d0000-0000-4000-8000-000000001502', now(), NULL,
        'MANUAL')
ON CONFLICT DO NOTHING;

INSERT INTO inventory_item (id, version, created_at, updated_at, user_id, material_id, game_item_id,
                            location_id, quality, amount, personal, owning_org_unit_id, stolen)
VALUES ('5a4d0000-0000-4000-8000-000000001901', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000001402', NULL,
        '5a4d0000-0000-4000-8000-000000001201', 700, 12.5, true,
        '00000000-0000-0000-0000-000000000001', false),
       ('5a4d0000-0000-4000-8000-000000001902', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000001402', NULL,
        '5a4d0000-0000-4000-8000-000000001201', 700, 3, true,
        '5a4d0000-0000-4000-8000-000000000101', false),
       ('5a4d0000-0000-4000-8000-000000001903', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000001403', NULL,
        '5a4d0000-0000-4000-8000-000000001202', 0, 8, true, NULL, false),
       ('5a4d0000-0000-4000-8000-000000001904', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000001404', NULL,
        '5a4d0000-0000-4000-8000-000000001202', 0, 5, true, NULL, false),
       ('5a4d0000-0000-4000-8000-000000001905', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', NULL, '5a4d0000-0000-4000-8000-000000001501',
        '5a4d0000-0000-4000-8000-000000001201', NULL, 1, true,
        '5a4d0000-0000-4000-8000-000000000101', false),
       ('5a4d0000-0000-4000-8000-000000001906', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000001401', NULL,
        '5a4d0000-0000-4000-8000-000000001202', 350, 20, true, NULL, false),
       ('5a4d0000-0000-4000-8000-000000001907', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000001', '5a4d0000-0000-4000-8000-000000001401', NULL,
        '5a4d0000-0000-4000-8000-000000001202', 350, 10, false,
        '5a4d0000-0000-4000-8000-000000000101', false),
       ('5a4d0000-0000-4000-8000-000000001911', 0, now(), now(),
        '5a4d0000-0000-4000-8000-000000000002', '5a4d0000-0000-4000-8000-000000001402', NULL,
        '5a4d0000-0000-4000-8000-000000001201', 600, 4, true,
        '00000000-0000-0000-0000-000000000001', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO material_exchange_offer (id, inventory_item_id, owner_id, owning_org_unit_id, remark,
                                     status, released_at, version, created_at, updated_at,
                                     offered_amount, offer_kind)
VALUES ('5a4d0000-0000-4000-8000-000000002001', '5a4d0000-0000-4000-8000-000000001901',
        '5a4d0000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001',
        'Seeded by the sandbox', 'ACTIVE', now(), 0, now(), now(), 5, 'MATERIAL')
ON CONFLICT DO NOTHING;

INSERT INTO ship (id, version, created_at, updated_at, name, ship_type_id, insurance, location_id,
                  fitted, owner_id, owning_org_unit_id)
VALUES ('5a4d0000-0000-4000-8000-000000002101', 0, now(), now(), 'SBX Hauler One',
        '5a4d0000-0000-4000-8000-000000001301', 'LTI', '5a4d0000-0000-4000-8000-000000001201', true,
        '5a4d0000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001'),
       ('5a4d0000-0000-4000-8000-000000002102', 0, now(), now(), 'SBX Miner One',
        '5a4d0000-0000-4000-8000-000000001302', '120', NULL, false,
        '5a4d0000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001'),
       ('5a4d0000-0000-4000-8000-000000002111', 0, now(), now(), 'SBX Hauler Two',
        '5a4d0000-0000-4000-8000-000000001301', 'LTI', '5a4d0000-0000-4000-8000-000000001202', false,
        '5a4d0000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO job_order (id, handle, priority, created_at, updated_at, version, status,
                       requesting_org_unit_id, responsible_org_unit_id, comment, type)
VALUES ('5a4d0000-0000-4000-8000-000000002201', NULL, 2, now(), now(), 0, 'OPEN',
        '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
        'Sandbox material order', 'MATERIAL'),
       ('5a4d0000-0000-4000-8000-000000002202', NULL, 2, now(), now(), 0, 'OPEN',
        '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
        'Sandbox item order', 'ITEM'),
       ('5a4d0000-0000-4000-8000-000000002203', NULL, 3, now(), now(), 0, 'IN_PROGRESS',
        '5a4d0000-0000-4000-8000-000000000101', '5a4d0000-0000-4000-8000-000000000101',
        'Sandbox squadron ore order', 'MATERIAL')
ON CONFLICT (id) DO NOTHING;

INSERT INTO job_order_material (id, job_order_id, material_id, quality_tier_id, amount,
                                created_at, updated_at, version)
VALUES ('5a4d0000-0000-4000-8000-000000002211', '5a4d0000-0000-4000-8000-000000002201',
        '5a4d0000-0000-4000-8000-000000001402', '6b1f2e0a-3c1d-4f5e-9a10-000000000650', 30, now(), now(),
        0),
       ('5a4d0000-0000-4000-8000-000000002212', '5a4d0000-0000-4000-8000-000000002201',
        '5a4d0000-0000-4000-8000-000000001403', '6b1f2e0a-3c1d-4f5e-9a10-000000000000', 10, now(), now(),
        0),
       ('5a4d0000-0000-4000-8000-000000002213', '5a4d0000-0000-4000-8000-000000002203',
        '5a4d0000-0000-4000-8000-000000001401', '6b1f2e0a-3c1d-4f5e-9a10-000000000650', 50, now(), now(),
        0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO job_order_item (id, job_order_id, game_item_id, blueprint_id, amount, created_at,
                            updated_at, version)
VALUES ('5a4d0000-0000-4000-8000-000000002221', '5a4d0000-0000-4000-8000-000000002202',
        '5a4d0000-0000-4000-8000-000000001501', '5a4d0000-0000-4000-8000-000000001601', 2, now(),
        now(), 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO job_order_item_material (id, job_order_item_id, material_id, required_quantity,
                                     quality_tier_id, created_at, updated_at, version)
VALUES ('5a4d0000-0000-4000-8000-000000002231', '5a4d0000-0000-4000-8000-000000002221',
        '5a4d0000-0000-4000-8000-000000001402', 1, '6b1f2e0a-3c1d-4f5e-9a10-000000000650', now(), now(),
        0),
       ('5a4d0000-0000-4000-8000-000000002232', '5a4d0000-0000-4000-8000-000000002221',
        '5a4d0000-0000-4000-8000-000000001404', 4, '6b1f2e0a-3c1d-4f5e-9a10-000000000000', now(), now(),
        0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO exchange_client (id, client_id, display_name, status, contact_url, created_at, version)
VALUES ('5a4d0000-0000-4000-8000-000000002301', 'sandbox-client', 'Sandbox Client', 'ACTIVE',
        'https://example.invalid/sandbox-client', now(), 0),
       ('5a4d0000-0000-4000-8000-000000002302', 'sandbox-suspended-client',
        'Suspended Example Client', 'SUSPENDED', 'https://example.invalid/suspended-client', now(), 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO exchange_client_capability (exchange_client_id, capability)
SELECT '5a4d0000-0000-4000-8000-000000002301'::uuid, c.capability
FROM (VALUES ('exchange.connect'), ('exchange.blueprints.read'), ('exchange.blueprints.write'),
             ('exchange.stock.read'), ('exchange.stock.write'), ('exchange.hangar.read'),
             ('exchange.hangar.write'), ('exchange.demand.read'), ('exchange.drafts.blueprints'),
             ('exchange.drafts.refinery')) AS c (capability)
UNION ALL
SELECT '5a4d0000-0000-4000-8000-000000002302'::uuid, c.capability
FROM (VALUES ('exchange.connect'), ('exchange.blueprints.read')) AS c (capability)
ON CONFLICT DO NOTHING;

UPDATE exchange_settings
SET enabled = true, updated_at = now(), version = version + 1
WHERE id = 1 AND NOT enabled;
