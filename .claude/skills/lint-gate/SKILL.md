---
name: lint-gate
description: The full local lint gate that must be green before every push — Spotless, Checkstyle, SpotBugs, Prettier, the strict frontend asset linters (Stylelint, ESLint, HTMLHint) and the JS type check. Use when preparing to push, when CI fails on formatting or lint, or when auto-fixing CSS/JS findings with the Gradle Node plugin's private Node.
---

# Lint gate

The root `CLAUDE.md` carries the binding rule: **before every push, `./gradlew spotlessApply`
(whole repo) plus the full frontend asset-lint gate must be green — no exceptions.** This skill is
the how-to behind that rule.

## Why `spotlessApply` alone is not enough

**Run `./gradlew spotlessApply` (whole repo) locally before *every* push — no exceptions, even for a one-line test or comment edit.** It formats **all** Java source sets (incl. `e2e`), the Gradle Kotlin scripts, and the YAML / Markdown / `.properties` files (whitespace-level for the last three); running a narrower task (`:<module>:checkstyleMain`, `compileE2eJava`, …) is **not** a substitute and will let a formatting violation slip through to CI. Checkstyle does not help there: `checkstyleE2e` is deliberately disabled, so Spotless is the only gate on the `e2e` source set's formatting. Spotless is wired into `check` via `isEnforceCheck = true`, and Checkstyle runs with `isIgnoreFailures = false` + `maxWarnings = 0` — any unformatted file or new Checkstyle warning fails CI immediately.

## The strict frontend gates CI runs independently

**ALL lint tasks must be green locally before *every* push — no exceptions.** Formatting (`spotlessApply` + `:frontend:prettierApply`) is necessary but **not sufficient**: `:frontend:check` also depends on strict gates that are **not** covered by Spotless or Checkstyle and fail the build on any finding (`frontend/build.gradle.kts`, the `check` wiring at the end of the Node section):

| Task | Tool | Covers |
| --- | --- | --- |
| `:frontend:lintCss` | Stylelint | `static/css/**` — e.g. media-query *range* notation `(width <= Npx)` not `(max-width: Npx)`, modern `rgb(r g b / a%)` not `rgba(...)` |
| `:frontend:lintCssInline` | Stylelint (+ postcss-html for templates) | the page stylesheets under `static/css/pages/` (the former inline `<style>` blocks, FE-PERF-02) and any `<style>` block that comes back — with the tiny `.stylelintrc.templates.json` rule set, not the strict one |
| `:frontend:lintJs` | ESLint | `static/js/**` — `no-var` → `let`/`const`, `prefer-const`, `object-shorthand` (both autofixable, since 2026-09-23), unused caught errors `_`-prefixed, raw `fetch` writes (REQ-FE-002), unescaped HTML sinks (REQ-FE-022), … |
| `:frontend:lintProbeJs` | ESLint | the e2e probe script, extracted from its Java text block |
| `:frontend:lintHtml` | HTMLHint | `templates/**` |
| `:frontend:prettierCheck` | Prettier | CSS / JS / `types/**/*.d.ts` formatting |
| `:frontend:typecheckJs` | `tsc --noEmit` | files carrying `// @ts-check` (REQ-FE-018, ADR-0125) |
| `:frontend:testGenApiTypes` | Node | the self-test of the OpenAPI → `.d.ts` emitter |

Before pushing, run the whole sweep and get it to **zero findings**:

```bash
./gradlew check
```

For a faster loop on a CSS/JS/template-only change, the frontend gates alone:

```bash
./gradlew :frontend:lintCss :frontend:lintCssInline :frontend:lintJs :frontend:lintProbeJs :frontend:lintHtml :frontend:prettierCheck :frontend:typecheckJs
```

## Auto-fixing CSS / JS findings

Prettier findings: `./gradlew :frontend:prettierApply`. Stylelint/ESLint auto-fix most of theirs — the Gradle Node plugin's private Node (not on `PATH`) lives under `frontend/.gradle/nodejs/node-v<version>-<os>/`; put it on `PATH` and run `node_modules/.bin/stylelint --fix <file.css>` / `node_modules/.bin/eslint --fix <file.js>` from `frontend/`, then re-run the Gradle lint task to confirm.

Never push relying only on the tests + Spotless being green.
