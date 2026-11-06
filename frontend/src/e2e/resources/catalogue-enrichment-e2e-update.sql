UPDATE ship_type SET vehicle_inventory_scu = 1.42, scwiki_game_version_seen = 'E2E-BUILD-B',
    version = version + 1, updated_at = now()
WHERE id = '0e2e0000-0000-4000-8000-000000009201';

UPDATE game_item SET wiki_metadata = jsonb_set(wiki_metadata, '{gameVersion}', '"E2E-BUILD-B"'),
    version = version + 1, updated_at = now()
WHERE id = '0e2e0000-0000-4000-8000-000000009301';

UPDATE blueprint SET wiki_metadata = jsonb_set(wiki_metadata, '{gameVersion}', '"E2E-BUILD-B"'),
    version = version + 1, updated_at = now()
WHERE id = '0e2e0000-0000-4000-8000-000000009401';
