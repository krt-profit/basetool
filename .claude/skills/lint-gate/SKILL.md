---
name: lint-gate
description: The full local lint gate that must be green before every push — Spotless, Checkstyle, SpotBugs, Prettier, and the three strict frontend asset linters (Stylelint, ESLint, HTMLHint). Use when preparing to push, when CI fails on formatting or lint, or when auto-fixing CSS/JS findings with the Gradle Node plugin's private Node.
---

# Lint gate

The root `CLAUDE.md` carries the binding rule: **before every push, `./gradlew spotlessApply`
(whole repo) plus the full frontend asset-lint gate must be green — no exceptions.** This skill is
the how-to behind that rule.

## Why `spotlessApply` alone is not enough

**Run `./gradlew spotlessApply` (whole repo) locally before *every* push — no exceptions, even for a one-line test or comment edit.** It formats **all** source sets (incl. `e2e`) and the `.properties` / Markdown / Gradle files; running a narrower task (`:<module>:checkstyleMain`, `compileE2eJava`, `checkstyleE2e`, …) is **not** a substitute and will let a formatting violation slip through to CI (e.g. an over-long Javadoc line in an `e2e` test that `checkstyleE2e` does not catch). Spotless is wired into `check` via `isEnforceCheck = true`, and Checkstyle runs with `isIgnoreFailures = false` + `maxWarnings = 0` — any unformatted file or new Checkstyle warning fails CI immediately.

## The three strict asset linters CI gates independently

**ALL lint tasks must be green locally before *every* push — no exceptions.** Formatting (`spotlessApply` + `:frontend:prettierApply`) is necessary but **not sufficient**: the frontend also runs three *strict* asset linters that fail CI independently and are **not** covered by Spotless/Prettier/Checkstyle — **`:frontend:lintCss`** (Stylelint: e.g. media-query *range* notation `(width <= Npx)` not `(max-width: Npx)`, and modern `rgb(r g b / a%)` not `rgba(...)`), **`:frontend:lintJs`** (ESLint: `no-var` → use `let`/`const`, unused caught errors must be `_`-prefixed, etc.), and **`:frontend:lintHtml`** (HTMLHint) — plus the static type check **`:frontend:typecheckJs`** (`tsc --noEmit` over the files carrying `// @ts-check`; REQ-FE-018, ADR-0125). Before pushing any change that touches `src/main/resources/static/**` (CSS/JS) or `templates/**`, run — and get to **zero findings** — the full local gate for both modules:

```bash
./gradlew :backend:check :frontend:lintCss :frontend:lintJs :frontend:lintHtml :frontend:prettierCheck :frontend:typecheckJs
```

(or the whole `./gradlew check`, which wires them all in).

## Auto-fixing CSS / JS findings

Stylelint/ESLint auto-fix most findings — the Gradle Node plugin's private Node lives under `frontend/.gradle/nodejs/…/node.exe`; put it on `PATH` and run `node_modules/.bin/stylelint --fix <file.css>` / `node_modules/.bin/eslint --fix <file.js>`, then re-run the Gradle lint task to confirm.

Never push relying only on the tests + Spotless being green.
