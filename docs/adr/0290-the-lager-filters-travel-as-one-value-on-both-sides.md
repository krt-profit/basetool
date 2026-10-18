# ADR-0290 — The Lager filters travel as one value on both sides

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** [ADR-0068](0068-frontend-mega-controller-read-write-split.md) ·
  [ADR-0283](0283-a-game-items-size-grade-and-class-come-from-the-sc-wiki-only.md) ·
  `REQ-INV-029`, `REQ-INV-031`, `REQ-INV-040`, `REQ-INV-053`, `REQ-INV-063`, `REQ-FE-001` ·
  issue #2735

## Context

The Lager's filters travelled as loose `@RequestParam`s and positional booleans: 17 on the
frontend's `/inventory/my`, 98 across four backend endpoints, and `personalOnly`, `nonPersonalOnly`,
`stolenOnly` and `nonStolenOnly` through twelve service signatures. The write controller re-rendered
a listing with fifteen positional `null`s, and the filter chips repeated the parameter names in
script. Each new filter touched thirty to forty files.

## Decision

1. **Backend: one `LagerFilter` record**, bound once per endpoint with `@ModelAttribute` and passed
   down in place of the parameters. It normalises its lists, defaults the catalog to `MATERIAL`,
   owns the contradictory flag pairs (private and shared, stolen and not stolen) and rejects the
   filters the requested catalog cannot honour (`requireMatchingCatalog`, formerly in
   `CatalogFilterGuard`). A contradictory pair answers empty without reading the database, as the
   query did before. The flags are boxed so an unsent flag binds as `false`.
2. **Frontend: one `LagerQuery` record**, bound with `@ModelAttribute`. It owns the parsing, the
   emptiness test, the query parameters sent to the backend (`backendParams`) and those of the page
   URL (`urlParams`), both from one set of parameter names. `InventoryBackendClient` takes it
   (`grouped`, `myEntryIds`); the write controller re-renders with `LagerQuery.empty()`.
3. **The names exist once for the script.** The server renders the filter parameter names and the
   attribute-filter families on the Lager tree as `data-filter-params` and
   `data-attribute-filters`; the page scripts read them instead of repeating them.
4. **URL and REST parameter names are unchanged**, so bookmarks and the end-to-end tests keep
   working. The squadron-wide reads ignore the personal flags and the flat lists ignore the stolen
   flags and the attribute lists, as before; `openapi.json` lists the shared filter set on those endpoints.

## Consequences

- A new filter touches the two records, the repository query and the template.
- The `entry-ids` and `grouped` requests send their parameters in one canonical order.
- A malformed filter value fails the binding with 400 instead of a parameter type mismatch.

## Alternatives considered

- **Keep the positional parameters and add a filter to each signature** — the cost the issue
  measured.
- **One record shared by both modules** — the modules share no code besides the contract; the
  records differ in what they must parse (UUID and enum on one side, names on the other).
