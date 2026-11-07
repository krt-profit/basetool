-- REQ-P4K-042…044, ADR-0347: the P4K shops, their UEX terminal link and their item lists.

-- One row per catalogue shop, keyed by its unique key (the CryGUID chain down to the shop entity).
-- p4k_uuid is a name-based UUID of the key, the GUID the shared P4K machinery keys the row by.
-- inventory is null while the shop's item list is unknown. terminal_link_manual marks an admin's
-- link or clear, which no APPLY overwrites.
CREATE TABLE shop (
    id                   UUID                     PRIMARY KEY,
    shop_key             TEXT                     NOT NULL,
    p4k_uuid             UUID                     NOT NULL,
    entity_guid          UUID,
    entity_name          VARCHAR(255),
    registry_name        VARCHAR(255),
    star_system          VARCHAR(64),
    location_name        VARCHAR(255),
    kind                 VARCHAR(32),
    brand                VARCHAR(64),
    commodity_exchange   BOOLEAN                  NOT NULL DEFAULT FALSE,
    inventory            VARCHAR(255),
    starmap_object_id    UUID                     REFERENCES starmap_object (id) ON DELETE SET NULL,
    terminal_id          UUID                     REFERENCES terminal (id) ON DELETE SET NULL,
    terminal_link_manual BOOLEAN                  NOT NULL DEFAULT FALSE,
    p4k_synced_at        TIMESTAMP WITH TIME ZONE,
    p4k_seen_run_id      UUID                     REFERENCES p4k_import_run (id),
    p4k_deleted_at       TIMESTAMP WITH TIME ZONE,
    p4k_hidden_at        TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_shop_shop_key UNIQUE (shop_key),
    CONSTRAINT uq_shop_p4k_uuid UNIQUE (p4k_uuid),
    CONSTRAINT ck_shop_kind
        CHECK (kind IN ('ADMIN', 'ARMOR', 'BAR', 'CARGO_ADMIN', 'CLOTHING', 'COMMEX',
                        'CONVENIENCE_STORE', 'COURIER', 'DEALERSHIP', 'ELECTRONICS', 'FOOD',
                        'MARKET_STALLS', 'MEDICAL', 'PERSONAL_WEAPON', 'REFINERY', 'SERVICE',
                        'SHIP_PART', 'SHIP_WEAPON', 'SHOP_COMM', 'SHOP_FOOD'))
);

CREATE INDEX idx_shop_starmap_object ON shop (starmap_object_id);
CREATE INDEX idx_shop_terminal ON shop (terminal_id);
CREATE INDEX idx_shop_p4k_seen_run ON shop (p4k_seen_run_id);

-- The items a shop's known item list names; a shop with an unknown list has no rows.
CREATE TABLE shop_item (
    shop_id      UUID NOT NULL REFERENCES shop (id) ON DELETE CASCADE,
    game_item_id UUID NOT NULL REFERENCES game_item (id) ON DELETE CASCADE,
    PRIMARY KEY (shop_id, game_item_id)
);
CREATE INDEX idx_shop_item_game_item ON shop_item (game_item_id);

-- The shop kind joins the run, the change report and the review list.
ALTER TABLE p4k_import_run_kind DROP CONSTRAINT chk_p4k_import_run_kind_kind;
ALTER TABLE p4k_import_run_kind
    ADD CONSTRAINT chk_p4k_import_run_kind_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT',
                        'SHOP', 'RESOURCE_GROUP'));
ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_kind;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT',
                        'SHOP', 'RESOURCE_GROUP'));
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_kind;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_kind
        CHECK (kind IN ('MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'STARMAP_OBJECT',
                        'SHOP', 'RESOURCE_GROUP'));

-- A shop the APPLY links to several UEX terminals of its place or to none; the case's guid is the
-- shop's p4k_uuid.
ALTER TABLE p4k_review_case DROP CONSTRAINT chk_p4k_review_case_reason;
ALTER TABLE p4k_review_case
    ADD CONSTRAINT chk_p4k_review_case_reason
        CHECK (reason IN ('UNCLEAR_PLAYABILITY', 'SEEDED_NOT_CREATABLE', 'GUID_CONFLICT',
                          'NAME_CONFLICT', 'PRODUCT_KEY_UNRESOLVED', 'PRODUCT_KEY_AMBIGUOUS',
                          'STARMAP_LINK_AMBIGUOUS', 'STARMAP_LINK_NOT_FOUND',
                          'SHOP_LINK_AMBIGUOUS', 'SHOP_LINK_NOT_FOUND', 'RESOURCE_JOIN_NONE',
                          'RESOURCE_JOIN_SEVERAL', 'NO_RESOURCE_GROUP'));
