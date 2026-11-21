> **Archived 2026-11-21.** Doc type: Historical record — the design prototype of the SC Extractor
> rebuild (epic #439, closed). The extractor is discontinued and its repository archived.
>
> **Current truth:** [`refinery-screenshot-import.md`](../../specs/refinery-screenshot-import.md).
> Index of the archive: [`README.md`](../README.md).

# Design references — Basetool SC Extractor

This folder holds the **offline-capable design prototype** for the SC Extractor rebuild
(epic [#439](https://github.com/krt-profit/basetool/issues/439)).

- **[`basetool-sc-extractor.offline.zip`](basetool-sc-extractor.offline.zip)** — a single,
  fully self-contained HTML file (`Basetool SC Extractor - offline.html`) (all CSS, JS, fonts and images embedded as an inline
  asset blob; **no network needed**), shipped zipped so the prototype's generated JS stays
  out of the repo's code scanning. Extract it and open the HTML in any browser to click
  through every screen and state of the launcher / Blueprint / Refinery workflows offline.
  It is the offline mirror of the Claude Design prototype, and a permanent template even if
  the online link ever goes away.

**Sources at the time**

- Written UI/UX contract: [`../DESIGN_SC_EXTRACTOR.md`](../DESIGN_SC_EXTRACTOR.md)
- Online Claude Design prototype / handoff bundle:
  <https://api.anthropic.com/v1/design/h/mM6v29vZctN6kiBsF62_eA>
- Living spec of the import it feeds: [`../../specs/refinery-screenshot-import.md`](../../specs/refinery-screenshot-import.md)
- Implementation plan (historical, archived — v1 shipped 2026-06-10):
  [`../REFINERY_SCREENSHOT_IMPORT_PLAN.md`](../REFINERY_SCREENSHOT_IMPORT_PLAN.md)

Pixels: the prototype wins; `DESIGN_SC_EXTRACTOR.md` is the written contract.
