# 4. Solution strategy

Six decisions carry most of the architecture. Everything in §5 to §8 is a consequence of one of
them.

## 4.1 Split the UI from the API, and give the split a single seam

The **backend** serves `/api/v1/...` and never HTML. The **frontend** renders Thymeleaf and holds
no business logic; it reaches the backend through exactly one class, `service.BackendApiClient`.

The point is not layering for its own sake — it is that the backend can then be kept off the
internet, that the Android app and the web UI consume the *same* contract, and that every outbound
call passes one place where timeouts, retries, circuit breaking and bulkheads are configured.
ArchUnit tests enforce the direction so the seam cannot quietly grow a second one.

*Trade-off accepted:* the frontend hand-mirrors the backend's DTOs rather than sharing a module,
so a contract change has to be made twice. Two gates watch for drift (`FrontendDtoContractTest`,
`GeneratedDtoAgreementTest`) — see §11.

## 4.2 Put the internet-facing gateway in its own module

**`ingest`** owns no database. It authenticates the desktop extractor, checks the client is an
approved one, and relays over the internal network to the backend. It exists so that the surface
an unauthenticated internet can reach is a small module with one job, rather than the module that
holds every table.

## 4.3 Let Keycloak own identity, and centralise authorisation on `@PreAuthorize`

The applications never see a password. Roles arrive in the JWT; every protected operation carries
an `@PreAuthorize` and authorisation lives in annotations rather than scattered `if` statements, so
the permission model can be read off the code and diffed against
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md). Discord login is a Keycloak identity
provider plus a custom SPI that gates on guild membership and an in-guild role — **fail-closed**:
if Discord cannot be asked, nobody gets in that way.

## 4.4 Make the org unit the tenant, and scope in the service layer

Visibility follows the real command structure (Organisationsleitung → Bereiche → Staffeln and
Spezialkommandos). Scoping is applied in the service layer through `OwnerScopeService` rather than
in controllers or repositories, because it has to hold for every path into an aggregate, including
the ones added later. Different aggregates genuinely need different rules — a Mission escapes its
unit when it is not internal, a Job Order is scoped by the processing unit and the customer
separately — so the model names those kinds explicitly instead of pretending one rule fits.
Specification: [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md).

## 4.5 Treat concurrent editing as the normal case

Every write DTO carries a `version`; a concurrent modification surfaces as HTTP 409 rather than a
lost update. The non-obvious half is the **granularity**: a screen-wide lock that makes two people
editing unrelated parts of the same mission collide is treated as a defect, not a simplification.
Large aggregates carry independent per-section counters, each bumped and echoed on its own.

This is the rule most likely to be "simplified" by someone who has not been bitten by it, which is
why it sits in `CLAUDE.md` in the agent-critical section and not only here.

## 4.6 Deliver as a signed, promotable bundle onto a rootless runtime

Images are built in CI, signed with Cosign and pinned by digest. Configuration ships as its own OCI
artifact that is promoted to `:stable` by an explicit act. On the host a timer pulls the promoted
bundle, verifies every signature, and reconciles systemd units — there is no interactive deploy and
no shell session that "just fixes it".

Since the Podman cutover the containers are **rootless** systemd units owned by an unprivileged
service user, and three observability components that needed privileges or a container socket were
moved onto the host instead of being given them. §7 has the detail; the point of strategy is that
the runtime was allowed to change shape rather than the security posture being bent to preserve it.

## 4.7 Where the strategy is written down

| Concern | Authority |
| --- | --- |
| Security, access, the role model | [`security-and-access.md`](../specs/security-and-access.md), `ROLES_AND_PERMISSIONS.md` |
| Tenancy and scoping | [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) |
| API shape and versioning | [`api-conventions.md`](../specs/api-conventions.md) |
| Persistence, migrations, concurrency | [`data-persistence.md`](../specs/data-persistence.md), `backend/CLAUDE.md` |
| Frontend behaviour and live update | [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md), [`ui-design-system.md`](../specs/ui-design-system.md) |
| Delivery and deployment | [`deployment-delivery.md`](../specs/deployment-delivery.md) |
| Observability | [`observability.md`](../specs/observability.md) |
| Backup and recovery | [`backup-recovery.md`](../specs/backup-recovery.md) |
