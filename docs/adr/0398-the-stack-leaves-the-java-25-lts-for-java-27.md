# ADR-0398 — The stack leaves the Java 25 LTS for Java 27

- **Status:** Accepted
- **Date:** 2026-11-19
- **Deciders:** @greluc
- **Related:** amends [ADR-0180](0180-compact-object-headers-on-java-25.md) (its "stay on the LTS"
  reasoning) · [ADR-0209](0209-the-images-ship-a-java-aot-cache-trained-eagerly-and-verified-at-build.md)
  (the AOT cache) · [ADR-0223](0223-only-final-java-features-and-no-preview-flags.md) (final
  features only) · spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (REQ-OPS-030) ·
  [`docs/dependency-pins.md`](../dependency-pins.md) · issue #3051 (Spring Boot 4.2)

## Context

ADR-0180 kept the stack on the Java 25 LTS and listed what stood in the way of JDK 27 on the day of
its GA: no Temurin build, Gradle refusing JDK 27, Spring Boot 4.1 supporting Java up to 26, Lombok
and JaCoCo without class-file 71 support, and 27 not being an LTS. All but the last are gone:

- Temurin 27 builds and the `eclipse-temurin:27-jdk-alpine` / `27-jre-alpine` images exist.
- Gradle runs on and compiles for JDK 27 since 9.8.0; the wrapper is on 9.9.2.
- Spring Boot 4.2 is "compatible with versions up to and including Java 27"; the stack runs
  4.2.0.
- Lombok 1.18.48 (the version Boot 4.2 manages) supports JDK 27; JaCoCo 0.8.16 reads class-file 71
  without the experimental path of 0.8.15; PIT reads it from 1.25.6 on (ASM 9.10.1).

The owner's standing preference is the newest stable release of everything, majors included, and
he decided to leave the LTS line after the costs were laid out:

- A feature release is supported for six months, until the next one ships. Until the next LTS,
  Java 29 in September 2027, the stack moves three times: 27 now, 28 in March 2027, 29 in
  September 2027.
- Spring Boot supports a new JDK only from its next minor, usually in May or November, so a JDK can
  be out of support for some weeks before the Boot release that runs on its successor exists.

CVE-2026-114938 (the AVX-512 Base64 intrinsic, 2026-11-17) is fixed in 25.0.5.1 and 27.0.1.1
alike, so it is no reason for either line; `-XX:-UseBASE64Intrinsics` mitigates it on both.

## Decision

**We will build, test and run on Java 27.**

- The Gradle toolchain, `javaRelease`, `setup-java` in CI and the `eclipse-temurin:27-*-alpine`
  images move to 27 together. `keycloak-spi` stays `--release 21`, because Keycloak's own JDK
  loads it.
- **The object layout stays explicit.** Compact object headers are the JDK 27 default (JEP 534),
  but `-XX:+UseCompactObjectHeaders` stays in the compose files and in the AOT-cache training run,
  so REQ-OPS-030's parity check keeps comparing two written values rather than a written value and
  a JDK default.
- **`-XX:-UseBASE64Intrinsics` stays** until the pinned images run Temurin ≥ 27.0.1.1 and Keycloak's
  image a JDK ≥ 21.0.13.1.
- **The test JVM refuses final-field mutation** (`--illegal-final-field-mutation=deny`, JEP 500),
  which JDK 25 could not express: the conversion `docs/agents/backend-testing.md` describes becomes
  a gate.
- **Every following JDK feature release is taken within its support window**, as soon as a Spring
  Boot release supports it, until the stack reaches Java 29 (LTS). `docs/dependency-pins.md` holds
  that row.

## Consequences

- The stack runs a JDK whose free updates end in March 2027. The move to Java 28 is due then, and
  its timing depends on the Spring Boot minor that supports 28.
- Two JVM flags stay that a patched JDK 27 alone would not need: compact headers are its default,
  and the Base64 intrinsic switch-off goes once the images run 27.0.1.1. Both are recorded where
  they are set and checked by the existing gates.
- A reflective write to a final field in test code, or in a library a test exercises, now fails
  that test. Production keeps JDK 27's default `warn`.
- Tool versions that follow the JDK are pinned explicitly instead of taken from plugin defaults:
  `jacoco` (0.8.16) and `pitestCore` (1.30.2) in the catalog.

## Alternatives considered

- **Stay on Java 25 (LTS) until Java 29.** One move instead of three, and 25.0.5.1 carries the
  CVE-2026-114938 fix. Rejected by the owner's preference for the newest stable releases.
- **Java 26.** Out of support since 27 shipped in September 2026.
- **Drop `-XX:+UseCompactObjectHeaders` now that it is the default.** Possible, but it has to go
  from both places in one change, and the parity check would then compare a JDK default. Keeping it
  costs nothing.
