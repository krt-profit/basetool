ALTER TABLE patch_change
    ADD COLUMN phase_ended_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN phase_ended_by UUID;

COMMENT ON COLUMN patch_change.phase_ended_at IS
    'When an admin ended the home-location phase early; NULL while it runs or when none was opened (REQ-PATCH-024).';
COMMENT ON COLUMN patch_change.phase_ended_by IS
    'Id of the admin who ended the home-location phase, a pseudonymous record of an act on every org unit; no foreign key, it outlives the account.';

CREATE TABLE patch_change_relocation (
    patch_change_id  UUID                     NOT NULL REFERENCES patch_change (id) ON DELETE CASCADE,
    user_id          UUID                     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    home_location_id UUID                     REFERENCES location (id) ON DELETE SET NULL,
    chosen_by        VARCHAR(8)               NOT NULL,
    relocated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    rows_moved       INTEGER                  NOT NULL CHECK (rows_moved >= 0),
    ships_moved      INTEGER                  NOT NULL CHECK (ships_moved >= 0),
    PRIMARY KEY (patch_change_id, user_id),
    CONSTRAINT ck_patch_change_relocation_chosen_by CHECK (chosen_by IN ('SELF', 'ADMIN', 'CLIENT'))
);

CREATE INDEX idx_patch_change_relocation_user ON patch_change_relocation (user_id);
CREATE INDEX idx_patch_change_relocation_home_location ON patch_change_relocation (home_location_id);

COMMENT ON TABLE patch_change_relocation IS
    'One member''s home-location choice in one home-location phase and what its relocation moved, at most once per member and phase (REQ-PATCH-021, ADR-0383).';
COMMENT ON COLUMN patch_change_relocation.chosen_by IS
    'Who made the choice: the member on the web (SELF), an admin on the member''s behalf (ADMIN) or the member''s exchange client (CLIENT).';
