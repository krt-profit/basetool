-- One applied P4K run with a 29-row change report and two open review cases on two P4K-seeded
-- ship types (REQ-P4K-015, REQ-P4K-016). change_number 1 keeps every real APPLY newer.

INSERT INTO p4k_import_run (id, job_id, channel, build_branch, build_version, change_number,
                            build_id, created_by, applied_at, version, created_at, updated_at)
VALUES ('0e2e0000-0000-4000-8000-000000009401', NULL, 'LIVE', 'sc-alpha-e2e-report', 'E2E-REPORT',
        1, 'e2e-report', '00000000-0000-0000-0000-000000000000', now(), 0, now(), now())
ON CONFLICT (id) DO UPDATE SET applied_at = now();

INSERT INTO p4k_import_run_kind (run_id, kind, completeness, matched, unmatched, added, changed,
                                 vanished, reappeared, sweep)
VALUES ('0e2e0000-0000-4000-8000-000000009401', 'ITEM', 'COMPLETE', 2, 26, 0, 2, 0, 0, 'SWEPT'),
       ('0e2e0000-0000-4000-8000-000000009401', 'SHIP', 'COMPLETE', 0, 0, 0, 0, 1, 0, 'SWEPT')
ON CONFLICT (run_id, kind) DO NOTHING;

DELETE FROM p4k_import_change WHERE run_id = '0e2e0000-0000-4000-8000-000000009401';

INSERT INTO p4k_import_change (id, run_id, kind, guid, change_type, field_name, class_name,
                               name_en, position)
SELECT gen_random_uuid(), '0e2e0000-0000-4000-8000-000000009401', 'ITEM', gen_random_uuid(),
       'UNMATCHED', NULL, 'E2E_UNMATCHED_' || n, 'E2E Unmatched ' || n, n
FROM generate_series(0, 25) AS n;

INSERT INTO p4k_import_change (id, run_id, kind, guid, change_type, field_name, old_value,
                               new_value, class_name, name_en, position)
VALUES (gen_random_uuid(), '0e2e0000-0000-4000-8000-000000009401', 'ITEM', gen_random_uuid(),
        'CHANGED', 'name_key', 'item_E2E_Old', 'item_E2E_New', 'E2E_CHANGED_0', 'E2E Changed 0',
        26),
       (gen_random_uuid(), '0e2e0000-0000-4000-8000-000000009401', 'ITEM', gen_random_uuid(),
        'CHANGED', 'mass', NULL, '12.5', 'E2E_CHANGED_1', 'E2E Changed 1', 27);

INSERT INTO p4k_import_change (id, run_id, kind, guid, change_type, class_name, name_en, position,
                               still_live_elsewhere)
VALUES (gen_random_uuid(), '0e2e0000-0000-4000-8000-000000009401', 'SHIP', gen_random_uuid(),
        'VANISHED', 'E2E_VANISHED_SHIP', 'E2E Vanished Ship', 28, true);

INSERT INTO ship_type (id, version, created_at, updated_at, name, hidden, source_systems,
                       p4k_uuid)
VALUES ('0e2e0000-0000-4000-8000-000000009411', 0, now(), now(), 'E2E Review Keep Ship', false,
        'P4K', '0e2e0000-0000-4000-8000-000000009421'),
       ('0e2e0000-0000-4000-8000-000000009412', 0, now(), now(), 'E2E Review Hide Ship', false,
        'P4K', '0e2e0000-0000-4000-8000-000000009422')
ON CONFLICT (id) DO UPDATE SET p4k_hidden_at = NULL;

INSERT INTO p4k_review_case (id, kind, guid, reason, status, row_id, first_run_id, last_run_id,
                             version, created_at, updated_at)
VALUES ('0e2e0000-0000-4000-8000-000000009431', 'SHIP', '0e2e0000-0000-4000-8000-000000009421',
        'SEEDED_NOT_CREATABLE', 'OPEN', '0e2e0000-0000-4000-8000-000000009411',
        '0e2e0000-0000-4000-8000-000000009401', '0e2e0000-0000-4000-8000-000000009401', 0,
        now(), now()),
       ('0e2e0000-0000-4000-8000-000000009432', 'SHIP', '0e2e0000-0000-4000-8000-000000009422',
        'SEEDED_NOT_CREATABLE', 'OPEN', '0e2e0000-0000-4000-8000-000000009412',
        '0e2e0000-0000-4000-8000-000000009401', '0e2e0000-0000-4000-8000-000000009401', 0,
        now(), now())
ON CONFLICT (id) DO UPDATE SET status = 'OPEN', resolution = NULL, resolved_by = NULL,
    resolved_at = NULL;
