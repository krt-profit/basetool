# ADR-0364 — Operational scripts share explicit source and failure contracts

Status: Accepted

## Context

Deployment functions, environment readers and host-file lists had overlapping maintenance, while
several failure paths could report successful validation or cleanup without completing their work.

## Decision

Keep deployment state transitions and rollback sequencing in `deploy.sh`. Move configuration/runtime
apply, release verification, drift and observability function groups into focused `scripts/lib`
owners, without introducing another deployment engine or additional mutable global state.

Use `env_file.py` as the single literal, single-line host environment parser for both renderers and
shell readers. Preserve literal values and last-key-wins behavior; this is a defined subset, not a
claim of complete Compose grammar compatibility.

Use `host-companions.json` for source/target/mode/kind of the operational units, drop-ins, logrotate
and tmpfiles companions. The role's static loops and release drift expectations consume it. Normalize
only comments and the service uid in the generated ordering drop-in. Read-only comparisons publish
the existing drift signals and never reconcile the host automatically.

Treat nonzero validation/prune/restore outcomes as failures. Cleanup still attempts independent
steps; failed runs preserve last-success timestamps. Restore verification requires successful COPY
and constraint creation, representative dump-to-database count agreement and validated constraints.

## Consequences

The role must install every helper before a release depending on it; helper hashes are apply-path
inputs. Existing deployment scenario tests preserve success and partial-failure rollback order.
`testAuditOperations` exercises parser parity, explicit timeouts, cleanup/validator failures, missing
restore contents and installed-companion drift using synthetic local fixtures.
