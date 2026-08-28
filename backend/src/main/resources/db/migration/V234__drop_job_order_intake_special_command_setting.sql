-- The job-order intake Spezialkommando setting loses its only consumer (ADR-0149).
--
-- V128 seeded it for one purpose: an anonymous job-order creation had no author and no org context,
-- so it was stamped onto a designated SK rather than landing nowhere. Creating an order now requires
-- a login, which means the caller always brings both -- the guest branch in JobOrderOrgUnitResolver
-- is gone, the admin control that filled this setting is gone, and nothing reads the row.
--
-- Deleting it rather than leaving it blank: a settings row with no reader is a control an
-- administrator can still find and set, expecting an effect that no longer exists.
--
-- is_profit_eligible (V128's other half) is untouched -- it is the rule that decides which units may
-- process an order at all, and that rule now applies to every caller instead of only to logged-in
-- ones.
DELETE FROM system_setting
WHERE setting_key = 'job_order.intake_special_command_id';
