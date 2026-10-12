CREATE TABLE member_payout_setting (
    user_id                   UUID                     PRIMARY KEY,
    version                   BIGINT                   NOT NULL DEFAULT 0,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    default_payout_preference VARCHAR(50)              NOT NULL,
    CONSTRAINT fk_member_payout_setting_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE member_blueprint_sharing (
    user_id        UUID                     PRIMARY KEY,
    version        BIGINT                   NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    share_globally BOOLEAN                  NOT NULL,
    CONSTRAINT fk_member_blueprint_sharing_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

INSERT INTO member_payout_setting (user_id, default_payout_preference)
SELECT id, default_payout_preference
FROM app_user
WHERE default_payout_preference IS NOT NULL
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO member_blueprint_sharing (user_id, share_globally)
SELECT id, TRUE
FROM app_user
WHERE share_blueprints_globally
ON CONFLICT (user_id) DO NOTHING;

COMMENT ON TABLE member_payout_setting IS
    'A member''s default payout preference, pre-filled at mission sign-up (REQ-MISSION-002, plan D-17). Absent = no explicit choice. Owned by the mission module; own optimistic-lock version.';
COMMENT ON COLUMN member_payout_setting.user_id IS
    'app_user.id of the member. FK ON DELETE CASCADE (REQ-DATA-008).';
COMMENT ON TABLE member_blueprint_sharing IS
    'A member''s opt-in to count their blueprints in every org unit''s availability views (REQ-INV-018, plan D-17). Absent = off. Owned by the blueprint module; own optimistic-lock version.';
COMMENT ON COLUMN member_blueprint_sharing.user_id IS
    'app_user.id of the member. FK ON DELETE CASCADE (REQ-DATA-008).';
