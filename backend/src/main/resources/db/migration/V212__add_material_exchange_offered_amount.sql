-- Materialbörse — partial offers (REQ-MARKET-002, ADR-0086). A member may now offer only a PART of
-- a Lager row instead of the whole stock: the offered quantity becomes a stored, owner-chosen value
-- on the offer instead of being read live from inventory_item.amount. Quality is still read live.
--
-- offered_amount is the SCU quantity the anbieter releases to the board; it is validated in the
-- service to be > 0 and <= the item's current amount at every release/edit. Backfill every existing
-- offer with the linked item's current amount so pre-migration offers keep meaning "the whole row"
-- (the previous live-read behaviour), then enforce NOT NULL.

ALTER TABLE material_exchange_offer
    ADD COLUMN offered_amount DOUBLE PRECISION;

UPDATE material_exchange_offer o
    SET offered_amount = i.amount
    FROM inventory_item i
    WHERE o.inventory_item_id = i.id;

ALTER TABLE material_exchange_offer
    ALTER COLUMN offered_amount SET NOT NULL;
