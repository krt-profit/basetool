-- UEX serves fractional cargo capacities (0.8 SCU); three decimals cover the µSCU grid.
ALTER TABLE ship_type
    ALTER COLUMN scu TYPE NUMERIC(10, 3);
