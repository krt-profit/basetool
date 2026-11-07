-- Commodities keyed by their own P4K GUID with the subtypeGuid join, and the resource groups as
-- the commodity categories (REQ-P4K-024…026, ADR-0339).

-- subtypeGuid is an n:1 join for ingredients and refined-from links, never a key.
ALTER TABLE material ADD COLUMN p4k_subtype_uuid UUID;
CREATE INDEX idx_material_p4k_subtype_uuid ON material (p4k_subtype_uuid);

-- A resource group is identified by its GUID; two groups may share a name (both "Man-made" on LIVE).
ALTER TABLE material_category DROP CONSTRAINT material_category_name_key;
ALTER TABLE material_category ADD COLUMN name_de VARCHAR(255);
ALTER TABLE material_category ADD COLUMN class_name VARCHAR(255);
ALTER TABLE material_category
    ADD COLUMN parent_id UUID REFERENCES material_category (id) ON DELETE SET NULL;
ALTER TABLE material_category ADD COLUMN p4k_uuid UUID;
ALTER TABLE material_category ADD COLUMN p4k_synced_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE material_category ADD COLUMN p4k_deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE material_category ADD COLUMN p4k_hidden_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE material_category ADD COLUMN p4k_seen_run_id UUID REFERENCES p4k_import_run (id);

CREATE UNIQUE INDEX uq_material_category_p4k_uuid ON material_category (p4k_uuid);
CREATE INDEX idx_material_category_parent ON material_category (parent_id);
CREATE INDEX idx_material_category_p4k_seen_run ON material_category (p4k_seen_run_id);

ALTER TABLE p4k_import_run_kind DROP CONSTRAINT chk_p4k_import_run_kind_kind;
ALTER TABLE p4k_import_run_kind
    ADD CONSTRAINT chk_p4k_import_run_kind_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'RESOURCE_GROUP'));
ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_kind;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'RESOURCE_GROUP'));
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_kind;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'RESOURCE_GROUP'));

-- A ResourceType that a blueprint ingredient or a refinedSubtypeGuid references joins no
-- commodity record, or several (REQ-P4K-024).
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_reason;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_reason
        CHECK (reason IN ('UNCLEAR_PLAYABILITY', 'SEEDED_NOT_CREATABLE', 'GUID_CONFLICT',
                          'RESOURCE_JOIN_NONE', 'RESOURCE_JOIN_SEVERAL'));
