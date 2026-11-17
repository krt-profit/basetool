# ADR-0366 — Shared technical algorithms have one runtime owner

Status: Accepted — amendment 1 (2026-11-17) adds the resilient Redis listener container

## Context

The applications maintained identical inbound-string normalization and observation-privacy
algorithms, guarded by source-copy parity tests. They have stable technical contracts and no domain
or application-bean dependency. Logging-support has a narrower log-hygiene responsibility.

## Decision

Add `technical-support`, a small Java library shipped inside the backend, frontend and ingest boot
jars. It owns inbound-string normalization and observation request-target scrubbing. Application
binding facades and component registration remain in their existing packages; they delegate to the
shared algorithms. The only runtime API dependency is Micrometer observation, already shipped by all
three applications, resolved through the existing Boot-managed line. The library reaches no domain.

Keep sandbox policy, application-specific tracing setup and trust-store/security wiring in their
applications. Their decisions and lifecycle differ, and consolidating them would widen the seam
beyond the selected pure algorithms. The inventory records these deliberate boundaries.

## Consequences

The application image build copies the library sources/build settings as shared inputs. SBOM
coverage records its three carriers rather than publishing another standalone artifact. Shared
contract tests and the existing application tests verify behavior and bean wiring. There is one
implementation to change; logging-support retains its existing responsibility and footprint.

## Amendment 1 — the resilient Redis listener container (2026-11-17)

ADR-0388 moves the frontend's live sync to a shared Redis the frontend no longer needs to start, so
the frontend needs what the backend's notification fan-out already had: a
`RedisMessageListenerContainer` that starts unsubscribed and retries instead of failing the context
refresh (ADR-0143 addendum). A second copy would drift from the first, so
`ResilientRedisMessageListenerContainer` moves from the backend's `platform.api` into this library,
package `de.greluc.krt.profit.basetool.technical.redis`.

It is a technical component with no domain and no bean registration; each application still builds
and wires its container. Spring Data Redis and SLF4J are `compileOnly`: every application that
uses the class ships both, and the library's own runtime dependency stays Micrometer observation.
