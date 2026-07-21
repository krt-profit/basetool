-- Materialbörse — wanted-listings (Gesuche), REQ-MARKET-015…020, ADR-0116.
-- The request-side sibling of the offer aggregate (V210, ADR-0082): instead of releasing owned stock,
-- a member advertises what they WANT — a catalogue material (MATERIAL) or a craftable item (ITEM) — in
-- a stated minimum quality and quantity, with a free-form Markdown description. Other members signal
-- "Ich kann liefern" (material_exchange_request_interest) and the requester is notified.
--
-- Unlike an offer there is NO backing inventory_item row: the member states the material/item identity
-- and quantity directly, so none of the offer's stock-derived rules (clamp-on-read, ratchet, the
-- one-active-per-Lager-row partial-unique) apply. A member may post several requests for the same
-- material or item (no de-duplication, REQ-MARKET-015). Requests are signal-only: posting one never
-- moves stock. Supplier names are disclosed only to the request's owner; the redaction is enforced in
-- the service, never by exposing a name-carrying projection to a non-owner (REQ-MARKET-019).
--
-- Rollback: DROP TABLE material_exchange_request_interest, material_exchange_request.

CREATE TABLE material_exchange_request (
    id                    UUID PRIMARY KEY,
    -- Discriminator: MATERIAL (catalogue material + SCU/piece amount) or ITEM (blueprint product +
    -- whole-piece quantity). The @Enumerated(STRING) mapping is the source of truth — no value CHECK.
    request_kind          VARCHAR(16) NOT NULL,
    -- MATERIAL branch: the catalogue material wanted. Cascade-delete so the board never lists a
    -- request whose material no longer exists.
    requested_material_id UUID REFERENCES material(id) ON DELETE CASCADE,
    -- ITEM branch: the craftable item's normalized blueprint product key + snapshotted display name +
    -- stated whole-piece quantity.
    item_product_key      VARCHAR(255),
    item_name             VARCHAR(255),
    item_quantity         INTEGER,
    -- MATERIAL branch: the desired quantity in the material's own unit (SCU for bulk, Stück for PIECE),
    -- stored as a double for SCU fractions.
    requested_amount      DOUBLE PRECISION,
    -- Optional minimum desired quality (0-1000), allowed on EITHER kind (REQ-MARKET-015). Unlike an
    -- offer this request stores it directly, so it owns the 0-1000 bound (there is no backing Lager row
    -- guarded by chk_inventory_item_quality_by_kind to inherit it from).
    min_quality           INTEGER,
    -- The requesting player (Suchende), stamped from the acting member at posting. Cascade-delete so a
    -- departed user's ephemeral requests are withdrawn with the account.
    owner_id              UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    -- The requester's org unit at posting, for the squadron badge; nullable. Set null if the org unit
    -- is later removed — the badge simply disappears.
    owning_org_unit_id    UUID REFERENCES org_unit(id) ON DELETE SET NULL,
    -- Free-form Markdown description, max 20 000 chars, stored raw and rendered server-side by the
    -- sanitizing @markdown renderer on display.
    remark                VARCHAR(20000),
    status                VARCHAR(16) NOT NULL,
    posted_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Exactly-one-branch integrity: a MATERIAL request names a material + positive amount and no item
    -- fields; an ITEM request names a product key / display name / positive quantity and no material
    -- fields. This is the structural guarantee the entity's nullability and the service's kind branches
    -- rely on.
    CONSTRAINT ck_material_exchange_request_kind CHECK (
        (request_kind = 'MATERIAL'
             AND requested_material_id IS NOT NULL
             AND requested_amount IS NOT NULL
             AND requested_amount > 0
             AND item_product_key IS NULL
             AND item_name IS NULL
             AND item_quantity IS NULL)
        OR
        (request_kind = 'ITEM'
             AND requested_material_id IS NULL
             AND requested_amount IS NULL
             AND item_product_key IS NOT NULL
             AND item_name IS NOT NULL
             AND item_quantity IS NOT NULL
             AND item_quantity > 0)
    ),
    -- The optional min quality is allowed on either kind, so its 0-1000 range is guarded separately
    -- from the branch CHECK.
    CONSTRAINT ck_material_exchange_request_min_quality CHECK (
        min_quality IS NULL OR (min_quality BETWEEN 0 AND 1000)
    )
);

-- Board list is filtered by status = 'ACTIVE' and, for "Meine Gesuche", by owner. The item-name index
-- keeps the "Name A-Z" sort and the name filter cheap for item requests (COALESCE-d with the material
-- name in the board query).
CREATE INDEX idx_material_exchange_request_status ON material_exchange_request (status);
CREATE INDEX idx_material_exchange_request_owner ON material_exchange_request (owner_id);
CREATE INDEX idx_material_exchange_request_item_name ON material_exchange_request (item_name);

CREATE TABLE material_exchange_request_interest (
    id                 UUID PRIMARY KEY,
    request_id         UUID NOT NULL REFERENCES material_exchange_request(id) ON DELETE CASCADE,
    interested_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

-- One signal per (request, user): "Ich kann liefern" is an idempotent upsert. The leading request_id
-- column also serves the per-request supplier count/name lookups, so no extra request index is needed.
CREATE UNIQUE INDEX uq_material_exchange_request_interest
    ON material_exchange_request_interest (request_id, interested_user_id);

-- The "du dabei" batch lookup and the user-delete cascade both filter by interested_user_id.
CREATE INDEX idx_material_exchange_request_interest_user
    ON material_exchange_request_interest (interested_user_id);
