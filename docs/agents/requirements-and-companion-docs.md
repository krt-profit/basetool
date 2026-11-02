# Requirements, specs and companion documents

The full rule set for what moves with a change: specs, audit log, monitoring, ADRs, arc42,
README, the role matrix and the user wiki.

Durable requirements are **first-class and binding** — they live as docs-as-code, not in
this file: canonical specs in [`docs/specs/`](../specs/INDEX.md) (registry + conventions
in its [`INDEX.md`](../specs/INDEX.md)), and architecture/design decisions in
[`docs/adr/`](../adr/README.md). The role matrix stays in
[`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md).

- **Every change to the project updates the requirements in the same PR** — add a new
  `REQ-<AREA>-NNN` or adapt the existing one(s) it touches. Code and spec move together; a
  behaviour change with no matching spec change is incomplete.
- **Every change to an audited area keeps its audit log in sync** — the audited areas (Bank,
  which keeps its own `bank_audit_event` trail, plus the thirteen `AuditDomain` values: Lager, Aufträge,
  Raffinerie, Mein Inventar, Missionen, Operationen, Rollen, Beförderung, Materialbörse, Hangar,
  Blueprints, Verbundene Anwendungen, Bedarf & Spenden) log
  **every** state-mutating
  activity (REQ-AUDIT-001, [`docs/specs/audit.md`](../specs/audit.md)). When you add, change or
  remove such an activity, adapt its audit logging in the **same PR**: add or adjust the
  `AuditEventType` and the `auditRecorder.record(...)` call (honouring the optimistic-locking
  landmines in [`backend-concurrency.md`](backend-concurrency.md) and the "no user free text / no PII in the details payload" rule), extend the
  unified viewer's per-area event-type filter and the DE/EN i18n labels, and reconcile the
  REQ-AUDIT-001 coverage list. A new mutation in an audited area with no matching audit event is
  incomplete.
- **Every change to the tool keeps the monitoring in sync.** Whenever you add, change, or remove a
  feature, endpoint, scheduled job, status enum, security gate, or external integration, evaluate the
  monitoring impact **in the same PR**: extend or adjust the business metrics (`basetool_*`, naming +
  bounded-label rules per REQ-OBS-011), the Prometheus alert rules and Grafana dashboards under
  [`monitoring/`](../../monitoring), the Alloy/Loki pipelines when a new log stream or format appears, the
  blackbox targets when a new public endpoint ships, and the tracing instrumentation for new outbound
  calls. A new scheduled job without task metrics, a new audited area without its audit-event counter,
  a new status enum without its queue gauge, a renamed/removed metric that breaks a dashboard or alert
  rule, or a new public surface without a probe is **incomplete**
  ([`docs/specs/observability.md`](../specs/observability.md), REQ-OBS-005…011).
- **Every architecturally significant or design decision is recorded as an ADR**
  ([`docs/adr/README.md`](../adr/README.md)) before or with the change that implements it.
- **The arc42 architecture documentation moves with the change, whenever it is affected**
  ([`docs/arc42/`](../arc42/README.md)). Twelve chapters describe the system as a whole. A change
  that touches a **module boundary or a new building block** (§5), a **runtime scenario** the
  chapters walk through (§6), the **host, the runtime, a deployment unit, a timer or the delivery
  path** (§7), a **cross-cutting rule** (§8), a **quality scenario or what measures it** (§10), or a
  **known risk or piece of debt** (§11) updates that chapter **in the same PR**. So does a new
  domain term (§12) and a change to the system boundary or an external neighbour (§3).
  - It is a **map, not a second copy**: where a subject has a canonical home — a spec, an ADR, the
    role matrix, a runbook — the chapter links it rather than restating it. Most changes therefore
    touch nothing here, and the ones that do are exactly the ones a later reader cannot reconstruct
    from the diff.
  - **§7 is written for the post-cutover runtime** and says so in its first line. A change to the
    Quadlet units, the host services, the uid translation or the operational timers lands there.
  - **Nothing gates this.** No build fails on a stale chapter — and a stale architecture document
    still reads as authoritative, which is precisely why the rule is written down instead of
    assumed.
- **README, the role matrix and the user wiki move with the change.** Whenever a change affects
  user-facing behaviour, architecture, env vars, the access/permission model, or any feature the
  handbook documents, keep these in sync **as part of the same unit of work**: the
  [`README.md`](../../README.md) overview, the [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md)
  role/permission matrix, and the **German end-user wiki** (the separate `basetool.wiki` repo —
  one page per feature area: Kartellbank, Benachrichtigungen, Rollen-und-Berechtigungen, …). The
  README and the role matrix ship in the **same PR** as the code; the wiki, being a separate git
  repo, is committed and pushed there alongside (German content, English commit messages — see the
  wiki carve-out in [`git-and-commits.md`](git-and-commits.md#language)). A change that touches one of these surfaces but leaves the README,
  the role matrix, or the relevant wiki page stale is **incomplete**.
- **The wiki carries no admin content.** It is written for normal users only — every role below
  Admin (members, Logistiker, Einsatz-Manager, officers, unit and area leads, bank staff, trade
  delegates). Admins get their information elsewhere. No wiki page describes the admin area, an
  admin-only page or action, what an admin can see, override or configure, or carries an Admin
  column in a permission table; there is no `Administration` page. An admin may appear only as a
  contact point ("wende dich an die Administration") or as the reason a user waits, never with how
  the admin does it. A user-facing fact that stems from an admin setting stays, without the
  setting. A change that only affects admin surfaces therefore needs no wiki update.
- **Requirements must always be honoured.** Code must not silently contradict a
  requirement. If a change *must* violate or override one, it needs **prior approval by the
  repository owner (@greluc)** AND the requirement must be **amended first** — never diverge
  from a spec and leave it stale. When in doubt, stop and ask.
- **Plan documents** (`docs/*_PLAN.md`, `docs/DESIGN_*.md`) carry a `Doc type:` header
  marking them *living spec* or *historical plan*; freeze a plan and point it at the living
  truth once it ships.
