# Archive — implemented plans and executed one-time runbooks

This folder collects the documents whose work is **done**: plans that have shipped, and runbooks
that were written to be carried out **once** (a rollout, a migration, a cutover) and have been.
Each one is a frozen historical record. It is kept because it explains *why* something was built
the way it was, and because code, migrations, alert rules and scripts still cite it by name or by
section number, not because it describes the system today.

> [!important] Nothing in here is current
> Every file opens with an **Archived** banner that says what it recorded and where the current
> truth lives. For what the system does now, read the specs in [`docs/specs/`](../specs/INDEX.md),
> the decisions in [`docs/adr/`](../adr/README.md), the architecture in
> [`docs/arc42/`](../arc42/README.md), and the living runbooks listed at the end of this page. A
> command in an archived runbook describes the host as it was then — several of them were written
> for the retired Docker/NPM host and do not work on the rootless Podman host.

## Rules for this folder

- **Archived documents are not edited to track new changes.** Only three kinds of edit are allowed:
  the Archived banner, fixing a link that moved, and removing something that must never be in the
  repository at all (a secret, personal data, a retired contact address).
- **A plan moves here in the PR that finishes it**, or as soon as it is found to be finished. So does
  a one-time runbook once it has been executed. It gets the Archived banner, an entry below, and
  every inbound link is re-pointed to `docs/archive/<NAME>.md` in the same change.
- **A procedure that is repeated in normal operations does not belong here**, even if it began life
  inside a rollout runbook. Move that part into a living document first, then archive the rest.
- The monitoring image-pin gate (`scripts/check-monitoring-image-pins.sh`) skips this folder: an
  archived runbook naming an old image tag is a record, not drift.

## Plans

| Document | What it planned | Outcome |
| --- | --- | --- |
| [`MULTI_SQUADRON_PLAN.md`](MULTI_SQUADRON_PLAN.md) | Several squadrons in one tool (German) | Shipped May 2026 (V80–V93); restored 2026-09-22 |
| [`SPEZIALKOMMANDO_PLAN.md`](SPEZIALKOMMANDO_PLAN.md) | Special commands as a second OrgUnit kind | Shipped May 2026 (R1–R9); restored 2026-09-22 |
| [`R8_DESTRUCTIVE_ROADMAP.md`](R8_DESTRUCTIVE_ROADMAP.md) | Dropping the legacy squadron columns and table | Executed 2026-05-23 (V100–V105); restored 2026-09-22 |
| [`SC_WIKI_SYNC_PLAN.md`](SC_WIKI_SYNC_PLAN.md) | The SC Wiki + UEX item sync | Shipped (R1–R9) |
| [`SC_WIKI_SYNC_AGENT_PROMPT.md`](SC_WIKI_SYNC_AGENT_PROMPT.md) | The implementation briefing for the sync | Shipped |
| [`SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md`](SC_WIKI_SYNC_DESTRUCTIVE_ROADMAP.md) | Dropping the sync's legacy columns | Executed 2026-06-01 (V125) |
| [`REFINERY_SCREENSHOT_IMPORT_PLAN.md`](REFINERY_SCREENSHOT_IMPORT_PLAN.md) | Refinery orders from game screenshots | v1 shipped 2026-06-10 |
| [`BANK_PLAN.md`](BANK_PLAN.md) | The Kartellbank | Shipped 2026-06-13 (epic #556) |
| [`materialboerse_PLAN.md`](materialboerse_PLAN.md) | The Materialbörse | Shipped |
| [`DESIGN_ITEM_INVENTORY.md`](DESIGN_ITEM_INVENTORY.md) | Item stock beside material stock | Shipped |
| [`ANDROID_API_EXPOSURE_PLAN.md`](ANDROID_API_EXPOSURE_PLAN.md) | Exposing `/api/v1` to the Android app | Shipped 2026-08-21 |
| [`MEMBERS_ONLY_PLAN.md`](MEMBERS_ONLY_PLAN.md) | Removing every anonymous and guest access | Shipped 2026-09-06 |
| [`WIRE_PROTOCOL_EVALUATION.md`](WIRE_PROTOCOL_EVALUATION.md) | Whether to leave REST/JSON (an analysis) | Decided 2026-09-10 → ADR-0161 |
| [`MGMT_VPN_PLAN.md`](MGMT_VPN_PLAN.md) | Management access over WireGuard | **Not carried out** beyond its inventory; closed 2026-07-08 |
| [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md) | Rootless Podman under Quadlet on a rebuilt host | Cut over 2026-09-22 |
| [`PODMAN_HOST_BOOTSTRAP.md`](PODMAN_HOST_BOOTSTRAP.md) | The host bootstrap, step by step | Superseded by the Ansible role (ADR-0188) |
| [`PODMAN_QUADLET_TRANSLATION.md`](PODMAN_QUADLET_TRANSLATION.md) | Translating Compose into Quadlet units | Superseded by `quadlet/` + `scripts/generate-quadlet.py` |

## One-time runbooks

| Document | What it carried out | Executed |
| --- | --- | --- |
| [`SK_ROLLOUT_RUNBOOK.md`](SK_ROLLOUT_RUNBOOK.md) | The staged special-command rollout | May 2026; restored 2026-09-22 |
| [`SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md`](SC_WIKI_SYNC_DEPLOYMENT_RUNBOOK.md) | Enabling the SC Wiki sync phase by phase | June 2026 |
| [`LIVESYNC_ROLLOUT_RUNBOOK.md`](LIVESYNC_ROLLOUT_RUNBOOK.md) | The Redis fan-out for live sync | Closed 2026-07-11 (epic #1102) |
| [`API_VHOST_ROLLOUT_RUNBOOK.md`](API_VHOST_ROLLOUT_RUNBOOK.md) | The public API vhost and its allow-list, phase by phase | Phases A–Y, 2026-08-21 → 2026-09-09 |
| [`MONITORING_ROLLOUT_RUNBOOK.md`](MONITORING_ROLLOUT_RUNBOOK.md) | The Prometheus/Grafana/Loki/Tempo stack | Closed 2026-08-28 (epics #936, #1041) |
| [`EDGE_CUTOVER_RUNBOOK.md`](EDGE_CUTOVER_RUNBOOK.md) | Nginx Proxy Manager → native nginx | 2026-09-12 |
| [`PODMAN_CUTOVER_RUNBOOK.md`](PODMAN_CUTOVER_RUNBOOK.md) | Docker host → rootless Podman host | 2026-09-22 |

The three documents marked *restored* had been deleted from the repository in May 2026. They were
brought back from git history on 2026-09-22 because some 40 Java sources and Flyway migrations
still cite them — and a Flyway migration's comment can never be edited, since its checksum is
recorded in every database it has run against.

## Living runbooks and plans (not archived)

These are still in use and stay in [`docs/`](../):

- [`deployment.md`](../deployment.md) — production deployment and operations
- [`backup.md`](../backup.md) — backup, restore and the restore drill
- [`KEYCLOAK_HARDENING_RUNBOOK.md`](../KEYCLOAK_HARDENING_RUNBOOK.md) — realm hardening; some steps
  are still open
- [`INGEST_KEYCLOAK_SETUP.md`](../INGEST_KEYCLOAK_SETUP.md) — Keycloak side of the ingest gateway,
  including onboarding a new approved ingest client
- [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md) — ADR-0001,
  not yet carried out
- [`TYPESCRIPT_MIGRATION_PLAN.md`](../TYPESCRIPT_MIGRATION_PLAN.md) — a costed option, not scheduled
- [`DESIGN_SC_EXTRACTOR.md`](../DESIGN_SC_EXTRACTOR.md) — the binding design of the SC Extractor GUI
- [`privacy/data-breach-runbook.md`](../privacy/data-breach-runbook.md) — the GDPR breach procedure
