# 2. Architecture constraints

Constraints are the things the architecture had to accept rather than choose. They are listed here
so a later reader does not mistake a constraint for a preference and "improve" it.

## 2.1 Organisational and legal

| Constraint | Consequence for the architecture |
| --- | --- |
| **One maintainer, one production deployment.** There is no ops team. The only other environment is a testing host that serves no users and is fed by its own `:testing` promotion channel (`REQ-OPS-022`). | Every operational action has to be a runbook, a script or the Ansible role, not tribal knowledge. Delivery is a timer pulling a signed bundle rather than a pipeline somebody drives. |
| **Unofficial, non-commercial *Star Citizen* fan project.** Not affiliated with or endorsed by Cloud Imperium. | Game data may be used within the fan-content terms; the tool can never present itself as official. Asset provenance is tracked (`docs/images/fankit/`). |
| **GPL-3.0-only**, with a DCO sign-off on every commit. | Third-party code has to be licence-compatible, and that is gated: every shipped module runs Licensee against a GPL-3.0-compatible allow-list in `check`, and `/licenses` lists what ships (ADR-0197). SBOMs are produced per module (CycloneDX) and dependency CVEs gate the build. |
| **GDPR: the maintainer is the controller** for real members' personal data. | Export and erasure are product features, not manual database work — including the free-text surfaces no foreign key points at. Retention is bounded and specified rather than open-ended. |
| **German is the primary language of the users.** | German is the default locale, English the second. Every user-visible string comes from the message bundles; there is no hardcoded UI text anywhere. |

## 2.2 Technical

| Constraint | Detail |
| --- | --- |
| **Java 25, Spring Boot 4.1** | Long-support Java; Boot 4 sets the servlet, security and observability idioms the modules follow. |
| **PostgreSQL 18**, schema owned by **Flyway** | Hibernate runs `ddl-auto=validate` everywhere. Nothing but a migration changes the schema — including in tests. |
| **Keycloak 26** as the only identity provider | The applications never manage credentials. Authorisation is carried in the JWT and enforced with `@PreAuthorize`. |
| **Redis** as the session store | Sessions must survive a frontend restart and be shared across replicas; the same Redis carries the live-sync pub/sub fanout. |
| **Gradle 9, Kotlin DSL, version catalog** | Versions live in `gradle/libs.versions.toml` and nowhere else; repositories only in `settings.gradle.kts`. CI builds with the configuration cache, so the build scripts must stay compatible with it. (Corrected 2026-09-23: this row said `versions.properties` was vestigial and "reads nothing" — three Dockerfiles copied it and the refreshVersions plugin recreated it. The file is gone; refreshVersions runs only under `-PrefreshVersions`.) |
| **Rootless Podman with Quadlet units** on Rocky Linux 10, SELinux enforcing | The production runtime since 2026-09-22 (ADR-0163). Containers are systemd units owned by an unprivileged service user; there is no Docker socket to hand anything. The Compose files remain the *source* the units are generated from, and the local and test stacks still run on Compose. See §7. |
| **Images are pinned by digest, Cosign-signed, and verified before they run** | A tag is not an identity. The deploy refuses an image whose signature does not verify against the pinned workflow identity. |
| **Node 24 for the frontend's asset toolchain** | It is a build-time dependency only; nothing in the served application is a Node runtime. |

## 2.3 Conventions that function as constraints

These are written down in `CLAUDE.md` and enforced by gates, which makes them binding in practice
even though they are style rules on paper:

- **Constructor injection only**, Lombok maximised, records for DTOs, JetBrains nullity annotations
  where the code establishes a contract (ADR-0192).
- **One logging facade, enforced** — `@Slf4j` everywhere but `keycloak-spi`, where it is
  `@JBossLog`; every other logging annotation is a compile error, and a hand-written logger or a
  console write in `src/main` fails a CI job (ADR-0193).
- **Javadoc is mandatory and gate-enforced** on every type and public member. Checkstyle enforces
  the form; the substance is on the author.
- **Google Java Style**, applied by Spotless and checked by Checkstyle; SpotBugs runs on `main`.
- **Every user-visible string is translated**, and German umlauts are `\uXXXX`-escaped inside
  `.properties` and literal UTF-8 everywhere else.
- **English for all developer-facing prose** — commits, PRs, issues, Javadoc, comments. The German
  end-user wiki is the single deliberate exception.

## 2.4 The documentation constraint

The **Basetool Knowledge** vault is a separate git repository that every part of the system orients
by, and `CLAUDE.md` binds every change to update it in the same unit of work. Nothing in CI can
enforce that — the vault is not a submodule of this repository, which is exactly why the rule is
written into every place an agent or a contributor starts. It is a constraint on how work is done,
not a suggestion about tidiness.
