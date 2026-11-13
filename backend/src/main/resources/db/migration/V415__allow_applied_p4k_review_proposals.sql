ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_resolution;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_resolution
        CHECK (resolution IN ('KEEP', 'HIDE', 'ASSIGN', 'RESOLVED', 'APPLIED'));

ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_resolved;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_resolved
        CHECK ((status = 'OPEN' AND resolution IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
            OR (status = 'REVIEWED' AND resolution IN ('KEEP', 'HIDE', 'ASSIGN', 'APPLIED')
                AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
            OR (status = 'REVIEWED' AND resolution = 'RESOLVED' AND resolved_by IS NULL
                AND resolved_at IS NOT NULL));
