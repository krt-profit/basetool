CREATE TABLE member_sale_value_setting (
    user_id          UUID                     PRIMARY KEY,
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sale_price_basis VARCHAR(20)              NOT NULL,
    CONSTRAINT fk_member_sale_value_setting_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_member_sale_value_setting_basis
        CHECK (sale_price_basis IN ('AVERAGE', 'HIGHEST', 'LOWEST'))
);

COMMENT ON TABLE member_sale_value_setting IS
    'The terminal price a member''s "Mein Lager" values the own materials at (REQ-INV-072). Absent = the average price. Owned by the inventory module; own optimistic-lock version.';
COMMENT ON COLUMN member_sale_value_setting.user_id IS
    'app_user.id of the member. FK ON DELETE CASCADE (REQ-DATA-008).';
COMMENT ON COLUMN member_sale_value_setting.sale_price_basis IS
    'AVERAGE, HIGHEST or LOWEST positive sale price across the non-hidden terminals.';
