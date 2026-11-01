CREATE TABLE IF NOT EXISTS navigation_preferences (
    user_id            UUID PRIMARY KEY,
    favourites_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_navigation_preferences_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS navigation_favourite (
    preferences_id UUID        NOT NULL,
    order_index    INTEGER     NOT NULL,
    entry_key      VARCHAR(64) NOT NULL,
    CONSTRAINT pk_navigation_favourite PRIMARY KEY (preferences_id, order_index),
    CONSTRAINT uq_navigation_favourite_key UNIQUE (preferences_id, entry_key)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_navigation_favourite_preferences
        FOREIGN KEY (preferences_id) REFERENCES navigation_preferences (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_navigation_favourite_order CHECK (order_index >= 0)
);
