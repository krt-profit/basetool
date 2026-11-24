CREATE TABLE exchange_job_order_fingerprint (
    job_order_id    UUID PRIMARY KEY REFERENCES job_order (id) ON DELETE CASCADE,
    fingerprint     BYTEA NOT NULL,
    changed_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    settlement_open BOOLEAN NOT NULL,
    CONSTRAINT chk_exchange_job_order_fingerprint_length CHECK (octet_length(fingerprint) = 32)
);

COMMENT ON TABLE exchange_job_order_fingerprint IS
    'The exchange fingerprint of each job order: the hash of its member-independent exchange read, written by the fingerprint worker only when it changed (REQ-OXCH-003, ADR-0406).';

COMMENT ON COLUMN exchange_job_order_fingerprint.fingerprint IS
    'The SHA-256 of the canonical encoding of the order''s member-independent exchange read.';

COMMENT ON COLUMN exchange_job_order_fingerprint.changed_at IS
    'When the worker last found the fingerprint changed; the read''s updatedAt, the last change of a carried field.';

COMMENT ON COLUMN exchange_job_order_fingerprint.settlement_open IS
    'The read''s settlementOpen as of the fingerprint: a terminal aUEC order still holding purpose-bound funds or an overpayment; always FALSE on MATERIAL and ITEM orders.';

CREATE TABLE exchange_job_order_dirty (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_order_id UUID NOT NULL REFERENCES job_order (id) ON DELETE CASCADE,
    tx           BIGINT NOT NULL,
    marked_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_exchange_job_order_dirty_order_tx UNIQUE (job_order_id, tx)
);

COMMENT ON TABLE exchange_job_order_dirty IS
    'Dirty marks: a job order whose fingerprint the worker must recompute, one mark per marking transaction. A later mark never replaces an earlier one, so the oldest mark of an order keeps its age until the worker clears the marks it read (REQ-OXCH-003, ADR-0406).';

COMMENT ON COLUMN exchange_job_order_dirty.tx IS
    'The marking transaction''s id; the worker clears exactly the marks it read, so a mark committed after its read survives.';

CREATE FUNCTION exchange_mark_job_order(p_order UUID) RETURNS VOID AS $$
    INSERT INTO exchange_job_order_dirty (job_order_id, tx)
    SELECT o.id, (pg_current_xact_id()::text)::bigint
      FROM job_order o
     WHERE o.id = p_order
    ON CONFLICT (job_order_id, tx) DO NOTHING;
$$ LANGUAGE sql;

COMMENT ON FUNCTION exchange_mark_job_order(UUID) IS
    'Marks a job order dirty once per transaction; an order that no longer exists, as in the cascade of its hard delete, is not marked (REQ-OXCH-003).';

-- The client set of REQ-OXCH-003: active, closed within 7 days, or aUEC with an open settlement,
-- read from the last fingerprint because settlementOpen is derived in Java.
CREATE FUNCTION exchange_job_order_in_window(
    p_status VARCHAR, p_closed_at TIMESTAMP WITH TIME ZONE, p_type VARCHAR,
    p_settlement_open BOOLEAN, p_at TIMESTAMP WITH TIME ZONE DEFAULT now())
RETURNS BOOLEAN AS $$
    SELECT p_status IN ('OPEN', 'IN_PROGRESS')
        OR p_closed_at >= p_at - INTERVAL '7 days'
        OR (p_type = 'AUEC' AND COALESCE(p_settlement_open, FALSE));
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION exchange_job_order_in_window(VARCHAR, TIMESTAMP WITH TIME ZONE, VARCHAR, BOOLEAN, TIMESTAMP WITH TIME ZONE) IS
    'The window of the client set at an instant, now by default: an active order, one closed within the 7 days before it, or an aUEC order whose last fingerprint says settlementOpen (REQ-OXCH-003).';

CREATE FUNCTION exchange_mark_job_order_client_set() RETURNS VOID AS $$
    INSERT INTO exchange_job_order_dirty (job_order_id, tx)
    SELECT o.id, (pg_current_xact_id()::text)::bigint
      FROM job_order o
      LEFT JOIN exchange_job_order_fingerprint f ON f.job_order_id = o.id
     WHERE exchange_job_order_in_window(o.status, o.closed_at, o.type, f.settlement_open)
    ON CONFLICT (job_order_id, tx) DO NOTHING;
$$ LANGUAGE sql;

COMMENT ON FUNCTION exchange_mark_job_order_client_set() IS
    'Marks every job order of the client set dirty: the active ones, those closed within 7 days and aUEC orders whose settlement is open (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_row_marked() RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_mark_job_order(NEW.id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_job_order_insert_exchange_dirty
AFTER INSERT ON job_order
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_row_marked();

COMMENT ON TRIGGER trg_job_order_insert_exchange_dirty ON job_order IS
    'Marks a new job order dirty, so the worker gives it its first fingerprint (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_update_exchange_dirty
AFTER UPDATE ON job_order
FOR EACH ROW
WHEN (OLD.* IS DISTINCT FROM NEW.*)
EXECUTE FUNCTION exchange_job_order_row_marked();

COMMENT ON TRIGGER trg_job_order_update_exchange_dirty ON job_order IS
    'Marks a job order dirty on every write that changes its row, bulk SQL included (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_line_marked() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR (TG_OP = 'UPDATE' AND NEW.job_order_id IS DISTINCT FROM OLD.job_order_id) THEN
        PERFORM exchange_mark_job_order(OLD.job_order_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM exchange_mark_job_order(NEW.job_order_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_job_order_line_marked() IS
    'Marks the job order a row of a table keyed by job_order_id belongs to, before and after the write (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_material_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_material
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_line_marked();

COMMENT ON TRIGGER trg_job_order_material_exchange_dirty ON job_order_material IS
    'Marks the order of a written material line dirty: requested, handed-over and remaining amounts (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_item_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_item
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_line_marked();

COMMENT ON TRIGGER trg_job_order_item_exchange_dirty ON job_order_item IS
    'Marks the order of a written item line dirty: ordered, manufactured and delivered units (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_assignees_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_assignees
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_line_marked();

COMMENT ON TRIGGER trg_job_order_assignees_exchange_dirty ON job_order_assignees IS
    'Marks the order of a written assignee entry dirty, account merges included (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_participation_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_participation
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_line_marked();

COMMENT ON TRIGGER trg_job_order_participation_exchange_dirty ON job_order_participation IS
    'Marks the order of a written support row dirty: the actively supporting units (REQ-OXCH-003).';

CREATE TRIGGER trg_material_claim_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON material_claim
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_line_marked();

COMMENT ON TRIGGER trg_material_claim_exchange_dirty ON material_claim IS
    'Marks the order of a written claim dirty: a claim lowers the open amount (REQ-OXCH-003).';

CREATE TRIGGER trg_inventory_item_job_order_allocation_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON inventory_item_job_order_allocation
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_line_marked();

COMMENT ON TRIGGER trg_inventory_item_job_order_allocation_exchange_dirty ON inventory_item_job_order_allocation IS
    'Marks the order of a written earmark dirty: linked stock lowers the open amount (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_money_marked() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR (TG_OP = 'UPDATE' AND NEW.order_id IS DISTINCT FROM OLD.order_id) THEN
        PERFORM exchange_mark_job_order(OLD.order_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM exchange_mark_job_order(NEW.order_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION exchange_job_order_money_marked() IS
    'Marks the job order a row of an order-money table keyed by order_id belongs to, before and after the write (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_money_target_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_money_target
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_money_marked();

COMMENT ON TRIGGER trg_job_order_money_target_exchange_dirty ON job_order_money_target IS
    'Marks an aUEC order dirty when its target or target version changes (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_money_operation_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_money_operation
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_money_marked();

COMMENT ON TRIGGER trg_job_order_money_operation_exchange_dirty ON job_order_money_operation IS
    'Marks an aUEC order dirty on every money operation, the patch change''s currency wipe included (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_money_correction_entry_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_money_correction_entry
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_money_marked();

COMMENT ON TRIGGER trg_job_order_money_correction_entry_exchange_dirty ON job_order_money_correction_entry IS
    'Marks an aUEC order dirty on every correction of a money operation (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_money_holder_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_money_holder
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_money_marked();

COMMENT ON TRIGGER trg_job_order_money_holder_exchange_dirty ON job_order_money_holder IS
    'Marks an aUEC order dirty on every holder write, account merges and name retention included (REQ-OXCH-003).';

CREATE TRIGGER trg_job_order_money_account_holding_exchange_dirty
AFTER INSERT OR UPDATE OR DELETE ON job_order_money_account_holding
FOR EACH ROW EXECUTE FUNCTION exchange_job_order_money_marked();

COMMENT ON TRIGGER trg_job_order_money_account_holding_exchange_dirty ON job_order_money_account_holding IS
    'Marks an aUEC order dirty on every write of its Bank account positions (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_earmarked_item_marked() RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_mark_job_order(a.job_order_id)
       FROM inventory_item_job_order_allocation a
      WHERE a.inventory_item_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_item_exchange_dirty
AFTER UPDATE OF material_id, quality ON inventory_item
FOR EACH ROW
WHEN (NEW.material_id IS DISTINCT FROM OLD.material_id OR NEW.quality IS DISTINCT FROM OLD.quality)
EXECUTE FUNCTION exchange_job_order_earmarked_item_marked();

COMMENT ON TRIGGER trg_inventory_item_exchange_dirty ON inventory_item IS
    'Marks the orders a stock row is earmarked for dirty when its material or quality changes the quality tier it counts toward (REQ-OXCH-003).';

CREATE FUNCTION exchange_job_order_hierarchy_changed() RETURNS TRIGGER AS $$
BEGIN
    PERFORM exchange_mark_job_order_client_set();
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_unit_exchange_dirty
AFTER UPDATE OF active, kind, department, parent_org_unit_id ON org_unit
FOR EACH ROW
WHEN (NEW.active IS DISTINCT FROM OLD.active
      OR NEW.kind IS DISTINCT FROM OLD.kind
      OR NEW.department IS DISTINCT FROM OLD.department
      OR NEW.parent_org_unit_id IS DISTINCT FROM OLD.parent_org_unit_id)
EXECUTE FUNCTION exchange_job_order_hierarchy_changed();

COMMENT ON TRIGGER trg_org_unit_exchange_dirty ON org_unit IS
    'Marks the client set dirty when a unit''s activation, kind, department or parent changes the Profit hierarchy that decides responsibilityInvalid (REQ-OXCH-003, REQ-ORDERS-055).';

CREATE TRIGGER trg_org_unit_insert_exchange_dirty
AFTER INSERT ON org_unit
FOR EACH ROW
WHEN (NEW.kind = 'BEREICH' AND NEW.department = 'PROFIT' AND NEW.active)
EXECUTE FUNCTION exchange_job_order_hierarchy_changed();

COMMENT ON TRIGGER trg_org_unit_insert_exchange_dirty ON org_unit IS
    'Marks the client set dirty when a new active Profit area makes the Profit root ambiguous (REQ-OXCH-003, REQ-ORDERS-055).';

CREATE TRIGGER trg_org_unit_delete_exchange_dirty
AFTER DELETE ON org_unit
FOR EACH ROW
WHEN (OLD.kind = 'BEREICH' AND OLD.department = 'PROFIT' AND OLD.active)
EXECUTE FUNCTION exchange_job_order_hierarchy_changed();

COMMENT ON TRIGGER trg_org_unit_delete_exchange_dirty ON org_unit IS
    'Marks the client set dirty when an active Profit area is deleted (REQ-OXCH-003, REQ-ORDERS-055).';

INSERT INTO exchange_job_order_dirty (job_order_id, tx)
SELECT o.id, (pg_current_xact_id()::text)::bigint FROM job_order o;
