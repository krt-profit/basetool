-- Materialbörse — stock-backed item offers (design DESIGN_ITEM_INVENTORY.md §8, REQ-MARKET-014,
-- ADR-0108). Once game items are trackable as Lager stock rows (V220, REQ-INV-029), an ITEM offer
-- may be released FROM an item stock row exactly like a MATERIAL offer is released from a material
-- stock row — instead of only as a free-stated, craft-on-demand listing (V213, REQ-MARKET-012).
--
-- The ONLY change is to relax the V213 exactly-one-branch CHECK so an ITEM offer MAY carry an
-- inventory_item_id (a game-item-kind Lager row, its physical stock), while the free-stated ITEM
-- offer (inventory_item_id NULL, craft-on-demand) stays valid. The MATERIAL branch is unchanged.
-- offered_amount stays exclusive to MATERIAL offers: a stock-backed ITEM offer states its whole-unit
-- quantity in item_quantity (validated <= the row's stock at release/edit and ratcheted down on
-- every stock decrement, REQ-MARKET-013/014), never in offered_amount.
--
-- Two-phase safety: this migration only LOOSENS a CHECK, so every existing row still satisfies it
-- (relaxing a constraint can never reject a previously valid row) and old code that creates only
-- free-stated item offers (inventory_item_id NULL) keeps passing; new code that creates stock-backed
-- item offers (inventory_item_id NOT NULL) passes the relaxed CHECK. No data backfill is needed.
--
-- Exact CHECK rewrite (was V213's ck_material_exchange_offer_kind):
--   BEFORE (V213): the ITEM branch additionally required  inventory_item_id IS NULL  AND
--                  offered_amount IS NULL.
--   AFTER  (V221): the ITEM branch drops the  inventory_item_id IS NULL  half only; it still
--                  requires  offered_amount IS NULL  and the item_product_key / item_name /
--                  positive item_quantity trio. The MATERIAL branch is byte-for-byte unchanged.
--
-- The V210 partial-unique index  uq_material_exchange_offer_active_item (inventory_item_id)
-- WHERE status = 'ACTIVE'  now naturally governs stock-backed item offers too: one ACTIVE offer per
-- Lager row (consistent — the row IS the physical stock, so re-releasing re-activates it). Free-
-- stated item offers keep their NULL inventory_item_id, which Postgres treats as distinct under a
-- partial unique index, so their deliberate multi-listing freedom is unchanged (REQ-MARKET-012).

ALTER TABLE material_exchange_offer DROP CONSTRAINT ck_material_exchange_offer_kind;

ALTER TABLE material_exchange_offer
    ADD CONSTRAINT ck_material_exchange_offer_kind CHECK (
        (offer_kind = 'MATERIAL'
             AND inventory_item_id IS NOT NULL
             AND offered_amount IS NOT NULL
             AND item_product_key IS NULL
             AND item_name IS NULL
             AND item_quantity IS NULL)
        OR
        (offer_kind = 'ITEM'
             AND offered_amount IS NULL
             AND item_product_key IS NOT NULL
             AND item_name IS NOT NULL
             AND item_quantity IS NOT NULL
             AND item_quantity > 0)
    );
