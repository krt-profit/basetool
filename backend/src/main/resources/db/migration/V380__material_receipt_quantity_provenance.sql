ALTER TABLE material_provision_credit ADD COLUMN quantity_fragments jsonb;
UPDATE material_provision_credit c SET quantity_fragments = COALESCE((
    SELECT jsonb_agg(jsonb_build_object('origin', f.origin, 'fromQuanta', f.from_quanta, 'toQuanta', f.to_quanta)
        ORDER BY f.origin, f.from_quanta)
    FROM material_provision_fragment f WHERE f.credit_id = c.id), '[]'::jsonb);
ALTER TABLE material_provision_credit ALTER COLUMN quantity_fragments SET DEFAULT '[]'::jsonb;
ALTER TABLE material_provision_credit ALTER COLUMN quantity_fragments SET NOT NULL;
ALTER TABLE material_provision_credit ADD COLUMN attributed_fragments jsonb;
UPDATE material_provision_credit c SET attributed_fragments = COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
        'origin', part.q->>'origin',
        'fromQuanta', (part.q->>'fromQuanta')::bigint,
        'toQuanta', LEAST((part.q->>'toQuanta')::numeric,
            (part.q->>'fromQuanta')::numeric + round(c.amount::numeric * 1000) - part.consumed)) ORDER BY part.ordinality)
    FROM (
        SELECT q, ordinality, COALESCE(sum((q->>'toQuanta')::numeric - (q->>'fromQuanta')::numeric)
            OVER (ORDER BY ordinality ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS consumed
        FROM jsonb_array_elements(c.quantity_fragments) WITH ORDINALITY AS fragments(q, ordinality)
    ) part WHERE part.consumed < round(c.amount::numeric * 1000)), '[]'::jsonb);
ALTER TABLE material_provision_credit ALTER COLUMN attributed_fragments SET DEFAULT '[]'::jsonb;
ALTER TABLE material_provision_credit ALTER COLUMN attributed_fragments SET NOT NULL;
CREATE INDEX idx_inventory_item_quantity_provenance ON inventory_item USING gin(quantity_provenance jsonb_path_ops);
CREATE INDEX idx_material_provision_quantity_fragments ON material_provision_credit USING gin(quantity_fragments jsonb_path_ops);
ALTER TABLE material_provision_credit ADD COLUMN evidence varchar(32) NOT NULL DEFAULT 'CREDITED_SUBSET';
ALTER TABLE material_provision_credit ADD CONSTRAINT chk_material_provision_evidence CHECK (evidence IN ('OBSERVED', 'CREDITED_SUBSET'));
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE material_provision_fragment ADD CONSTRAINT excl_material_provision_physical_once
    EXCLUDE USING gist (origin WITH =, int8range(from_quanta, to_quanta, '[)') WITH &&);
