# Shared technical contract inventory

| Contract | Owners at audit | Disposition |
| --- | --- | --- |
| NFC/trim/blank normalization and 8,000-character free-text cap | backend kernel; frontend web kernel | Identical algorithms; one implementation in technical-support, existing binding APIs delegate. |
| Observation request-target key mapping, query removal and UUID/numeric low-cardinality segments | backend platform; frontend observability; ingest observability | Identical algorithm; technical-support owns it and each application retains its component adapter. |
| Client-request observation convention | backend platform; ingest observability | Transport/framework adapter; retains local registration and invokes the shared scrubbing contract. |
| Sandbox profile guard | backend exchange; frontend security; ingest assembly | Application profile/security wiring; policy and target bean lifecycle remain local. |
| Tracing-enabled metric | all three apps | Small application-environment adapter and registration; no new generic runtime abstraction. |
| Keycloak trust support | backend identity; ingest relay | Application-specific client/TLS configuration and ownership; retain separate wiring. |

The selected library contains no domain types and no Spring component/security configuration.
Micrometer observation is its only runtime API dependency and is already in every carrier. Its JAR
is shipped inside each boot jar, with the existing build/verification/SBOM policies; logging-support
is not widened. ADR-0366 records the packaging decision. No runtime or heap improvement is asserted
from source deduplication.

The first local Java 25 build produced a 4,517-byte `technical-support` JAR containing the two
algorithm classes. This measures that compiled snapshot's packaging footprint, not heap or startup
cost. The existing application runtime already supplied its Micrometer API dependency.
