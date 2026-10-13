CREATE TABLE inventory_item_donation_allocation (
    id                UUID PRIMARY KEY,
    inventory_item_id UUID                     NOT NULL REFERENCES inventory_item (id) ON DELETE CASCADE,
    donation_id       UUID                     NOT NULL,
    amount            DOUBLE PRECISION         NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_inv_donation_alloc UNIQUE (inventory_item_id, donation_id),
    CONSTRAINT chk_inv_donation_alloc_amount CHECK (amount > 0)
);

CREATE INDEX idx_inv_donation_alloc_donation ON inventory_item_donation_allocation (donation_id);
CREATE INDEX idx_inv_donation_alloc_item ON inventory_item_donation_allocation (inventory_item_id);

COMMENT ON TABLE inventory_item_donation_allocation IS
    'The slice of a Lager row a member pledged as a donation (REQ-INV-057, REQ-INV-058). A third allocation kind beside the job-order and mission slices; owned by the inventory module. donation_id is a plain id reference to the supply donation, with no foreign key into another module.';
COMMENT ON COLUMN inventory_item_donation_allocation.donation_id IS
    'The pledged donation this slice is earmarked for. Released when the donation is received, declined or withdrawn.';
