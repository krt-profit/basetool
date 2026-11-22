-- Two ship types the upload name "Choice Hornet" fits once "E2E" is dropped (REQ-HANGAR-019).
INSERT INTO ship_type (id, version, created_at, updated_at, name, manufacturer_id, hidden, p4k_uuid)
VALUES ('44444444-4444-4444-4444-444444444451', 0, now(), now(), 'E2E Choice Hornet Mk I',
        '33333333-3333-3333-3333-333333333333', false, '44444444-4444-4444-4444-4444444445a1')
ON CONFLICT (id) DO UPDATE SET p4k_uuid = EXCLUDED.p4k_uuid;

INSERT INTO ship_type (id, version, created_at, updated_at, name, manufacturer_id, hidden, p4k_uuid)
VALUES ('44444444-4444-4444-4444-444444444452', 0, now(), now(), 'E2E Choice Hornet Mk II',
        '33333333-3333-3333-3333-333333333333', false, '44444444-4444-4444-4444-4444444445a2')
ON CONFLICT (id) DO UPDATE SET p4k_uuid = EXCLUDED.p4k_uuid;
