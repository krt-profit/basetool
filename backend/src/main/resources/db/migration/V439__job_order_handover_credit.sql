ALTER TABLE job_order_material
    ADD COLUMN credited_amount DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE job_order_handover_item
    ADD COLUMN surplus_amount DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_job_order_handover_item_surplus CHECK (surplus_amount >= 0);

CREATE TABLE job_order_handover_credit (
    job_order_handover_item_id UUID             NOT NULL
        REFERENCES job_order_handover_item (id) ON DELETE CASCADE,
    job_order_material_id      UUID             NOT NULL
        REFERENCES job_order_material (id) ON DELETE CASCADE,
    amount                     DOUBLE PRECISION NOT NULL,
    CONSTRAINT pk_job_order_handover_credit
        PRIMARY KEY (job_order_handover_item_id, job_order_material_id),
    CONSTRAINT chk_job_order_handover_credit_amount CHECK (amount > 0)
);

CREATE INDEX idx_job_order_handover_credit_material
    ON job_order_handover_credit (job_order_material_id);

-- ADR-0400 backfill: an item is credited in full to one line of its order and material, the
-- highest floor its quality meets, else the lowest floor; ties go to the smallest line id.
INSERT INTO job_order_handover_credit (job_order_handover_item_id, job_order_material_id, amount)
SELECT DISTINCT ON (hi.id) hi.id, m.id, hi.amount
FROM job_order_handover_item hi
JOIN job_order_handover h ON h.id = hi.job_order_handover_id
JOIN job_order_material m ON m.job_order_id = h.job_order_id AND m.material_id = hi.material_id
JOIN quality_tier t ON t.id = m.quality_tier_id
WHERE hi.amount > 0
ORDER BY hi.id,
         (t.min_quality <= COALESCE(hi.quality, 0)) DESC,
         CASE WHEN t.min_quality <= COALESCE(hi.quality, 0) THEN -t.min_quality
              ELSE t.min_quality END,
         m.id;

-- An item whose material has no line on its order any more is surplus.
UPDATE job_order_handover_item hi
SET surplus_amount = hi.amount
WHERE hi.amount > 0
  AND NOT EXISTS (SELECT 1
                  FROM job_order_handover_credit c
                  WHERE c.job_order_handover_item_id = hi.id);

-- The handover lowered the stored amount by the credit, so requested = remaining + credited.
UPDATE job_order_material m
SET credited_amount = ROUND(c.credited::numeric, 3)::double precision,
    amount          = ROUND((m.amount + c.credited)::numeric, 3)::double precision
FROM (SELECT job_order_material_id, SUM(amount) AS credited
      FROM job_order_handover_credit
      GROUP BY job_order_material_id) c
WHERE c.job_order_material_id = m.id;

ALTER TABLE job_order_material
    ADD CONSTRAINT chk_job_order_material_credited
        CHECK (credited_amount >= 0 AND credited_amount <= amount);
