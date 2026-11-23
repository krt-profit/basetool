# ADR-0405 — Job-order commands take the writer rule, the read check and the actor as parameters

- **Status:** Accepted — the create seam
  ([#3640](https://github.com/krt-profit/basetool/issues/3640)) and the status, target and
  information commands ([#3641](https://github.com/krt-profit/basetool/issues/3641))
- **Date:** 2026-11-23
- **Deciders:** @greluc
- **Related:** decided in [#3611](https://github.com/krt-profit/basetool/issues/3611),
  [#3613](https://github.com/krt-profit/basetool/issues/3613) and
  [#3621](https://github.com/krt-profit/basetool/issues/3621) · spec
  [#3604](https://github.com/krt-profit/basetool/issues/3604) · REQ-ORDERS-053 · REQ-XCH-009 ·
  REQ-XCH-045 · ADR-0257 (Logisticians on the exchange) · ADR-0287 (one edit module per aggregate) ·
  ADR-0352 (one explicit bank access policy)

## Context

Every job-order command decided who may write from the web's contextual authorities:
`JobOrderAccessPolicy` and `AccessGateService` read the `OrgUnitContextualAuthority` grants the JWT
converter mints from the database, narrowed by the `X-Active-Org-Unit-Id` pin, with an administrator
passing within their scope. The actor was read the same way, through
`AuthHelperService.currentUserId()`.

The exchange's acting member carries none of those grants and is never an administrator
(REQ-XCH-009), so every web writer check is false for a client. Job orders on the exchange need the
same commands with a narrower rule decided from the database
([#3611](https://github.com/krt-profit/basetool/issues/3611)): a client creates an aUEC order only
for a unit the member directly belongs to and is a Logistician of by the org-stock rule, as the
requesting side, and the administrator is a plain member. A second copy of the commands for the
exchange would let the two drift apart, and the exchange module may only call `joborder::api`
while the commands live in `joborder.internal`.

## Decision

1. **The authority is a parameter.** The commands take a `JobOrderWriterRule` and a
   `JobOrderActor` (both in `joborder.api`) instead of reading the security context.
   - `JobOrderWriterRule.CONTEXTUAL` is the web's rule: the contextual Logistician authorities with
     the honoured pin, for either side of an order, an administrator writing every unit unpinned
     and the pinned unit pinned.
   - `JobOrderWriterRule.DIRECT_LOGISTICIAN` is the exchange's rule: the units the actor's member
     directly belongs to and is a Logistician of (the flag, a leadership rank, or Bereich or
     Organisationsleitung reach; `KRT Member`; active units only,
     `OrgUnitMembershipQueryService.findDirectLogisticianOrgUnitIds`), for the requesting side
     only. It reads no role, so an administrator is a plain member.
   - `coversResponsibleSide()` states each rule's direction, as an exhaustive switch.
   - `JobOrderActor` is `Web(member)` or `Client(member, clientId)`. The commands attribute the write
     to its member (the created notice excludes it) and resolve the database rule for that member;
     later slices choose the client's audit types and notice parameter from it.
2. **Resolved on every call, under the command's locks.** `JobOrderWriters` (package-private in
   `joborder.internal`) resolves a rule for an actor into the writer units of that call: the web's
   `ScopePredicate` for `CONTEXTUAL`, the member's direct Logistician units for
   `DIRECT_LOGISTICIAN`. Nothing is cached across calls.
3. **One create command.** Every kind's create runs one path in `JobOrderService`: the order locks,
   both units resolved (an unknown unit and a responsible unit outside the eligible Profit units
   answer `400` first), the pair rechecked with the rule it was given, the lines of the kind, the
   unit history, the priority, the created notice, the `orders/queue` frame after the commit and
   the audit event. Nothing is filled in. The web's MATERIAL, ITEM and aUEC creates pass
   `CONTEXTUAL` and the request's member. The port `JobOrderCreateCommand.createAuec` (implemented
   by `JobOrderAuecCreation`, which also stores the target) is what the exchange calls with
   `DIRECT_LOGISTICIAN` and a `Client` actor.
4. **One create-options projection.** `JobOrderCreationOptions.current(rule, actor)` projects the
   choices for the rule it is given. A requesting option is writable when the actor writes the unit;
   a responsible option only when the rule also covers the responsible side, so under
   `DIRECT_LOGISTICIAN` the requesting list is exactly the member's writer units and the
   responsible list the eligible Profit units, with the canonical Profit area as the aUEC
   suggestion. `canCreate()` stays the web's capability flag under `CONTEXTUAL`.
5. **The exchange never permits more than the web.** For the same member without a pin, every pair
   `DIRECT_LOGISTICIAN` permits is one `CONTEXTUAL` permits, because a direct Logistician unit is a
   unit the converter grants and the requesting side counts under both rules. A test pins it with the
   web's authorities minted by the real converter.
6. **No policy object.** The writer rule is one predicate and one projection, not a bank-style
   `BankAccessPolicy` with decision objects (ADR-0352); that is rejected in
   [#3611](https://github.com/krt-profit/basetool/issues/3611) for job orders.
7. **The status, target and information commands** (the status command of `JobOrderService`,
   `JobOrderMoneyOrderChanges.target` and `.information`, through `JobOrderEditor`) take the same
   writer rule and actor and, beside them, a `JobOrderReadCheck`.
   - `JobOrderReadCheck.CONTEXTUAL` is the web's complete-order read (REQ-ORDERS-052) of the
     signed-in caller; the exchange's read set comes with the read slice
     ([#3644](https://github.com/krt-profit/basetool/issues/3644)). The read check, like the rule,
     is a value the joborder module resolves, so a caller names a check and never brings its own
     predicate.
   - Each command keeps its own section version (lifecycle, target, comment and contact),
     `JobOrderResponsibilityGuard.assertWritable` and the order locks, and decides the read check
     and the rule on the loaded order before and after the locks, so a refusal and a concurrent
     money submission, handover or hierarchy change yield one outcome. The web's order of
     refusals stays: a status change answers an invalid responsibility (`400`) before the read
     check and the rule (`403`) and the version (`409`); a target or information change answers
     the read check (`403`) first.
   - The rule decides per side on the loaded order: `JobOrderWriters.Scope.permitsStatus` (the
     responsible writer sets every status, a requesting writer cancels and reopens a
     cancellation) and `writesEitherSide` for the target and the information. Under
     `DIRECT_LOGISTICIAN` only the requesting side counts. The status change carries no
     `JobOrderGate`; the gates `STATUS`, `CANCEL` and `SET_OPEN` are gone.
   - The actor reaches the audit and notice call sites: the notices exclude its member, the
     target history names it, and the need-change notice speaks for the responsible side only
     when the rule lets the actor write it there. The web's audit types stay; the exchange's write
     slices choose their own types and the notices' application parameter from the actor. An
     automatic completion or reopening a target change causes keeps the request's member.
   - The ports `JobOrderStatusCommand.cancel` (implemented by `JobOrderCancellation`),
     `JobOrderTargetCommand.changeTarget` and `JobOrderInformationCommand.changeComment` (both by
     `JobOrderMoneyOrderChanges`) expose exactly the writes a client gets
     ([#3613](https://github.com/krt-profit/basetool/issues/3613)). No port reopens an order, and
     the comment write checks and raises only the comment version, never the contact.
   - For the same member without a pin, every status, target and comment write
     `DIRECT_LOGISTICIAN` permits is one `CONTEXTUAL` permits; a test pins it with the web's
     authorities minted by the real converter, beside point 5's pair test.

## Consequences

- **Positive.** The web and the exchange share one create path and one status, target and
  information command each, so validation, the locks, the eligible-Profit check, the versions, the
  audit event, the notices and the live update cannot drift between them. The exchange depends on
  five narrow ports in `joborder.api` and on three small value types. The web's behaviour is
  unchanged, pinned by characterisation tests of the create, the options and the three commands.
- **Negative / cost.** Each command carries two or three more parameters, and the web entry points
  build the `CONTEXTUAL` rule and read check and the request's actor themselves.
  `JobOrderCreationOptions` became an interface implemented in `joborder.internal`. The database
  rule reads the member's memberships on every call.
- **Neutral.** The web's refusals keep their codes: `400` for an unknown or ineligible unit before
  `403` for a pair the rule does not permit. How the exchange answers these is its own contract.

## Alternatives considered

- **A second create path for the exchange.** Rejected: two copies of the locks, checks, audit and
  notices drift, which is the abuse case "exchange and web rules drifting apart" of
  [#3611](https://github.com/krt-profit/basetool/issues/3611).
- **A bank-style policy object for an explicit actor** (ADR-0352). Rejected in
  [#3611](https://github.com/krt-profit/basetool/issues/3611): create is one predicate and one
  projection; the bank's refactor answered rules spread over five services.
- **Minting contextual authorities for the acting member.** Rejected: it would give the exchange the
  web's whole reach, the Bereich and OL cascade without membership and the administrator's scope
  included, against REQ-XCH-009.
- **The rule carrying the member.** Rejected: the member would then live in two parameters that must
  agree. The rule names the authority, the actor names who acts.
