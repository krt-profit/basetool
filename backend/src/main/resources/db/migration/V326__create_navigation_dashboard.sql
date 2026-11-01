CREATE TABLE IF NOT EXISTS navigation_dashboard_layout (
    id             UUID        PRIMARY KEY,
    preferences_id UUID        NOT NULL,
    mode           VARCHAR(16) NOT NULL,
    layout_version BIGINT      NOT NULL DEFAULT 0,
    customised     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_navigation_dashboard_layout_mode UNIQUE (preferences_id, mode),
    CONSTRAINT fk_navigation_dashboard_layout_preferences
        FOREIGN KEY (preferences_id) REFERENCES navigation_preferences (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_navigation_dashboard_layout_mode CHECK (mode IN ('MEMBER', 'ADMIN'))
);

CREATE TABLE IF NOT EXISTS navigation_dashboard_widget (
    id              UUID        PRIMARY KEY,
    layout_id       UUID        NOT NULL,
    widget_type     VARCHAR(32) NOT NULL,
    column_side     VARCHAR(8)  NOT NULL,
    position        INTEGER     NOT NULL,
    options_version BIGINT      NOT NULL DEFAULT 0,
    org_unit_id     UUID,
    range_days      INTEGER,
    row_count       INTEGER,
    status_filter   VARCHAR(16),
    CONSTRAINT fk_navigation_dashboard_widget_layout
        FOREIGN KEY (layout_id) REFERENCES navigation_dashboard_layout (id) ON DELETE CASCADE,
    CONSTRAINT fk_navigation_dashboard_widget_org_unit
        FOREIGN KEY (org_unit_id) REFERENCES org_unit (id) ON DELETE SET NULL,
    CONSTRAINT ck_navigation_dashboard_widget_column CHECK (column_side IN ('WIDE', 'NARROW')),
    CONSTRAINT ck_navigation_dashboard_widget_position CHECK (position >= 0)
);

CREATE INDEX IF NOT EXISTS idx_navigation_dashboard_widget_layout
    ON navigation_dashboard_widget (layout_id, position);

CREATE INDEX IF NOT EXISTS idx_navigation_dashboard_widget_org_unit
    ON navigation_dashboard_widget (org_unit_id);
