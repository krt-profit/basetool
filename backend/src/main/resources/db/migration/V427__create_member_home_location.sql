CREATE TABLE member_home_location (
    user_id     UUID                     PRIMARY KEY,
    version     BIGINT                   NOT NULL DEFAULT 1,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    location_id UUID,
    CONSTRAINT fk_member_home_location_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_home_location_location
        FOREIGN KEY (location_id) REFERENCES location (id) ON DELETE SET NULL,
    CONSTRAINT ck_member_home_location_version CHECK (version >= 1)
);

CREATE INDEX idx_member_home_location_location ON member_home_location (location_id);

COMMENT ON TABLE member_home_location IS
    'A member''s profile setting "my home location", the default location of the stock booking, the refinery store and a new ship (REQ-PATCH-030). Absent = no choice. Owned by the identity module; own optimistic-lock version, starting at 1 so version 0 always means "no row".';
COMMENT ON COLUMN member_home_location.user_id IS
    'app_user.id of the member. FK ON DELETE CASCADE (REQ-DATA-008).';
COMMENT ON COLUMN member_home_location.location_id IS
    'The chosen curated home location; set by the member on the profile or by a patch-change relocation (REQ-PATCH-031). NULL once the location is deleted.';
