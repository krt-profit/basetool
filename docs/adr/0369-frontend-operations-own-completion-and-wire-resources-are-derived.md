# ADR-0369 - Frontend operations own completion and wire resources are derived

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** @greluc
- **Related:** REQ-FE-002, REQ-FE-029, REQ-FE-031, REQ-FE-032, REQ-FE-035, REQ-FE-036, REQ-FE-037; #3267

## Context

The frontend's persistence result and its display refresh have different failure boundaries.
Protecting only `ClientResponse` headers releases concurrency and timing observations before a body
finishes. Materializing upload and export files also makes retained memory depend on complete file
sizes. Static assets and manually mirrored resources add independent duplicate maintenance.

## Decision

`BackendCallPolicy` wraps the complete request/decoded-result publisher in the backend kernel.
Safe reads alone may retry; HTTP client refusals retain their status without retry or circuit-failure
accounting. Explicit data-export response budgets cover the full operation. Binary downloads use a
single protected transfer with bounded body prefetch and blocking output on bounded-elastic workers.
Transfers never retry after output could be visible. Backend refusal precedes download headers;
post-commit interruption reaches the existing disconnected-client handler. P4K relays a multipart
resource and lets its writer close the stream.

`krtFetch` reports callback failures through the existing client-error beacon and returns
`refreshFailed` separately from a successful write. Keyed JSON reads retain cancellation through
body parsing. Reauthentication and consent navigation use `safeSameOriginUrl`. Read fallbacks keep
their explicit section contract; mutations propagate backend errors.

The page layout declares optional component capabilities. Their scripts retain their static tag
order and import maps; picker, filter and overflow styles retain the existing cascade layers. An
inventory check includes fragment trees, static imports and dynamically inserted domain fragments.
Mission write adapters each own one operation family, sharing conversion and binding helpers.

The German bundle is canonical; Gradle copies it deterministically to the default runtime resource.
A small OpenAPI-to-record pilot generates `PurchasingVersionRequest` into build output, preserving
its package, validation and JSON shape. Its typed Gradle task uses the JVM and Gradle's bundled JSON
parser, so main-source generation has no platform-specific Node executable dependency. Existing DTO
parity checks remain binding; unsupported generator shapes fail rather than erase types. The Alpine
image check also runs when the generator implementation or frontend build registration changes.

## Consequences

Call duration and concurrency accounting include body consumption and decoding. A saved mutation
whose display refresh failed remains saved and is never automatically repeated. Streaming retains
request/security context on the initiating servlet thread while body writes use bounded workers.
Large-transfer performance claims still require reproducible heap measurements.

Page capability declarations require maintenance when new controls or fragment families appear.
Wire generation is deliberately incremental; the remaining manual DTOs retain their drift guards.
The default translation bundle remains packaged, so locale fallback keeps its German behavior.

## Alternatives considered

- Buffering bodies inside a response filter keeps heap proportional to document size.
- Retrying a failed display refresh together with its write can duplicate a completed mutation.
- Removing the default bundle changes fallback behavior for unsupported locales.
- A framework, bundler or language migration is unnecessary for these bounded ownership changes.
