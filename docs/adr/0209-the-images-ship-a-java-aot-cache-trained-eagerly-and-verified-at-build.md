# ADR-0209 — The images ship a Java AOT cache, trained on an eager context refresh and verified at build time

- **Status:** Accepted — amended 2026-09-23 (Amendment 1: the training run unsets the release builder's `OTEL_*` variables and stores no machine code)
- **Date:** 2026-09-23
- **Deciders:** @greluc (IMG-MOD-11, IMG-PERF-12, IMG-CI-13 and IMG-SIMP-14 approved with the
  improvement audit of 2026-09-22; "adopt the AOT cache only if readiness is not worse")
- **Amends:** [ADR-0180](0180-compact-object-headers-on-java-25.md) — its object-layout invariant now
  binds an AOT cache instead of an AppCDS archive, and a mismatch is detected instead of silent
- **Related:** `REQ-OPS-030`, `REQ-OPS-031`
  ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · `REQ-OBS-007`
  ([`observability.md`](../specs/observability.md)) · `docker/app/Dockerfile` ·
  `monitoring/loki/rules/fake/basetool-log-alerts.yml` (`JvmStartupCacheRejected`) ·
  `scripts/check-object-layout-parity.py` · `scripts/check-loki-rule-signatures.py`

## Context

Since the Alpine images, every app image baked a **dynamic AppCDS archive**: a training run in the
runtime stage (`-XX:ArchiveClassesAtExit=/app/application.jsa -Dspring.context.exit=onRefresh
-Dspring.main.lazy-initialization=true …`), `|| true` behind it, and an entrypoint
`sh -c 'if [ -f /app/application.jsa ]; then exec java -XX:SharedArchiveFile=… ; else exec java …; fi'`.
ADR-0180 added the one invariant this depends on — the object layout of the training run and of
the runtime must agree — and recorded that a mismatch is silent: the JVM refuses the archive and
starts without it.

The improvement audit of 2026-09-22 asked three questions about that design. Reproducing the
training runs on 2026-09-23 answered them, and the answers were worse than the questions:

1. **The backend and frontend training runs did not complete — and never had.** Both died at
   `Could not load store from 'classpath:keystore.p12'`: `server.ssl` is on in every profile and the
   keystore is mounted at run time, never baked. The context aborted while the web server was being
   created, and `ArchiveClassesAtExit` dumped whatever the JVM had loaded until then. Only the ingest
   run, which already passed `-Dserver.ssl.enabled=false`, trained on a refreshed context. Nothing
   noticed, because the `|| true` and the `if [ -f … ]` fallback were built precisely so that nothing
   would (IMG-CI-13).
2. **Lazy initialisation hid most of the application from training** (IMG-PERF-12). Under
   `lazy-initialization` a refresh instantiates only what the web server and its filters need.
3. **Nothing checked that a produced archive would be accepted** — the ADR-0180 mismatch, or any
   other reason the JVM refuses a cache (IMG-CI-13).

Java 25 offers a successor to dynamic AppCDS, the **AOT cache** (JEP 483, with JEP 514's one-step
`-XX:AOTCacheOutput` and JEP 515's method profiles): the training run records the classes it loaded
*and linked*, plus profiling data, and the runtime maps them with `-XX:AOTCache` (IMG-MOD-11). It
brings a switch the AppCDS path never had: `-XX:AOTMode=on` makes a JVM that cannot use the cache
refuse to start, instead of warning and carrying on.

## Decision

1. **The images ship a Java AOT cache**, `/app/app.aot`, created in the runtime stage of
   `docker/app/Dockerfile` by `java -XX:AOTCacheOutput=/app/app.aot -XX:+UseCompactObjectHeaders
   -Dspring.context.exit=onRefresh …`, and loaded by an **exec-form** entrypoint,
   `["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]`. No shell sits between the
   container runtime and the JVM any more.
2. **The training run refreshes the whole context — no lazy initialisation.** Each module replaces
   exactly the external service it cannot reach in a build container, and nothing else:
   - all three: `server.ssl.enabled=false`;
   - backend: a JDBC URL on the reserved, never-resolving `aot-training.invalid`, Hikari's
     `initialization-fail-timeout=-1`, Flyway off, `ddl-auto=none`, Hibernate given its dialect and
     `hibernate.boot.allow_jdbc_metadata_access=false`, and a JWK set URI instead of the issuer (a
     decoder built from a JWK set URI fetches lazily, one built from an issuer at creation);
   - frontend: the `keycloak` registration pointed at a stub provider with explicit endpoints
     while `provider.keycloak.issuer-uri` stays set for the two beans that read it by `@Value`, a
     backend URL, a never-resolving Redis host, and `app.session.configure-keyspace-notifications=false`
     — a new property, default `true`, that returns Spring Session's `NO_OP` action instead of the
     startup `CONFIG GET`, the one call of the refresh that dials Redis (`RedisSessionConfig`);
   - ingest: a JWK set URI instead of the issuer.
   It trains the **default** profile, as before. The `prod` profile's startup guards
   (`JwtAudienceStartupCheck` and others) refuse a configuration without real audiences, and
   stubbing each of them would train a context no deployment runs either.
3. **Nothing about the cache is best-effort.** The image build fails when the training run does not
   complete the refresh (its log is printed with the reason), when no cache is written, or when a
   second start under `-XX:AOTMode=on` with the image's object layout refuses the cache. A bean that
   starts needing a live service at refresh time therefore breaks the image build with a message
   that says so, and gets a stub.
4. **The layout is checked where it can break, three times:** before merge,
   `scripts/check-object-layout-parity.py` (in `repo-lint.yml`) compares the Dockerfile's `layout=`
   with every `JAVA_TOOL_OPTIONS` in the compose files and `quadlet/env.d`; at build time, decision 3;
   at run time, the Loki rule **`JvmStartupCacheRejected`** (warning) fires on the JVM's
   `Unable to use AOT cache` / `Loading static archive failed` lines in the `<svc>-stdout` streams —
   which is what the ADR-0180 rollback lever `IRI_EXTRA_JAVA_OPTS=-XX:-UseCompactObjectHeaders`
   produces on purpose. `scripts/check-loki-rule-signatures.py` holds the rule to the verbatim lines.
5. The garbage collector is **not** part of the invariant: a cache trained under G1 is accepted by
   the ingest's Serial collector (verified), so the training run names no collector.

## Measurements (2026-09-23, Docker Desktop, 32 cores, the isolated E2E stack's throwaway credentials)

Time-to-readiness is measured from `docker compose up -d --no-deps <svc>-dev` until
`/actuator/health/readiness` answers 200 (polled every 200 ms); "Started" is Spring Boot's own
`Started … in N seconds`. Five alternating runs per variant after one discarded warm-up (the
backend's first start runs every Flyway migration); the median is reported. *AppCDS* is the image as
it shipped until this change (lazy, and for backend and frontend the half-trained archive described
above); *AOT* is this decision.

| Image    | Readiness, AppCDS | Readiness, AOT | Change | `Started … in`, AppCDS | `Started … in`, AOT |
|----------|------------------:|---------------:|-------:|-----------------------:|--------------------:|
| backend  |           14.08 s |        11.76 s |  −16 % |                12.31 s |              9.70 s |
| frontend |            7.18 s |         5.43 s |  −24 % |                 5.45 s |              3.61 s |
| ingest   |            6.16 s |         4.74 s |  −23 % |                 4.38 s |              3.06 s |

Every run of both variants reached readiness, and none printed a cache rejection. The five runs of
each cell lay within ±0.9 s of their median. Earlier, inside the image, a bare start of the ingest
context (`spring.context.exit=onRefresh`) took 6.5 s without a cache and 3.2 s with the AOT cache —
the same order as the table's gain.

**Readiness is not worse — it is better on all three**, which was the owner's condition for adopting
the AOT cache. The price is paid at build time and in bytes:

| Image    | Archive, AppCDS (raw / gzip) | Cache, AOT (raw / gzip) | Image size, AppCDS → AOT | Training step in the build |
|----------|-----------------------------:|------------------------:|-------------------------:|---------------------------:|
| backend  |             84.8 MB / 20.8 MB |      195.9 MB / 42.5 MB |         598 MB → 728 MB |                8.8 s → 57.2 s |
| frontend |             68.6 MB / 17.0 MB |      132.3 MB / 29.7 MB |         555 MB → 629 MB |               10.3 s → 25.0 s |
| ingest   |             84.7 MB / 21.1 MB |      118.7 MB / 26.7 MB |         530 MB → 567 MB |               10.8 s → 19.4 s |

A pull downloads the gzip column: about 22 MB more for the backend image, 13 MB for the frontend and
6 MB for the ingest. The longer training step is the eager refresh plus the verifying second start;
in the same change the Gradle step got shorter by switching the image build to `bootJar`
(BLD-PERF-01: backend 55 s → 39 s, frontend 50 s → 28 s, ingest 29 s → 24 s), so the whole image
build grows by about half a minute for the backend and not at all for the other two.

## Consequences

- **The images start faster and say so when they cannot.** A refused cache used to be invisible;
  now the build refuses to produce one, the parity check refuses a mismatched merge, and a runtime
  override is an alert. An image started **bare** — `docker run` with no `JAVA_TOOL_OPTIONS` — runs
  JDK 25's default layout (compact headers off) and therefore starts without the cache, exactly as
  it started without the AppCDS archive before; every compose service and Quadlet unit sets the flag.
  Putting it into the entrypoint instead stays rejected for ADR-0180's reason: a command-line flag
  beats `JAVA_TOOL_OPTIONS` and would take away the operator's rollback lever.
- **Training is now a contract with the application code.** A new bean that reaches a database,
  Keycloak, Redis or the backend while the context refreshes fails the image build until it gets a
  stub. That is deliberate — the alternative is the silent half-training this ADR found — and
  `CONTRIBUTING.md` (*Building an app image*) tells a contributor what to do. The image build runs in
  `release-images.yml` and, on `e2e`-labelled pull requests, in `e2e.yml`; it is not part of `ci.yml`,
  so a pull request without the label first meets this failure on `main`.
- **The images are larger** by the difference in the cache sizes above, and the image build is longer
  by the second (verifying) start. The build still runs once per commit (ADR-0137).
- **`app.session.configure-keyspace-notifications`** exists in the frontend. Every deployment keeps
  the default `true`; only the training run sets it `false`.
- **Without a shell in the entrypoint**, one of the two things that kept the images off a
  shell-less (distroless) base is gone; the BusyBox `wget` healthcheck is the other.
- **A Temurin bump owes `JvmStartupCacheRejected` the same re-check** as `JvmNativeThreadExhaustion`
  (`monitoring/README.md`, *After a Temurin bump*): the rejection wording belongs to HotSpot.

## Alternatives considered

- **Keep AppCDS and only fix the training run** (TLS off, eager refresh, a hard check). It would
  have removed the silence and most of the loss, but kept the shell entrypoint and the dynamic
  archive's weaker model (classes only, no linking, no profiles), and it offers no `AOTMode=on` to
  prove acceptance with. With the AOT cache available on the LTS the stack already runs, fixing the
  predecessor would have been the same work for less.
- **Train under the `prod` profile.** Closer to production, but its startup guards reject a
  training configuration by design, and neutralising each guard for the build would make the guards
  themselves conditional.
- **Keep `lazy-initialization`.** Trains a small fraction of the application; the whole point of the
  change is the classes lazy initialisation skipped.
- **A stub server for the OIDC discovery document** (the JDK's `jwebserver`) instead of the stub
  provider. It picks a content type by file extension and the discovery path has none, so the
  document would not go out as JSON; and it would add a process to the build for what a handful of
  properties achieve.
- **Spring AOT (`processAot`), CRaC or a GraalVM native image.** Each changes how the application is
  built or run far beyond a startup cache, and none was asked for.
- **Warn instead of failing the build** on a training or acceptance failure. A warning printed inside
  a BuildKit step reaches no one — it is exactly how the half-trained archives went unnoticed.

## Amendment 1 (2026-09-23) — nothing from the build machine goes into the cache

Two failures, found the day this ADR shipped, both invisible to the local builds its measurements
were taken with:

- **The release builder's tracing variables.** `release-images.yml` builds with a
  `docker-container` BuildKit, and that builder hands every `RUN` step `OTEL_TRACES_EXPORTER=otlp`,
  `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc` and
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=unix:///dev/otel-grpc.sock`. Spring Boot read them and built a
  gRPC span exporter, which rejects a `unix://` endpoint, so every module's training refresh died at
  `otlpGrpcSpanExporter` and the first release build of this Dockerfile failed in all six build jobs.
  Docker Desktop's default builder, `docker build` in `e2e.yml` and every measurement above inject
  nothing. **The training `RUN` now unsets every `OTEL_*` variable** before the JVM starts — all of
  them, not the three by name, so a BuildKit that adds a fourth cannot bring the failure back.
- **Machine code in the cache.** Creating a cache switches JDK 25's `AOTAdapterCaching` on by
  ergonomics, so the cache also held the interpreter/compiled-code adapters — machine code generated
  for the CPU of the runner that built the image (723 entries in the ingest cache). The first E2E run
  on `main` with these images crashed the backend at start on 10 of 16 runners with `SIGILL` in
  `~AdapterBlob`, after `Saved blob's name … is different from the expected name` and `Failed to link
  AdapterHandlerEntry … in the AOT code cache`; the six runners that started it share the build
  runner's CPU, and nothing guarantees that a host does. **Training now runs with `-XX:-AOTAdapterCaching
  -XX:-AOTStubCaching`**, and the verifying start logs `aot+codecache+init` and **fails the build unless
  the JVM reports `AOT Code Cache is empty`** — so a Temurin update that starts caching another kind of
  code is refused rather than shipped. What stays in the cache — parsed and linked classes and method
  profiles — is data and does not depend on the CPU.

Neither failure reached a host: no image from this Dockerfile had been published.

Readiness does not suffer. A bare context start of the ingest image (`spring.context.exit=onRefresh`,
seven alternating runs, median, including `docker run`): **7.54 s without a cache, 4.85 s with the
cache as first shipped, 4.75 s with the amended cache.** The adapters were never where the gain came
from. The cache sizes are unchanged within 1 % (ingest 117.6 MB, backend 194.1 MB).

`CONTRIBUTING.md` (*Building an app image*) now tells a contributor who changes the training `RUN`
or the base image to build once with a `docker-container` builder, which is the one release uses.
