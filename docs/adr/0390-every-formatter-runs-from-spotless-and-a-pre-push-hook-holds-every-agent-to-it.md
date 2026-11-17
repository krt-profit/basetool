# ADR-0390 — Every formatter runs from `spotlessApply`, and a pre-push hook holds every agent to it

- **Status:** Accepted
- **Date:** 2026-11-17
- **Deciders:** @greluc
- **Related:** REQ-OPS-058 · REQ-OPS-059 · REQ-OPS-034 · REQ-OPS-055 · ADR-0208 · ADR-0248 ·
  ADR-0327 · ADR-0391 ·
  [`basetool.repository-format-conventions`](../../build-logic/src/main/kotlin/basetool.repository-format-conventions.gradle.kts) ·
  [`.githooks/pre-push`](../../.githooks/pre-push) · [`.editorconfig`](../../.editorconfig)

## Context

Code in this repository is written by people and by coding agents of more than one vendor (Claude
Code, Codex), each reading `AGENTS.md` (ADR-0327). A uniform format has to come from tools, not from
each writer's habits.

- `./gradlew build` in CI ran `spotlessCheck` — google-java-format for Java, ktfmt for the Gradle
  Kotlin, Gson for JSON, whitespace for YAML, Markdown and `.properties` — and
  `:frontend:prettierCheck` for the frontend's CSS and JS. Where a formatter existed, `main` stayed
  formatted.
- The agent rule named one command, `./gradlew spotlessApply`, which did not run Prettier. An agent
  following the summary pushed unformatted CSS or JS, and nothing local enforced the rule at all: a
  forgotten format cost a CI round.
- About 75 Python scripts and 60 shell scripts had no formatter; their indentation varied between
  two and four spaces. The CSS and JS of the Keycloak theme and the exchange developer pages lay
  outside Prettier's globs. `.editorconfig` stated the charset and the line ending only.
- Ruff and shfmt are native executables. Spotless 8.11 can run one through `nativeCmd` or its
  `shfmt` step, but it hashes the executable when the configuration cache is stored, which is
  before any task of a fresh checkout could install it.

## Decision

We will make one Gradle command the format of the repository and refuse a push that skips it.

1. **One entry point.** `./gradlew spotlessApply` rewrites every file a formatter owns and
   `./gradlew spotlessCheck` fails on any it would change; `check`, and so CI's `build`, depends on
   `spotlessCheck`.

   | Files | Formatter | Settings |
   | --- | --- | --- |
   | Java, every source set | google-java-format (Google style) through Spotless | `basetool.spotless-conventions` |
   | Gradle Kotlin scripts, `build-logic` | ktfmt (Google style) through Spotless | `basetool.repository-format-conventions` |
   | JSON | Gson, two spaces, through Spotless | same |
   | YAML, Markdown, `.properties` | whitespace and final newline through Spotless | same |
   | Python | Ruff, `ruffFormatCheck` / `ruffFormatApply` | [`ruff.toml`](../../ruff.toml) |
   | Shell scripts and `.githooks/*` | shfmt, `shfmtCheck` / `shfmtApply` | `.editorconfig` |
   | CSS and JS of the frontend, the Keycloak theme and `docs/exchange/assets` | Prettier, `:frontend:prettierCheck` / `prettierApply` | [`.prettierrc.json`](../../.prettierrc.json) |

2. **Native formatters are verified release binaries.** Ruff and shfmt come from their vendors'
   GitHub releases through two Ivy repositories, the way the Node.js archive does; the versions live
   in the catalog and the SHA-256 for Linux and Windows on x86-64 in
   `gradle/verification-metadata.xml` (ADR-0208). `install<Tool>` unpacks the binary under
   `build/tools/` when a task needs it. The check and apply tasks run it over the file list Gradle
   computes and are dependencies of `spotlessCheck` and `spotlessApply`, as Prettier's are.
3. **The settings live where every tool and editor reads them.** `.editorconfig` states indentation
   per file type, final newline and trailing whitespace as the code already has them, and carries
   shfmt's options, which shfmt reads itself; Ruff reads `ruff.toml`; Prettier reads
   `.prettierrc.json` at the root.
4. **A versioned pre-push hook holds every agent to it.** `.githooks/pre-push` runs
   `./gradlew spotlessCheck` and refuses a push that carries commits while it fails.
   `core.hooksPath = .githooks` turns it on: Claude Code's `SessionStart` hook sets it, `AGENTS.md`
   tells every other agent to set it once per clone, and since worktrees share their clone's
   configuration one setting covers all of them. CI remains the gate that cannot be skipped.
5. **What stays without a formatter:** `docs/archive/` (frozen), Markdown and YAML beyond
   whitespace, Thymeleaf templates, XML, SQL (applied migrations are checksummed) and the two
   PowerShell hooks.

## Consequences

- An agent needs one command, and a missed format is refused at the push instead of in CI.
- Every push costs a Gradle run: seconds when the formatter tasks are up to date, a few minutes in a
  fresh worktree, which installs Node, Ruff and shfmt and formats every Java file once.
- The hook checks the working tree, not the pushed commits; a dirty tree can fail or pass a push
  on files it does not carry.
- Dependabot and refreshVersions do not see the Ivy modules. `native-tool-updates.yml` proposes
  the Ruff and shfmt bumps every week, with both platforms' checksums from the release digests and
  any reformat in a commit of its own listed in `.git-blame-ignore-revs` (REQ-OPS-059, ADR-0391).
- There is no verified binary for macOS or ARM. There `install<Tool>` fails with a message naming
  the platform; no other task resolves the tools.
- Formatting changed the bytes of host scripts, so the first promotion after it reports them as
  changed (REQ-OPS-045).

## Alternatives considered

- **Spotless `nativeCmd` or `shfmt()` steps.** They hash the executable while the configuration
  cache is stored; a fresh checkout fails before the install task can run.
- **Resolving the tools at configuration time through an artifact transform.** It would download
  them on every configuration of the root project, image builds and ARM hosts included.
- **Ruff and shfmt from Docker images.** Formatting would need a running Docker daemon.
- **A pre-commit hook, or the `pre-commit` framework.** A Gradle run on every signed commit is too
  slow, and the framework would add a Python tool chain every agent has to install.
- **Agent-specific hooks** such as a Claude Code `PostToolUse` formatter. They bind one vendor and
  leave the others unchecked.
- **The written rule alone.** It is what failed.
