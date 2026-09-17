-- =====================================================================
-- V242 - deletion_request: a member's Art. 17 erasure request, decided
--        by an admin rather than executed on the spot (REQ-SEC-061)
-- =====================================================================
-- Until now a member had no way to ask for their account to be removed:
-- the privacy policy promised the right, and the only route was to
-- contact an admin out of band. This table is the queue that closes
-- that, modelled on the registration-approval queue rather than a
-- second pattern of its own.
--
-- WHY A TABLE AND NOT A STATUS ON app_user. A request has its own
-- lifecycle and its own facts: when it was raised, whether the member
-- also asked for the retained handle snapshots to go, who decided,
-- when, and why. A status column carries the last of those and loses
-- the rest -- and a withdrawn or declined request must remain
-- readable, which a single status cannot express. app_user also has
-- exactly one row per member, so a member who withdraws and asks again
-- would overwrite the first request's record.
--
-- WHY IT IS NOT AN IMMEDIATE SELF-DELETE (decision 5, @greluc). The
-- deletion removes the Keycloak account and purges the member's
-- warehouse stock and hangar while reassigning missions and refinery
-- orders (REQ-DATA-008). None of that is reversible, and a mis-click on
-- one's own profile page must not be able to trigger it.
--
-- ON DELETE CASCADE, DELIBERATELY. When the request is executed, the
-- app_user row goes -- and this row goes with it. That is correct: the
-- request is itself personal data about the member, so keeping it after
-- an erasure would defeat the erasure. The record that the deletion
-- happened lives in the audit trail (USER_DELETED), which is where a
-- deletion is supposed to be recorded, and which does not name the
-- request. Consequence worth knowing: there is no "EXECUTED" row to
-- read afterwards, which is why the status enum does not have one.
--
-- ONE OPEN REQUEST PER MEMBER, enforced by a partial unique index
-- rather than by application code. A member who clicks twice must not
-- create a second queue entry for the same decision; a member whose
-- request was declined must be able to raise a new one later, which is
-- why the index is partial on PENDING instead of unique on user_id.
--
-- erase_history_requested IS A WISH, NOT AN INSTRUCTION. It records
-- that the member also asked for the handle snapshots that survive a
-- deletion (both audit trails, the bank booking history, the booking
-- requests and the two handover recipients) to be anonymised. An admin
-- decides it deliberately and separately; nothing acts on this column
-- automatically (decision 6, @greluc).
-- =====================================================================

CREATE TABLE IF NOT EXISTS deletion_request
(
    id                       UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    version                  BIGINT      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    user_id                  UUID        NOT NULL
        REFERENCES app_user (id) ON DELETE CASCADE,

    status                   VARCHAR(20) NOT NULL
        CONSTRAINT chk_deletion_request_status
            CHECK (status IN ('PENDING', 'WITHDRAWN', 'DECLINED')),

    erase_history_requested  BOOLEAN     NOT NULL DEFAULT FALSE,

    decided_by_id            UUID
        REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at               TIMESTAMPTZ,
    decision_note            TEXT,

    -- A decision is all-or-nothing: a decided row carries both the instant
    -- and (for a decline) the reasoning, and a PENDING row carries neither.
    -- The note is required on DECLINED because Art. 12(4) obliges the
    -- controller to tell the requester WHY a request is refused, and a
    -- reason nobody wrote down cannot be told to them.
    CONSTRAINT chk_deletion_request_decision_complete
        CHECK (
            (status = 'PENDING' AND decided_at IS NULL AND decided_by_id IS NULL)
                OR (status = 'WITHDRAWN' AND decided_at IS NOT NULL)
                OR (status = 'DECLINED' AND decided_at IS NOT NULL
                    AND decision_note IS NOT NULL AND length(btrim(decision_note)) > 0)
            )
);

COMMENT ON TABLE deletion_request IS
    'A member''s Art. 17 erasure request (REQ-SEC-061). Decided by an admin; never executed '
    'automatically. Cascades away with the account when the request is carried out, so there is '
    'no EXECUTED state -- the USER_DELETED audit event is the record of the deletion.';

COMMENT ON COLUMN deletion_request.erase_history_requested IS
    'The member also asked for the handle snapshots that survive a deletion to be anonymised. '
    'A wish an admin decides deliberately; nothing acts on it automatically.';

COMMENT ON COLUMN deletion_request.decision_note IS
    'The admin''s recorded reasoning. Mandatory on DECLINED: Art. 12(4) requires telling the '
    'requester why a request is refused.';

-- One open request per member. Partial, so a declined or withdrawn request
-- does not block a later one.
CREATE UNIQUE INDEX IF NOT EXISTS idx_deletion_request_one_pending_per_user
    ON deletion_request (user_id)
    WHERE status = 'PENDING';

-- The admin queue reads "oldest pending first", and the business-metrics
-- sampler reads MIN(created_at) WHERE status = 'PENDING' once a minute.
CREATE INDEX IF NOT EXISTS idx_deletion_request_pending_created_at
    ON deletion_request (created_at)
    WHERE status = 'PENDING';
