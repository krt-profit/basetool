# ADR-0193 — One logging facade, enforced, with `keycloak-spi` excepted on purpose

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** @greluc
- **Related:** ADR-0192 (the Lombok/annotation sweep that surfaced this) · `CLAUDE.md` → *Java
  conventions* · [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-*`) ·
  `scripts/check-logging-facade.sh`

## Context

The repository's logging was uniform **by habit**, not by rule. A census across every module and
source set:

| How the logger is obtained | Count | Where |
| --- | --- | --- |
| Lombok `@Slf4j` | 301 files | `backend`, `frontend`, `ingest` (`src/main`) |
| Lombok `@JBossLog` | 5 files | `keycloak-spi` (`src/main`) |
| Hand-written `LoggerFactory.getLogger(...)` | **0** | `src/main` — none, anywhere |
| Hand-written `LoggerFactory.getLogger(...)` | 33 files | `src/test` — all **log capture**, see below |
| Lombok `@Slf4j` | 2 files | `src/test` |
| Log4j / Log4j2 / `java.util.logging` / commons-logging / Flogger | **0** | nowhere |
| `System.out` / `System.err` | 0 in `src/main`, 12 files in `src/test` + `src/e2e` | — |

So there was exactly one split — SLF4J everywhere except JBoss Logging in `keycloak-spi` — and
nothing anywhere stopped the 307th file from reaching for `@Log4j2`. The convention lived in
`CLAUDE.md` and in reviewers' heads, and a logging facade that drifts is not discovered until a log
line goes missing in production.

**The 33 test files are not a violation and must never be treated as one.** They do not log; they
**capture**. A log assertion needs the `Logger` *instance* of the class under test so it can attach
a `ListAppender` — or, in `PageNotFoundLogLevelTest`, read the configured level back off it.
`@Slf4j` is the opposite operation and cannot express it. Any rule that flagged them would be wrong
33 times out of 33.

**Could `keycloak-spi` move to `@Slf4j` and remove the split entirely?** Technically yes, and this
was checked rather than assumed: `org.jboss.slf4j:slf4j-jboss-logmanager:2.0.2.Final` and
`org.slf4j:slf4j-api:2.0.17` are both on that module's compile classpath. But look at *how* they
arrive —

```
keycloak-services → keycloak-config-api → quarkus-opentelemetry → quarkus-core
                                                                    └── slf4j-jboss-logmanager
```

— the SLF4J→JBoss-LogManager bridge is a transitive of an **optional Quarkus feature module**, in a
JVM this project does not control and does not ship. And an SLF4J call with no binding does not
fail loudly: it prints one line to stderr at startup and then **silently discards every log
statement**. This module's logs are the only diagnosis for the fail-closed Discord membership gate
refusing everybody — the code says so in as many words — so "silently discards" is the single
failure mode it must not have. `org.jboss.logging.Logger` carries no such risk: the Keycloak SPI
classes the module compiles against use it themselves, so it cannot go missing while the module
still builds.

## Decision

**We will keep one facade per JVM, and enforce which one, rather than unify onto one facade across
JVMs we do not own.**

1. **`@Slf4j` is the only logging annotation that compiles** in `backend`, `frontend`, `ingest` and
   `test-support`. The repository-root `lombok.config` sets `flagUsage = ERROR` for
   `apacheCommons`, `custom`, `flogger`, `javaUtilLogging`, `jbosslog`, `log4j`, `log4j2` and
   `xslf4j`.
2. **`@JBossLog` is the only logging annotation that compiles in `keycloak-spi`.** Its own
   `keycloak-spi/lombok.config` flips the rule for that module (`jbosslog = ALLOW`,
   `slf4j = ERROR`) and carries the reasoning above. The exception is now *declared in one file*
   instead of being inferred from what the code happens to do.
3. **The generated field is pinned**: `lombok.log.fieldName = log`, `lombok.log.fieldIsStatic =
   true`. Both are Lombok's defaults; they are written down because `log.debug(...)` appears in
   ~300 files and a silent change to either would touch all of them.
4. **No production logger may be hand-rolled, and production code may not write to the console.**
   `scripts/check-logging-facade.sh` scans every tracked `*/src/main/java/**/*.java` for
   `(LoggerFactory|Logger).getLogger(` and for `System.out`/`System.err` writes, and runs as the
   `logging-facade` job in `repo-lint.yml` behind its own self-test. `src/test` and `src/e2e` are
   out of scope by design — the capture handles above, and the Playwright suite's deliberate
   console output for a human watching a browser drive itself.

## Consequences

- The two halves are enforced by **different mechanisms on purpose, because they fail differently.**
  `lombok.config` catches the wrong *annotation* at compile time. It cannot catch a hand-written
  `LoggerFactory.getLogger(...)`, because to Lombok that is simply not an annotation — hence the
  source scan. Neither alone closes the rule.
- Both directions of the `lombok.config` rule were **proved by making them fail**: a `@JBossLog`
  added to a `backend` class and a `@Slf4j` added to a `keycloak-spi` class each produce
  `error: Use of @… is flagged according to lombok configuration`, and the tree compiles clean once
  reverted. A gate nobody has seen bite is a gate nobody knows works.
- A future contributor who genuinely needs a different facade in a module now has to change a
  config file and read the paragraph explaining why it is set — which is the point.
- **The split survives**, and with it the cost that a developer moving into `keycloak-spi` writes
  `log.warnf("%s", x)` instead of `log.warn("{}", x)`. That is accepted: one printf-vs-braces
  difference in 5 files, against silent log loss in the module where logs matter most.
- If a future Keycloak makes SLF4J a first-class, non-optional part of its provider contract, point
  3 of the decision is a two-line change and this ADR should be superseded rather than edited.

## Alternatives considered

- **Move `keycloak-spi` to `@Slf4j` and have one annotation repo-wide** — rejected, on the
  dependency path above. The benefit is one annotation name; the risk is silently losing the log
  lines that diagnose a total login outage. If the bridge were a direct, declared dependency of
  `keycloak-services` rather than a transitive of an optional Quarkus feature, this would be the
  right answer.
- **Declare `slf4j-api` + a binding as explicit dependencies of `keycloak-spi`** — rejected. They
  would have to be `compileOnly` (the JAR bundles nothing, by ADR and by its SBOM gate), which
  changes nothing about what the Keycloak JVM actually provides at runtime. Shipping a binding
  inside the provider JAR instead would put a second logging backend into someone else's JVM.
- **An ArchUnit rule instead of the source scan** — rejected because it cannot work. Lombok's
  `@Slf4j` emits exactly the `LoggerFactory.getLogger(...)` call a hand-written field does; in
  bytecode the two are indistinguishable, which is precisely Lombok's purpose. The distinction
  exists only in source, so the check has to read source.
- **A Checkstyle `RegexpSinglelineJava` rule** — rejected. `config/checkstyle/google_checks.xml` is
  vendored verbatim from the Checkstyle release tag; editing it to add a local rule makes the next
  upgrade a merge instead of a copy. The repository already keeps this class of rule in
  `scripts/check-*.sh` with a self-test (`adr-numbering`, `alloy-log-masking`, `monitoring-image-pins`,
  …), so this one goes where its siblings live.
- **Flagging `System.out` in tests and e2e too** — rejected. The Playwright suite prints progress
  for a human watching it run; there is no correlation id to carry and no appender to reach.
