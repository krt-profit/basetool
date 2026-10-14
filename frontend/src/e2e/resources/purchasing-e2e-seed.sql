INSERT INTO system_setting (setting_key, setting_value, version)
VALUES ('purchasing.enabled', 'true', 0)
ON CONFLICT (setting_key) DO UPDATE SET setting_value = 'true';

UPDATE purchasing_setting SET default_monthly_limit = 100000000
WHERE id = '00000000-0000-0000-0000-0000000a0001';

INSERT INTO purchasing_setting_allowed_tier (setting_id, quality_tier_id)
SELECT '00000000-0000-0000-0000-0000000a0001', id FROM quality_tier
ON CONFLICT DO NOTHING;
