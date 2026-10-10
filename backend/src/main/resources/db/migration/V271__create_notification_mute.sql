CREATE TABLE notification_mute (
    id                UUID PRIMARY KEY,
    version           BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    user_id           UUID                     NOT NULL,
    notification_type VARCHAR(64)              NOT NULL,
    CONSTRAINT fk_notification_mute_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT uq_notification_mute_user_type UNIQUE (user_id, notification_type)
);

COMMENT ON TABLE notification_mute IS
    'A member''s choice not to receive one notification type (issue #2414, REQ-NOTIF-027). Present = muted; a muted type is neither stored in the inbox nor pushed.';
COMMENT ON COLUMN notification_mute.user_id IS
    'app_user.id of the member who muted the type. FK ON DELETE CASCADE (REQ-DATA-008).';
COMMENT ON COLUMN notification_mute.notification_type IS
    'NotificationType name; @Enumerated(STRING) is the source of truth, no CHECK (growing set).';
