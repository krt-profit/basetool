CREATE TABLE member_active_org_unit (
    user_id     UUID                     PRIMARY KEY,
    version     BIGINT                   NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    org_unit_id UUID,
    CONSTRAINT fk_member_active_org_unit_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_active_org_unit_org_unit
        FOREIGN KEY (org_unit_id) REFERENCES org_unit (id) ON DELETE SET NULL
);

CREATE INDEX ix_member_active_org_unit_org_unit ON member_active_org_unit (org_unit_id);

COMMENT ON TABLE member_active_org_unit IS
    'A member''s last choice in the active org-unit switcher, restored at the next login (REQ-ORG-031). Absent or org_unit_id NULL = all of the member''s org units. Owned by the scope module; own optimistic-lock version.';
COMMENT ON COLUMN member_active_org_unit.user_id IS
    'app_user.id of the member. FK ON DELETE CASCADE (REQ-DATA-008).';
COMMENT ON COLUMN member_active_org_unit.org_unit_id IS
    'The chosen org unit, or NULL for all of the member''s org units. A choice the member may no longer select is ignored on read.';
