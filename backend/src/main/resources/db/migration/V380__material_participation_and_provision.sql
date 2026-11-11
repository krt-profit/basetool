ALTER TABLE inventory_item ADD COLUMN quantity_provenance jsonb;

UPDATE inventory_item SET quantity_provenance = jsonb_build_array(
    jsonb_build_object('origin', id, 'fromQuanta', 0, 'toQuanta', round(amount::numeric * 1000)::bigint))
WHERE quantity_provenance IS NULL AND amount > 0;
UPDATE inventory_item SET quantity_provenance = '[]'::jsonb WHERE quantity_provenance IS NULL;
ALTER TABLE inventory_item ALTER COLUMN quantity_provenance SET DEFAULT '[]'::jsonb;
ALTER TABLE inventory_item ALTER COLUMN quantity_provenance SET NOT NULL;
CREATE TABLE job_order_participation (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    job_order_id UUID NOT NULL REFERENCES job_order(id) ON DELETE CASCADE,
    claiming_org_unit_id UUID NOT NULL REFERENCES org_unit(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (job_order_id, claiming_org_unit_id)
);

INSERT INTO job_order_participation (id, job_order_id, claiming_org_unit_id, active)
SELECT gen_random_uuid(), c.job_order_id, c.claiming_org_unit_id,
       bool_or(c.ended_at IS NULL) AND c.claiming_org_unit_id IS DISTINCT FROM j.responsible_org_unit_id
FROM material_claim c JOIN job_order j ON j.id = c.job_order_id
GROUP BY c.job_order_id, c.claiming_org_unit_id, j.responsible_org_unit_id;

ALTER TABLE material_claim ADD COLUMN participation_id UUID REFERENCES job_order_participation(id);
UPDATE material_claim c SET participation_id = p.id FROM job_order_participation p
WHERE p.job_order_id = c.job_order_id AND p.claiming_org_unit_id = c.claiming_org_unit_id;
ALTER TABLE material_claim ALTER COLUMN participation_id SET NOT NULL;
ALTER TABLE material_claim DROP CONSTRAINT chk_material_claim_amount_positive;
ALTER TABLE material_claim ADD CONSTRAINT chk_material_claim_amount_nonnegative CHECK (amount >= 0);
CREATE INDEX idx_material_claim_participation ON material_claim(participation_id);
ALTER TABLE material_claim ADD COLUMN credited_amount double precision NOT NULL DEFAULT 0;
ALTER TABLE material_claim ADD COLUMN changes jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE material_provision_credit (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    job_order_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    participation_id UUID NOT NULL,
    claiming_org_unit_id UUID NOT NULL,
    material_id UUID NOT NULL,
    quality_tier_id UUID NOT NULL,
    source_row_id UUID NOT NULL,
    amount DOUBLE PRECISION NOT NULL CHECK (amount >= 0)
);
CREATE INDEX idx_material_provision_credit_order ON material_provision_credit(job_order_id);

CREATE TABLE material_provision_fragment (
    credit_id UUID NOT NULL REFERENCES material_provision_credit(id),
    origin UUID NOT NULL,
    from_quanta BIGINT NOT NULL CHECK (from_quanta >= 0),
    to_quanta BIGINT NOT NULL,
    PRIMARY KEY (credit_id, origin, from_quanta, to_quanta),
    CHECK (to_quanta > from_quanta)
);
CREATE INDEX idx_material_provision_fragment_origin ON material_provision_fragment(origin);
CREATE INDEX idx_job_order_participation_claiming_unit ON job_order_participation(claiming_org_unit_id);
