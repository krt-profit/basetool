CREATE TABLE exchange_name_withholding (
    user_id       UUID                     PRIMARY KEY,
    version       BIGINT                   NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    withhold_name BOOLEAN                  NOT NULL,
    CONSTRAINT fk_exchange_name_withholding_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

COMMENT ON TABLE exchange_name_withholding IS
    'A member''s choice to keep their name away from connected applications (REQ-XCH-044, ADR-0257). Absent = the name is shared. Owned by the exchange module; own optimistic-lock version.';
COMMENT ON COLUMN exchange_name_withholding.user_id IS
    'app_user.id of the member. FK ON DELETE CASCADE (REQ-DATA-008).';
