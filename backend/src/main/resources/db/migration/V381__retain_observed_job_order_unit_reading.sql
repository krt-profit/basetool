CREATE TABLE job_order_unit_read_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_order_id UUID NOT NULL REFERENCES job_order(id) ON DELETE CASCADE,
    org_unit_id UUID NOT NULL REFERENCES org_unit(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_order_unit_read_history UNIQUE (job_order_id, org_unit_id)
);

CREATE INDEX idx_job_order_unit_read_history_unit
    ON job_order_unit_read_history (org_unit_id, job_order_id);

INSERT INTO job_order_unit_read_history (job_order_id, org_unit_id)
SELECT id, responsible_org_unit_id FROM job_order
UNION
SELECT id, requesting_org_unit_id FROM job_order
UNION
SELECT job_order_id, claiming_org_unit_id FROM material_claim
ON CONFLICT (job_order_id, org_unit_id) DO NOTHING;
