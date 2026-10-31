# ADR-0214 — Code carries documentation comments and short technical comments, no history

- **Status:** Accepted — amended 2026-10-02 (`_` for an empty catch), amended 2026-10-31 (short technical comments allowed again, see below)
- **Date:** 2026-09-25
- **Deciders:** @greluc (owner)
- **Related:** REQ-OPS-020 (amended) · ADR-0125 (JSDoc types) · `CLAUDE.md` → *Documentation*

## Context

The code base had grown a second narrative next to the code: about 65,000 comment lines across
Java, JavaScript, CSS, Thymeleaf, YAML, shell, Python, SQL, nginx, systemd and build scripts, and
Javadoc blocks that ran to essays. Much of it was history — which PR changed what, what broke on
which date, what an earlier version did — and much of it restated reasoning that already lives in
commit messages, PR bodies, ADRs and specs. Comments are not compiled, tested or linted for truth,
so they drift; a stale comment still reads as authoritative. Several requirements had even made a
comment the carrier of a fact (the container sizing measurements of REQ-OPS-020).

## Decision

We keep no comments in code. What stays:

- **Javadoc** on every class, interface, enum, record and public/protected member, as Checkstyle
  requires. It is short, precise and states the contract: one summary sentence, a contract
  sentence only when a caller needs it, and the block tags. It carries **no history** — no dates,
  PR or issue numbers, "previously", "now", migration or incident stories, and no rationale essays.
  A bare `REQ-…` / `ADR-…` pointer is allowed.
- The same for **JSDoc** (its type annotations are load-bearing for `typecheckJs`, ADR-0125),
  **Python docstrings** (module docstrings double as `--help` text) and PowerShell comment-based help.
- **Licence headers** (the GPL notice with `SPDX-License-Identifier`; Spotless enforces it on Java).
- **Tool directives** that are syntax for a tool, not prose: `// @ts-check`, `/* global */`,
  `/* exported */`, `eslint-disable…`, `stylelint-disable…`, `# shellcheck disable=|source=|shell=`,
  `# hadolint ignore=`, `# noqa`, `# pragma: no cover`, `# zizmor: ignore[…]`, `# image-pin-gate: ignore-file`, shebangs, Thymeleaf natural
  templates `/*[[…]]*/` and prototype-only `<!--/*/ … /*/-->`. A directive carries no explanation
  after it.

Reasoning goes into the **commit message and the PR body**; durable facts go into the spec, the ADR,
the runbook or the knowledge base — never into a comment.

Out of scope, because they are not hand-written code: generated files (`openapi.json`, SBOMs,
`gradle/verification-metadata.xml`, the Quadlet units and `env.d` templates that
`scripts/generate-quadlet.py` writes), vendored files (`gradlew`, `gradlew.bat`), applied Flyway
migrations (editing `V*.sql` changes its checksum and fails `validate` on every existing database),
test fixtures whose comments are the data under test, and Markdown documentation.

## Consequences

- A reader learns *why* from `git log -p` / `git blame` and the PR, which are dated and attributable,
  instead of from prose next to the code that nobody re-checks.
- Two gates that demanded a comment now demand something else: an empty `catch` names its variable
  `ignored` or `expected` (Checkstyle `EmptyCatchBlock`) and ESLint allows an empty `catch`
  (`no-empty` with `allowEmptyCatch`).
- REQ-OPS-020 keeps its measurements in a sizing ledger in the spec rather than beside each
  `memory:` / `cpus:` value; a changed limit changes its ledger row in the same PR.
- Earlier ADRs that point at a comment keep their text as the record of their decision; the comment
  they name no longer exists, and the fact it held lives in that ADR, the spec or the knowledge base.
- Nothing enforces the rule mechanically outside the two gates above; review does.

## Alternatives considered

- **Keep comments, prune only the stale ones** — rejected: it needs the same judgement on every
  future change and drifts again; the owner asked for none.
- **Move the comments into Javadoc** — rejected: it would keep the history, only in another place.
- **A lint rule banning comments now** — deferred: Checkstyle cannot tell a directive from prose in
  every language the repository uses, and a partial gate reads as full coverage.

## Amendment — 2026-10-02: `_` for an unused variable, empty catch blocks included

Owner decision D-14 of the domain modularisation plan (§8.1). The unnamed variable `_` (final since
Java 22) is accepted for every variable whose value is deliberately unused — a lambda parameter, a
catch parameter, a pattern component — and that includes the parameter of an empty `catch`, beside
the names `ignored` and `expected` of the consequence above. Checkstyle's `EmptyCatchBlock`
accepts `^(ignored|expected|_)$` as its `exceptionVariableName`. A descriptive name stays where the
name states intent. Not in `keycloak-spi`: it emits Java-21 bytecode, where `_` is not a valid
identifier, so javac refuses it there.

Applied to `main` on 2026-10-10 (backend, frontend, ingest, logging-support): 114 catch parameters,
110 lambda parameters and one type-pattern binding whose name did not occur in their body became `_`.
Names that state why the value is unused stay (`notFound`, `absent`, `race`, `malformed`,
`notAnId`, `userGone`, `alreadyRegistered` and the like). Test sources follow when their files are
touched.

## Amendment — 2026-10-31: short technical comments are allowed next to the documentation comment

Owner decision: the owner's global comment rule was relaxed on 2026-10-27, and this repository
follows it. The "no comments" decision above is replaced by the following; the rest of this ADR
(context, the `_` amendment, out-of-scope files) stands.

**Allowed.** The language's documentation comment (Javadoc, JSDoc, Python docstrings, PowerShell
comment-based help), unchanged in form, and **short technical comments** — `//`, `/* */`, `#`, `--`,
and `<!--/* … */-->` in templates — where they document what the code cannot say itself: a
non-obvious invariant, a format or protocol detail, a unit, a coordinate convention, why an
algorithm is shaped the way it is, or the spec section it implements. A technical comment is a line
or two, states only what is true now, and sits next to the code it describes.

**Still forbidden**, in a documentation comment and in a technical comment alike: history of any kind
(dates, PR or issue numbers, "previously" / "now" / "used to" / "was changed", migration or incident
stories), comments that restate the code, commented-out code or config, `TODO` / `FIXME` notes (a
follow-up issue instead), and long prose. The story of a change stays in the commit message and the
PR body; decisions go into ADRs, specs and runbooks.

**Unchanged.** Licence headers and tool directives stay. An empty block that a linter wants
justified is named or configured, not commented (`_` / `ignored` / `expected` for a catch variable).
Applied Flyway migrations, generated and vendored files, Markdown and test fixtures remain out of
scope. A rendered Thymeleaf template still never emits a plain `<!-- -->` (REQ-UI-023,
`TemplateCommentHygieneTest`): a technical comment there is a parser-level `<!--/* … */-->` block,
which the engine drops at parse time.

**Gates.** No gate is relaxed or added: Checkstyle `TodoComment` keeps failing `TODO` / `FIXME`,
`EmptyCatchBlock` keeps its variable-name form, and the stylesheet and script linters carry no rule
against comments. Existing code is not swept; the rule applies to what is written or touched. Review
enforces the rest.

**Consequences.** A comment that records a fact the code cannot show is welcome again; one that
records history or repeats the code is rejected in review. The earlier consequence "nothing enforces
the rule mechanically outside the two gates" still holds.
