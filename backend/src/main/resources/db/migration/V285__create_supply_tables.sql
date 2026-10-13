CREATE TABLE supply_unit_setting (
    org_unit_id       UUID                     PRIMARY KEY,
    version           BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    may_raise_needs   BOOLEAN                  NOT NULL DEFAULT FALSE,
    accepts_donations BOOLEAN                  NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_supply_unit_setting_org_unit
        FOREIGN KEY (org_unit_id) REFERENCES org_unit (id) ON DELETE CASCADE
);

CREATE TABLE supply_need (
    id               UUID                     PRIMARY KEY,
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    org_unit_id      UUID                     NOT NULL,
    material_id      UUID,
    game_item_id     UUID,
    quality_tier_id  UUID,
    amount           DOUBLE PRECISION         NOT NULL,
    fulfilled_amount DOUBLE PRECISION         NOT NULL DEFAULT 0,
    purchased_amount DOUBLE PRECISION         NOT NULL DEFAULT 0,
    priority         VARCHAR(16)              NOT NULL,
    purchasable      BOOLEAN                  NOT NULL DEFAULT FALSE,
    purchase_reason  VARCHAR(32),
    max_unit_price   BIGINT,
    job_order_id     UUID,
    status           VARCHAR(16)              NOT NULL,
    created_by       UUID,
    CONSTRAINT fk_supply_need_org_unit FOREIGN KEY (org_unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_supply_need_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_supply_need_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT fk_supply_need_game_item FOREIGN KEY (game_item_id) REFERENCES game_item (id),
    CONSTRAINT fk_supply_need_quality_tier FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id),
    CONSTRAINT chk_supply_need_catalog_xor
        CHECK ((material_id IS NOT NULL) <> (game_item_id IS NOT NULL)),
    CONSTRAINT chk_supply_need_amount CHECK (amount > 0),
    CONSTRAINT chk_supply_need_purchasable
        CHECK (NOT purchasable OR (purchase_reason IS NOT NULL AND max_unit_price IS NOT NULL AND max_unit_price > 0))
);

CREATE INDEX idx_supply_need_unit_status ON supply_need (org_unit_id, status);
CREATE INDEX idx_supply_need_status ON supply_need (status);
CREATE INDEX idx_supply_need_material ON supply_need (material_id);
CREATE INDEX idx_supply_need_game_item ON supply_need (game_item_id);
CREATE INDEX idx_supply_need_quality_tier ON supply_need (quality_tier_id);
CREATE INDEX idx_supply_need_created_by ON supply_need (created_by);

CREATE TABLE supply_donation (
    id                       UUID                     PRIMARY KEY,
    version                  BIGINT                   NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    donor_user_id            UUID,
    anonymous                BOOLEAN                  NOT NULL DEFAULT FALSE,
    receiving_org_unit_id    UUID                     NOT NULL,
    material_id              UUID,
    game_item_id             UUID,
    quality                  INTEGER,
    pledged_amount           DOUBLE PRECISION         NOT NULL,
    received_amount          DOUBLE PRECISION,
    need_id                  UUID,
    source_inventory_item_id UUID,
    status                   VARCHAR(24)              NOT NULL,
    decline_reason           VARCHAR(32),
    received_by              UUID,
    received_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_supply_donation_donor FOREIGN KEY (donor_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_supply_donation_received_by FOREIGN KEY (received_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_supply_donation_org_unit FOREIGN KEY (receiving_org_unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_supply_donation_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT fk_supply_donation_game_item FOREIGN KEY (game_item_id) REFERENCES game_item (id),
    CONSTRAINT fk_supply_donation_need FOREIGN KEY (need_id) REFERENCES supply_need (id),
    CONSTRAINT chk_supply_donation_catalog_xor
        CHECK ((material_id IS NOT NULL) <> (game_item_id IS NOT NULL)),
    CONSTRAINT chk_supply_donation_pledged CHECK (pledged_amount > 0)
);

CREATE INDEX idx_supply_donation_unit_status ON supply_donation (receiving_org_unit_id, status);
CREATE INDEX idx_supply_donation_donor ON supply_donation (donor_user_id);
CREATE INDEX idx_supply_donation_need ON supply_donation (need_id);
CREATE INDEX idx_supply_donation_material ON supply_donation (material_id);
CREATE INDEX idx_supply_donation_game_item ON supply_donation (game_item_id);
CREATE INDEX idx_supply_donation_received_by ON supply_donation (received_by);

COMMENT ON TABLE supply_unit_setting IS
    'Which org units may raise supply needs and which accept material donations (REQ-SUP-002). Owned by the supply module; own optimistic-lock version.';
COMMENT ON TABLE supply_need IS
    'A raising unit''s demand for a material or game item, optionally open to purchase (REQ-SUP-003, REQ-SUP-004). Owned by the supply module.';
COMMENT ON TABLE supply_donation IS
    'A member''s pledged or received material donation to a receiving unit (REQ-SUP-007 ... REQ-SUP-012). donor_user_id is nulled on erasure; the donation stays for the books. source_inventory_item_id is a plain id reference into the Lager.';
