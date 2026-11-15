CREATE TABLE patch_change (
    id                     UUID PRIMARY KEY,
    version                BIGINT                   NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE,
    updated_at             TIMESTAMP WITH TIME ZONE,
    label                  VARCHAR(32)              NOT NULL,
    kind                   VARCHAR(16)              NOT NULL,
    live_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    triggered_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    triggered_by           UUID,
    cancel_refinery_orders BOOLEAN                  NOT NULL,
    currency_wipe          BOOLEAN                  NOT NULL,
    home_location_prompt   BOOLEAN                  NOT NULL,
    CONSTRAINT uk_patch_change_label UNIQUE (label),
    CONSTRAINT ck_patch_change_kind CHECK (kind IN ('REBUILD', 'FULL_WIPE')),
    CONSTRAINT ck_patch_change_label CHECK (label ~ '^[0-9]{1,9}(\.[0-9]{1,9}){1,2}$'),
    CONSTRAINT ck_patch_change_live_at CHECK (live_at <= triggered_at)
);

CREATE INDEX idx_patch_change_live_at ON patch_change (live_at DESC);

COMMENT ON TABLE patch_change IS
    'One admin-recorded Star Citizen patch change with its kind, live time and options (REQ-PATCH-001, ADR-0380).';
COMMENT ON COLUMN patch_change.live_at IS
    'When the patch went live, the cutoff of every effect; fixed once triggered (REQ-PATCH-003, ADR-0381).';
COMMENT ON COLUMN patch_change.triggered_by IS
    'Id of the admin who triggered it, a pseudonymous record of an act on every org unit; no foreign key, it outlives the account.';

CREATE TABLE patch_change_wipe_group (
    patch_change_id UUID        NOT NULL REFERENCES patch_change (id) ON DELETE CASCADE,
    wipe_group      VARCHAR(32) NOT NULL,
    PRIMARY KEY (patch_change_id, wipe_group),
    CONSTRAINT ck_patch_change_wipe_group CHECK (wipe_group IN (
        'CONSUMABLES', 'FPS_EQUIPMENT', 'SHIP_COMPONENTS', 'MATERIALS_RAW', 'MATERIALS_REFINED',
        'MATERIALS_NO_REFINE', 'OTHER_ITEMS', 'IN_GAME_SHIPS'))
);

COMMENT ON TABLE patch_change_wipe_group IS
    'The wipe groups a patch change selected as an option (REQ-PATCH-002).';

CREATE TABLE patch_change_effect_count (
    patch_change_id UUID        NOT NULL REFERENCES patch_change (id) ON DELETE CASCADE,
    count_kind      VARCHAR(48) NOT NULL,
    count_value     BIGINT      NOT NULL CHECK (count_value >= 0),
    PRIMARY KEY (patch_change_id, count_kind)
);

COMMENT ON TABLE patch_change_effect_count IS
    'What the effects of a patch change touched at its trigger, one count per kind (REQ-PATCH-005).';

ALTER TABLE refinery_order
    ADD COLUMN canceled_by_patch_change UUID,
    ADD CONSTRAINT ck_refinery_order_canceled_by_patch_change CHECK (
        canceled_by_patch_change IS NULL OR status = 'CANCELED');

COMMENT ON COLUMN refinery_order.canceled_by_patch_change IS
    'The patch change that cancelled the order as its reason; NULL for every other order (REQ-PATCH-006).';
