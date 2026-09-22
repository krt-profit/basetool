# ADR-0196 — A rootless host aliases its own public names to the container gateway

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc
- **Related:** ADR-0163 (rootless Podman) · ADR-0187 (the PROXY-protocol front end) ·
  ADR-0188 (the host bootstrap is an Ansible role) ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-005`, `REQ-OBS-008`) ·
  `ansible/roles/basetool_host/tasks/50-podman.yml` · `scripts/generate-quadlet.py` (`VALUE_VARS`)

## Context

Several containers dial this deployment's **own public names**:

| container | why |
| --- | --- |
| `backend`, `frontend`, `ingest` | they resolve the OIDC issuer at startup — `https://<public>/auth/realms/iri` |
| `grafana` | its OAuth **token exchange** is a back-channel call to the same origin |
| `blackbox-exporter` | it probes all four public vhosts |

Under Docker this needed nothing. Public DNS resolved the name to the host's own address, the
packet left the bridge, the daemon's NAT turned it around, and the edge answered. Nobody had cause
to write the mechanism down, and the Ansible role's default said so explicitly: *"Production needs
nothing here: its public name resolves to the host that serves it."*

**On a rootless host that is false.** Measured on the production host 2026-09-22, from inside a
container:

| target | result |
| --- | --- |
| its own public IPv4, ports 22 / 80 / 443 | refused |
| its own public IPv6, port 443 | refused |
| the netavark bridge gateway, port 443 | refused |
| `host-gateway` (`169.254.1.2`), ports 22 / 80 / 443 | **open** |
| Cloudflare DNS over IPv6, port 443 | open |

The last row is the one that names the cause. Egress works, on both protocols. What fails is
specifically a connection to *this machine's own address*: the container networks live inside the
service user's network namespace and outbound leaves through **pasta**, which does not fold such a
connection back into the host. `host.containers.internal` exists for exactly this, and podman maps
it for IPv4 only.

It cost the cutover three separate investigations before the common cause was visible — the backend
refusing to start, Grafana answering *"Failed to get token from provider"*, and every blackbox probe
reporting `probe_success=0` against endpoints that were serving. Each reads as a different outage.

The mechanism already existed and was unused. `generate-quadlet.py` resolves
`IRI_KEYCLOAK_HOST_ALIAS` to its no-op default at generation time — deliberately, because the units
are one promotable artifact for every environment — names a systemd drop-in as the host-side
remedy, says it *"belongs to Ansible"*, and states the failure it prevents: *"a container timing out
against its own issuer at start-up — which reads as a Keycloak outage and is a missing line."*
`50-podman.yml` implements it. The variable was empty, because the default believed Docker's
behaviour was the platform's.

## Decision

**Every rootless host aliases the public names its containers dial to `host-gateway`**, through the
`10-host-alias.conf` drop-in the role already writes. Production included — the default is no longer
"empty is right for production".

Three consequences follow from making it real rather than notional:

1. **It is a list.** One `hostname:ip` was not enough: `blackbox-exporter` probes four names, and a
   single alias left three of them resolving to an address no container can reach.
   `basetool_host_public_name_alias` becomes `basetool_host_public_name_aliases`, and an inventory
   still carrying the singular **stops the run** — a string satisfies `| length > 0` just as a list
   does, and the template would otherwise emit one `AddHost` line per letter: a drop-in that
   parses, units that start, and nothing resolved.
2. **`grafana` and `blackbox-exporter` join the service list.** The generator emits `AddHost=` for
   the three JVM modules only, because only their compose services carry `extra_hosts`. These two
   have no route to an alias except the drop-in.
3. **The JVM truststore gets its own service list.** It shared the alias list while the two
   coincided; sharing it now would mount a Java truststore into a Go process.

`host-gateway` rather than a literal address: podman resolves it, and the generator already emits
`AddHost=alloy:host-gateway` for the host-native Alloy. The first entry must still match
`IRI_KEYCLOAK_HOST_ALIAS` in the host `.env` — `check-conformance.py` fails a host where the two
disagree silently, which is the whole reason that check exists.

## Consequences

**The probes no longer traverse public DNS or internet routing.** TLS, the certificate, haproxy, the
PROXY header, the edge, vhost routing and the application are all still exercised end to end, so
what a green `blackbox-http` means is nearly unchanged — but a DNS record pointed at the wrong
machine would no longer be caught by it.

Carried honestly: **this was already true on the Docker host.** Its hairpin kept the packet on the
machine too; the probe never left. What those probes proved was *"the name has a record and the edge
answers"*, and that is what they still prove.

**The IPv6 probe modules cannot work here at all**, and that is a real loss rather than a rewording.
`host-gateway` is IPv4-only, the host's public IPv6 is refused from a container, and the netavark
bridge's own gateway is inside the namespace where no host process listens — measured, all three.
`http_2xx_ipv6` and `http_2xx_or_401_ipv6` therefore fail with *"no suitable address found"*. The
options are recorded in [arc42 §11](../arc42/11-risks-and-technical-debt.md) rather than decided
here: give pasta an IPv6 host mapping (which would make **every** alias in this ADR unnecessary and
is the outcome worth testing), or move the IPv6 assertion to a host-level probe, which the host can
make — it reaches its own public IPv6 perfectly well.

**One fact now lives in two files.** The `.env` and the drop-in must agree, and
`env-reaches-the-units` is what keeps them agreeing. That is the same trade ADR-0187 made for
`EDGE_TRUSTED_PROXY`, and the same check enforces both.

## Rejected alternatives

- **Make the hairpin work.** It is the fix that would remove every alias, and it is not a
  configuration this deployment has: it means giving pasta a host mapping, in a
  `containers.conf` the role keeps deliberately empty and whose emptiness is itself documented. It
  is worth measuring on the testing host first, and doing it during a cutover window on the machine
  that serves is not measuring.
- **Point the alias at the edge container's address on a shared network.** It skips haproxy — so the
  PROXY header, the rate limiter's view of the client and the TLS the edge actually presents all go
  untested by the very probes that exist to test them — and the address changes whenever the
  network is recreated.
- **Resolve the names through the host's `/etc/hosts`.** Podman seeds a container's hosts file from
  it, so this looks equivalent and is worse: the host must resolve its own name to its own public
  address, which is precisely the address a container cannot reach. Measured 2026-09-22 — with both
  entries present, Go's RFC 6724 sorting demotes the link-local `169.254.1.2` **below** the global
  address and `blackbox-exporter` dialled the unreachable one, while the JVM modules, which do not
  sort, took the first line and worked. Two runtimes, one hosts file, opposite answers.
- **Leave production without an alias and carry the three failures.** They are not failures anyone
  would connect: a Keycloak outage, a Grafana login bug and a monitoring blind spot.
