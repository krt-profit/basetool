# ADR-0330 — The promotion system is removed

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Related:** issue #2988 · REQ-PROMO-001…004 (retired) · REQ-AUDIT-001 ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0300](0300-officer-reach-is-derived-per-org-unit-and-the-keycloak-officer-role-is-retired.md)

## Context

The promotion system (Beförderungssystem) — topics, categories, level contents, rank requirements,
member evaluations, the evaluation matrix and the per-squadron switch — is no longer used. Keeping
it costs a backend module with its access policy, the `/api/v1/promotion/**` API, five frontend
pages and their proxy, the `org_unit.is_promotion_enabled` flag, an audit area with 17 event types,
navigation entries and the tests behind all of it.

## Decision

We will remove the promotion system completely:

1. **Code.** The backend module `promotion` (and its Spring Modulith declaration),
   `scope.api.PromotionReach`, every `/api/v1/promotion/**` endpoint and
   `PATCH /api/v1/squadrons/{id}/promotion-enabled` go. The frontend loses the promotion pages, the
   `/api/proxy/promotion/**` proxy, the navigation entries and flags, the squadron admin switch and
   the page-tab covering mechanism, which only promotion used. `/me/capabilities` and `/me/layout`
   drop `canManagePromotions`, `canManagePromotionsAnywhere` and `canUsePromotionAnywhere`.
2. **Audit.** `AuditDomain.PROMOTION` and its `PROMOTION_*` event types go, with the viewer's tab,
   filter and labels.
3. **Schema.** `V331__drop_promotion_system.sql` drops `promotion_topic`, `promotion_category`,
   `promotion_level_content`, `rank_requirement`, `member_evaluation`, the two owner-kind guard
   functions, `org_unit.is_promotion_enabled` with `chk_org_unit_promotion_only_squadron`, and
   deletes the `audit_event` rows of domain `PROMOTION`, whose enum values no longer exist. Nothing
   is exported first.
4. **Officer reach stays.** The per-unit officer reach (ADR-0300), the Leitung page and the
   leadership pages are unchanged; promotion was one of several consumers.

## Consequences

- The evaluations, rank requirements and the promotion audit trail are gone after the deploy; they
  can only be restored from a database backup. A GDPR export no longer has an `evaluations`
  section.
- Stored navigation keys of the removed entries are pruned on load by the existing unknown-key
  pruning; no migration of user settings is needed.
- REQ-PROMO-001…004 are retired and `docs/specs/promotion-pages.md` and
  `docs/specs/promotion-evaluation-matrix.md` are deleted. Accepted ADRs that name promotion as one
  consumer among others keep their text.
- The backend declares one Modulith module fewer, and the audit viewer one area fewer.

## Alternatives considered

- **Hide the feature behind the per-squadron switch** — rejected: the code, schema, audit area and
  tests would still have to be maintained for a surface nobody uses.
- **Export the evaluations before dropping them** — rejected: the data has no further use, and a
  backup taken before the deploy already holds it.
