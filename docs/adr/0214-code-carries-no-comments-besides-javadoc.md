# ADR-0214 — Code carries no comments besides Javadoc

- **Status:** Accepted
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
  `# hadolint ignore=`, `# noqa`, `# image-pin-gate: ignore-file`, shebangs, Thymeleaf natural
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
