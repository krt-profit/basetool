ALTER TABLE ship ADD COLUMN production_status VARCHAR(16);
ALTER TABLE ship ADD COLUMN pledge_id VARCHAR(32);
ALTER TABLE ship ADD COLUMN pledge_date DATE;
ALTER TABLE ship ADD COLUMN pledge_cost NUMERIC(12, 2);
ALTER TABLE ship ADD COLUMN pledge_cost_currency VARCHAR(3);
ALTER TABLE ship ADD COLUMN meltable BOOLEAN;
ALTER TABLE ship ADD COLUMN giftable BOOLEAN;

ALTER TABLE ship ADD CONSTRAINT ck_ship_production_status
    CHECK (production_status IS NULL OR production_status IN ('FLIGHT_READY', 'IN_CONCEPT',
        'IN_PRODUCTION'));

ALTER TABLE ship ADD CONSTRAINT ck_ship_pledge_cost_non_negative
    CHECK (pledge_cost IS NULL OR pledge_cost >= 0);

CREATE INDEX idx_ship_owner_pledge ON ship (owner_id, pledge_id) WHERE pledge_id IS NOT NULL;
