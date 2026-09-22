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

## Amendment 1 — the hairpin was made to work, for IPv6 — 2026-09-22

*"Make the hairpin work"* is listed below as a rejected alternative, on the grounds that it was not
a configuration this deployment had and that a cutover window is not the place to measure one. It
was measured afterwards, on the testing host, and it works.

**What the alias could not do.** `host-gateway` is IPv4 only. Podman implements it by starting the
rootless netns's pasta with `--map-guest-addr 169.254.1.2` — one address, one family — so an alias
gives a container an A record and nothing else. The blackbox modules `http_2xx_ipv6` and
`http_2xx_or_401_ipv6` pin `preferred_ip_protocol: ip6` with `ip_protocol_fallback: false`, so they
had no address at all and failed locally. `EdgeIpv6Unreachable` then fired about the monitoring
rather than about the edge, against an edge serving IPv6 perfectly well — measured from the host
itself the same day: `302`, `302`, `401` in tens of milliseconds.

**What was measured** on `10.9.0.15`, Rocky 10.2 and podman 5.8.2, the same versions production
runs, in a throwaway rootless namespace so the running stack was never touched:

| question | answer |
| --- | --- |
| Does `containers.conf`'s `pasta_options` reach the **rootless netns** pasta, or only `--network=pasta` containers? | It reaches it. |
| Does a v6 `--map-guest-addr` work? | Yes — the mapped address went from `unreachable` to `open`. |
| Does podman **append** its own `--map-guest-addr 169.254.1.2`? | **No — it replaces it.** In the run that set only the v6 mapping, `host-gateway` went from `open` to `unreachable`. |
| Do both work together? | Yes. Both families open, in one container, in the same second. |
| Does a real request work, not just a connect? | `https://<name>/` answered **200** and `/healthz` **302** over the mapped v6 — byte-identical to its v4 twin. |

**The decision.** `containers.conf` gains `pasta_options` naming **both** addresses, and the alias
drop-in gains a second `AddHost` block pointing the same public names at the v6 one. Three things
follow, and each is a trap avoided rather than a preference:

1. **The IPv4 entry is restated, not inherited.** Podman replaces the argument rather than adding
   to it, so omitting `169.254.1.2` from `pasta_options` breaks every alias in this ADR at once —
   the backend's issuer lookup, Grafana's sign-in and all four IPv4 probes.
2. **Only services with an IPv6 route get the v6 alias**, which today means `blackbox-exporter`
   alone, on `net-blackbox-v6`. An AAAA record in a container with no v6 route is a resolver trap:
   Go's RFC 6724 sorting puts an unreachable IPv6 address ahead of a reachable IPv4 one, which has
   already cost this deployment a day.
3. **The address is a ULA chosen to read as the v4 one's twin** (`fd00:169:254::2`) and must not
   overlap any network in the stack — `fd00:bb::/64` and `fd00:28:*::/64` are taken.

**What this does not change.** The probe still does not leave the machine; it reaches haproxy
through a mapping rather than through public DNS and internet routing, exactly as the IPv4 probe
does. What it restores is parity between the two families and an alert that means something.

`containers.conf` is therefore no longer empty, and the paragraph documenting its emptiness stays —
the question it answers (*"shouldn't this set `rootless_port_forwarder`?"*) is still asked and the
answer is still no. A different setting arrived; that one did not.

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

- ~~**Make the hairpin work.**~~ **Accepted for IPv6 on 2026-09-22 — see Amendment 1.** The
  reasoning here was right about the method and about the timing, and wrong only about the verdict:
  it does mean giving pasta a host mapping in a `containers.conf` the role kept deliberately empty,
  and a cutover window is not where that gets measured. Measured afterwards on the testing host, it
  works — so the IPv6 half of the problem is solved this way and the IPv4 aliases stay, because
  podman replaces its own mapping rather than adding to it and the aliases are what carry v4.
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
