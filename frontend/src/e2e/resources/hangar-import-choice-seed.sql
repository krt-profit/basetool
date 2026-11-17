-- Two ship types the upload name "Choice Hornet" fits once "E2E" is dropped (REQ-HANGAR-019).
INSERT INTO ship_type (id, version, created_at, updated_at, name, manufacturer_id, hidden)
VALUES ('44444444-4444-4444-4444-444444444451', 0, now(), now(), 'E2E Choice Hornet Mk I',
        '33333333-3333-3333-3333-333333333333', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ship_type (id, version, created_at, updated_at, name, manufacturer_id, hidden)
VALUES ('44444444-4444-4444-4444-444444444452', 0, now(), now(), 'E2E Choice Hornet Mk II',
        '33333333-3333-3333-3333-333333333333', false)
ON CONFLICT (id) DO NOTHING;
