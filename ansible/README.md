# Host provisioning

The Basetool host bootstrap, as an Ansible role. [ADR-0185](../docs/adr/0185-the-host-bootstrap-is-an-ansible-role.md).

> [!important] This tree provisions. It does not deploy.
> Images, unit files and configuration bundles reach the host by `REQ-OPS-001`'s **pull-only** path,
> driven by `deploy.sh` on a timer. This playbook installs packages, creates the service user and
> its subuid range, lays out and owns the directories, sets SELinux contexts, and writes
> `containers.conf`. **It must never be run against a host that is serving traffic, and it must
> never grow into a second delivery path.** That boundary is the load-bearing half of ADR-0185:
> once a playbook configures the host, running the playbook is one short step from pushing the
> next compose file with it, and the pull-only property is gone by convenience rather than by
> decision.

## Why this exists at all

[`PODMAN_MIGRATION_PLAN.md` §11](../docs/PODMAN_MIGRATION_PLAN.md) rules that the **testing host is
built first and production is built from the same procedure afterwards**. A prose checklist executed
twice by a human is not the same procedure twice — it is two procedures that resemble each other,
and the resemblance is precisely what the sequence was supposed to guarantee. This role makes
"the same procedure" literal, checkable, and re-runnable after each of the deliberate breakages
Phases 1–4 consist of.

## The document this implements

[`docs/PODMAN_HOST_BOOTSTRAP.md`](../docs/PODMAN_HOST_BOOTSTRAP.md) carries the **why** and is not
duplicated here: why redis needs uid 999 and cannot chown its own data directory, why the Keycloak
provider directory must exist or the deploy fails at the very last step after every container is
already healthy, why SELinux will bite the certificate handover first. A playbook is a poor place
for that reasoning, and the reasoning is half the value.

This tree carries the **what**, and one thing the prose cannot: the uid arithmetic is **derived**
rather than transcribed.

## The uid arithmetic

Under a user namespace the container's uid is not the host's uid. Measured on 2026-09-16 with
`podman unshare cat /proc/self/uid_map`:

```
0       1000          1      <- container root = the service user itself
1     100000      65536      <- container uid N = base + N - 1
```

The prose bootstrap tells a human to use `podman unshare chown`, because a hand-computed `110000`
is silently wrong the day the subuid base changes. The role computes the host uid from
`basetool_subuid_base` — **the same variable that grants the range** — so the two cannot disagree,
and it gets real idempotence, which a `command:` wrapping `podman unshare` could never have.

Set `basetool_report_uid_map: true` to have it print the translation it used, so a reader can check
it against `podman unshare` on the host.

## Running it

```bash
ansible-galaxy collection install -r requirements.yml
cp inventory/hosts.yml.example inventory/hosts.yml   # and fill it in; hosts.yml is gitignored

ansible-playbook site.yml --limit testing --check --diff    # see what would change
ansible-playbook site.yml --limit testing                   # do it
```

The controller cannot be Windows natively — use WSL or a container.

## What it asserts, and refuses to proceed without

Two version facts, because the entire migration rests on them and both fail **silently** at runtime
rather than loudly at install time:

- **podman ≥ 6.0.** `rootless_port_forwarder="pasta"` arrived there. Below it, published ports on
  user-defined bridge networks go through `rootlessport`, which replaces the client's source
  address with its own.
- **`/usr/bin/pesto` present.** It ships in `passt` and is what that setting invokes. Without it
  the setting has nothing to call and the fallback is the same one.

Measured on 2026-09-16, which is why these are assertions rather than notes: on Debian 13 with
podman 5.4.2, two containers of the same image on the same host differing only in network mode — a
bridge container logged `10.89.0.2`, the forwarder; a pasta container logged the real client
address. Rocky 10 and AlmaLinux 10 are EL 10 and will pass the distribution check; they ship
podman 5.8.2 with no `pesto`, and the version assertion is what stops them.

## Acceptance

Not "the playbook ran cleanly". A host is accepted when
[`scripts/check-conformance.py`](../scripts/check-conformance.py) is green against it — with
`client-address-visible` the one that matters, because every other check can pass while the edge
has quietly become a single bucket for the entire internet.

```bash
python scripts/check-conformance.py --ssh <host>
```

