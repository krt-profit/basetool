INSERT INTO system_setting (setting_key, setting_value, version)
VALUES ('supply.enabled', 'true', 0)
ON CONFLICT (setting_key) DO UPDATE SET setting_value = 'true';

INSERT INTO supply_unit_setting (org_unit_id, version, may_raise_needs, accepts_donations)
VALUES ('00000000-0000-0000-0000-000000000001', 0, true, true)
ON CONFLICT (org_unit_id) DO NOTHING;
