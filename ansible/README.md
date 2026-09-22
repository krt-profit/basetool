# Host provisioning

The Basetool host bootstrap, as an Ansible role. [ADR-0188](../docs/adr/0188-the-host-bootstrap-is-an-ansible-role.md).
It built the testing host and the production host `rocky-16gb-nbg1-1` (Rocky Linux 10, rootless
Podman), which has served since the cutover on 2026-09-22. How the stack is then
delivered and operated is [`docs/deployment.md`](../docs/deployment.md).

> [!important] This tree provisions. It does not deploy.
> Images, unit files and configuration bundles reach the host by `REQ-OPS-001`'s **pull-only** path,
> driven by `deploy.sh` on a timer. This role installs packages, creates the accounts and the
> subuid range, lays out and owns the directories, sets SELinux contexts, configures Podman, the
> firewall, fail2ban, the haproxy front end and the host half of the monitoring plane, and installs
> the operational scripts and their timers. **It must never grow into a second delivery path**: it
> ships no image, no Quadlet unit of the stack and no configuration bundle, and it is never
> scheduled on the host. That boundary is the load-bearing half of ADR-0188.
>
> **When it runs** (ADR-0188): in full, against a host that is not serving yet; and afterwards only
> for a **deliberate host change** — a new operational script, a changed alias or firewall rule —
> usually limited with `--tags`. On production every such run is a host write: owner-approved,
> `--check --diff` first.

The testing host is built first and production from the same role with the same variables except
where the two genuinely differ ([`PODMAN_MIGRATION_PLAN.md` §11](../docs/archive/PODMAN_MIGRATION_PLAN.md)):
a prose checklist executed twice is two procedures that resemble each other, and this makes "the
same procedure" literal, checkable and re-runnable.

## Where the reasoning lives

[`docs/archive/PODMAN_HOST_BOOTSTRAP.md`](../docs/archive/PODMAN_HOST_BOOTSTRAP.md) carries the
**why** — why redis needs uid 999 and cannot chown its own data directory, why the Keycloak
provider directory must exist, why SELinux bites the certificate handover first, why there are two
firewalls. It is archived as the record of the bootstrap it describes and is not duplicated here.
The task files themselves carry the measurements behind each step. This README carries the **what**.

## What the role does

| Task file | Tag(s) | What |
|---|---|---|
| `00-preflight.yml` | `preflight` | EL 10, cgroup v2, SELinux loaded — and **sets** enforcing, scheduling a relabel and saying a reboot is owed when it had to change the mode |
| `10-packages.yml` | `packages` | podman, passt, netavark, aardvark-dns, crun, skopeo, restic, rclone, acl, rsync, firewalld, SELinux tooling; asserts the Podman floor; removes cockpit and refuses a host still listening on 9090 |
| `15-cosign.yml` | `packages`, `cosign` | the upstream cosign binary, checked against the sha256 pinned in `defaults/main.yml` |
| `20-user.yml` | `user` | the service user `iri`, its subuid/subgid range, lingering |
| `22-deploy-user.yml` | `user`, `deploy` | the `deploy` account and the sudoers bridge to `iri` (`podman *`, `systemctl --user *`, `systemctl restart alloy.service`) — and proves it works. `podman *` means `deploy` can run any code **as `iri`**; it cannot become root |
| `30-directories.yml` | `directories` | `/var/iri`, `/var/lib/iri`, `/etc/iri`, the setgid `env.d`, the backup staging tree, the unit delivery directory `/etc/containers/systemd/users/<uid>`, every data directory at its **translated** owner |
| `25-scripts.yml` | `scripts` | `deploy.sh`, `backup.sh`, `restore-drill.sh`, `container-cleanup.sh`, `lib/container-runtime.sh`, `lib/common.sh`, `render-env-d.py` (root-owned, `0755`); the four `iri-*` units and timers with their shared sandbox drop-in `10-deploy-account-sandbox.conf` (`basetool_host_deploy_account_units`), logrotate, the lock tmpfiles; timers **enabled, not started** |
| `40-selinux.yml` | `selinux` | `container_file_t` on the data tree, `bin_t` on the scripts, `restorecon -RF` |
| `27-observability.yml` | `observability`, `monitoring` | node_exporter, alloy (enabled, not started), prometheus-podman-exporter as a user unit of `iri`, persistent journald bounded to 31 days and 4G (REQ-OBS-010), log-source permissions for alloy, the cgroup and certificate-expiry collectors and their timers (started) |
| `45-updates.yml` | `updates` | `dnf-automatic` for **security advisories only**, the container runtime excluded, never rebooting; its timer moved to 07:00 (started); an `ExecStopPost=` that records each run and `iri-host-updates-metrics.service`, which re-reads `needs-restarting -r` at boot (REQ-OPS-032) |
| `50-podman.yml` | `podman` | `~iri/.config/containers/containers.conf` (the pasta `--map-guest-addr` pair, ADR-0196), the public-name alias drop-ins, the optional JVM truststore drop-in |
| `60-hardening.yml` | `hardening` | an OpenSCAP scan against `cis_server_l1` with the tailoring in `defaults/main.yml`; remediation only with `basetool_host_hardening_remediate: true` |
| `65-firewall.yml` | `firewall` | firewalld default-deny (SSH, plus the front end's ports), loopback trusted, fail2ban's sshd jail |
| `70-frontend.yml` | `frontend` | haproxy on 80/443 forwarding to the edge's loopback ports with PROXY protocol v2 (ADR-0187); **enabled, not started** |

It deliberately does **not** write any secret: `.env`, the keystore, `realm-export.json`, the redis
`users.acl`, the GHCR pull token (it only warns if `deploy` cannot read it), `backup.env` and
`rclone.conf`, and the monitoring secrets and certificates are operator-provided — see
[`docs/deployment.md` → *Secrets and host-only files*](../docs/deployment.md#secrets-and-host-only-files).
Nor does it start the first deploy or haproxy: both are the operator's decision, and haproxy has
nothing to forward to before the stack exists.

## Before the role: the machine has to exist and let you in

[`cloud-init/hetzner-rocky10.yaml`](cloud-init/hetzner-rocky10.yaml) does that and **nothing else**
— one key-only `sudo` user (`sysadm`), two keys, a package upgrade and one reboot into the new
kernel. Two keys, because `root` and `sysadm` are password-locked and the console accepts no login:
the operator's key is the way in when the automation's cannot be used. Paste it into Hetzner's
*Cloud config* field when creating the server, and check both fingerprints first. Root stays
reachable by key (`disable_root: false`) until `sysadm` is proven.

The restraint is the design: anything cloud-init configures that the role also configures exists in
two places, and two places drift.

One thing neither can defer: **the disk layout**. CIS wants `/var`, `/var/log`, `/var/log/audit`
and `/home` as separate filesystems, and on a cloud VM that is decided when the machine is created.

Firewalls: the production host runs **both** the provider's cloud firewall and `firewalld`, on
purpose — see [`PODMAN_HOST_BOOTSTRAP.md` §10](../docs/archive/PODMAN_HOST_BOOTSTRAP.md). The cloud
firewall is outside this role.

## Running it

```bash
ansible-galaxy collection install -r requirements.yml
cp inventory/hosts.yml.example inventory/hosts.yml   # fill it in; hosts.yml is gitignored

ansible-playbook site.yml --limit testing --check --diff    # see what would change
ansible-playbook site.yml --limit testing                   # do it
```

The controller cannot be Windows natively — use WSL or a container. Ansible **ignores an
`ansible.cfg` in a world-writable directory**, which a Windows mount under `/mnt/` is: it prints a
warning and then silently runs without `roles_path`, `collections_path` or `host_key_checking`.
Copy the tree into the Linux filesystem before running it. The collection versions are capped in
`requirements.yml` so that an `ansible-core` 2.16 controller keeps working. CI runs
`ansible-playbook --syntax-check` and `ansible-lint` (`repo-lint.yml` → `ansible-lint`).

**A new host gets a full, untagged run**, and a second run must report `changed=0`. Measured on the
production host 2026-09-18: first run `changed=25`, second `changed=0`.

> [!warning] A `--tags` run on a fresh host leaves exactly the half the deployer needs
> Audited on the production host 2026-09-20 after a `--tags scripts,selinux,observability` run: the
> scripts, the labels and the timers were right, while `/var/lib/iri`, `/etc/iri` and
> `/var/iri/code` still had the wrong owner and `env.d`, `/var/iri/backup`, the unit delivery
> directory and the lock tmpfiles did not exist. Tags are for changing a provisioned host, not for
> building one.

**Changing a provisioned host.** The operational scripts reach the host only this way — they are not
in the config bundle, so a merged change to `deploy.sh` is not on the host until the role runs:

```bash
ansible-playbook site.yml --limit production --tags deploy,scripts --check --diff
ansible-playbook site.yml --limit production --tags deploy,scripts
```

`--tags observability` does the same for the two collectors, `--tags updates` for the security-update
setup, `--tags podman` for the alias drop-ins,
`--tags cosign` after a cosign pin bump. Each task file that needs the service user's uid looks it
up itself, so a tag-limited run does not depend on a skipped file.

> [!note] `--check` on a FRESH host stops partway, and that is not a defect
> Many tasks read what earlier tasks install — the podman version floor, the SCAP datastream, the
> firewalld service. In check mode nothing was installed, so those reads find nothing. The role
> skips the ones it can and says so, but a dry run on an empty host still stops at the first task
> that needs a package to *exist*. `--check` earns its keep on an **already-provisioned** host,
> where it is the idempotence proof.

## The inventory

`inventory/hosts.yml.example` has both hosts. Override only what genuinely differs — every override
is a way for the rehearsal to stop rehearsing the real thing. The addresses of the real machines
are not in this public repository.

| Variable | Why it matters |
|---|---|
| `basetool_host_public_name_aliases` | **Required on every rootless host, production included** ([ADR-0196](../docs/adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md)). A container cannot reach the host through its own public address, so each public name a container dials — the frontend, api, ingest and grafana hosts — is aliased to `host-gateway`. The first entry must match `IRI_KEYCLOAK_HOST_ALIAS` in the host `.env`. Empty removes the drop-ins, so a hand-written one does not survive the next run: put it here. |
| `basetool_host_public_name_aliases_v6` | the same names on the IPv6 guest address, for `blackbox-exporter` only |
| `basetool_host_edge_trusted_proxies` | the edge's six pinned addresses (default in `defaults/main.yml`); must equal the pins `scripts/generate-quadlet.py` writes into `edge.container` — the generator refuses a mismatch — and `EDGE_TRUSTED_PROXY` in `.env` must name the same six |
| `basetool_host_jvm_truststore_path` | only where the edge holds a privately signed certificate (testing); production sets nothing |
| `basetool_host_fail2ban_ignoreip` | loopback by default; a management network belongs here, or the jail can lock out the only way in |
| `basetool_host_hardening_remediate` | `false` by default: scanning always, remediation only on a host you hold a snapshot of |
| `basetool_host_report_uid_map` | print the container→host uid translation the role used |

## The uid arithmetic

Under a user namespace the container's uid is not the host's. Measured with
`podman unshare cat /proc/self/uid_map`:

```
0       <uid of iri>  1      <- container root = the service user itself
1         100000  65536      <- container uid N = base + N - 1
```

A hand-computed `110000` is silently wrong the day the subuid base changes, so the role **derives**
every host uid from `basetool_host_subuid_base` — the same variable that grants the range — and the
two cannot disagree. It also gets real idempotence, which a `command:` wrapping `podman unshare`
could not have. `basetool_host_report_uid_map: true` prints the translation for checking against
`podman unshare` on the host.

## What it asserts, and refuses to proceed without

**Podman >= 5.8.** Not for networking — that floor used to be 6.0, for
`rootless_port_forwarder="pasta"`, and
[ADR-0187](../docs/adr/0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md)
removed the need for it. It is about **Quadlet**: podman 5.8.2 carries `Memory=`, `PidsLimit=`,
`ReadOnly=`, `DropCapability=`, `NoNewPrivileges=`, `AutoUpdate=` and `Notify=healthy`, while 5.4.2
has no `Memory=` at all. A Quadlet key that does not exist is not an error — the unit starts and the
limit is simply never applied — so the role also reads the shipped `podman-systemd.unit(5)` and
confirms `Memory=` is documented there.

Beyond that it refuses: a host that is not EL 10 or not on cgroup v2, SELinux not loaded, a web
console still listening on 9090, a trusted front-end address given as a prefix or wildcard (or the
retired singular variable), a sudoers bridge that
parses but does not actually grant, an alloy that is up but cannot read the logs it ships, and a
fail2ban running without its jail. `/usr/bin/pesto` is no longer required — it was, while the
migration depended on the pasta forwarder.

## Acceptance

Not "the playbook ran cleanly". A host is accepted when
[`scripts/check-conformance.py`](../scripts/check-conformance.py) is green against it — with
`client-address-visible` the one that matters, because every other check can pass while the edge has
quietly become a single bucket for the entire internet.

```bash
python scripts/check-conformance.py --ssh root@<host>
```

As **root**: the container checks read the service user's store and `.env` is `0640 deploy:deploy`,
so a login account sees an empty host. The external checks — `client-address-visible` among them —
describe whatever the public names resolve to, so they only speak for this host once DNS points at
it.
