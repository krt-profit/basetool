-- REQ-P4K-027, REQ-P4K-028, ADR-0340: the P4K leads a blueprint's recipe, its categories are the
-- blueprint categories, and the SC Wiki blueprint sync fills gaps.

-- A blueprint the P4K seeds has no SC Wiki UUID until the Wiki lists it.
ALTER TABLE blueprint ALTER COLUMN scwiki_uuid DROP NOT NULL;

-- One row per P4K blueprint category, keyed by its GUID; categories do not nest. name is the
-- localised name, null while the game has none.
CREATE TABLE blueprint_category (
    id              UUID                     PRIMARY KEY,
    p4k_uuid        UUID                     NOT NULL,
    class_name      VARCHAR(255)             NOT NULL,
    name            VARCHAR(255),
    name_de         VARCHAR(255),
    p4k_synced_at   TIMESTAMP WITH TIME ZONE,
    p4k_seen_run_id UUID                     REFERENCES p4k_import_run (id),
    p4k_deleted_at  TIMESTAMP WITH TIME ZONE,
    p4k_hidden_at   TIMESTAMP WITH TIME ZONE,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_blueprint_category_p4k_uuid UNIQUE (p4k_uuid)
);

CREATE INDEX idx_blueprint_category_p4k_seen_run ON blueprint_category (p4k_seen_run_id);

-- The P4K's own values of the columns it leads; the Wiki writes a led column only where its P4K
-- value is null. p4k_recipe marks a recipe graph the P4K wrote.
ALTER TABLE blueprint
    ADD COLUMN category_id UUID REFERENCES blueprint_category (id) ON DELETE SET NULL;
ALTER TABLE blueprint ADD COLUMN required_group_count INTEGER;
ALTER TABLE blueprint ADD COLUMN p4k_craft_time_seconds INTEGER;
ALTER TABLE blueprint ADD COLUMN p4k_default_available BOOLEAN;
ALTER TABLE blueprint ADD COLUMN p4k_dismantle_time_seconds INTEGER;
ALTER TABLE blueprint ADD COLUMN p4k_dismantle_efficiency DOUBLE PRECISION;
ALTER TABLE blueprint ADD COLUMN p4k_recipe BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_blueprint_category ON blueprint (category_id);

ALTER TABLE blueprint_requirement_group ADD COLUMN is_optional BOOLEAN NOT NULL DEFAULT FALSE;

-- The ResourceType or entity GUID a P4K cost line names.
ALTER TABLE blueprint_ingredient ADD COLUMN p4k_resource_guid UUID;
ALTER TABLE blueprint_ingredient ADD COLUMN p4k_item_guid UUID;
ALTER TABLE blueprint_dismantle_return ADD COLUMN p4k_resource_guid UUID;

-- property_guid is the gameplayProperties GUID; value_kind is MULTIPLIER (a factor) or ADDITIVE
-- (a summand), null for a Wiki modifier.
ALTER TABLE blueprint_requirement_modifier ADD COLUMN property_guid UUID;
ALTER TABLE blueprint_requirement_modifier ADD COLUMN value_kind VARCHAR(16);
ALTER TABLE blueprint_requirement_modifier
    ADD CONSTRAINT chk_blueprint_requirement_modifier_value_kind
        CHECK (value_kind IN ('MULTIPLIER', 'ADDITIVE'));

-- The blueprint categories join the run, the change report and the review list.
ALTER TABLE p4k_import_run_kind DROP CONSTRAINT chk_p4k_import_run_kind_kind;
ALTER TABLE p4k_import_run_kind
    ADD CONSTRAINT chk_p4k_import_run_kind_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT',
                        'SHOP', 'RESOURCE_GROUP', 'BLUEPRINT_CATEGORY'));
ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_kind;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT',
                        'SHOP', 'RESOURCE_GROUP', 'BLUEPRINT_CATEGORY'));
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_kind;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT',
                        'SHOP', 'RESOURCE_GROUP', 'BLUEPRINT_CATEGORY'));
