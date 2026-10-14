# ADR-0256 — The edge limits static assets and the token endpoint in zones of their own

- **Status:** Accepted
- **Date:** 2026-10-14
- **Deciders:** @greluc
- **Related:** spec [`security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-023`) ·
  [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (`REQ-FE-035`) ·
  [ADR-0112](0112-edge-real-client-ip-restore-native-ipv6.md) ·
  [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) ·
  [ADR-0187](0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md) ·
  [ADR-0250](0250-scripts-are-es-modules-behind-a-per-page-import-map.md)

## Context

The edge counted every request of a client in one zone: 20 r/s, burst 80, per IPv4 address or IPv6
`/64`. Since the scripts became ES modules (ADR-0250), the order detail page fetches 35 modules
plus stylesheets, fonts, flags and logos — about 50–60 requests on a cold load. A member who
switched between orders and missions every one or two seconds with the browser cache disabled
received 107 refusals on hashed assets within three minutes: the same 72 asset URLs were fetched
up to 26 times each, and every page load drained the bucket the pages and the API share. Each
failed module also posted a client-error beacon into the same bucket.

The assets are content-hashed and served `Cache-Control: immutable`; with a working cache a browser
requests each one once per release. They are cheap to serve and carry no state. The token endpoint,
on the other hand, shared the general zone's rate and only had a burst of its own, so any raise of
the general rate would also have raised what a password-spraying client may send to it.

## Decision

1. **Three zones on the same key** (`$krt_limit_key`), defined in `docker/edge/conf.d/00-maps.conf`:
   - `krt_req_perip` — **50 r/s, burst 150**, applied to every vhost by `include/limits.conf`;
   - `krt_static_perip` — **200 r/s, burst 600**, for the frontend's `/js/`, `/css/`, `/fonts/`,
     `/images/` and `/logos/` trees, in a regex `location` of the frontend vhost;
   - `krt_token_perip` — **20 r/s, burst 10**, for the Keycloak token endpoint, unchanged in effect.
2. **A location's own `limit_req` replaces the vhost's**, so a static request counts in its own zone
   only and never drains the pages' and the API's budget. The static zone stays bounded: assets are
   proxied to the frontend, which still has to answer them.
3. `limit_conn` (500 per client) and the 429 status are unchanged. `/auth/resources/` is not a
   static tree in this sense and stays in the general zone.

## Consequences

- Quick navigation with the cache disabled, or the first pages after a release, no longer draws 429s
  on assets; pages and the API tolerate 2.5 times the old sustained rate.
- A flooding client now gets 150 instead of 80 requests through before the first refusal. The
  backend's per-subject Bucket4j limiter (REQ-SEC-011, REQ-SEC-032, REQ-SEC-033) stays the precise
  layer behind the edge.
- The opt-in conformance load check sends 400 requests from 64 workers so that it still crosses the
  larger burst.
- Brute-forcing the token endpoint is exactly as limited as before.
