# ADR-0325 — The backend serves the dashboard widget catalogue, and widget fragments share one controller kind

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Related:** REQ-UI-034 · [ADR-0299](0299-the-home-page-is-a-personalisable-widget-dashboard.md) · spec
  [`ui-design-system.md`](../specs/ui-design-system.md)

## Context

The dashboard widget catalogue existed twice. The backend enum `DashboardWidgetType` and the
frontend enum of the same name each carried the mode, the default column, the offered options and
the status choices; the frontend option helper repeated the defaults and the choice lists. A new
widget or option was therefore two changes that nothing forced to agree. The five widget
controllers also repeated the layout opt-in, the shared path and the `krtFetch` header condition on
every handler.

## Decision

1. **The backend enum is the one catalogue.** `GET /api/v1/navigation/dashboard-widgets` serves
   every widget type with its mode, default column, whether the default dashboard holds it, its
   options in dialog order, its status choices in display order and its defaults, plus the choice
   lists of the days, range, rows and status options. The body holds no member data, so any
   authenticated caller may read it, and a golden file pins it for both modules.
2. **The frontend mirrors it and keeps only what depends on the caller.** The read goes through the
   frontend's catalogue cache (6 hours, the catalogue only changes with a release). What stays in
   the frontend is the gate (roles and flags) per widget type, the endpoint segment and the message
   key, in one small enum. The vocabulary enums (mode, column, option, status) stay as mirrors the
   contract test pins to the backend: they carry the behaviour the code branches on, and a backend
   value without a frontend constant fails the build instead of the page.
3. **A widget type the backend serves must have a frontend gate.** The enum mirror check of the
   contract test fails otherwise, so a new widget cannot reach production without its gate.
4. **Fragment controllers share one kind.** `@FragmentController` carries the layout opt-in
   (`@UsesLayoutModel`) and the shared path, `@FragmentGetMapping` the `GET` and the `krtFetch`
   header condition. `@Controller` stays on the class so the architecture rules that look for it
   keep seeing the controller; those rules look through the composed annotation for the opt-in.

## Consequences

- A new widget over existing options is one backend change plus its gate, label, fragment and
  controller read on the frontend; a new option kind or status value still needs its constant on
  both sides, which the contract test enforces.
- A frontend that cannot read the catalogue shows the dashboard's error state, like any other failed
  read.
- The status choices keep their order explicitly in the enum, so the options dialog and the golden
  file agree.

## Rejected

- **Embedding the catalogue in the dashboard read:** the widget fragments are separate requests and
  would each need the whole dashboard.
- **Generating the frontend enums from the OpenAPI document:** the gate and the behaviour of the
  vocabulary enums are hand-written, and a build step would hide a missing gate.
- **Making the vocabulary enums strings:** it would replace compile-time branches in the widget
  controllers by string comparisons.