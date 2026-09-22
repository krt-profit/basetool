-- Covering indexes for every foreign key that still had none (REQ-DATA-017, BE-PERF-10).
--
-- A foreign key without an index whose LEADING column is the key makes every DELETE (and key
-- UPDATE) of the referenced row scan the whole referencing table to check or cascade the
-- constraint, and every "rows of this parent" read a sequential scan. The earlier sweeps (V34, V92,
-- V122, V175) were done by reading the migrations; this one comes from the catalogue: the new
-- ForeignKeyIndexCoverageTest reads pg_constraint and pg_index and fails on any FK whose columns are
-- not the leading columns of a usable index. On 2026-09-22 it found the 38 below.
--
-- Two shapes that looked covered but are not:
--   * a composite index led by another column (e.g. mission_crew_job_types' primary key
--     (mission_crew_id, job_type_id) cannot serve a job_type_id lookup; neither can
--     refinery_yield's (terminal_id, material_id) serve material_id);
--   * a partial index with a business predicate (e.g. the active-offer unique index on
--     material_exchange_offer.inventory_item_id WHERE status = 'ACTIVE') hides every other row
--     from the constraint check.
--
-- A nullable key gets a partial index WHERE <column> IS NOT NULL: every lookup is "column = ?",
-- which implies the predicate, so Postgres uses it, and the index skips the (often majority) NULL
-- rows. A NOT NULL key gets a plain index.
--
-- Plain btree additions only: no data is touched and no constraint changes, so this migration is
-- reversible by dropping the indexes. Regular CREATE INDEX (no CONCURRENTLY) because Flyway runs
-- each migration inside a transaction; every table here is small.

-- Bank --------------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_bank_account_approval_limit_grantee_user_id
    ON bank_account_approval_limit (grantee_user_id) WHERE grantee_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_account_grant_granted_by
    ON bank_account_grant (granted_by) WHERE granted_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_account_view_grant_grantee_user_id
    ON bank_account_view_grant (grantee_user_id) WHERE grantee_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_counterparty_org_unit_id
    ON bank_booking_request (counterparty_org_unit_id) WHERE counterparty_org_unit_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_counterparty_user_id
    ON bank_booking_request (counterparty_user_id) WHERE counterparty_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_decided_by
    ON bank_booking_request (decided_by) WHERE decided_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_holder_id
    ON bank_booking_request (holder_id) WHERE holder_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_owner_approval_granted_by
    ON bank_booking_request (owner_approval_granted_by) WHERE owner_approval_granted_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_resulting_transaction_id
    ON bank_booking_request (resulting_transaction_id) WHERE resulting_transaction_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_booking_request_target_account_id
    ON bank_booking_request (target_account_id) WHERE target_account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_transaction_counterparty_org_unit_id
    ON bank_transaction (counterparty_org_unit_id) WHERE counterparty_org_unit_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bank_transaction_counterparty_user_id
    ON bank_transaction (counterparty_user_id) WHERE counterparty_user_id IS NOT NULL;

-- Catalogue -----------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_blueprint_external_alias_output_item_id
    ON blueprint_external_alias (output_item_id) WHERE output_item_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_material_refined_material_id
    ON material (refined_material_id) WHERE refined_material_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_refinery_yield_material_id
    ON refinery_yield (material_id);
CREATE INDEX IF NOT EXISTS idx_refinery_order_refining_method_id
    ON refinery_order (refining_method_id) WHERE refining_method_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_p4k_import_job_preview_job_id
    ON p4k_import_job (preview_job_id) WHERE preview_job_id IS NOT NULL;

-- Users, roles, deletion ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_deletion_request_decided_by_id
    ON deletion_request (decided_by_id) WHERE decided_by_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_deletion_request_user_id
    ON deletion_request (user_id);
CREATE INDEX IF NOT EXISTS idx_user_approval_event_decided_by_id
    ON user_approval_event (decided_by_id) WHERE decided_by_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id
    ON role_permissions (role_id);
CREATE INDEX IF NOT EXISTS idx_org_unit_grand_admiral_user_id
    ON org_unit (grand_admiral_user_id) WHERE grand_admiral_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rank_requirement_category_id
    ON rank_requirement (category_id) WHERE category_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rank_requirement_topic_id
    ON rank_requirement (topic_id) WHERE topic_id IS NOT NULL;

-- Orders, claims, exchange --------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_job_order_handover_executing_squadron_id
    ON job_order_handover (executing_squadron_id) WHERE executing_squadron_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_job_order_handover_executing_user_id
    ON job_order_handover (executing_user_id) WHERE executing_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_material_claim_claimed_by_user_id
    ON material_claim (claimed_by_user_id) WHERE claimed_by_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_material_claim_claiming_org_unit_id
    ON material_claim (claiming_org_unit_id);
CREATE INDEX IF NOT EXISTS idx_material_claim_material_id
    ON material_claim (material_id);
CREATE INDEX IF NOT EXISTS idx_material_exchange_offer_inventory_item_id
    ON material_exchange_offer (inventory_item_id) WHERE inventory_item_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_material_exchange_offer_owning_org_unit_id
    ON material_exchange_offer (owning_org_unit_id) WHERE owning_org_unit_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_material_exchange_request_owning_org_unit_id
    ON material_exchange_request (owning_org_unit_id) WHERE owning_org_unit_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_material_exchange_request_requested_material_id
    ON material_exchange_request (requested_material_id) WHERE requested_material_id IS NOT NULL;

-- Missions ------------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_mission_crew_job_types_job_type_id
    ON mission_crew_job_types (job_type_id);
CREATE INDEX IF NOT EXISTS idx_mission_frequency_frequency_type_id
    ON mission_frequency (frequency_type_id) WHERE frequency_type_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mission_ownership_owner_id
    ON mission_ownership (owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mission_participant_squadron_id
    ON mission_participant (squadron_id) WHERE squadron_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mission_unit_responsible_user_id
    ON mission_unit (responsible_user_id) WHERE responsible_user_id IS NOT NULL;
