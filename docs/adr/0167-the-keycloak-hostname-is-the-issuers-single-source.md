# ADR-0167 — The Keycloak hostname is the issuer's single source, and a gate says so

- **Status:** Accepted — implemented
- **Date:** 2026-09-13
- **Deciders:** Claude (analysis and implementation), @greluc (review)
- **Related:** [ADR-0166](0166-identity-moves-onto-the-app-origin.md) (which measured the failure
  this prevents, and moved identity to `/auth` on the app origin) ·
  [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) (the edge configuration whose
  `check-edge-nginx.sh` is the pattern this follows) · spec
  [`deployment-delivery.md`](../specs/deployment-delivery.md) `REQ-OPS-022` (amended)

## Context

The identity base URL is a literal on roughly **36 lines across 19 files** — the three service
templates in `docker-compose.yml` plus its `KC_HOSTNAME`, four compose overrides, the monitoring
stack's three Grafana OIDC URLs, Prometheus's probe targets, the three apps' `application*.yml`
fallback defaults, `.env.example`, and some twenty more in documentation and the README.

**Nothing compared any two of them.** `.env.example` stated the invariant as an instruction to the
reader — "SET BOTH OR NEITHER", the two values "must agree" — which is a comment, not a check. Two
variables existed where one fact does: `IRI_KEYCLOAK_HOSTNAME` (Keycloak's `KC_HOSTNAME`) and
`IRI_KEYCLOAK_ISSUER_URI` (validated by backend, frontend and ingest), each independently settable
and each independently wrong.

The failure that combination invites is the one [ADR-0166](0166-identity-moves-onto-the-app-origin.md)
measured on the pinned Keycloak 26.7 image. Keycloak's advertised base URL comes from `KC_HOSTNAME`
*and* `KC_HTTP_RELATIVE_PATH` together:

|    `KC_HOSTNAME`    | `KC_HTTP_RELATIVE_PATH` | serves at |      advertised issuer       |             |
|---------------------|-------------------------|-----------|------------------------------|-------------|
| `https://host`      | `/auth`                 | `/auth`   | `https://host/realms/…`      | **broken**  |
| `https://host/auth` | `/auth`                 | `/auth`   | `https://host/auth/realms/…` | **correct** |

The first row is silent in every place anyone would look. Keycloak answers on `/auth`, its discovery
document parses, and the container reports **healthy** — while backend, frontend and ingest each die
at start-up on `The Issuer "…/realms/iri" did not match the requested issuer "…/auth/realms/iri"`.
That reads as a backend fault, and it is a failed deploy. A first draft of ADR-0166 specified exactly
that combination, on the strength of the upstream reverse-proxy guide's wording; only running the
smoke stack caught it, and the root cause was a *second* file — `docker-compose.e2e.yml` — overriding
`KC_HOSTNAME` with an origin-only URL.

The repository already contained the better pattern, in Java. `SecurityHeaders.cspNonceHeaderWriter`
derives the logout `form-action` origin from the configured `issuerUri` instead of naming a host, and
its Javadoc says why — it states the *rule*, not the hostname. That class is the one place identity
moved through in ADR-0166 that **needed no edit at all**.

## Decision

**`IRI_KEYCLOAK_HOSTNAME` is the single source, and the issuer is composed from it.** The three
service templates in `docker-compose.yml` read:

```yaml
KEYCLOAK_ISSUER_URI: ${IRI_KEYCLOAK_ISSUER_URI:-${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online/auth}/realms/iri}
```

An operator moving the deployment to another domain sets one value, with its `/auth` path, and the
issuer follows at all three services. The pair can no longer be half-set, because there is no pair.

`IRI_KEYCLOAK_ISSUER_URI` is kept as an override rather than deleted: a deployment whose advertised
issuer genuinely differs from `KC_HOSTNAME` — one behind a rewriting proxy, say — has no other way to
say so, and keeping it means no existing `.env` changes behaviour. Setting it wins over the
derivation, and thereby opts that deployment out of the agreement check below; that is stated in
`.env.example` rather than left to be discovered.

**The shape constraint is part of the contract.** The hostname carries the `/auth` path, because
Keycloak's `KC_HTTP_RELATIVE_PATH` and the hostname's path component have to agree (the table above).
So the composition is `${IRI_KEYCLOAK_HOSTNAME}/realms/iri` and not an origin plus a path assembled
here.

**And the invariant is gated rather than documented.** `scripts/check-keycloak-issuer.py` runs in
`repo-lint.yml` and asserts four things across all seven stack combinations this repository starts:

- **A** — a full-URL `KC_HOSTNAME` carries exactly the path `KC_HTTP_RELATIVE_PATH` serves under.
  A *bare* hostname is exempt, and that is not a loophole: scheme, port and context path all come
  from the request when no full URL is configured, which ADR-0166 measured separately for the test
  stack.
- **B** — every service's issuer is the one this Keycloak will advertise.
- **C** — the services within one stack agree with each other.
- **D** — the `application*.yml` fallback defaults name the issuer `docker-compose.yml` deploys, so
  an app started without `KEYCLOAK_ISSUER_URI` does not quietly trust a retired one.
- **E** — Grafana's three OIDC endpoints sit on that issuer. They derive from the same variable now
  (see below), and the rule checks both that they still do and that they follow an override.
- **F** — the four Prometheus identity probe targets name the deployed identity base, and all three
  required probes are still present.

**The monitoring stack derives from the same variable.** Grafana's `generic_oauth` has no discovery
option, so `docker-compose.monitoring.yml` names the authorize, token and userinfo URLs one at a
time — the realm base appeared three times, in three places that could each drift alone. All three
now share one `${IRI_KEYCLOAK_HOSTNAME:-…}` expansion. That works because both compose projects run
with the same `--project-directory` (`/var/iri/code`) and compose reads `.env` from there, so one
value feeds the app stack and the monitoring stack alike. A monitoring stack started from elsewhere
gets the production default, exactly as before. Rendered output with the variable unset is
byte-identical to what shipped.

**`prometheus.yml` is the one identity surface that cannot be derived, so it is compared.** Nothing
interpolates that file — it is loaded as written and validated by `promtool` — so a domain move never
reaches it on its own. Rule F therefore checks it in **both** directions: no target under the identity
path may sit on another base, *and* the three required probes (the discovery document, `/auth/health`,
`/auth/metrics`) must still exist. The second half was not in the first draft, and the regression
suite is what found the hole: a probe relocated to another host **and** another path shape leaves the
scan entirely, so the one-directional rule reported success over a shrinking list. The path test is
segment-exact rather than a prefix match, for the same reason the edge uses `location = /auth` plus
`location /auth/` — a prefix swallows `/authorize` and `/authors`, and the suite keeps a negative
control that proves an ordinary `/authors` route is not mistaken for a stray identity probe.

**The gate renders, it does not parse.** It runs `docker compose config` — compose's own
interpolation, the same code path the deploy uses — and checks the result. Re-implementing the nested
`${A:-${B:-default}}` in the checker would be asserting a copy of the thing under test against
itself. This is `check-edge-nginx.sh`'s principle applied one layer up: render the real artefact,
then hand it to the real tool.

Rendering is also what makes the *derivation* testable at all. The `prod-hostname-override` scenario
sets `IRI_KEYCLOAK_HOSTNAME` alone and requires all three apps to move with it. Restore the issuer to
a second independent literal and every other check still passes — today's two values agree — while
that one scenario fails immediately. Its regression suite,
`scripts/check-keycloak-issuer.test.sh`, breaks the configuration each way in turn and requires the
gate to report it, and runs **first** so the gate cannot pass vacuously.

> The derivation itself shipped with the identity move in
> [#1870](https://github.com/krt-profit/basetool/pull/1870) without an ADR of its own. Recording it
> here, with the enforcement and the documentation it needed, is the repair rather than a second
> decision.

## Alternatives considered

**A start-up assertion in the three apps.** Rejected because it already exists and is exactly the
symptom: Spring Security compares the discovery document's `issuer` against the configured
`issuer-uri` and refuses to start when they differ. That *is* the `did not match the requested
issuer` message. Adding another would move nothing earlier — the information arrives at deploy time
either way, on a host, to a person mid-rollout. The value is in failing on a pull request, where a
configuration mistake costs a CI run instead of a rollback.

**Leave two variables and document harder.** That is the state this ADR replaces. `.env.example`
already carried the rule in capitals, and the rule was still broken once — in a file
(`docker-compose.e2e.yml`) whose author was implementing the very ADR that measured it.

**Compute the issuer and drop the override.** Tempting, and one variable is better than two. But
`KC_HOSTNAME` is what Keycloak *advertises*, and a deployment can legitimately advertise something
its own container was not told — the override is the escape hatch for that, and removing it would
trade a checkable invariant for an unrepresentable configuration.

**Parse the YAML statically instead of rendering it.** Cheaper, needs no Docker in CI, and wrong for
the reason given above: the thing most worth checking is compose's interpolation, and a hand-rolled
copy of it cannot check itself. `repo-lint.yml` already runs Docker for the Prometheus, edge and acme
gates, so the cost is one more job on a runner that has it.

**Template `prometheus.yml` so its probe targets could be derived too.** Rejected. It is a static
file loaded straight by Prometheus and validated by `promtool` in CI; adding a render step would put
a templating layer in front of the one monitoring config that is currently readable exactly as it
runs, and would have to be threaded through `deploy.sh` and the promoted bundle. The four identity
targets are compared against the deployed base instead — see the decision above. A literal that
cannot be derived should at least be checked.

**Derive Grafana's endpoints from a discovery URL instead of three literals.** Not available:
Grafana's `generic_oauth` provider takes `auth_url`, `token_url` and `api_url` individually and has
no OIDC discovery option. One expansion shared by all three is as far as that provider allows.

## Consequences

**A domain move is one edit and a green gate**, instead of a hand-audited sweep across 19 files.
`IRI_KEYCLOAK_HOSTNAME` now feeds the three app issuers and Grafana's three OIDC endpoints; the
`application*.yml` fallbacks and the four Prometheus targets cannot follow a variable, and are
compared against it instead. Every place where something has to agree with the issuer Keycloak
advertises is now either derived from it or checked against it.

**CI gains a job that needs Docker.** It renders seven app stacks plus the monitoring project twice,
reads `prometheus.yml` and the six Spring configs, and self-tests first; measured at well under the
job's 10-minute ceiling.

**The gate pins two deliberate asymmetries so they stay deliberate.** `docker-compose.android.yml`
creates a split-horizon on purpose — Keycloak advertises `127.0.0.1` for the emulator and only
`backend-dev` follows it, which is why the web frontend's own login stops working while that override
is in effect. The checker asserts *both* halves of that arrangement rather than skipping the stack,
so the day it changes, it is reported. The same is true of the explicit-issuer override, which
disables rule B by design and says so in its output.

**The checker holds one copy of the production base URL**, to build its expectations from. That is
itself a 37th literal, so it is pinned back to `docker-compose.yml` by a self-check that fails with
the file and line to edit — the gate cannot drift away from the thing it gates.
