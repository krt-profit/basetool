-- =====================================================================
-- V237 - Record which client an audited mutation came through
--        (REQ-AUDIT-005, GHSA-2vq5-8p8w-5r64)
-- =====================================================================
-- audit_event says who acted, on what, and when -- but not through which
-- client software. That was inert for as long as exactly one client could
-- perform a given action: "which client did this" had one possible answer
-- and the column would have restated the row's own domain. It stops being
-- inert the moment two first-party clients can reach the same mutation,
-- because then the trail cannot answer the first question of any
-- post-incident review -- including the case that makes it reachable at
-- all: an access token replayed inside its lifetime acts with the
-- member's authority, and its rows would be indistinguishable from the
-- same person working in the browser.
--
-- The value is the token's `azp` claim, mapped through the SAME bounded
-- allowlist basetool_api_client_requests_total{client_id} already uses
-- (REQ-OBS-018): a known first-party or gateway client verbatim, an
-- unrecognised one as 'other', and a caller with no token (a scheduled
-- job) or a token carrying no azp as 'none'. Bounded on purpose -- an
-- unbounded write would put a client-chosen string into the audit trail,
-- which is the one place free values must never land. `azp` is signed by
-- Keycloak and a client cannot set it, so recording it introduces no new
-- trust (ADR-0129 already leans on the same claim for the far more
-- dangerous on-behalf-of decision).
--
-- NULLABLE, AND THE NULLS ARE NOT DATA LOSS. Rows written before this
-- column existed are unambiguous without it, because at the time they
-- were written only one client could hold the authority they record. A
-- backfill is therefore neither possible (the claim was never stored)
-- nor needed, and a later reader must not read the nulls as a gap in the
-- trail. New rows always carry a value: 'none' is a recorded answer, not
-- an absence.
--
-- VARCHAR(60) matches event_type's width and is far above any Keycloak
-- client id in this realm; AuditService clamps to it so an over-long
-- value can never throw and roll back the business mutation it is
-- recording.
--
-- Rollback: ALTER TABLE audit_event DROP COLUMN client_id (drops the
-- index with it). No other object references it.

ALTER TABLE audit_event
    ADD COLUMN client_id VARCHAR(60);

-- The viewer's client filter narrows one area's log, newest first -- the
-- same access shape as idx_audit_event_domain_occurred, with the client
-- pinned. Low cardinality (four values today), so this only pays off as
-- a prefix of the domain it is combined with, which is how the viewer
-- always queries it.
CREATE INDEX idx_audit_event_domain_client_occurred
    ON audit_event (domain, client_id, occurred_at DESC);

COMMENT ON COLUMN audit_event.client_id IS
    'Bounded client attribution: the token''s azp mapped through the known-client allowlist (REQ-OBS-018), else ''other'' / ''none''. NULL only on rows predating V237, where the client was unambiguous - not missing data.';
