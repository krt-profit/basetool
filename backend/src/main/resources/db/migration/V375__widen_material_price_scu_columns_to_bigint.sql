-- UEX SCU quantities and their averages exceed the INTEGER range (REQ-DATA-030).
ALTER TABLE material_price
    ALTER COLUMN scu_buy            TYPE BIGINT,
    ALTER COLUMN scu_sell           TYPE BIGINT,
    ALTER COLUMN scu_sell_stock     TYPE BIGINT,
    ALTER COLUMN scu_buy_avg        TYPE BIGINT,
    ALTER COLUMN scu_sell_avg       TYPE BIGINT,
    ALTER COLUMN scu_sell_stock_avg TYPE BIGINT;
