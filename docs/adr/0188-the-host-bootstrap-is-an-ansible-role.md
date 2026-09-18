# ADR-0188 — The host bootstrap is an Ansible role, and provisioning stays separate from delivery

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** @greluc (the decision), Claude (analysis)
- **Related:** [ADR-0049](0049-config-as-promotable-oci-artifact.md) ·
  [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  specs `REQ-OPS-001`, `REQ-OPS-004` ·
  [`PODMAN_HOST_BOOTSTRAP.md`](../PODMAN_HOST_BOOTSTRAP.md) ·
  [`PODMAN_MIGRATION_PLAN.md`](../PODMAN_MIGRATION_PLAN.md) §11

## Context

[ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) rebuilds the
production host on CentOS Stream 10, and [the plan's §11](../PODMAN_MIGRATION_PLAN.md) rules that
the **testing host is built first and production is built from the same procedure afterwards**. That
sequence is the whole safety argument of the migration.

A prose checklist executed twice by a human is not the same procedure twice. It is two procedures
that resemble each other, and the resemblance is exactly what the sequence was supposed to
guarantee. The current bootstrap — `deployment.md` → *Initial server bootstrap*, eight numbered
steps of `apt`, `useradd`, `mkdir` and `chown` — has been executed once, on one host, and there is
no way to assert that a second host came out the same.

Three further facts make this more than a preference:

- **This project already decided that host configuration is not a document.** ADR-0049 /
  `REQ-OPS-004` make it a promotable, digest-pinned artifact, and hand-editing it on the host is
  forbidden precisely because the next deploy silently overwrites it. The *bootstrap* being prose is
  the exception to a rule this project has already made everywhere else.
- **Phases 1–4 will run it repeatedly.** The testing host is snapshotted, broken on purpose,
  restored and re-bootstrapped. Idempotence is not a nicety there, it is the loop.
- **Rebuilds are recurring, not one-off.** ADR-0163 chose "rebuild rather than upgrade", and CentOS
  Stream 10 is supported to 2030-05-31. This happens again, by design.

## Decision

**The host bootstrap is an Ansible role**, run by the operator from their own workstation against a
host that is not yet serving traffic. The testing host and the production host are built from the
same role, with the same variables except for the ones that genuinely differ.

**Provisioning and delivery stay separate, and the separation is the load-bearing part of this
decision:**

|             |                              Provisioning                               |                  Delivery                   |
|-------------|-------------------------------------------------------------------------|---------------------------------------------|
| what        | packages, users, subuid ranges, directories, SELinux, `containers.conf` | images, unit files, configuration bundles   |
| by          | Ansible, push over SSH, operator-driven                                 | `deploy.sh`, pull-only, timer-driven        |
| when        | before the host serves traffic, and at deliberate host changes          | every five minutes, forever                 |
| governed by | this ADR                                                                | `REQ-OPS-001`, `REQ-OPS-002`, `REQ-OPS-003` |

> [!danger] Ansible must never become a second delivery path
> The temptation is concrete and will arrive quickly: once a playbook configures the host,
> running the playbook is one short step from pushing the next compose file with it, and
> `REQ-OPS-001` has been quietly undone by convenience rather than by decision. **The playbook
> does not deploy, does not ship unit files as part of a release, and is not run against a host
> that is serving traffic.**
> Whatever a future contributor needs, the answer is never to widen this role's job.

### On `REQ-OPS-001`, and a correction it forced

The obvious objection is that Ansible is push-over-SSH while `REQ-OPS-001` is "pull-only delivery".
Checking it turned up a defect in the requirement rather than in the plan.

`REQ-OPS-001`'s prose read *"There is no inbound SSH"*, flatly. Its own acceptance criteria have
always said something narrower — *"no SSH key, deploy key, or git credential is provisioned **for
the deploy path**"* — and the reality, documented at length in the production-access runbook, is
that the operator's SSH **is** the host's sole administrative entrance and the only route to the two
loopback-bound admin interfaces. @greluc confirmed on 2026-09-16 that it exists and stays.

So the prose was false, the acceptance criteria were right, and the requirement governs the
**delivery mechanism, not human access**. It was corrected in the same session, in
`docs/specs/deployment-delivery.md`, `docs/deployment.md` and ADR-0049. Ansible at bootstrap
therefore adds no inbound path that did not already exist, and changes nothing about the pull-only
deploy.

That correction is recorded here because it is the reason this ADR is not in tension with that one,
and because an objection resting on a sentence that turns out to be wrong is worth saying out loud
rather than quietly dropping.

## Alternatives considered

**An idempotent bash script in `scripts/`.** The house style: `deploy.sh`, `backup.sh` and the
`check-*` family are all bash with stubbed `.test.sh` harnesses, and this would add no tool. Rejected
because bash idempotence is hand-rolled at every step and its failure mode is *"ran half of it"* —
whereas a host bootstrap is almost entirely packages, users, directories, modes and units, which is
the shape Ansible's modules are for. `--check` and `--diff` come free and have no bash equivalent
worth writing.

**cloud-init or a kickstart.** Hetzner runs cloud-init natively and it would handle first boot well.
Rejected as the primary mechanism because it is one-shot: it cannot re-converge a host after a
deliberate change, which is precisely what Phases 1–4 need. It remains useful for the first-boot
minimum that gets the host reachable.

**`ansible-pull` on the host.** Preserves the pull direction and is superficially the tidier fit with
`REQ-OPS-001`. Rejected: it puts a git credential and a fetch path onto the production host, which is
the *opposite* of what that requirement is protecting, and it makes the host converge on a schedule
— turning provisioning into a second delivery loop, which the boundary above exists to prevent.

**Keep the prose checklist.** Rejected by the plan's own structure: it cannot make "production is
built from the same procedure as testing" true, only hoped for.

## Consequences

- **A new tool in the house.** `ansible-core` on the operator's workstation, plus
  `community.general` for the SELinux modules, pinned in a `requirements.yml`. The controller cannot
  be Windows natively — WSL or a container.
- **The prose document does not go away, and is not duplicated.**
  [`PODMAN_HOST_BOOTSTRAP.md`](../PODMAN_HOST_BOOTSTRAP.md) carries the **why**: why redis needs
  uid 999, why the Keycloak provider directory must exist or the deploy fails at the very last step,
  why SELinux will bite the certificate handover first. A playbook is a bad place for that reasoning
  and the reasoning is half the value. The role carries the **what**, and the two cross-reference.
- **The uid arithmetic becomes derived rather than transcribed.** The prose tells a human to use
  `podman unshare chown` because a hand-computed uid is silently wrong the day the subuid base
  changes. The role does better: it computes the host uid *from the same variable that sets the
  range*, so the two cannot disagree — and it gains real idempotence, which a `command:` task
  wrapping `podman unshare` could not have.
- **It needs its own verification.** Syntax check and `ansible-lint` in CI; a real run against the
  testing host; and the Phase 0 conformance suite as the acceptance, exactly as for everything else
  in this migration. A playbook that ran cleanly and produced a host with a red
  `client-address-visible` has failed.

## Status of this decision

Accepted. The role is written against
[`PODMAN_HOST_BOOTSTRAP.md`](../PODMAN_HOST_BOOTSTRAP.md) and is **unvalidated against a real host**
until the CentOS Stream 10 testing VM exists — which is the same status as the document it
implements, and for the same reason.
