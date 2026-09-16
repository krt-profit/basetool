# Host bootstrap — CentOS Stream 10, rootless Podman

> **Doc type:** Implementation plan — **living**, and **not yet validated on a host**. It is the
> Phase 2 deliverable of [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md) §11, written before
> the VM exists so that building it is a matter of following a procedure rather than improvising
> one. Every line marked *measured* was; everything else is derived from the Docker bootstrap in
> [`deployment.md`](deployment.md) and has to survive first contact.
> **Decision record:** [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md),
> Accepted 2026-09-16 with choice 1 amended to CentOS Stream 10.
> **Status:** draft. The testing host is built from this **first**; production is built from the
> corrected version afterwards. That order is the point — see §11 of the plan.
> **Last updated:** 2026-09-16.

---

## 0. What this replaces, and what it does not

[`deployment.md` → *Initial server bootstrap*](deployment.md) is the current procedure: Ubuntu
24.04, `apt`, a root Docker daemon, and a `deploy` user whose only privilege is membership in the
`docker` group. That document stays authoritative until the migration lands; **this one does not
edit it.**

What actually changes is smaller than it looks, and the parts that change are the parts that were
hard-won:

|                      |                Docker / Ubuntu today                 |              Rootless Podman / CentOS Stream 10               |
|----------------------|------------------------------------------------------|---------------------------------------------------------------|
| package manager      | `apt`                                                | `dnf`                                                         |
| the daemon           | `dockerd` as root                                    | **none**                                                      |
| privilege model      | `deploy` in group `docker`, which is root-equivalent | an unprivileged user with a subuid range                      |
| orchestration        | `docker compose` + `docker-compose.yml`              | Quadlet units under the user's systemd                        |
| bind-mount ownership | the container's uid, verbatim                        | the container's uid **translated through the user namespace** |
| confinement          | AppArmor                                             | **SELinux**, enforcing                                        |
| published ports      | `docker-proxy` binds :80/:443 as root                | a decision — see §5                                           |

> [!important] The one thing that must not be lost in translation
> Every ownership rule in the current bootstrap exists because something failed loudly and
> confusingly when it was missing — redis dying on `Can't open or create append-only dir`, Keycloak
> silently losing its file log, the deploy failing at the last step on the provider JAR. Those
> reasons are unchanged. Only the arithmetic changes, and §3 is where it changes.

---

## 1. System packages

```bash
sudo dnf -y install podman podman-plugins passt netavark aardvark-dns crun \
                    policycoreutils-python-utils setools-console \
                    git curl jq rsync util-linux-user
```

`policycoreutils-python-utils` provides `semanage`, which §4 needs; `setools-console` provides
`sesearch`, which is how an SELinux denial is diagnosed rather than guessed at.

Verify the versions actually installed — the whole migration rests on two of them:

```bash
podman --version          # expect 6.1.0 or newer
ls -l /usr/bin/pesto      # must exist; it is what carries the source address
rpm -q passt netavark aardvark-dns crun conmon
```

**Measured on CentOS Stream 10 on 2026-09-16:** podman 6.1.0, passt `0^20260728.gf8df3f1`
(`/usr/bin/pesto` present, 40 920 bytes), netavark 2.1.0, aardvark-dns 2.1.0, crun 1.29.1,
conmon 2.2.1.

> [!warning] If `/usr/bin/pesto` is absent, stop here
> Without it, `rootless_port_forwarder="pasta"` in §6 has nothing to invoke, published ports fall
> back to `rootlessport`, and the edge sees one client address for the entire internet. That is
> the failure this whole migration exists to avoid, and it is silent: every container starts, every
> health check passes, and the per-IP limiter and the admin allow-list quietly stop meaning
> anything. See [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md) §3.1.

### cosign

Unchanged from `deployment.md` step 1: the host verifies image signatures before applying them
(`REQ-OPS-015`), and **the host major must be at least the major CI signs with** — 3.x verifies both
2.x and 3.x keyless signatures, 2.x cannot verify 3.x. Install the same pinned version the current
host runs and verify it against its published checksum exactly as that document does.

---

## 2. The rootless service user

```bash
# No shell, no sudo, no SSH. It is not in any privileged group, because there is no
# docker group to be in -- which is the entire security case for this migration.
sudo useradd --system --create-home --shell /sbin/nologin iri

# A subordinate uid/gid range. 65536 is the conventional width and it is what the
# arithmetic in section 3 assumes.
sudo usermod --add-subuids 100000-165535 --add-subgids 100000-165535 iri

# The user's systemd instance must run without anybody logged in, or the containers
# stop the moment the last session closes -- and they will not start at boot at all.
sudo loginctl enable-linger iri
```

> [!note] `--create-home`, unlike the Docker bootstrap's `--no-create-home`
> Rootless Podman keeps its entire state under `$HOME`: the image store in
> `~/.local/share/containers`, the per-user configuration in `~/.config/containers`, and the
> generated units in `~/.config/containers/systemd`. The Docker bootstrap could do without a home
> because the root daemon owned everything; here the home **is** the deployment.

Verify the range landed and lingering is on:

```bash
grep '^iri:' /etc/subuid /etc/subgid
loginctl show-user iri --property=Linger   # expect Linger=yes
ls -d /run/user/$(id -u iri)               # the user manager's runtime dir must exist
```

---

## 3. Directory layout, and the ownership arithmetic

This is the section that differs most, and the one where a wrong number produces a container that
starts and then fails in a way that reads like an application fault.

### Why the numbers move

Under a user namespace the container's uid is **not** the host's uid. Measured on 2026-09-16 with
`podman unshare cat /proc/self/uid_map`, for a user with `100000-165535`:

```
0       1000          1      <- container root  = the service user itself
1     100000      65536      <- container uid N  = 100000 + N - 1
```

So the uids every bind mount in this deployment depends on translate like this — each one measured
with `podman unshare chown`, not calculated by hand:

| Runs as, in the container |                 What needs it                  | Host uid today | **Host uid under the namespace** |
|---------------------------|------------------------------------------------|----------------|----------------------------------|
| `0` (root)                | —                                              | 0              | **1000** (the service user)      |
| `70`                      | postgres data dirs                             | 70             | **100069**                       |
| `101`                     | the edge, and the certificates `acme` hands it | 101            | **100100**                       |
| `999`                     | redis AOF + snapshot                           | 999            | **100998**                       |
| `1000`                    | Keycloak's file log                            | 1000           | **100999**                       |
| `10001`                   | backend / frontend / ingest log dirs           | 10001          | **110000**                       |

### Do not compute these by hand

`podman unshare` enters the namespace and does the translation for you, so the command reads like
the container's own view and lands correctly on the host:

```bash
sudo -u iri podman unshare chown -R 10001:10001 /var/iri/backend/log
```

That is the idiom for **every** ownership line below. Writing `chown 110000:110000` works and is a
latent bug: change the subuid base and every hand-computed number is silently wrong, while the
`unshare` form keeps being right.

### The layout

```bash
sudo mkdir -p /var/iri/{code,secrets,db-backend,db-keycloak,redis}
sudo mkdir -p /var/iri/{backend,frontend,ingest}/log
sudo mkdir -p /var/iri/keycloak/log
sudo mkdir -p /var/iri/monitoring/{data,textfile,certs,secrets}
sudo mkdir -p /var/lib/iri /etc/iri
sudo chown -R iri:iri /var/iri /var/lib/iri

# Then, from inside the namespace, the per-service owners. Same reasons as the Docker
# bootstrap -- redis cannot chown its own /data because users.acl is mounted :ro from the
# same directory, Keycloak loses its file log silently, the app modules run as 10001.
sudo -u iri podman unshare chown -R 10001:10001 /var/iri/backend/log \
                                                /var/iri/frontend/log \
                                                /var/iri/ingest/log
sudo -u iri podman unshare chown -R 1000:1000   /var/iri/keycloak/log
sudo -u iri podman unshare chown -R 999:999     /var/iri/redis
sudo -u iri podman unshare chown -R 70:70       /var/iri/db-backend /var/iri/db-keycloak
```

> [!warning] `/var/iri/code/keycloak/providers` is the one that fails at the very end
> The deployer stages the promoted Keycloak provider JAR into it. If it does not exist and is not
> writable by the service user, the deploy fails on the **last** step, after every container is
> already healthy — which reads like a post-success glitch and is not. Create it explicitly:
>
> ```bash
> sudo install -d -o iri -g iri /var/iri/code/keycloak/providers
> ```

---

## 4. SELinux

Enforcing by default, and this is new: the Ubuntu host runs AppArmor, which never had an opinion
about bind mounts. SELinux does.

```bash
getenforce        # expect Enforcing -- do NOT set it permissive to make something work
```

A container process runs under `container_t` and may only read files labelled `container_file_t`.
A bind mount from `/var/iri` carries whatever label that path has, which is not that — so the
container gets `Permission denied` on a file whose mode bits say it should be readable, which is
exactly the kind of failure that gets misdiagnosed as an ownership problem.

Two ways to fix it, and the difference matters:

- **`:z` / `:Z` on the mount** relabels the source. `:z` marks it shared between containers, `:Z`
  marks it private to one. Convenient, and it **rewrites labels on the host path** — never put it
  on a path that something outside the container also owns.
- **A permanent rule** via `semanage`, which survives a relabel and does not mutate anything at
  mount time:

  ```bash
  sudo semanage fcontext -a -t container_file_t '/var/iri(/.*)?'
  sudo restorecon -Rv /var/iri
  ```

The permanent rule is preferred for the data directories, because they outlive any one container
and because a `:Z` on a shared path is how two services end up unable to read each other's files.

> [!danger] The certificate handover is where this will bite first
> `acme` writes `fullchain.pem` and `privkey.pem` and hands them to uid 101 so the edge can open
> them (`REQ-OPS-026`). Under this migration that path crosses **two** boundaries at once: a
> user-namespace uid translation *and* an SELinux label. A failure in either produces the same
> symptom — the edge exits three seconds after start, the health gate fails, the deploy rolls back,
> and nothing anywhere says "label". Diagnose with `ausearch -m AVC -ts recent`, never by guessing.

---

## 5. Binding :80 and :443

Rootless processes may not bind below 1024 unless the host says otherwise. Three options, in
increasing order of blast radius:

1. **`AmbientCapabilities=CAP_NET_BIND_SERVICE`** on the generated unit — narrowest, one unit, no
   host-wide change. **Try this first.**
2. **`net.ipv4.ip_unprivileged_port_start=80`** via `/etc/sysctl.d/` — host-wide, and it lets *any*
   unprivileged process on the box bind those ports.
3. **Socket activation** — systemd owns the listener. Note nginx speaks no `LISTEN_FDS`, so this
   can only apply to the forwarder, not to nginx itself.

Whichever is chosen, it interacts with §6: the process that binds the host side is the port
forwarder, not nginx, which already listens on 8080/8443 inside the container as uid 101.

---

## 6. `containers.conf` — the setting the migration turns on

```ini
# ~iri/.config/containers/containers.conf
[network]
rootless_port_forwarder = "pasta"
```

Without it, published ports on **user-defined bridge networks** go through `rootlessport`, a
userspace proxy that replaces the client's address with its own. Measured on 2026-09-16, two
containers of the same image on the same host differing only in network mode: on a bridge the
container logged `10.89.0.2`, the forwarder; on pasta it logged the real client address.

The option is **experimental and off by default upstream**. That is a standing risk on an
internet-facing rate limiter, not a footnote — it is recorded as such in ADR-0163, and it is why
the acceptance below asserts the behaviour rather than the setting.

> [!important] Assert it, do not trust it
> After the stack is up, `scripts/check-conformance.py --ssh <host>` must report
> `client-address-visible` **green**. That check exists for precisely this, it is red today against
> the testing host through its proxy, and it is the only thing standing between a working limiter
> and one that has silently become a single bucket.

---

## 7. Units, configuration and secrets

The Quadlet units live in `~iri/.config/containers/systemd/` and are materialised by
`systemctl --user daemon-reload`. The unit files replace `docker-compose.yml` inside the promoted
config bundle (`REQ-OPS-004` — host configuration stays a promotable, digest-pinned artifact).

Secrets are unchanged in shape and stay **host-only**, never in the bundle (`REQ-OPS-005`,
`REQ-OPS-012`): `/var/iri/code/.env` at `0640`, the keystore under `/var/iri/secrets`, the GHCR
pull token under `/etc/iri`. Two carry-overs from the Docker bootstrap that still apply:

- **The keystore needs a POSIX ACL, not a wider mode.** The app modules run as one uid and Keycloak
  as another, so no single owner/group covers both; the current host grants the second uid read via
  `setfacl` rather than making private key material world-readable. The same applies here, with the
  **translated** uids from §3.
- **Check the line endings on `.env`.** The deployer reads paths back out of it with `grep`/`cut`,
  so a CRLF file hands it a path with a trailing carriage return and the pre-flight aborts with
  `required file missing:` for a file that is sitting right there.

---

## 8. Verification, before anything is promoted to it

In order, because each step makes the next one meaningful:

```bash
# the user manager is really running without a session
sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user status

# the namespace maps what section 3 assumed
sudo -u iri podman unshare cat /proc/self/uid_map

# cgroup delegation actually reached the user slice, or Memory= and PidsLimit= are decoration
cat /sys/fs/cgroup/user.slice/user-$(id -u iri).slice/cgroup.controllers   # expect memory and pids

# the units generate, before they are started
sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) \
  /usr/libexec/podman/quadlet -dryrun -user
```

Then the real gate:

```bash
python scripts/check-conformance.py --ssh <host>          # all checks green
python scripts/check-conformance.py --ssh <host> --include-load   # and the limiter refuses
```

> [!success] The acceptance is the suite, not this document
> A bootstrap that produced a running stack and a red `client-address-visible` has failed, however
> smoothly it ran. The suite is green against production-on-Docker today, which is what makes
> "green here too" a meaningful claim rather than a new baseline invented for a new host.

---

## 9. What is still unwritten

Named here rather than discovered later:

- **The deployer.** `deploy.sh` speaks Compose throughout, and `REQ-OPS-003`'s digest pin, health
  gate and automatic rollback are guarantees that have to move across rather than be re-invented.
  Phase 3.
- **`backup.sh`, `docker-cleanup.sh`, `restore-drill.sh`** all speak Docker.
- **The monitoring plane**, which has never run on the testing host at all, and the cgroup
  collector (`scripts/cgroup-container-metrics.py`) that replaces cAdvisor's missing series.
  Phase 4, and [`PODMAN_MIGRATION_PLAN.md`](PODMAN_MIGRATION_PLAN.md) §10.
- **`dnf-automatic`**, the equivalent of the current host's unattended upgrades.

