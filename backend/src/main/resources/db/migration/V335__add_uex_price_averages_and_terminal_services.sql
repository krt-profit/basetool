-- UEX commodities_prices_all averages and accepted container sizes (REQ-DATA-030).
ALTER TABLE material_price
    ADD COLUMN price_buy_avg      NUMERIC,
    ADD COLUMN price_sell_avg     NUMERIC,
    ADD COLUMN scu_buy_avg        INTEGER,
    ADD COLUMN scu_sell_avg       INTEGER,
    ADD COLUMN scu_sell_stock_avg INTEGER,
    ADD COLUMN container_sizes    INTEGER[];

-- UEX terminals: largest container, services on site and the game version of the data.
ALTER TABLE terminal
    ADD COLUMN max_container_size INTEGER,
    ADD COLUMN is_refinery        BOOLEAN,
    ADD COLUMN is_refuel          BOOLEAN,
    ADD COLUMN is_repair          BOOLEAN,
    ADD COLUMN game_version       VARCHAR(32);
