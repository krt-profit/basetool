INSERT INTO material (id, version, created_at, updated_at, name, type, quantity_type, code,
                      is_raw, is_refined, is_visible, wiki_mining_data)
VALUES ('0e2e0000-0000-4000-8000-000000009101', 0, now(), now(), 'E2E Catalogue Ore', 'RAW',
        'SCU', 'E2ECATORE', 1, 0, true,
        '{"tier":2,"instability":12.5,"resistance":3.5,"shipMineable":true,"fpsMineable":false,"boxSizesScu":[1,8],"commodityGroups":["E2E mineral group"],"methods":["ship"],"systems":["Stanton"],"locations":[{"name":"E2E Mining Belt","parentName":"E2E Parent Planet","entries":[{"groupName":"E2E deposit","qualityMin":600,"qualityMax":900,"entryCount":4}]}]}'::jsonb)
ON CONFLICT (id) DO UPDATE SET wiki_mining_data = EXCLUDED.wiki_mining_data;

INSERT INTO ship_type (id, version, created_at, updated_at, name, manufacturer_id, hidden,
                       vehicle_inventory_scu, ore_capacity, max_medical_tier, health, shield_hp,
                       scwiki_game_version_seen)
VALUES ('0e2e0000-0000-4000-8000-000000009201', 0, now(), now(), 'E2E Catalogue Ship Type',
        '33333333-3333-3333-3333-333333333333', false, 0.71, 32, 2, 1500, 600, 'E2E-BUILD-A')
ON CONFLICT (id) DO UPDATE SET vehicle_inventory_scu = EXCLUDED.vehicle_inventory_scu,
    version = COALESCE(ship_type.version, 0), updated_at = now(),
    ore_capacity = EXCLUDED.ore_capacity, max_medical_tier = EXCLUDED.max_medical_tier,
    health = EXCLUDED.health, shield_hp = EXCLUDED.shield_hp,
    scwiki_game_version_seen = EXCLUDED.scwiki_game_version_seen;

INSERT INTO game_item (id, version, created_at, updated_at, name, manufacturer_id, kind,
                       source_systems, class_name, external_uuid, wiki_metadata)
VALUES ('0e2e0000-0000-4000-8000-000000009301', 0, now(), now(), 'E2E Catalogue Evidence Rifle',
        '33333333-3333-3333-3333-333333333333', 'WEAPON', 'WIKI_ONLY',
        'e2e_catalogue_evidence_rifle', '0e2e0000-0000-4000-8000-000000009311',
        '{"source":"SC_WIKI","gameVersion":"E2E-BUILD-A","requestedIncludes":["VARIANTS"],"geometry":{"physical":{"width":0.3,"height":0.1,"length":1.2},"cargo":{"width":0.4,"height":0.2,"length":1.3},"ui":{"width":0.5,"height":0.3,"length":1.4},"volumeScu":0.0002},"lootable":true,"tags":["<em>E2E source tag</em>"],"variants":[],"uexPriceEvidence":[{"origin":"UEX_VIA_SC_WIKI","priceBuy":321,"terminalId":900901,"terminalName":"E2E Derived Terminal","updatedAt":"2026-10-01T12:00:00Z","gameVersion":"E2E-OLDER-BUILD"}]}'::jsonb)
ON CONFLICT (id) DO UPDATE SET wiki_metadata = EXCLUDED.wiki_metadata;

INSERT INTO blueprint (id, version, created_at, updated_at, scwiki_uuid, scwiki_key, output_item_id,
                       output_name, craft_time_seconds, is_available_by_default, ingredient_count,
                       wiki_metadata)
VALUES ('0e2e0000-0000-4000-8000-000000009401', 0, now(), now(), '0e2e0000-0000-4000-8000-000000009411',
        'BP_E2E_CATALOGUE_EVIDENCE', '0e2e0000-0000-4000-8000-000000009301',
        'E2E Catalogue Evidence Rifle', 120, false, 0,
        '{"source":"SC_WIKI","gameVersion":"E2E-BUILD-A","loadedSections":["MISSIONS"],"unlockingMissions":[{"title":"E2E unlock mission","chance":0.25,"webUrl":"https://api.star-citizen.wiki/E2E-mission"}]}'::jsonb)
ON CONFLICT (id) DO UPDATE SET wiki_metadata = EXCLUDED.wiki_metadata;

INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name, output_item_id, source)
SELECT '0e2e0000-0000-4000-8000-000000009501', id, 'e2e catalogue evidence rifle',
       'E2E Catalogue Evidence Rifle', '0e2e0000-0000-4000-8000-000000009301', 'MANUAL'
FROM app_user WHERE username = 'test-member'
ON CONFLICT (id) DO NOTHING;
