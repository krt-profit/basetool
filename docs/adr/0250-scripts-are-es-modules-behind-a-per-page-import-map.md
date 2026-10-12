# ADR-0250 — Scripts are ES modules behind a per-page import map

- **Status:** Accepted — implements the ES-module row of the domain modularisation plan §8.2 (F5).
- **Date:** 2026-10-12
- **Deciders:** @greluc
- **Related:** spec [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (`REQ-FE-035`,
  `REQ-FE-018`, `REQ-FE-023`, `REQ-FE-034`) · [ADR-0069](0069-inline-js-page-module-extraction.md)
  (page modules, inline scripts as data) · [ADR-0125](0125-typed-javascript-via-checkjs-not-typescript.md)
  (type check) · [ADR-0239](0239-the-browser-baseline-is-baseline-2025-and-trusted-types-follow.md)
  (browser baseline) · [`DOMAIN_MODULARISATION_PLAN.md`](../DOMAIN_MODULARISATION_PLAN.md) §7.8, §8.2

## Context

Until now the 140 scripts under `static/js` were classic `<script defer>` tags sharing one global
scope. Their dependencies on one another were invisible: a function declared in one file and called
by name in another, a `let` another file assigned, a section helper of a 2,081-line immediately
invoked function. The plan allowed ES modules "only after the global-scope and inline-script work,
and only behind a server-rendered import map", because every asset URL is content-hashed and cached
for a year as `immutable`, and an `import` specifier cannot carry a hash. The preconditions hold:
every inline script is a data bootstrap (ADR-0069), the cross-file name dependencies were four small
clusters, and the CSP is `script-src 'nonce-…' 'strict-dynamic'`, which admits the static imports of
a nonced module.

## Decision

1. **A script is a module unless `frontend/module-ratchet.json` lists it as classic.** Today the list
   holds `kernel/krt-client-error.js`, which stays the head's first, synchronous script. A module is
   linked `<script type="module" th:src="@{…}" th:attr="nonce=${cspNonce}">` without `defer` (a module
   is deferred and runs in document order with the other deferred scripts). `ModuleScriptsTest` fails
   on a module linked as a classic script and the other way round; the list may only shrink.
2. **Imports are static and use the absolute asset path** — `import { x } from '/js/<domain>/<file>.js'`
   — and only of the module's own domain or of the kernel. No relative path, no bare specifier, no
   dynamic `import()`, no import of another domain. ESLint enforces it per domain folder with the
   built-in `no-restricted-imports` (no dependency was added), `ModuleScriptsTest` repeats it and
   rejects an import cycle, and `tsconfig.json` maps `/js/*` to the script folder so the type check
   follows the imports (`moduleDetection: force`, so every script is checked as a module). The
   kernel API is imported, not read from `window`: every kernel script exports its API and its
   consumers import it (2,000 reads in 100 modules); `window.<name>` is still set where something
   outside the module graph needs it (see *Consequences*).
3. **Each page renders an import map in its head** (`kernel/fragments/head.html`), produced by the
   `moduleImportMap` bean: the content-hashed URL of every module the page's module scripts import,
   transitively, keyed by the absolute path. The bean finds a page's scripts by scanning the page
   template and the fragments it includes (the layout, the head and the page's own tail), reads each
   module's static imports, and asks the same `ResourceUrlProvider` the `@{…}` links use for the
   hashed URL, so an entry script and an import of the same module are one module instance. The map
   is an inline `<script type="importmap">` with the request's CSP nonce, placed before the first
   module script. A page without imports renders an empty map.
4. **`bank.js` is split along its responsibilities** into an entry module and 16 modules in
   `static/js/bank/`. Every module registers its document listeners in an `init…` function, and the
   entry calls them in the order the one function registered them, so the order of listeners per
   event type — which decides which handler sees a change first — is unchanged.
5. **`krt-client-error.js` and the head's `krtEvents` stub stay classic** because they must run
   before, and without, the module graph.
6. **A script under its plain URL is revalidated.** `/js/**` answers `Cache-Control: no-cache`, and
   only a content-hashed name (`*-` plus 32 characters, `.js`) answers `max-age=31536000, public,
   immutable`, so a module reached outside the import map can never be cached stale for a year.
7. **Wrappers go where it is provably neutral.** A top-level immediately invoked function around a
   module body is removed when it has no `return`, `await`, `this` or `arguments` of its own, no
   name that collides with another top-level name of the file, and removing it leaves the set of
   global names the file reads unchanged; the `'use strict'` directive goes everywhere but in the
   classic beacon.

## Consequences

- A changed module is a new URL and a stale cached copy cannot be loaded: every import of a page is
  in its map. A module imported through a template construct the scan cannot follow would be
  requested under its plain URL, which is revalidated, not immutable; `ModuleScriptsTest` pins that
  every page finds its own module scripts and renders exactly its import closure.
- Module code is strict. The scripts without their own `'use strict'` now run strictly; `no-undef` as a module
  (ESLint) and the type check found nothing, and the browser check of two rendered pages under the
  real CSP showed no violation. The end-to-end suite, which loads every page route
  (`ScriptLoadOrderE2eTest`), is the remaining check.
- **`modulepreload` was measured and is not used.** `<link rel="modulepreload">` for the import
  closure works under `script-src 'nonce-…' 'strict-dynamic'` only with the page's nonce on the link
  (without it the preload is blocked and every module is fetched twice: 971 ms against 520 ms to
  `DOMContentLoaded` for the bank page), and it must come after the import map (before it the map is
  ignored). With the nonce and the right order the bank page (28 module scripts, a graph five
  levels deep) took 518, 547 and 1,035 ms (the first, cold load) against 516, 517 and 570 ms without,
  over HTTP/1.1 with 50 ms per script, where Chromium's six connections per host, not the depth, set the time. HTTP/2 could not
  be measured (the browser pane does not trust a local certificate). The expected gain is the depth of
  the graph in round trips on a cold cache only, since a warm module is not fetched at all.
- A kernel script that something outside the module graph reads keeps its `window` property, and
  each is recorded with its reader: `krtFetch` and `krtCsrf` (Playwright sources and the node tests),
  `krtLiveSync` (ten Playwright sources and the node test), `krtModal`, `krtSearchableSelect`,
  `krtHtml` and `krtComboboxRemoteSources` (Playwright sources, and the Trusted Types node test for
  `krtHtml`), `krtEvents` (the head stub that `event-delegation.js` replaces and its five second
  check), `krtI18nText` (the classic beacon is its owner; modules import a one-line facade). The
  node tests run a module graph as one classic script (`scripts/flatten-module.mjs`).
- The drawer and the palette, which are optional to each other, meet in a registry module
  (`krt-chrome.js`), which also keeps their imports acyclic.
- Not converted: 23 `window.<name>` seams between the parts of one page or between a page and a
  shared module (the mission, order, operation and inventory parts, `krtMaterialRelease` and
  `krtMaterialRequest` read by Playwright and another domain, `filterTable` read by the kernel). They
  are late-bound on purpose: the parts evaluate in tag order, and an import would make a part
  evaluate its dependency first.

## Rejected

- **A bundler** — the plan's rule: a compromised bundler release would be site-wide script injection
  the nonce CSP cannot stop, and the served file would no longer be the checked file.
- **Relative specifiers and a rewriting resource transformer** — the import map already rewrites
  every specifier to its hashed URL, and an absolute path is also what ESLint and the type check
  resolve without a plugin.
- **One map with every module on every page** — 140 entries on every response for the benefit of
  nobody; the per-page closure is a few hundred bytes.
- **`eslint-plugin-import`** — a new dependency to check what `no-restricted-imports` and one Java
  test already check.
