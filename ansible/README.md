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

## Before the role: the machine has to exist and let you in

[`cloud-init/hetzner-rocky10.yaml`](cloud-init/hetzner-rocky10.yaml) does that and **nothing else** —
one user, one key, sudo. Paste it into Hetzner's *Cloud config* field when creating the server.

The restraint is the design. This migration's safety argument is that testing and production come
out of the *same* procedure; anything cloud-init configures that the role also configures exists in
two places, and two places drift. A host built by "cloud-init plus the role" is then a near
neighbour of the one that was rehearsed rather than the same host, and the resemblance is exactly
what the sequence was meant to guarantee.

One thing it cannot defer, and neither can you: **the disk layout**. CIS wants `/var`, `/var/log`,
`/var/log/audit` and `/home` as separate filesystems, and on a cloud VM that is decided when the
machine is created. `/tmp` is the exception — `tmp.mount` gives it as a tmpfs without partitioning,
and takes effect at the next boot with `noexec`, so it is switched on deliberately and measured.

Firewalls: the production host runs **both** the provider's and `firewalld`, on purpose. See
[`PODMAN_HOST_BOOTSTRAP.md` §10](../docs/PODMAN_HOST_BOOTSTRAP.md) for what each allows and why the
doubling is not redundancy.

## Running it

```bash
ansible-galaxy collection install -r requirements.yml
cp inventory/hosts.yml.example inventory/hosts.yml   # and fill it in; hosts.yml is gitignored

ansible-playbook site.yml --limit testing --check --diff    # see what would change
ansible-playbook site.yml --limit testing                   # do it
```

The controller cannot be Windows natively — use WSL or a container.

## What it asserts, and refuses to proceed without

**Podman >= 5.8.** Not for networking -- that floor used to be 6.0, for
`rootless_port_forwarder="pasta"`, and [ADR-0187](../docs/adr/0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md)
removed the need for it entirely. It is about **Quadlet**: measured 2026-09-16, podman 5.8.2 carries
`Memory=`, `PidsLimit=`, `ReadOnly=`, `DropCapability=`, `NoNewPrivileges=`, `AutoUpdate=` and
`Notify=healthy`, while 5.4.2 has no `Memory=` at all.

That is the kind of gap worth an assertion because of **how** it fails: a Quadlet key that does not
exist is not an error. The unit starts, the container runs, every health check passes -- and the
memory limit measured for that service was simply never applied. The role therefore also reads the
shipped `podman-systemd.unit(5)` and confirms `Memory=` is really documented there, because a
distribution rebase can move that underneath a version number.

**`/usr/bin/pesto` is no longer required, and that is a change rather than an oversight.** It was
asserted while the migration depended on the pasta forwarder. Measured on 2026-09-16, that forwarder
preserves the client address on IPv4 and delivers **no IPv6 at all** -- so the deployment gets its
client addresses from a host-level PROXY-protocol front end instead, which needs neither pesto nor
podman 6. See the plan's section 13.

## Acceptance

Not "the playbook ran cleanly". A host is accepted when
[`scripts/check-conformance.py`](../scripts/check-conformance.py) is green against it — with
`client-address-visible` the one that matters, because every other check can pass while the edge
has quietly become a single bucket for the entire internet.

```bash
python scripts/check-conformance.py --ssh <host>
```

