# Contributing to Profit Basetool

Thanks for taking the time to help improve Profit Basetool — the
squadron-management web app for the *DAS KARTELL* / IRIDIUM organization.
This document is the entry point for everything around *how* to contribute:
asking questions, reporting bugs, proposing features, sending pull
requests, and the style / quality rules every change is held to.

If you only have a couple of minutes, the very short version is:

1. **Security issues never go into public Issues** — use the confidential
   channel in [`.github/SECURITY.md`](.github/SECURITY.md).
2. **General questions go into
   [GitHub Discussions](https://github.com/krt-profit/basetool/discussions)**,
   not the issue tracker.
3. **Bugs and features go through the
   [issue templates](https://github.com/krt-profit/basetool/issues/new/choose)**.
4. **Pull requests branch from `main`, target `main`**, follow
   [Conventional Commits](#commit-messages), and must pass
   `./gradlew check` + `./gradlew spotlessApply` locally before they are
   pushed.
5. **Every user-visible change updates
   [`CHANGELOG.md`](CHANGELOG.md)** under `## [Unreleased]`.
6. **Sign the [Contributor License Agreement](CLA.md) once** before
   Your first PR, and **add a `Signed-off-by:` trailer
   ([DCO](#developer-certificate-of-origin-dco-sign-off)) to every
   commit** (`git commit -s`).

The rest of this document goes into detail.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Project documentation map](#project-documentation-map)
- [Asking questions](#asking-questions)
- [Reporting a security vulnerability](#reporting-a-security-vulnerability)
- [Reporting bugs](#reporting-bugs)
- [Suggesting features](#suggesting-features)
- [Your first code contribution](#your-first-code-contribution)
- [Local development setup](#local-development-setup)
- [Pull requests](#pull-requests)
- [Contributor License Agreement (CLA)](#contributor-license-agreement-cla)
- [Developer Certificate of Origin (DCO) sign-off](#developer-certificate-of-origin-dco-sign-off)
- [Commit messages](#commit-messages)
- [Branch names](#branch-names)
- [Style guides](#style-guides)
  - [Java](#java)
  - [Javadoc](#javadoc)
  - [Internationalization (i18n)](#internationalization-i18n)
  - [Frontend / UI](#frontend--ui)
  - [Markdown and documentation](#markdown-and-documentation)
  - [Git and GitHub language](#git-and-github-language)
- [Architectural invariants to know before you change code](#architectural-invariants-to-know-before-you-change-code)
- [Before-you-push checklist](#before-you-push-checklist)
- [License](#license)

---

## Code of Conduct

This project and everyone who participates in it are governed by the
[Contributor Covenant 3.0 Code of Conduct](CODE_OF_CONDUCT.md). By
participating, you agree to uphold it. Report unacceptable behaviour
confidentially to
[lucas.greuloch@gmail.com](mailto:lucas.greuloch@gmail.com).

---

## Project documentation map

Most of the substance lives in dedicated documents. Read the one that
matches what you are about to change *before* you open a PR:

| Topic                                                                                                    | Where                                                                                                                                                                            |
|:---------------------------------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Project overview, prerequisites, local dev/test stack, deployment runbook                                | [`README.md`](README.md)                                                                                                                                                         |
| Release notes and every user-visible change                                                              | [`CHANGELOG.md`](CHANGELOG.md)                                                                                                                                                   |
| Security vulnerability reporting, supported versions, scope, safe harbor                                 | [`.github/SECURITY.md`](.github/SECURITY.md)                                                                                                                                     |
| Architectural invariants, build/test commands, AI-assistant guardrails                                   | [`CLAUDE.md`](CLAUDE.md)                                                                                                                                                         |
| Role and permission matrix (`ADMIN`, `OFFICER`, `LOGISTICIAN`, `MISSION_MANAGER`, `KRT_MEMBER`)          | [`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md)                                                                                                                           |
| Flyway migration conventions (destructive-ops two-phase rule, data migrations, pre-merge checklist)      | [`backend/src/main/resources/db/migration/README.md`](backend/src/main/resources/db/migration/README.md)                                                                         |
| "DAS KARTELL" Corporate Design Manual (brand colours, fonts, department palette)                         | [`.claude/skills/das-kartell-design/README.md`](.claude/skills/das-kartell-design/README.md) (binding rules: [`docs/specs/ui-design-system.md`](docs/specs/ui-design-system.md)) |
| Pull-request expectations (template + checklist that ships with every PR)                                | [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)                                                                                                           |
| Production deployment runbook (host bootstrap, releases, rollback, PAT rotation)                         | [`docs/deployment.md`](docs/deployment.md)                                                                                                                                       |
| License                                                                                                  | [`LICENSE.md`](LICENSE.md) — GPL-3.0                                                                                                                                             |

CLAUDE.md and the SECURITY.md are the two documents that most often
surprise first-time contributors — please read them before touching the
backend security model, the multi-squadron data paths, or anything that
could conceivably leak data across users.

---

## Asking questions

> [!IMPORTANT]
> The issue tracker is **not** a Q&A forum. General questions land in
> [GitHub Discussions](https://github.com/krt-profit/basetool/discussions);
> the [issue-template config](.github/ISSUE_TEMPLATE/config.yml) routes
> "blank" issues there on purpose.

Before asking, please:

- Search the [README](README.md), this document, [CLAUDE.md](CLAUDE.md),
  and the [CHANGELOG](CHANGELOG.md). Most "why does the build do X?" /
  "how do I run the test stack?" / "what is `owning_squadron_id`?"
  questions are answered there.
- Search open **and closed** Discussions / Issues for an existing answer.

If none of that helps, open a Discussion with:

- A clear, specific title.
- What you tried and what you observed.
- Versions (commit SHA / release tag) and environment
  (local Gradle, local Compose, local test stack, …).

---

## Reporting a security vulnerability

**Do not open a public Issue, Discussion, or Pull Request for anything
you believe is security-sensitive.** Public disclosure before a fix is
available puts every operator running this project at risk.

Report vulnerabilities confidentially through
**[GitHub Private Vulnerability Reporting](https://github.com/krt-profit/basetool/security/advisories/new)**.
The full policy — supported versions, scope (cross-tenant data access,
auth bypass, optimistic-lock bypass, SSRF, sensitive data in logs,
supply-chain), coordinated-disclosure window, safe-harbor commitment,
how to verify a released container image via Cosign / SLSA / SBOM — is
in [`.github/SECURITY.md`](.github/SECURITY.md).

---

## Reporting bugs

Bugs are tracked as
[GitHub Issues](https://github.com/krt-profit/basetool/issues) via the
[Bug Report template](.github/ISSUE_TEMPLATE/bug_report.yml). The
template asks for everything maintainers normally need: summary, area,
version / commit SHA, environment, reproduction steps, expected vs.
actual behaviour, the **`X-Correlation-Id`** of the failing request, the
affected role, and (for UI bugs) the browser and device class.

Two non-obvious rules worth highlighting:

- **Never include tokens, passwords, email addresses, real names, or JWTs**
  in a bug report. The Self-Check on the template makes you confirm this
  explicitly — please do not check the box if there is sensitive data in
  the issue body.
- **If you found a closed issue that looks like the same problem, open a
  new one** and link the original from the body. Reopening someone
  else's closed issue makes triage harder.

A good bug report includes the correlation ID; with it, maintainers can
go straight to the matching log lines (`correlationId` MDC field in
`logs/{backend,frontend}.json`) instead of guessing which request you
mean.

---

## Suggesting features

Feature requests use the
[Feature Request template](.github/ISSUE_TEMPLATE/feature_request.yml).
The template intentionally asks for:

- The **problem / use case** (who benefits, in which role).
- A **proposed solution** — UI sketch, API shape, or workflow.
- **Alternatives** you considered and rejected.
- A scope checklist that flags non-obvious follow-on work (Flyway
  migration, role logic, multi-user / multi-squadron isolation,
  responsive UI work, i18n keys in DE + EN + fallback, CHANGELOG entry,
  OpenAPI updates, Resilience4j configuration for new backend calls).

If your proposal needs a Flyway migration or touches the role model or
the squadron-scope rules, please link to the relevant sections of
[CLAUDE.md](CLAUDE.md) so the discussion can move quickly.

Refactors, dependency upgrades, build / CI work, and docs-only changes
use the
[Task template](.github/ISSUE_TEMPLATE/task.yml) instead.

---

## Your first code contribution

Looking for an entry point? Filter the issue tracker by these labels:

- [`good first issue`](https://github.com/krt-profit/basetool/labels/good%20first%20issue) — small, well-scoped fixes that don't require deep architectural knowledge.
- [`help wanted`](https://github.com/krt-profit/basetool/labels/help%20wanted) — open tasks where additional contributors are explicitly welcome.

If you cannot find anything that suits you, opening a Discussion that
describes what you would like to work on is a perfectly fine starting
point.

---

## Local development setup

The README is the canonical place. The relevant sections:

- [Prerequisites](README.md#prerequisites) — Java 25 + Docker + Compose.
- [Local development (apps from Gradle)](README.md#local-development-apps-from-gradle) — recommended for active development (apps on the host JVM, dependencies in containers).
- [Full stack via Docker Compose](README.md#full-stack-via-docker-compose) — when you need the whole thing in containers.
- [Running the local test stack](README.md#running-the-local-test-stack) — when you need an isolated stack with throwaway credentials. **Use this every time you spin up a stack from a worktree.** Never re-use the production `.env`, the production `keystore.p12`, or the shared `realm-export.json` — see [CLAUDE.md → Testing](CLAUDE.md) for the rationale.
- [Running tests](README.md#tests) — Gradle wrapper only, never the IDE test runner.
- [Linting, static analysis and SBOM](README.md#linting-static-analysis-and-sbom) — `./gradlew check`, Checkstyle, SpotBugs, Spotless, CycloneDX.

Hard project rule: **always use the Gradle wrapper** (`./gradlew`).
Never the IDE test runner; never `mvn`; never a system-installed Gradle.
This is what CI runs, this is what every contributor's machine runs,
and "works in the IDE" is not a passing state.

---

## Pull requests

### Workflow

1. Fork (or branch directly if you have push access).
2. Branch off `main` — there is no long-lived `develop` branch.
3. Make focused commits using the [commit-message style](#commit-messages).
4. Run the [before-you-push checklist](#before-you-push-checklist) locally.
5. Open the PR **against `main`**.
6. Fill in [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) — the template auto-populates the PR body and includes a multi-section checklist (General, Code Quality, Tests, API & Database, Security & Data Isolation, UI / Frontend, Configuration & Dependencies). Tick what applies; sections marked *(if affected)* can stay untouched if your change does not touch that area.
7. Mark the PR as **Draft** and prefix the title with `WIP` while it is in progress.

### CI

The [CI workflow](.github/workflows/ci.yml) runs `./gradlew build --continue`
on every PR and push to `main`. That includes Checkstyle, SpotBugs, the
full JUnit suite, and the JaCoCo coverage report. Reports are uploaded as
workflow artefacts so reviewers can download them when investigating a
failure. **CI must be green before a PR merges** — there is no "rerun
until it passes" allowance.

The CycloneDX SBOMs are a release artefact, not a build output: they are
regenerated and committed only by the
[release-prepare workflow](.github/workflows/release-prepare.yml) (or on
demand via `./gradlew :<module>:cyclonedxBom`), never by `./gradlew build`.

In addition, [CodeQL](.github/workflows/codeql.yml) and an OWASP
[dependency check](.github/workflows/dependency-check.yml) run on a
schedule; if your change introduces a new security finding, expect to
address it before merge.

### Review and merge

- PRs are reviewed by maintainers; non-trivial changes typically need at least one approving review.
- Reviewers are requested automatically from [`.github/CODEOWNERS`](.github/CODEOWNERS). That file is **review routing, not access control** — an entry there grants nobody any permission. It also documents which surfaces the project treats as high-risk: the release and deploy path, the Gradle build and dependency pins, the binding specs and ADRs, the role matrix, the licensing and security documents, and the security-critical application code (authorization config, Flyway migrations, the Keycloak SPI and theme). If you touch one of those, expect the review to be closer. Note that in CODEOWNERS **the last matching rule wins** — rules do not accumulate the way `.gitignore` patterns do — so add new stanzas from broad to narrow and never re-broaden further down the file.
- Reviewer comments are not blockers by default — please respond to them, even if your response is "I disagree, here is why".
- Maintainers normally merge via **squash merge** so the final commit on `main` matches the PR title. Write the PR title accordingly: it is what ends up in `git log`.
- Do not force-push to a PR branch once review has started unless a reviewer asks you to; prefer additional commits and let the squash collapse them at merge time.

---

## Contributor License Agreement (CLA)

Before Your first contribution is merged, You must sign the
[Profit Basetool Individual Contributor License Agreement](CLA.md).

The CLA grants the Project Maintainers a perpetual, worldwide,
royalty-free license to distribute Your contributions under GPL-3.0
and any later GPL-compatible license the Project may adopt, together
with a parallel patent license to defend the Project against IP
claims. **You retain copyright in Your contributions** — the CLA does
not transfer ownership.

The CLA is signed **once** and covers all of Your present and future
contributions to the Project unless and until You explicitly withdraw.
There are two signing paths, both documented in
[§ 11 of the CLA](CLA.md#11-how-to-sign):

- **Signature PR** — open a PR titled `cla: sign — <your-github-handle>` that appends Your name, GitHub handle, commit email, the current date in ISO-8601, and the CLA version to [`docs/cla-signatures.md`](docs/cla-signatures.md). Include the verbatim acceptance sentence from § 11 in the PR description.
- **CLA-Assistant** — if the maintainers have enabled [CLA-Assistant](https://cla-assistant.io/) on the repository, sign electronically by adding a single comment to Your first PR; follow the in-PR instructions.

If You contribute on behalf of an employer or other legal entity, a
separate **Entity CLA** is required. Contact
[lucas.greuloch@gmail.com](mailto:lucas.greuloch@gmail.com) to obtain the
template. Until the Entity CLA is countersigned, Your employer-funded
contributions cannot be merged.

A pending PR from an author without a signed CLA will be put on hold
until the CLA is signed; maintainers will leave a friendly reminder
comment linking to the signing instructions. This is not a punishment —
it just means the Project cannot legally accept Your code yet.

### What about contributions to non-code areas?

The CLA applies to **every** Contribution as defined in § 1 of the
CLA — source code, build configuration, Flyway migrations, Keycloak
theme assets, documentation, translations, GitHub Actions workflows,
issue comments that the maintainers later commit verbatim, and so on.
The bar for "needs a signed CLA" is "the change ends up in a commit on
`main` under Your authorship," not "it touches Java code."

Typo-fix PRs and other trivial changes still require a signed CLA,
purely to keep the rule unambiguous. The signing procedure takes about
two minutes; once done, it is never needed again.

---

## Developer Certificate of Origin (DCO) sign-off

In addition to the one-time CLA, **every individual commit** in a
pull request must carry a `Signed-off-by` trailer that certifies the
[Developer Certificate of Origin, version 1.1](https://developercertificate.org/).

### Why both a CLA and a DCO?

- The **CLA is a one-time legal grant** signed once per contributor. It defines the IP terms under which the Project may use Your code in perpetuity.
- The **DCO sign-off is a per-commit attestation** that this specific commit is Your own work (or third-party work You are allowed to forward). It is much lighter-weight than the CLA and easy to audit — every commit either has a `Signed-off-by:` line or it does not.

Together they give the Project a clear legal foundation (CLA) and a
verifiable per-contribution chain of custody (DCO) without slowing
contributors down on each PR.

### What the DCO certifies

By adding a `Signed-off-by` line to a commit, You certify that:

> **Developer Certificate of Origin, Version 1.1**
>
> (a) The contribution was created in whole or in part by me and I
> have the right to submit it under the open-source license indicated
> in the file; or
>
> (b) The contribution is based upon previous work that, to the best
> of my knowledge, is covered under an appropriate open-source license
> and I have the right under that license to submit that work with
> modifications, whether created in whole or in part by me, under the
> same open-source license (unless I am permitted to submit under a
> different license), as indicated in the file; or
>
> (c) The contribution was provided directly to me by some other
> person who certified (a), (b), or (c) and I have not modified it.
>
> (d) I understand and agree that this project and the contribution
> are public and that a record of the contribution (including all
> personal information I submit with it, including my sign-off) is
> maintained indefinitely and may be redistributed consistent with
> this project or the open-source license(s) involved.

The canonical text lives at <https://developercertificate.org/>.

### How to sign off

Pass `-s` to `git commit`:

```bash
git commit -s -m "feat(missions): add CSV export of mission roster"
```

That appends a trailer of the form
`Signed-off-by: Your Name <you@example.org>` to the commit message.
The name and email **must** match Your `git config user.name` and
`user.email` and must be a real, reachable identity — anonymized
addresses or `noreply` aliases are not accepted as DCO sign-off,
because they cannot be matched against Your CLA signature.

To sign off **every** future commit in this repository automatically,
opt in once:

```bash
git config format.signOff true
```

### Retroactively signing off existing commits

If You have already made commits without `-s`, rewrite them before
pushing:

- **Last commit only:**

  ```bash
  git commit --amend --signoff --no-edit
  ```
- **All commits since branching from `main`:**

  ```bash
  git rebase --signoff main
  ```

Force-push the rewritten branch with
`git push --force-with-lease origin <your-branch>` to update the PR.
Never force-push to `main` itself.

### Enforcement

The [`.github/workflows/dco.yml`](.github/workflows/dco.yml) workflow
runs on every pull request and verifies that every commit in the PR
carries a `Signed-off-by` trailer whose name and email match the
commit's author (case-insensitive on the email). Merge commits and
commits authored by well-known bots (Dependabot, Renovate, GitHub
Actions) are skipped — see the workflow header for the exact
exemption list.

If the check fails:

1. Fix it with the rebase / amend commands above.
2. `git push --force-with-lease origin "$(git branch --show-current)"` to refresh the PR.
3. The check re-runs automatically.

We block at **merge** time (the check is wired up as a required status
check via branch protection on `main`), not at commit time. If You
forget the `-s` flag a few times, the rebase fix is one command — no
harm done.

> [!NOTE]
> The DCO requirement applies to commits authored **after** this
> policy lands. Pre-existing commits in `main` are grandfathered in
> and do not need to be retroactively rewritten — `git log` history
> stays as it is.

---

## Commit messages

The repository follows **[Conventional Commits](https://www.conventionalcommits.org/)**.
The PR title becomes the squash-merge commit subject, so the same rules
apply there.

### Format

```
<type>(<scope>): <short summary>

<optional body>

<optional footer(s)>
```

- **`<type>`** — one of `feat`, `fix`, `chore`, `refactor`, `docs`, `test`, `style`, `build`, `ci`, `perf`. Add a `!` (e.g. `feat!`) for breaking changes, or use a `BREAKING CHANGE:` footer.
- **`<scope>`** — short identifier of the affected area. Recent history uses scopes such as `multi-tenant`, `db/V80`, `frontend/orders`, `admin/uex`, `operations`, `missions`, `materials`, `promotion`, `actions`, `deps`. Look at `git log --oneline` for live examples; pick the closest existing scope rather than inventing a new one.
- **`<short summary>`** — imperative mood (*"add foo"*, not *"added foo"* or *"adds foo"*), lower-case start, no trailing period, ≤ 72 characters including the prefix.
- **Body** — explain the *why*. Reference Issues / PRs (`closes #123`, `refs #456`). Keep lines ≤ 100 characters.
- **Footer** — `BREAKING CHANGE:` (with a colon), `Co-authored-by:`, and — required on every commit — the `Signed-off-by:` trailer from `git commit -s` (see [DCO sign-off](#developer-certificate-of-origin-dco-sign-off)).

### Examples (taken from real history)

```
feat(multi-tenant): make Basetool multi-squadron capable
fix(db/V80): drop+recreate mission_participant FK around IRIDIUM UUID swap
chore(multi-tenant): Phase 7 part 1 - stop writing legacy job_order.squadron VARCHAR
fix(frontend/orders): relay the material fetch through the member's bearer
refactor(logging): replace [DEBUG_LOG] markers with debug-level logging
docs(github): translate issue and PR templates to English
chore(deps): refreshVersions proposals
```

> [!NOTE]
> The Conventional Commits format **replaces** the older
> `[FEATURE – X]` / `[BUG – X]` prefix scheme that earlier versions of
> this guide described. Do not use the bracket-prefixed style any more.

---

## Branch names

There is no strict naming policy, but a useful convention is:

```
<type>/<short-kebab-case-description>
```

Examples: `feat/squadron-switcher-ui`, `fix/orders-material-relay`,
`chore/refresh-versions-may`. Avoid embedding issue numbers in branch
names — they go into commit messages and PR descriptions instead.

The `main` branch is the only long-lived branch. There is no `develop`,
no `release/*`, no `staging/*`. Releases are cut by tagging `main` with
`vX.Y.Z`; see [`docs/deployment.md`](docs/deployment.md) for the full
release loop.

---

## Style guides

### Java

- **Google Java Style** is the baseline, enforced by Checkstyle
  (`config/checkstyle/google_checks.xml`). IntelliJ IDEA settings live in
  the `settings/` directory.
- **Spotless** auto-formats Java sources — `./gradlew spotlessApply` is
  mandatory before every push. Spotless is wired into `check` with
  `isEnforceCheck = true`, so unformatted code fails CI.
- **Checkstyle** runs with `maxWarnings = 0`. **Every** new or changed
  warning in the changed code must be fixed; do not silence findings
  with `@SuppressWarnings` / `@SuppressFBWarnings` / Checkstyle
  suppressions unless the rule is genuinely wrong for that specific
  call site — and then leave a one-line comment explaining why.
- **SpotBugs** runs as part of `check`. Same rule: no new findings on
  top of pre-existing ones.
- **Constructor injection only**, ideally via Lombok
  `@RequiredArgsConstructor`. No field `@Autowired`.
- **Records** for DTOs and immutable config wrappers; no equivalent
  POJOs.
- **Lombok** — lean on it (`@Slf4j`, `@Getter`, `@Setter`, `@Builder`,
  `@RequiredArgsConstructor`, `@NoArgsConstructor`, `@AllArgsConstructor`,
  `@Data`) to avoid boilerplate.
- **Loggers** exclusively via `@Slf4j` — never `LoggerFactory.getLogger(...)`.
- **Modern Java** — switch expressions, pattern matching, sealed classes
  where exhaustiveness genuinely helps.
- **JetBrains annotations** (`@NotNull`, `@Nullable`, `@Contract`) on
  anything that communicates a real contract.

### Javadoc

Javadoc is **gate-enforced** via Checkstyle (`MissingJavadocType`,
`MissingJavadocMethod`, `SummaryJavadoc`, `InvalidJavadocPosition`,
`JavadocParagraph`, `AtclauseOrder`). The rules:

- **Every** new / changed class, interface, enum, record, and
  public/protected method needs Javadoc — including trivial
  getters/setters (document at the field level for Lombok-generated
  members).
- Javadoc must describe the *actual* behaviour, parameters, return
  values, side effects, thrown exceptions, and non-obvious invariants
  of the specific code it annotates.
- **Generic boilerplate is forbidden.** Phrases like *"Gets the value"*,
  *"Returns the result"*, *"Does something"*, *"Helper method"*, or just
  restating the method name in prose are not acceptable. If you cannot
  write a concrete, code-specific sentence, read the implementation
  again until you can.
- Always update the `@author` tag for substantial changes but retain
  earlier authors.
- Use the `@since` tag with the release version when adding new public
  API.

### Internationalization (i18n)

- **Every** user-visible string comes from
  `frontend/src/main/resources/messages.properties` (and
  `messages_de.properties` / `messages_en.properties`). No exceptions —
  labels, buttons, tooltips, error messages, flash messages, alerts,
  placeholders, titles. No hardcoded text in HTML, JS, or Java.
- **German umlauts in `.properties` files MUST be encoded as `\uXXXX`**
  — e.g. `ä` for `ä`. This is a hard rule, enforced by review.
- **German umlauts in Markdown files MUST be literal UTF-8 characters.**
  Never use `\uXXXX` outside `.properties`.
- New keys land in `messages.properties` (fallback) plus both
  `messages_de.properties` and `messages_en.properties`.

### Frontend / UI

The UI follows the *DAS KARTELL* Corporate Design Manual strictly — see
[`.claude/skills/das-kartell-design/README.md`](.claude/skills/das-kartell-design/README.md)
and the *Frontend / UI rules* section
of [`CLAUDE.md`](CLAUDE.md). Highlights:

- **Brand colour** `#E77E23` (orange). Logo only in this orange, white,
  or black.
- **Backgrounds** `#000000` / `#141414` (dark-mode aesthetic).
- **Type** `Lato` only (Light 300 standard, Bold 700 emphasis). Headlines are
  Lato **Bold + uppercase only** with display letter-spacing (~0.05em) — there is
  no separate display face (Audiowide/Ethnocentric retired 2026-06).
- **Department colours** are semantic — Combat red `#A3000A`,
  Sub-Radar/Covert blue `#355DDC`, Research cyan `#37BBC0`, Profit
  green `#239E33`, Search & Rescue yellow `#FFD23F`, Marine Corps
  purple `#7A5E96`. Use them only in their semantic context.
- **Never** use `confirm()`, `alert()`, or any native browser dialog.
  Build a KRT-styled modal or toast instead.
- **Responsive design is mandatory across four device classes**:
  Smartphone (≤ 768 px), Tablet (768–1024 px), Desktop (1024–1600 px),
  Ultra-wide (1600 px+). Minimum touch target 44 px; long-form text
  capped at `max-width: 80ch`.
- **DOM `data-version` propagation** — when an entity is updated via
  AJAX, the new `version` must propagate to **every** related element in
  the same context (edit buttons, modals inside the same `<tr>` /
  container). A missed `data-version` becomes a 409 on the user's next
  click. When in doubt, `window.location.reload()` on success.

### Markdown and documentation

- Wrap lines around 80 characters in long-form prose; tables and code
  blocks are exempt.
- Use Markdown headings hierarchically (no jumping from `##` to `####`).
- Code samples in fenced blocks with a language hint (` ```java`,
  ` ```bash`, ` ```yaml`).
- Cross-link to other docs in the repo with relative paths so links
  work both on GitHub and in a local checkout.
- The
  [`docs/`](docs/) directory is for long-form documentation
  (deployment runbook, design notes); ad-hoc scratch files do not
  belong there.

### Git and GitHub language

**Always write Git and GitHub content in English** — commit messages,
branch names, tag names and messages, PR titles and bodies, PR review
comments, issue titles and bodies, issue comments, Discussions posts,
release notes. This applies regardless of the language a contributor or
reporter writes in: translate substance into English before committing
or posting. The only exception is verbatim quoting of existing
non-English content (e.g. quoting a user-reported error message in an
issue) — the surrounding prose you author stays English.

Reason: the codebase has international contributors and the source-of-
truth audit trail (git log, PR history, advisories) must be readable by
all of them.

---

## Architectural invariants to know before you change code

The codebase has a small number of invariants that exist because real
bugs hit production when they were violated. They are enforced by
ArchUnit, Checkstyle, the CI build, and — where automation cannot reach
— by review. Read the matching CLAUDE.md / README.md sections **before**
you touch these areas.

### Security model

- Both modules use Spring Security with Keycloak OIDC. Backend = resource
  server (validates JWT); frontend = OAuth2 client.
- **Authorization is centralised in `@PreAuthorize` annotations on
  services and controllers.** Keep checks out of business logic.
- `SecurityContextHolder` is forbidden outside the auth-helper service
  (ArchUnit-enforced). Every `@RestController` carries at least one
  `@PreAuthorize`. Controllers do not return JPA entities. The frontend
  module does not depend on Spring Data JPA.
- Full role / permission matrix:
  [`ROLES_AND_PERMISSIONS.md`](ROLES_AND_PERMISSIONS.md).

### Multi-user data isolation

- Every read / write must filter by JWT `sub` unless the caller has an
  elevated role (`ADMIN`, `OFFICER`, …). Enforce this in the service
  layer, not the controller.
- For a member below Logistician, the controller must explicitly clear
  sensitive fields (e-mail, real name, internal orders / items) via a
  `cleanup…ForPeer`-style helper (REQ-SEC-007). Returning a full DTO to a peer
  is an information-disclosure bug, and the ArchUnit rule
  `peerReadableMissionEndpointsMustRedactPii` fails the build on one.

### Multi-squadron tenancy

- Five aggregate roots are **strictly squadron-scoped**: `Ship`,
  `InventoryItem` (direct Lager-View), `RefineryOrder`, `Operation`,
  and `Mission` (with a public-escape carve-out for non-internal
  missions).
- `JobOrder` is intentionally **cross-squadron** and carries a
  `responsible_org_unit_id` (the processing unit — governs visibility,
  changed only via `PATCH /{id}/responsible-org-unit`) and a
  `requesting_org_unit_id` (the customer — grants no visibility). It is
  conditionally scoped: an SK-responsible order is a public shared queue
  that squadrons sign up for via material claims, a squadron-responsible
  order is private to that squadron + admins (reworked in #340, `V129`).
- Scope is enforced in the service layer via
  [`OwnerScopeService`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/OwnerScopeService.java);
  controllers gate detail / write endpoints with
  `@PreAuthorize("@ownerScopeService.canEdit…")`.
- ArchUnit rule `staffelScopedServicesMustWireOwnerScopeOrAuthHelper`
  breaks the build if a staffel-scoped service stops injecting
  `AuthHelperService` / `OwnerScopeService`. Update the whitelist in
  [`ArchitectureTest`](backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java)
  when you add a new staffel-scoped aggregate.
- Full operational rules: [`CLAUDE.md`](CLAUDE.md) → *Multi-squadron tenancy*.

### Concurrency: optimistic locking and the `*WithinTransaction` pattern

The codebase has been bitten by optimistic-locking traps multiple times.
The rules in [CLAUDE.md → Concurrency](CLAUDE.md) exist because of real
bugs that shipped. The short version:

- **Optimistic locking via `@Version`** — every write DTO carries
  `version`; concurrent modifications surface as
  `ObjectOptimisticLockingFailureException` → HTTP 409. Do not strip
  `version` from DTOs.
- **`@Modifying` bulk updates with `clearAutomatically = true` detach
  the entire persistence context.** Never run such a bulk update inside
  a loop that mutates more than one item of the same aggregate.
- **`*WithinTransaction` pattern** — when a `@Transactional` service
  method modifies an entity and then calls another service that operates
  on the **same** entity, expose a dedicated
  `completeSomethingWithinTransaction(Entity entity)` method annotated
  `@Transactional(propagation = MANDATORY)` that operates on the
  already-managed entity and relies on dirty-checking. Canonical
  example:
  [`JobOrderService.completeJobOrderWithinTransaction`](backend/src/main/java/de/greluc/krt/profit/basetool/backend/service/JobOrderService.java).
- **Pessimistic locking** for bulk reorders / priority shifts:
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` (or atomic SQL).

### API conventions

- Versioned URI paths `/api/v1/...`; breaking changes → `/api/v2/...`
  plus `@ApiDeprecation(sunset=..., replacement=...)` on the retired
  endpoint.
- **DTOs only at boundaries.** Never expose JPA entities. DTOs are
  records with Jakarta validation annotations on write fields.
- `@Valid` on every `@RequestBody` for POST / PUT / PATCH.
- Errors are RFC 7807 Problem Details (`application/problem+json`) via
  `GlobalExceptionHandler` — do not throw into the void.
- List endpoints take `Pageable` and return `PageResponse`. **Whitelist
  allowed sort fields** in the service — never pass user input directly
  to `Sort`.
- All timestamps in UTC (`Instant` / `OffsetDateTime`); timezone
  conversion happens in the display layer only.
- Keep [`backend/src/main/resources/api/openapi.json`](backend/src/main/resources/api/openapi.json)
  in sync with controller changes; every endpoint carries SpringDoc
  annotations (`@Operation`, `@ApiResponses`).

### Database

- Schema is owned by Flyway. Every change is a new
  `V<n>__<description>.sql` under
  [`backend/src/main/resources/db/migration`](backend/src/main/resources/db/migration).
- **Hibernate `ddl-auto` stays at `validate` everywhere — never
  `update` or `create`.** Validate is one-directional (entity → DB);
  orphan DB columns are intentional during two-phase drops.
- Destructive operations follow the two-phase rule (stop-write release
  → drop release) documented in
  [`db/migration/README.md`](backend/src/main/resources/db/migration/README.md).
- Avoid N+1: prefer `JOIN FETCH`, `@EntityGraph`, or Spring Data
  projections.

### Frontend resilience and config

- WebClient is centrally configured (base URL, headers, connect / read /
  write timeouts).
- **Resilience4j** wraps every backend call (Timeout, Retry,
  CircuitBreaker, Bulkhead). State transitions are logged via
  `ResilienceEventLogger` so degraded-backend symptoms
  (`SERVICE_UNAVAILABLE`, `BACKEND_TIMEOUT`) always have a matching log
  line.
- Type-safe configuration via `@ConfigurationProperties` + `@Validated`
  on every Keycloak / backend / limits properties class. Misconfiguration
  is caught at startup.

### Logging

- Both modules emit one access-log line per request and enrich every log
  line with MDC fields `correlationId`, `userId`, and `orgUnitId`.
- The `prod` profile additionally writes structured JSON
  (`LogstashEncoder`) to `logs/{backend,frontend}.json` with errors
  rolled into `*-error.log` for fast triage.
- **Never log names, emails, JWTs, or tokens.**

### Tests

- Tests live in the same package structure under `src/test/java/`
  mirroring `src/main/java/`.
- Naming: `*Test` suffix (`UserServiceTest`).
- Structure: Given / When / Then (or Arrange / Act / Assert).
- Mock external / complex dependencies with Mockito (`@Mock`,
  `@InjectMocks`).
- **Every new feature ships with tests.** No exceptions.
- **Never use production / real credentials in tests or local test
  stacks.** This is a hard rule covering Mockito unit tests, MockMvc,
  `@SpringBootTest`, TestContainers, and any locally-spun-up stack used
  to verify a change. The recovery for a leaked secret is more
  expensive than always using the `.env.test` / throwaway `keystore.p12` /
  stripped `realm-export.json` route described in
  [README → Running the local test stack](README.md#running-the-local-test-stack) and
  [CLAUDE.md → Testing](CLAUDE.md).

---

## Before-you-push checklist

A condensed version of the PR template. Run through this before opening
the PR — every box should be reasonably tickable.

- [ ] `./gradlew spotlessApply` was the last formatting change.
- [ ] `./gradlew check` passes locally (Checkstyle, SpotBugs, tests).
- [ ] No new Checkstyle / SpotBugs findings in the **changed code**.
- [ ] Every new / changed public API has Javadoc — concrete, code-specific, no boilerplate.
- [ ] New / changed code has tests (`*Test`, Given/When/Then); concurrency-sensitive changes test the optimistic-lock path.
- [ ] No production credentials in test artefacts, stack spin-ups, or screenshots.
- [ ] `CHANGELOG.md` updated under `## [Unreleased]` in the correct category (`Added` / `Changed` / `Fixed` / `Removed` / `Security`).
- [ ] No tokens, passwords, real names, emails, or JWTs in the diff or in logs.
- [ ] My [CLA](CLA.md) is on file (one-time, signature in [`docs/cla-signatures.md`](docs/cla-signatures.md) or via CLA-Assistant).
- [ ] **Every** commit in the PR carries a `Signed-off-by:` trailer (DCO; `git commit -s`).
- [ ] For schema changes: a new `V<n>__<desc>.sql` migration, `ddl-auto=validate` still passes, destructive operations follow the two-phase rule.
- [ ] For API changes: `openapi.json` updated, every endpoint carries SpringDoc annotations, write DTOs carry Jakarta validation annotations, list endpoints whitelist sort fields, all timestamps in UTC.
- [ ] For UI changes: verified on at least one of each device class (Smartphone / Tablet / Desktop / Ultra-wide), every user-visible string in `messages.properties` (DE + EN + fallback), umlauts encoded `\uXXXX` in `.properties`, literal in Markdown.
- [ ] For dependency upgrades: edited `versions.properties` / `gradle/libs.versions.toml`, not `build.gradle.kts` directly.

If you find yourself wanting to skip a checklist item "for now", that is
the moment to add a follow-up issue and link it from the PR description
instead of silently shipping the gap.

---

## License

Profit Basetool is released under the
[GNU General Public License v3.0](LICENSE.md). By contributing, you
agree that your contribution is distributed under GPL-3.0.

Two contributor agreements complement the GPL-3.0 license and are
covered in their own sections above:

- The one-time [Contributor License Agreement](#contributor-license-agreement-cla) — defines the IP terms under which the Project may use Your contributions in perpetuity. Full text in [`CLA.md`](CLA.md).
- The per-commit [Developer Certificate of Origin sign-off](#developer-certificate-of-origin-dco-sign-off) — certifies that this specific commit is Your own work (or third-party work You are allowed to forward).

If You incorporate third-party code, make sure the upstream license is
compatible with GPL-3.0 and disclose it in the PR description
(see [§ 6 of the CLA](CLA.md#6-third-party-material)). Common
GPL-compatible upstream licenses include MIT, BSD-2/3-Clause, Apache
2.0 (with caveats), and ISC; LGPL and AGPL are compatible in
context-sensitive ways. When in doubt, ask in the PR or via
[lucas.greuloch@gmail.com](mailto:lucas.greuloch@gmail.com) before submitting.

---

Thanks again for being here. The Profit Basetool exists because a
bunch of people kept showing up to make squadron logistics a little
less painful — your contribution is part of that.
