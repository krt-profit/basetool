CREATE TABLE purchasing_setting (
    id                    UUID                     PRIMARY KEY,
    version               BIGINT                   NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    governing_area_id     UUID,
    funding_account_id    UUID,
    default_monthly_limit BIGINT                   NOT NULL DEFAULT 0,
    budget_cap            BIGINT,
    budget_period         VARCHAR(16)              NOT NULL DEFAULT 'MONTH',
    settlement_due_days   INTEGER                  NOT NULL DEFAULT 7,
    paused                BOOLEAN                  NOT NULL DEFAULT FALSE,
    pause_reason          VARCHAR(32),
    paused_by             UUID,
    paused_at             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_purchasing_setting_area FOREIGN KEY (governing_area_id) REFERENCES org_unit (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchasing_setting_paused_by FOREIGN KEY (paused_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_purchasing_setting_limit CHECK (default_monthly_limit >= 0),
    CONSTRAINT chk_purchasing_setting_cap CHECK (budget_cap IS NULL OR budget_cap >= 0),
    CONSTRAINT chk_purchasing_setting_due CHECK (settlement_due_days BETWEEN 1 AND 90)
);

CREATE INDEX idx_purchasing_setting_area ON purchasing_setting (governing_area_id);
CREATE INDEX idx_purchasing_setting_paused_by ON purchasing_setting (paused_by);

INSERT INTO purchasing_setting (id) VALUES ('00000000-0000-0000-0000-0000000a0001');

CREATE TABLE purchasing_setting_allowed_tier (
    setting_id      UUID NOT NULL,
    quality_tier_id UUID NOT NULL,
    PRIMARY KEY (setting_id, quality_tier_id),
    CONSTRAINT fk_purchasing_allowed_tier_setting FOREIGN KEY (setting_id) REFERENCES purchasing_setting (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchasing_allowed_tier_tier FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id) ON DELETE CASCADE
);

CREATE INDEX idx_purchasing_allowed_tier_tier ON purchasing_setting_allowed_tier (quality_tier_id);

CREATE TABLE purchasing_unit_pause (
    id          UUID                     PRIMARY KEY,
    version     BIGINT                   NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    org_unit_id UUID                     NOT NULL,
    reason      VARCHAR(32)              NOT NULL,
    paused_by   UUID,
    paused_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_purchasing_unit_pause_unit UNIQUE (org_unit_id),
    CONSTRAINT fk_purchasing_unit_pause_unit FOREIGN KEY (org_unit_id) REFERENCES org_unit (id) ON DELETE CASCADE,
    CONSTRAINT fk_purchasing_unit_pause_by FOREIGN KEY (paused_by) REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_purchasing_unit_pause_by ON purchasing_unit_pause (paused_by);

CREATE TABLE purchasing_risk_class (
    id                     UUID                     PRIMARY KEY,
    version                BIGINT                   NOT NULL DEFAULT 0,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    code                   VARCHAR(8)               NOT NULL,
    max_total              BIGINT                   NOT NULL,
    max_advance_percent    INTEGER                  NOT NULL,
    requires_known_partner BOOLEAN                  NOT NULL,
    escort_suggested       BOOLEAN                  NOT NULL,
    CONSTRAINT uq_purchasing_risk_class_code UNIQUE (code),
    CONSTRAINT chk_purchasing_risk_class_code CHECK (code IN ('A', 'B', 'C')),
    CONSTRAINT chk_purchasing_risk_class_total CHECK (max_total > 0),
    CONSTRAINT chk_purchasing_risk_class_advance CHECK (max_advance_percent BETWEEN 0 AND 100)
);

INSERT INTO purchasing_risk_class (id, code, max_total, max_advance_percent, requires_known_partner, escort_suggested)
VALUES ('00000000-0000-0000-0000-0000000b0001', 'A', 100000, 0, FALSE, FALSE),
       ('00000000-0000-0000-0000-0000000b0002', 'B', 500000, 50, TRUE, FALSE),
       ('00000000-0000-0000-0000-0000000b0003', 'C', 5000000, 0, TRUE, TRUE);

CREATE TABLE purchasing_delegate (
    id           UUID                     PRIMARY KEY,
    version      BIGINT                   NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    user_id      UUID,
    appointed_by UUID,
    appointed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    valid_until  TIMESTAMP WITH TIME ZONE,
    monthly_limit BIGINT,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    revoked_by   UUID,
    CONSTRAINT fk_purchasing_delegate_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchasing_delegate_appointed_by FOREIGN KEY (appointed_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchasing_delegate_revoked_by FOREIGN KEY (revoked_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_purchasing_delegate_limit CHECK (monthly_limit IS NULL OR monthly_limit >= 0)
);

CREATE UNIQUE INDEX uq_purchasing_delegate_active_user ON purchasing_delegate (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_purchasing_delegate_user ON purchasing_delegate (user_id);
CREATE INDEX idx_purchasing_delegate_appointed_by ON purchasing_delegate (appointed_by);
CREATE INDEX idx_purchasing_delegate_revoked_by ON purchasing_delegate (revoked_by);

CREATE TABLE purchasing_partner (
    id              UUID                     PRIMARY KEY,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    kind            VARCHAR(16)              NOT NULL,
    name            VARCHAR(120)             NOT NULL,
    org_tag         VARCHAR(16),
    channel         VARCHAR(16)              NOT NULL,
    conditions_note VARCHAR(500),
    reputation_note VARCHAR(500),
    contact_via     VARCHAR(16)              NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    erased          BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_by      UUID,
    CONSTRAINT fk_purchasing_partner_created_by FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_purchasing_partner_kind CHECK (kind IN ('ORG', 'INDIVIDUAL')),
    CONSTRAINT chk_purchasing_partner_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX idx_purchasing_partner_created_by ON purchasing_partner (created_by);
CREATE INDEX idx_purchasing_partner_kind_name ON purchasing_partner (kind, lower(name));

CREATE TABLE purchasing_deal (
    id                 UUID                     PRIMARY KEY,
    version            BIGINT                   NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deal_no            BIGINT GENERATED ALWAYS AS IDENTITY,
    need_id            UUID                     NOT NULL,
    raising_org_unit_id UUID                    NOT NULL,
    partner_id         UUID                     NOT NULL,
    delegate_user_id   UUID,
    material_id        UUID,
    game_item_id       UUID,
    quality_tier_id    UUID,
    amount             DOUBLE PRECISION         NOT NULL,
    unit_price         BIGINT                   NOT NULL,
    total              BIGINT                   NOT NULL,
    payment_model      VARCHAR(16)              NOT NULL,
    risk_class         VARCHAR(8)               NOT NULL,
    escort_wish        BOOLEAN                  NOT NULL DEFAULT FALSE,
    mission_id         UUID,
    status             VARCHAR(16)              NOT NULL,
    delivery_closed    BOOLEAN                  NOT NULL DEFAULT FALSE,
    settlement_due_at  TIMESTAMP WITH TIME ZONE,
    overdue_notified_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_purchasing_deal_no UNIQUE (deal_no),
    CONSTRAINT fk_purchasing_deal_unit FOREIGN KEY (raising_org_unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_purchasing_deal_partner FOREIGN KEY (partner_id) REFERENCES purchasing_partner (id),
    CONSTRAINT fk_purchasing_deal_delegate FOREIGN KEY (delegate_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchasing_deal_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT fk_purchasing_deal_game_item FOREIGN KEY (game_item_id) REFERENCES game_item (id),
    CONSTRAINT fk_purchasing_deal_tier FOREIGN KEY (quality_tier_id) REFERENCES quality_tier (id),
    CONSTRAINT chk_purchasing_deal_catalog_xor CHECK ((material_id IS NOT NULL) <> (game_item_id IS NOT NULL)),
    CONSTRAINT chk_purchasing_deal_amount CHECK (amount > 0),
    CONSTRAINT chk_purchasing_deal_price CHECK (unit_price > 0 AND total > 0),
    CONSTRAINT chk_purchasing_deal_status CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'FUNDED', 'IN_DELIVERY', 'DELIVERED', 'SETTLED', 'CANCELLED', 'FAILED'))
);

CREATE INDEX idx_purchasing_deal_need ON purchasing_deal (need_id);
CREATE INDEX idx_purchasing_deal_unit ON purchasing_deal (raising_org_unit_id);
CREATE INDEX idx_purchasing_deal_partner ON purchasing_deal (partner_id);
CREATE INDEX idx_purchasing_deal_delegate ON purchasing_deal (delegate_user_id);
CREATE INDEX idx_purchasing_deal_material ON purchasing_deal (material_id);
CREATE INDEX idx_purchasing_deal_game_item ON purchasing_deal (game_item_id);
CREATE INDEX idx_purchasing_deal_tier ON purchasing_deal (quality_tier_id);
CREATE INDEX idx_purchasing_deal_status ON purchasing_deal (status);
CREATE INDEX idx_purchasing_deal_settlement_due ON purchasing_deal (settlement_due_at) WHERE settlement_due_at IS NOT NULL;

CREATE TABLE purchasing_deal_tranche (
    id              UUID                     PRIMARY KEY,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deal_id         UUID                     NOT NULL,
    kind            VARCHAR(16)              NOT NULL,
    amount          BIGINT                   NOT NULL,
    bank_request_id UUID,
    transaction_id  UUID,
    state           VARCHAR(16)              NOT NULL,
    decided_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_purchasing_tranche_deal FOREIGN KEY (deal_id) REFERENCES purchasing_deal (id),
    CONSTRAINT chk_purchasing_tranche_amount CHECK (amount > 0),
    CONSTRAINT chk_purchasing_tranche_state CHECK (state IN ('REQUESTED', 'CONFIRMED', 'REFUSED', 'CANCELLED'))
);

CREATE INDEX idx_purchasing_tranche_deal ON purchasing_deal_tranche (deal_id);
CREATE INDEX idx_purchasing_tranche_request ON purchasing_deal_tranche (bank_request_id);
CREATE INDEX idx_purchasing_tranche_created ON purchasing_deal_tranche (created_at);

CREATE TABLE purchasing_deal_delivery (
    id               UUID                     PRIMARY KEY,
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deal_id          UUID                     NOT NULL,
    amount           DOUBLE PRECISION         NOT NULL,
    location_id      UUID,
    recorded_by      UUID,
    status           VARCHAR(24)              NOT NULL,
    accepted_amount  DOUBLE PRECISION,
    rejected_amount  DOUBLE PRECISION,
    reject_reason    VARCHAR(32),
    measured_quality INTEGER,
    accepted_by      UUID,
    accepted_at      TIMESTAMP WITH TIME ZONE,
    booked_row_id    UUID,
    CONSTRAINT fk_purchasing_delivery_deal FOREIGN KEY (deal_id) REFERENCES purchasing_deal (id),
    CONSTRAINT fk_purchasing_delivery_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_purchasing_delivery_recorded_by FOREIGN KEY (recorded_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_purchasing_delivery_accepted_by FOREIGN KEY (accepted_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_purchasing_delivery_amount CHECK (amount > 0),
    CONSTRAINT chk_purchasing_delivery_quality CHECK (measured_quality IS NULL OR measured_quality BETWEEN 0 AND 1000),
    CONSTRAINT chk_purchasing_delivery_status CHECK (status IN
        ('RECORDED', 'ACCEPTED', 'PARTIALLY_ACCEPTED', 'REJECTED'))
);

CREATE INDEX idx_purchasing_delivery_deal ON purchasing_deal_delivery (deal_id);
CREATE INDEX idx_purchasing_delivery_location ON purchasing_deal_delivery (location_id);
CREATE INDEX idx_purchasing_delivery_recorded_by ON purchasing_deal_delivery (recorded_by);
CREATE INDEX idx_purchasing_delivery_accepted_by ON purchasing_deal_delivery (accepted_by);

CREATE TABLE purchasing_deal_settlement (
    id                   UUID                     PRIMARY KEY,
    version              BIGINT                   NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deal_id              UUID                     NOT NULL,
    money_received       BIGINT                   NOT NULL,
    money_paid           BIGINT                   NOT NULL,
    remainder            BIGINT                   NOT NULL,
    loss_amount          BIGINT                   NOT NULL DEFAULT 0,
    loss_reason          VARCHAR(16),
    remainder_request_id UUID,
    recorded_by          UUID,
    settled_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_purchasing_settlement_deal UNIQUE (deal_id),
    CONSTRAINT fk_purchasing_settlement_deal FOREIGN KEY (deal_id) REFERENCES purchasing_deal (id),
    CONSTRAINT fk_purchasing_settlement_recorded_by FOREIGN KEY (recorded_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_purchasing_settlement_amounts CHECK
        (money_received >= 0 AND money_paid >= 0 AND remainder >= 0 AND loss_amount >= 0
         AND money_paid + remainder = money_received AND loss_amount <= money_paid),
    CONSTRAINT chk_purchasing_settlement_loss CHECK (loss_amount = 0 OR loss_reason IS NOT NULL)
);

CREATE INDEX idx_purchasing_settlement_recorded_by ON purchasing_deal_settlement (recorded_by);
CREATE INDEX idx_purchasing_settlement_request ON purchasing_deal_settlement (remainder_request_id);
