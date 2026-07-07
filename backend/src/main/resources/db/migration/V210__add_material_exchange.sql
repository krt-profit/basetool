-- Materialbörse — material exchange marketplace (Flotte & Logistik), REQ-MARKET-001…, ADR-0082.
-- A central, org-wide-visible trade board: a player releases a Lager row (inventory_item) for trade
-- with a free-form Markdown remark; the board shows only WHICH player offers WHICH material in WHICH
-- quality and quantity. Material / quality / amount are read LIVE from the linked inventory_item; the
-- item's location is deliberately never joined into any board query, so the Standort stays private
-- (REQ-MARKET-004).
--
-- Offers are signal-only: releasing one never moves stock. Interest registrations live in a separate
-- table (material_exchange_interest) — an independent aggregate with no mapped collection on the
-- offer — so registering or withdrawing interest never bumps the offer's @Version. Interessenten
-- names are disclosed only to the offer's owner; the redaction is enforced in the service, never by
-- exposing a name-carrying projection to a non-owner.

CREATE TABLE material_exchange_offer (
    id                 UUID PRIMARY KEY,
    -- The source Lager row; material/quality/amount are read live from it. Cascade-delete so the
    -- board never lists an offer whose underlying stock row is gone.
    inventory_item_id  UUID NOT NULL REFERENCES inventory_item(id) ON DELETE CASCADE,
    -- The offering player (Anbieter), denormalised from inventory_item.user at release. Cascade-delete
    -- so a departed user's ephemeral trade postings are withdrawn with the account.
    owner_id           UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    -- The owner's org unit at release, for the squadron badge; nullable for an ownerless-personal
    -- item. Set null if the org unit is later removed — the badge simply disappears.
    owning_org_unit_id UUID REFERENCES org_unit(id) ON DELETE SET NULL,
    -- Free-form Markdown remark ("was suchst du im Gegenzug?"), max 20 000 chars, stored raw and
    -- rendered server-side by the sanitizing @markdown renderer on display.
    remark             VARCHAR(20000),
    status             VARCHAR(16) NOT NULL,
    released_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

-- One ACTIVE offer per Lager row: re-releasing an item re-activates its row instead of inserting a
-- duplicate (the upsert invariant). A partial unique index leaves DEACTIVATED history unconstrained.
CREATE UNIQUE INDEX uq_material_exchange_offer_active_item
    ON material_exchange_offer (inventory_item_id)
    WHERE status = 'ACTIVE';

-- Board list is filtered by status = 'ACTIVE' and, for "Meine Angebote", by owner.
CREATE INDEX idx_material_exchange_offer_status ON material_exchange_offer (status);
CREATE INDEX idx_material_exchange_offer_owner ON material_exchange_offer (owner_id);

CREATE TABLE material_exchange_interest (
    id                 UUID PRIMARY KEY,
    offer_id           UUID NOT NULL REFERENCES material_exchange_offer(id) ON DELETE CASCADE,
    interested_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

-- One registration per (offer, user): "Interesse anmelden" is an idempotent upsert. The leading
-- offer_id column also serves the per-offer interessenten count/name lookups, so no extra offer index
-- is needed.
CREATE UNIQUE INDEX uq_material_exchange_interest
    ON material_exchange_interest (offer_id, interested_user_id);

-- The "du dabei" batch lookup and the user-delete cascade both filter by interested_user_id.
CREATE INDEX idx_material_exchange_interest_user
    ON material_exchange_interest (interested_user_id);
