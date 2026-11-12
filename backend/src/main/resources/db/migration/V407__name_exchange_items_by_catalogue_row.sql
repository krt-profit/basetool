-- An ITEM offer or request names its item by exactly one identity: the blueprint product key of an
-- item an active blueprint produces, or the catalogue row of an item no blueprint produces
-- (REQ-MARKET-012, REQ-MARKET-014, REQ-MARKET-015, ADR-0372). Only the ITEM branch widens, so rows
-- an older application writes keep satisfying the checks.
ALTER TABLE material_exchange_offer ADD COLUMN item_game_item_id UUID REFERENCES game_item (id);

CREATE INDEX idx_material_exchange_offer_item_game_item
    ON material_exchange_offer (item_game_item_id);

ALTER TABLE material_exchange_offer DROP CONSTRAINT ck_material_exchange_offer_kind;

ALTER TABLE material_exchange_offer
    ADD CONSTRAINT ck_material_exchange_offer_kind CHECK (
        (offer_kind = 'MATERIAL'
             AND inventory_item_id IS NOT NULL
             AND offered_amount IS NOT NULL
             AND item_product_key IS NULL
             AND item_game_item_id IS NULL
             AND item_name IS NULL
             AND item_quantity IS NULL)
        OR
        (offer_kind = 'ITEM'
             AND offered_amount IS NULL
             AND (item_product_key IS NULL) <> (item_game_item_id IS NULL)
             AND item_name IS NOT NULL
             AND item_quantity IS NOT NULL
             AND item_quantity > 0)
    );

ALTER TABLE material_exchange_request ADD COLUMN item_game_item_id UUID REFERENCES game_item (id);

CREATE INDEX idx_material_exchange_request_item_game_item
    ON material_exchange_request (item_game_item_id);

ALTER TABLE material_exchange_request DROP CONSTRAINT ck_material_exchange_request_kind;

ALTER TABLE material_exchange_request
    ADD CONSTRAINT ck_material_exchange_request_kind CHECK (
        (request_kind = 'MATERIAL'
             AND requested_material_id IS NOT NULL
             AND requested_amount IS NOT NULL
             AND requested_amount > 0
             AND item_product_key IS NULL
             AND item_game_item_id IS NULL
             AND item_name IS NULL
             AND item_quantity IS NULL)
        OR
        (request_kind = 'ITEM'
             AND requested_material_id IS NULL
             AND requested_amount IS NULL
             AND (item_product_key IS NULL) <> (item_game_item_id IS NULL)
             AND item_name IS NOT NULL
             AND item_quantity IS NOT NULL
             AND item_quantity > 0)
    );
