INSERT INTO material (id, version, created_at, updated_at, name, type, quantity_type, code,
                      is_raw, is_refined, is_visible)
VALUES ('0e2e0000-0000-4000-8000-000000009501', 0, now(), now(), 'Zz E2E Matrix Commodity',
        'NO_REFINE', 'SCU', 'E2EMATRIX', 0, 0, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO terminal (id, name, hidden)
VALUES ('0e2e0000-0000-4000-8000-000000009502', 'E2E Matrix Terminal', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO material_price (id, material_id, terminal_id, price_sell, status_sell, price_buy,
                            status_buy)
VALUES ('0e2e0000-0000-4000-8000-000000009503', '0e2e0000-0000-4000-8000-000000009501',
        '0e2e0000-0000-4000-8000-000000009502', 120, true, 100, true)
ON CONFLICT (id) DO NOTHING;
