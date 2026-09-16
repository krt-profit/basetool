-- Indexes for the four queries the GDPR surfaces run without one (REQ-SEC-058, REQ-NOTIF-009).
--
-- Three of them are on the member-reachable data export. Every member can call it, it is
-- @PreAuthorize("isAuthenticated()"), and all ~30 sections run unconditionally -- so the three
-- unindexed audit sections were three guaranteed sequential scans of the two largest tables in the
-- schema, per export, JSON and PDF alike. The fourth sibling section was already covered by
-- idx_audit_event_actor, which is why the gap was easy to miss: one of the four was fast.
--
-- The fourth index is on the unread half of the notification retention sweep. The read half has had
-- a partial index since V155; the unread half was added without one and scans the table daily.
--
-- All four are plain btree additions. No data is touched and no constraint changes, so this
-- migration is reversible by dropping the four indexes.

-- audit_event.target_user_id -- the export's "actions performed ON the member" section, and the
-- admin viewer's per-member filter. idx_audit_event_actor covers the actor direction only.
CREATE INDEX IF NOT EXISTS idx_audit_event_target
    ON audit_event (target_user_id)
    WHERE target_user_id IS NOT NULL;

-- bank_audit_event: both directions. Neither had an index at all -- V154 indexed the occurrence
-- time and the account, which is what the viewer sorts and filters by, not what a per-member
-- projection looks up.
CREATE INDEX IF NOT EXISTS idx_bank_audit_event_actor
    ON bank_audit_event (actor_user_id)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bank_audit_event_target
    ON bank_audit_event (target_user_id)
    WHERE target_user_id IS NOT NULL;

-- notification: the unread retention window (180 days from created_at). Mirrors V155's
-- idx_notification_read_at, which serves the read window (90 days from read_at) -- the two existing
-- recipient-leading indexes cannot serve either, because a sweep has no recipient to lead with.
CREATE INDEX IF NOT EXISTS idx_notification_created_unread
    ON notification (created_at)
    WHERE is_read = FALSE;

COMMENT ON INDEX idx_audit_event_target IS
    'Per-member audit projections: the data export''s actions-on-member section (REQ-SEC-058).';
COMMENT ON INDEX idx_bank_audit_event_actor IS
    'Per-member bank audit projection, actor direction (REQ-SEC-058).';
COMMENT ON INDEX idx_bank_audit_event_target IS
    'Per-member bank audit projection, target direction (REQ-SEC-058).';
COMMENT ON INDEX idx_notification_created_unread IS
    'The unread half of the notification retention sweep (REQ-NOTIF-009).';
