ALTER TABLE refinery_order
    ADD COLUMN game_data_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN finances_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN refinery_order.game_data_version IS
    'Optimistic-lock counter of the game-data section: location, method, timer, status, goods, pickup and store markers (REQ-REFINERY-029).';
COMMENT ON COLUMN refinery_order.finances_version IS
    'Optimistic-lock counter of the finances section: expenses, other expenses, ore sales (REQ-REFINERY-029).';
