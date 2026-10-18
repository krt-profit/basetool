# ADR-0280 — A resent held batch gets the staged confirmation link and is not relayed

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** spec `REQ-XCH-021` ([`external-exchange.md`](../specs/external-exchange.md)) ·
  spec `REQ-OBS-011` ([`observability.md`](../specs/observability.md)) ·
  [ADR-0110](0110-ingest-handoff-consume-off-navigational-get.md) ·
  [ADR-0207](0207-each-service-reaches-redis-as-its-own-acl-user.md) ·
  `ingest/…/handoff/HandoffStagingService.java`, `ingest/…/web/ExchangeController.java` ·
  `monitoring/prometheus/alerts/business.yml`

## Context

A change set the backend's mass-change guard holds back writes nothing; the gateway stages it in the
handoff staging and answers `409 MASS_CHANGE_CONFIRMATION_REQUIRED` with a `confirmationUrl`. The
staging keeps one held batch per client and member, so the client's next held batch replaces the
previous one (REQ-XCH-021). The contract tells a client to show the member the link and not to resend
the batch (`docs/exchange/errors.md`, `docs/exchange/sync-guide.md`).

On 2026-10-18 `ExchangeGuardStorm` fired for one registered client: a single installation had resent
the identical held stock batch about every 5.5 minutes since the previous evening. The guard behaved
as specified, but every resend

- was relayed and evaluated again, and counted `basetool_exchange_writes_total{outcome="held"}`, so
  the alert counted one held batch a dozen times an hour, and
- was staged anew, evicting the handoff of the link the member had just been shown, so the member
  could never open a link that was still valid by the time they got to it.

The root cause is the client's retry, but a misbehaving client must not be able to make the
confirmation flow unusable for the member, nor make one held batch look like a storm.

## Decision

**While a client's held batch still waits for the member, a byte-identical resend of it by the same
installation to the same resource is answered with the staged batch's link, without relaying it.**

1. **The digest.** When the gateway stages a held batch, it records
   `ingest:handoff-digest:mass:<client>:<sub>` — the SHA-256 of the installation's key thumbprint, the
   resource and the change set as the gateway serializes it, with the handoff id and the guard's
   `detail` — for the handoff's own lifetime. The client's next held batch overwrites it.
2. **The resend.** Before relaying a change set that is not a dry run, the gateway computes its digest;
   when the recorded digest matches and the handoff it names is still staged (the member has not
   opened it), the gateway answers `409 MASS_CHANGE_CONFIRMATION_REQUIRED` with that handoff's
   `confirmationUrl` and `detail`. Nothing is relayed or staged, and the answer is not cached.
3. **Only GET and SET.** The digest is a string key under the gateway's own `ingest:*` keys, so the
   Redis ACL of the ingest user (REQ-SEC-068) needs no new command. Reading and writing it is
   best-effort: a failure, or an entry that cannot be read, makes the gateway relay the change set
   as if nothing were staged.
4. **Counted apart.** A resend counts
   `basetool_ingest_exchange_mass_changes_repeated_total{client_id}` and never reaches the backend, so
   `basetool_exchange_writes_total{outcome="held"}` — and `ExchangeGuardStorm` on it — counts distinct
   held batches. A new warning `ExchangeHeldBatchResent` fires when one client resends held batches
   more than ten times in an hour, which is the signal to contact its author.
5. **Quota unchanged.** A resend is counted against the write quota exactly as the first send was;
   the check sits after the quota count and right before the relay.

## Consequences

- The link the member was shown stays valid for the batch's 30-minute staging lifetime, however often
  the client resends. Once the member opens it (the frontend's `GETDEL`), or it expires, the next
  resend is relayed and, if still held, staged with a new link — the behaviour before this decision.
- A resend is not re-evaluated by the guard. A batch whose window has meanwhile freed up still waits
  for the member's confirmation until its staging expires; the contract already tells the client to
  wait for the member, so no conforming client sees a difference.
- Any change to the change set — another op, another order, a different field — is a new batch and
  replaces the staged one, as before. So does the same batch from another installation.
- Two identical resends that race each other may both be relayed and staged; the later one wins, as
  before. This is not worth a lock.
- One more small key per client and member with a held batch; it expires with the handoff and is not
  counted against the exchange's byte budget, as the index keys are not.

## Alternatives considered

- **Count the resend in the backend as `outcome="held_repeat"`.** Keeps one metric, but the backend
  would have to know about the gateway's staging, and every resend would still be relayed and
  evaluated; the gateway already holds everything needed to recognise it.
- **Keep the staged batch on any resend and refuse the new one.** Protects the link, but a client that
  legitimately sends a corrected batch would be stuck with the stale one for 30 minutes.
- **Look the staged batch up through the index list.** Needs `LRANGE` or `LINDEX` in the ingest
  user's ACL and parsing the staged document on every write; the digest key needs neither.
- **Rate-limit `409` answers per client.** Hides the symptom from the alert but still evicts the
  member's link on every accepted resend.
- **Leave it to the client.** The contract is clear, but one non-conforming release is enough to make
  the confirmation flow unusable for every member running it.
