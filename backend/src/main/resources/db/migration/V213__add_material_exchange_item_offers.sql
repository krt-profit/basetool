-- Materialbörse — item offers (#1185, REQ-MARKET-012, ADR-0087).
-- Extends the material_exchange_offer aggregate (V210, ADR-0082) with a second offer kind: an ITEM
-- offer for a craftable item ("an item for which a blueprint exists"). Unlike a MATERIAL offer — a
-- thin overlay on a Lager row whose material/quality/amount are read LIVE from inventory_item — an
-- ITEM offer has NO backing stock row: it references a blueprint product by its normalized
-- product_key (the same identity personal_blueprint / default_blueprint use), snapshots the display
-- name, and the offering player STATES the quantity (there is no live source to read it from). An
-- item offer carries no quality and no location.

-- A MATERIAL offer keeps its Lager row; an ITEM offer has none, so relax the NOT NULL. Likewise
-- offered_amount (V212, partial offers) applies only to a Lager-backed offer — an item offer states
-- its quantity in item_quantity instead — so relax that NOT NULL too; the CHECK below re-scopes it.
ALTER TABLE material_exchange_offer ALTER COLUMN inventory_item_id DROP NOT NULL;
ALTER TABLE material_exchange_offer ALTER COLUMN offered_amount DROP NOT NULL;

-- Discriminator + the item-offer branch columns. offer_kind defaults to MATERIAL so every existing
-- row (all Lager-backed) is stamped correctly; the default is dropped afterwards because the entity
-- always sets the kind explicitly.
ALTER TABLE material_exchange_offer
    ADD COLUMN offer_kind       VARCHAR(16) NOT NULL DEFAULT 'MATERIAL',
    ADD COLUMN item_product_key VARCHAR(255),
    ADD COLUMN item_name        VARCHAR(255),
    ADD COLUMN item_quantity    INTEGER;

ALTER TABLE material_exchange_offer ALTER COLUMN offer_kind DROP DEFAULT;

-- Exactly-one-branch integrity: a MATERIAL offer has a Lager row and a stored offered_amount (V212)
-- and no item fields; an ITEM offer has a product key / display name / positive quantity and neither
-- a Lager row nor an offered_amount. This is the structural guarantee the entity's nullability and
-- the service's kind branches rely on.
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
             AND inventory_item_id IS NULL
             AND offered_amount IS NULL
             AND item_product_key IS NOT NULL
             AND item_name IS NOT NULL
             AND item_quantity IS NOT NULL
             AND item_quantity > 0)
    );

-- The board searches / sorts item offers by their display name (COALESCE-d with the material name);
-- an index on item_name keeps the "Material A–Z" sort and the name filter cheap. No partial-unique
-- index is added for item offers: a member may deliberately list the same item more than once
-- (REQ-MARKET-012), and the V210 uq_material_exchange_offer_active_item index only constrains
-- inventory_item_id, whose NULLs (item offers) are distinct under a Postgres partial unique index.
CREATE INDEX idx_material_exchange_offer_item_name ON material_exchange_offer (item_name);
