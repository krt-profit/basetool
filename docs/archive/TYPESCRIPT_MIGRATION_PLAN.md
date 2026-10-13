> **Archived 2026-10-13.** Doc type: Historical record — frozen, kept as a record and no longer updated. The checker work it sequenced is done; the conversion to `.ts` it priced is not pursued.
>
> **Current truth:** spec REQ-FE-018 in [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md), [ADR-0125](../adr/0125-typed-javascript-via-checkjs-not-typescript.md) (with its amendment of 2026-10-13), [ADR-0130](../adr/0130-own-openapi-dts-emitter-and-typescript-7.md), [ADR-0250](../adr/0250-scripts-are-es-modules-behind-a-per-page-import-map.md) and the *Type checking* section of [`frontend/CLAUDE.md`](../../frontend/CLAUDE.md). Index of the archive: [`README.md`](README.md).

# TypeScript migration plan

> **Owner area:** FE/UI · **Related ADRs:** ADR-0125, ADR-0130, ADR-0069, ADR-0250, ADR-0012/0013 · **Spec:** REQ-FE-018

## Outcome

The plan was written on 2026-08 as a costed option: type-check the existing JavaScript (ADR-0125),
and convert to TypeScript only if a trigger appeared. By 2026-10-13 the checker half was finished
and the decisive blocker of the conversion half had disappeared, so the plan was closed.

| Phase | What it planned | Outcome |
|-------|-----------------|---------|
| 0 Checked JavaScript | `tsc --noEmit` in the gate, `.d.ts` contracts, DTO types generated from `openapi.json` | Done (ADR-0125, ADR-0130) |
| 1 `@ts-check` coverage | Every script green, then `checkJs` globally | Done 2026-10-11 (all scripts green, three large scripts split first); flipped to `checkJs: true` with the per-file pragmas removed on 2026-10-13 |
| 2 Inline template JS | Extract the logic still inline in the templates | Done in effect (ADR-0069): the 57 inline blocks that remain are data bootstraps (`th:inline`, about 1 400 lines) plus the `krtEvents` stub and watchdog in the head, kept as a reviewed exception (ADR-0250, `InlineScriptDataOnlyTest`) |
| 3 Strictness | `noImplicitAny`, `useUnknownInCatchVariables`, real shapes for the `unknown` placeholders | Done 2026-10-13: both options on, 813 implicit-`any` errors annotated with JSDoc, `createReceiver`, `krtSearchableSelect` and the combobox sources typed |
| 4 Build step | A bundler or transpiler emitting classic scripts | Not built |
| 5 `.js` → `.ts` | Mechanical conversion, still classic scripts | Not done; 0 `.ts` files |
| 6 Modules | Replace the shared global scope with `import` / `export` | Done another way: ES modules behind a per-page import map with no build step (ADR-0250, 2026-10-12) |

## Why the conversion is not pursued

- The premise of "not now" was ADR-0069's shared global lexical scope, which TypeScript could only
  escape with a bundler. ADR-0250 ended that scope natively, so the largest risk and the largest cost
  line of phases 4 to 6 are gone.
- Every script is checked under `strict`, `checkJs`, `noImplicitAny` and
  `useUnknownInCatchVariables`, and the backend DTO types are generated. What `.ts` would still add is
  shorter annotations; what it costs is a build step between the sources and the served files, which
  must keep the content-hashed `immutable` asset URLs, the import map built from the source specifiers,
  and the line numbers of client-error reports intact.
- The sources stay the files the browser receives; that property is what ADR-0125 and the declined
  minification of 2026-09-23 rest on.

## When to reopen it

Only through a new ADR, and only if one of these holds:

1. A bundler or an npm runtime dependency that cannot be vendored arrives for an independent reason.
2. JSDoc visibly costs more than the equivalent TypeScript syntax would, for example through repeated
   generic or overloaded signatures that JSDoc spells badly.
3. A recurring defect class sits inside a single module, where the generated DTO types and the
   strict checker do not reach.

The lightest form of the conversion is then a type-stripping step without bundling (erasable syntax
only, one output file per source file), because the import map already carries the module graph.
