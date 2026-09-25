# ADR-0180 — Compact object headers are enabled on Java 25, ahead of JDK 27's default

- **Status:** Accepted — implemented; amended by [ADR-0209](0209-the-images-ship-a-java-aot-cache-trained-eagerly-and-verified-at-build.md) (2026-09-23: the archive this invariant binds is now a Java AOT cache, and a mismatch is detected at build and at run time instead of being silent). *Status corrected 2026-09-22:* it read "Proposed", but the change it decides has been on `main` since 2026-09-16 (`2937c2f99`, #1921: `-XX:+UseCompactObjectHeaders` in the three Dockerfiles and `docker-compose.yml`).
- **Date:** 2026-09-15
- **Deciders:** @greluc (pending)
- **Related:** specs `REQ-OPS-030` (new) ·
  [`deployment-delivery.md`](../specs/deployment-delivery.md) ·
  [ADR-0085](0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md) (the limit scheme whose
  headroom this buys) · [ADR-0175](0175-jvm-garbage-collectors-are-set-explicitly.md) (the other
  JVM-flag decision, and the "measure one variable at a time" lesson this follows) ·
  [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) (why the three images
  ship as one unit) · `docker-compose.yml` (the `JVM CONTAINER SIZING` block) ·
  `backend/Dockerfile`, `frontend/Dockerfile`, `ingest/Dockerfile` (the AppCDS training runs)

## Context

The stack is memory-constrained, not CPU-constrained. Summed over every prod-profile service the
declared limits are **14 160 MiB (13.83 GiB)** against a host with **15.24 GiB** — inside ADR-0085's
~14 GB review trigger with about 1.4 GiB to spare. Every capacity conversation in this repo for the
last three months has been about finding heap: #937 returned 768 MiB from two over-sized Postgres
containers rather than touch a JVM ceiling, and ADR-0085's `2048M -> 1792M` backend lever is still
deliberately un-taken because nobody wanted to shrink the busiest JVM.

HotSpot has an option that returns heap without taking any away from an application. Compact object
headers shrink the object header from **96 to 64 bits** on 64-bit platforms. Upstream reports 22 %
less heap space, 8 % less CPU time and 15 % fewer collections on SPECjbb2015, and Amazon runs
hundreds of production services on the layout. Its path through the JDK matters here:

- **JEP 450 (JDK 24)** — experimental, behind `-XX:+UnlockExperimentalVMOptions`.
- **JEP 519 (JDK 25)** — a **product** option: `-XX:+UseCompactObjectHeaders` with no unlock flag,
  off by default.
- **JEP 534 (JDK 27)** — **on** by default; `-XX:-UseCompactObjectHeaders` still disables it, and
  upstream states that the escape hatch is planned for deprecation.

The value therefore does not need JDK 27 — JDK 25, which this project already builds and runs on
(`eclipse-temurin:25-jdk-alpine` / `25-jre-alpine`), ships the same layout one flag away. That is
fortunate, because **JDK 27 is not available to this project today** and would be a poor place to
stand even if it were. Checked on 2026-09-15, the day of its GA:

- **No Temurin build exists.** Adoptium's `available_releases` ends at 26 and Docker Hub has no
  `eclipse-temurin:27-*` tag, so the digest-pinned base images cannot move.
- **Gradle 9.7.1 refuses it** — "JVM 27 and later versions are not yet supported", daemon 17–26,
  toolchains to 26. Support arrives in 9.8.0, which exists only as RC1 (2026-09-08).
  *(2026-09-25: the wrapper is on 9.8.0 GA now, which runs on and compiles for JDK 27 and makes
  JaCoCo 0.8.15 the default — so neither this point nor the JaCoCo half of the Lombok point below
  blocks JDK 27 any more. The Temurin, Spring Boot, Lombok and LTS points are unchanged.)*
- **Spring Boot 4.1.1 is "compatible with versions up to and including Java 26."**
- Boot's BOM pins **Lombok 1.18.46** (JDK 26); JDK 27 support landed in 1.18.48. Gradle's default
  **JaCoCo 0.8.14** cannot read class-file 71; 0.8.15 calls that support experimental.
- **27 is not an LTS.** The next is 29 in September 2027, while 25 is supported to at least
  September 2031. Adopting 27 buys a six-month window and two further migrations.

One property of the deployment makes this more than a one-line flag. Both images bake an **AppCDS
archive** during the build (`-XX:ArchiveClassesAtExit=/app/application.jsa`, a full Spring context
refresh), and the runtime `ENTRYPOINT` loads it with `-XX:SharedArchiveFile`. A CDS archive records
the object layout it was dumped with, and the JVM validates it on startup. The build stage does not
see `JAVA_TOOL_OPTIONS` — that variable comes from `docker-compose.yml` at runtime — so a flag set
only on the runtime side puts the two out of step. Verified on `eclipse-temurin:25-jdk-alpine`, in
both directions:

```
[warning][cds] Unable to use shared archive file.
               The shared archive file's UseCompactObjectHeaders setting (disabled) does not
               equal the current UseCompactObjectHeaders setting (enabled).
[error  ][cds] Loading dynamic archive failed.
```

The process then **starts normally, without CDS**. The `ENTRYPOINT`'s fallback cannot catch this: it
tests whether the `.jsa` file *exists*, and it does. The failure mode of getting this half-right is
therefore a silent loss of the 30–50 % startup saving, with nothing failing anywhere. The same check
also rules the other way — an archive dumped with the flag is rejected by a runtime without it.

## Decision

**We will enable `-XX:+UseCompactObjectHeaders` on all three application JVMs on Java 25, and set it
on the AppCDS training run of every image in the same change.**

- `docker-compose.yml` carries the flag in each service's `JAVA_TOOL_OPTIONS`, beside the collector
  flag REQ-OPS-028 put there. A JVM decision is recorded in that file; that is the pattern ADR-0175
  established and this follows it.
- `backend/Dockerfile`, `frontend/Dockerfile` and `ingest/Dockerfile` pass the same flag on their
  `-XX:ArchiveClassesAtExit` line. This is not optional and not a duplicate: it is the only place
  that can make the baked archive agree with the runtime.
- **All three services, not one.** The object layout is a property of the stack, not of a service:
  three JVMs on one layout is one thing to reason about, and it is the layout JDK 27 imposes anyway.
- **No memory limit and no `MaxRAMPercentage` changes with it.** Every figure in the
  `JVM CONTAINER SIZING` block was measured with 96-bit headers, so those numbers are the *before*
  side of this comparison and cannot also justify a new budget.
- **Re-measuring is part of this decision, not a follow-up wish.** After the images have run a full
  week on production, re-measure with the snapshot queries in `monitoring/README.md` and write the
  new table into the sizing block. Only then may the freed headroom be spent.

## Consequences

- Heap use drops on all three services. **By how much is not known from this ADR** and is
  deliberately not guessed: the 22 % upstream figure is a benchmark's, not this application's, and
  the object-graph shape of a Thymeleaf render tier and a JPA API tier are not SPECjbb2015's.
- The `JVM CONTAINER SIZING` block gains a second "everything above was measured under different
  conditions" caveat, one section after the first. That is the cost of changing a variable that the
  measured derivation depends on, and the alternative — changing the layout and the limits together
  — is exactly the mistake ADR-0175 had to correct.
- **A rollback is a flag, but it costs CDS.** `IRI_EXTRA_JAVA_OPTS=-XX:-UseCompactObjectHeaders` is
  appended after the base options and the last setting of a boolean `-XX` flag wins (verified), so
  an operator can turn the layout off without a deploy. The baked archive then mismatches and the
  service starts without CDS until the image is rebuilt. Slower startup, correct behaviour — an
  acceptable emergency lever, not a steady state.
- **Two flags must stay in sync forever**, in two different files, with a failure mode that is
  invisible. Both sides carry a comment naming the other, and REQ-OPS-030 states the invariant so it
  is reviewable rather than remembered.
- **Monitoring needs no change.** No metric, alert rule, dashboard, log stream or probe changes
  shape: `jvm_memory_used_bytes` / `jvm_memory_committed_bytes` and the container working-set alert
  are the *instrument* for this change, and the 90 % `ContainerMemoryHigh` line can only move
  further away. That is asserted here so the next reader does not have to re-derive it.
- **When the stack eventually reaches JDK 27, the flag becomes the default.** At that point the
  compose flag is a no-op that still documents intent. Keep it or drop it deliberately — but note
  that the Dockerfile side stops being load-bearing only when *both* sides default to the same
  layout, which is precisely the migration step that must not be done half-way.
- The `2048M -> 1792M` backend lever becomes more attractive than it was, and with `-XX:+UseG1GC`
  now written out on all three services it no longer risks a silent collector switch. It stays
  un-taken until the re-measurement supports it.

## Alternatives considered

- **Migrate to Java 27 and get the layout by default.** The reason this ADR exists. Blocked on
  Temurin, Gradle, Spring Boot, Lombok and JaCoCo (see Context), and 27 is a six-month release —
  so it would trade one flag for two further migrations and a supported-version gap, to obtain
  exactly the layout JEP 519 already offers on the LTS.
- **Wait for JDK 29 (LTS, September 2027) and take the default then.** Free, and it leaves a year of
  heap on the table while the host sits 1.4 GiB from its ceiling. The flag is a product option
  precisely so that this wait is not necessary.
- **Set the flag only at runtime and leave the images alone.** Rejected on evidence, not on taste:
  the archive mismatch above disables CDS silently. A change that quietly trades startup time for
  heap, with no record of the trade, is worse than either choice made deliberately.
- **Enable it on `ingest` first as a canary.** Rejected. The risk here is not per-service — it is a
  JVM-wide layout with two JDK releases of upstream production use — and a canary on the idlest
  service (7 requests in 13 hours) would produce the least information of the three. The images also
  build and ship as one release (ADR-0137), so a per-service rollout is more moving parts, not fewer.
- **Spend the freed headroom in this change** by taking the `2048M -> 1792M` lever at the same time.
  Rejected: the only numbers available to justify it were measured on the old layout. One variable
  at a time is the whole lesson of the 2026-09-13 section of the sizing block.
- **Put the flag in each image's `ENTRYPOINT` instead of in `docker-compose.yml`.** Tempting,
  because the archive-matching invariant is image-internal and this would make the image correct
  when run bare. Rejected: a command-line flag beats `JAVA_TOOL_OPTIONS`, so it would remove the
  operator's rollback lever and turn a flag flip into an image rebuild — and it would split JVM
  decisions across two surfaces, when REQ-OPS-028 just finished collecting them in one.
