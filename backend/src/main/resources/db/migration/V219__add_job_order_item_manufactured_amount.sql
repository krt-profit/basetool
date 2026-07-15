-- Add the manufactured-units counter to ordered item lines (REQ-ORDERS-025, "Herstellung").
-- Bearbeiter record how many whole units of an ordered item have been manufactured (production
-- booked, consuming the order's linked stock) independently of delivery. The booking flow reduces
-- the required materials' linked inventory by the consumed amount; this column tracks the running
-- produced count. The invariant is 0 <= delivered_amount <= manufactured_amount <= amount — a unit
-- can only be delivered once it has been manufactured.
--
-- Backfill: legacy rows are treated as "delivered counts as manufactured" (anything already handed
-- over must have been made), so manufactured_amount starts at delivered_amount for existing rows and
-- the >= delivered_amount / <= amount invariants hold immediately (delivered_amount was already
-- bounded by amount via the item-handover over-delivery guard).
ALTER TABLE job_order_item
    ADD COLUMN manufactured_amount INTEGER NOT NULL DEFAULT 0;

UPDATE job_order_item
    SET manufactured_amount = delivered_amount;

ALTER TABLE job_order_item
    ADD CONSTRAINT chk_job_order_item_manufactured
        CHECK (manufactured_amount >= 0);

ALTER TABLE job_order_item
    ADD CONSTRAINT chk_job_order_item_manufactured_ge_delivered
        CHECK (manufactured_amount >= delivered_amount);

ALTER TABLE job_order_item
    ADD CONSTRAINT chk_job_order_item_manufactured_le_amount
        CHECK (manufactured_amount <= amount);

COMMENT ON COLUMN job_order_item.manufactured_amount IS 'Whole units of the ordered item already manufactured (production booked, consuming linked stock). Invariant: 0 <= delivered_amount <= manufactured_amount <= amount (REQ-ORDERS-025).';
