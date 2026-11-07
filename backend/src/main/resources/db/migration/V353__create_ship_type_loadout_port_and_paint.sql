-- REQ-P4K-040: a ship type's default loadout as its port tree, written by the P4K import only.
-- parent_id nests a port under the port whose attachment carries it; position orders siblings as the
-- catalogue lists them. types holds one entry per item type, written as Type or Type.SubType.
-- item_guid keeps the catalogue's default entity, game_item_id the item row it resolves to.
CREATE TABLE ship_type_loadout_port
(
    id               UUID         NOT NULL PRIMARY KEY,
    ship_type_id     UUID         NOT NULL REFERENCES ship_type (id) ON DELETE CASCADE,
    parent_id        UUID REFERENCES ship_type_loadout_port (id) ON DELETE CASCADE,
    position         INTEGER      NOT NULL,
    name             VARCHAR(255) NOT NULL,
    min_size         INTEGER,
    max_size         INTEGER,
    types            TEXT[]       NOT NULL,
    flags            TEXT[]       NOT NULL,
    required_tags    TEXT[]       NOT NULL,
    display_name_key VARCHAR(255),
    source           VARCHAR(16),
    item_guid        UUID,
    game_item_id     UUID REFERENCES game_item (id) ON DELETE SET NULL,
    CONSTRAINT ck_ship_type_loadout_port_source
        CHECK (source IN ('LOADOUT', 'OVERRIDE', 'PORT_DEFAULT', 'UNSET', 'VEHICLE')),
    CONSTRAINT ck_ship_type_loadout_port_size CHECK (min_size >= 0 AND max_size >= 0)
);
CREATE INDEX idx_ship_type_loadout_port_ship_type ON ship_type_loadout_port (ship_type_id);
CREATE INDEX idx_ship_type_loadout_port_parent ON ship_type_loadout_port (parent_id);
CREATE INDEX idx_ship_type_loadout_port_game_item ON ship_type_loadout_port (game_item_id);

-- REQ-P4K-041: the ship types a paint item fits, the catalogue's paints.shipGuids.
CREATE TABLE ship_type_paint
(
    game_item_id UUID NOT NULL REFERENCES game_item (id) ON DELETE CASCADE,
    ship_type_id UUID NOT NULL REFERENCES ship_type (id) ON DELETE CASCADE,
    PRIMARY KEY (game_item_id, ship_type_id)
);
CREATE INDEX idx_ship_type_paint_ship_type ON ship_type_paint (ship_type_id);

-- LOADOUT_ISSUES: a ship loadout with issue codes or unresolved ports (REQ-P4K-040).
ALTER TABLE p4k_import_change DROP CONSTRAINT chk_p4k_import_change_type;
ALTER TABLE p4k_import_change
    ADD CONSTRAINT chk_p4k_import_change_type
        CHECK (change_type IN ('ADDED', 'UNMATCHED', 'REVIEW', 'IDENTITY_MISMATCH', 'UNKNOWN_VALUE',
                               'CHANGED', 'VANISHED', 'REAPPEARED', 'SWEEP_SKIPPED', 'LOADOUT_ISSUES'));
