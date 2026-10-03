ALTER TABLE inventory_item
    ADD CONSTRAINT ck_inventory_item_quality_range
        CHECK (quality IS NULL OR quality BETWEEN 0 AND 1000);

ALTER TABLE refinery_good
    ADD CONSTRAINT ck_refinery_good_quality_range
        CHECK (quality IS NULL OR quality BETWEEN 0 AND 1000);

ALTER TABLE job_order_handover_item
    ADD CONSTRAINT ck_job_order_handover_item_quality_range
        CHECK (quality IS NULL OR quality BETWEEN 0 AND 1000);

ALTER TABLE blueprint_ingredient
    ADD CONSTRAINT ck_blueprint_ingredient_min_quality_range
        CHECK (min_quality IS NULL OR min_quality BETWEEN 0 AND 1000);

ALTER TABLE job_order_material
    ADD CONSTRAINT ck_job_order_material_min_quality_range
        CHECK (min_quality IS NULL OR min_quality BETWEEN 0 AND 1000);

ALTER TABLE blueprint_requirement_modifier
    ADD CONSTRAINT ck_blueprint_requirement_modifier_quality_range
        CHECK ((quality_min IS NULL OR quality_min BETWEEN 0 AND 1000)
           AND (quality_max IS NULL OR quality_max BETWEEN 0 AND 1000));

ALTER TABLE blueprint_modifier_segment
    ADD CONSTRAINT ck_blueprint_modifier_segment_quality_range
        CHECK ((quality_min IS NULL OR quality_min BETWEEN 0 AND 1000)
           AND (quality_max IS NULL OR quality_max BETWEEN 0 AND 1000));
