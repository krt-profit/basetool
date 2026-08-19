# Design Spec — Basetool SC Extractor (GUI rebuild for epic #439)

> **Doc type:** Binding design spec for epic #439 — **implemented** (desktop tool: `basetool-bp-extractor` PR #5; frontend review surface: basetool #518; both 2026-06-10). Registered in [`docs/specs/INDEX.md`](specs/INDEX.md). Remains the binding visual reference for the extractor GUI and the shared review language; UI changes must keep matching it or amend it in the same PR.
>
> **Provenance / how to read this.** This is the **binding** UI/UX design for the SC
> Extractor desktop tool (and the shared visual language of the frontend review
> surface). It was produced with **Claude Design** and exported as a handoff bundle.
> **Canonical design + interactive prototype:**
> <https://api.anthropic.com/v1/design/h/mM6v29vZctN6kiBsF62_eA>
> — a gzip bundle; extract it and open `project/Basetool SC Extractor.html` to click
> through every screen and state. **Offline-capable mirror (vendored in this repo):**
> [`design/basetool-sc-extractor.offline.zip`](design/basetool-sc-extractor.offline.zip)
> — a single self-contained HTML file (all CSS / JS / fonts / images embedded; **no
> network needed**), shipped zipped; extract it and open the HTML in any browser to click
> through every screen offline. This Markdown file is the written contract that
> travels with the basetool repo; the prototype is the pixel source of truth. Where
> the two disagree, the prototype wins and this doc is updated. Imported 2026-06-05.
> When Phase 3 (#436) is built in `basetool-sc-extractor`, carry this doc (and the
> prototype from the bundle) into that repo too.
>
> **Zusammenfassung (DE):** Verbindliches UI/UX-Design für den Umbau von
> `basetool-bp-extractor` → **`basetool-sc-extractor`**. Ein zentraler Launcher
> führt per **Top-Tabs** in zwei Workflows: **Blueprints** (bestehend, Game.log →
> JSON) und **Refinery** (neu, Screenshots → lokales VLM via Ollama → JSON). Das
> Design ist als interaktiver Prototyp umgesetzt und soll bei der Umsetzung des
> Epics **1:1** als Vorlage dienen. Festgelegte Entscheidungen: **Tabs-Navigation,
> komfortable Dichte, Honeycomb-Textur an, Konfidenz als Prozentwert.**
>
> **Reference prototype:** the interactive HTML prototype inside the Claude Design
> bundle linked above (`project/Basetool SC Extractor.html`), or — fully offline — the
> vendored mirror
> [`design/basetool-sc-extractor.offline.zip`](design/basetool-sc-extractor.offline.zip) (extract + open the HTML) — every screen and state
> below is clickable; the DE/EN toggle, density, nav model and confidence display are
> togglable via the in-app Tweaks panel, but the **frozen defaults** are the ones
> listed under *Locked decisions*.

This document is the UI/UX contract for the rebuild. It does **not** change any
behavioural contract from the epic (the `RefineryExtract` JSON, the matching
algorithm, the phase split) — it specifies how the desktop tool and the frontend
review surface should **look and flow**. Paste-ready acceptance blocks per
sub-issue are in §7.

---

## 1. Locked decisions (owner, 2026-06-05)

| Aspect                      | Decision                                             | Rationale                                                                                                           |
|:----------------------------|:-----------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------|
| Navigation model            | **Top-Tabs + step stepper**                          | Workflows are peers and always one click away; the per-workflow stepper keeps the multi-step Refinery flow legible. |
| Density                     | **Comfortable**                                      | 44px touch targets, generous table rows — readability over cramming.                                                |
| Background texture          | **Honeycomb on** (`honeycomb-bg.svg`, ~0.32 opacity) | On-brand ambient HUD texture without hurting contrast.                                                              |
| Confidence display (review) | **Percent + status dot**                             | Compact, exact, colour-coded by threshold.                                                                          |
| Window chrome               | **Custom undecorated KRT title bar**                 | Keep the existing `WindowChrome.kt` look (app mark + title + min/max/close, orange hairline, resize grip).          |
| UI language                 | **German default, full EN parity**                   | Matches the live app's i18n; DE/EN toggle in the title bar.                                                         |

These freeze the prototype's defaults. The other nav models (rail, launcher) and
the bar/flag confidence styles were explored and **rejected for v1** but remain in
the prototype for reference.

---

## 2. Design foundations

All visuals come from the **DAS KARTELL / KRT design system** — do not invent
tokens. The desktop app already mirrors these in `ui/Theme.kt`; keep them in sync.

- **Palette.** Near-black `#000` canvas, `#141414` surfaces, `#1C1C1C` input/head
  fill, hairlines `#282828`, body text `#D2D2D2`, muted `#646464`. One hero accent:
  house orange `#E77E23` (hover `#EEB64B`). Status: danger `#A3000A`,
  success `#239E33`, warning `#FFD23F`, info `#355DDC`. Department hues only where a
  department actually applies.
- **Type.** One typeface: **Lato**. Headlines are Lato **Bold 700**, UPPERCASE,
  tracking 0.05em — headings, tab/step labels, status pills, the title bar (no
  separate display face). Body Lato Light 300, Bold 700 for labels/emphasis.
  Numeric/ID/path readouts use Lato with **tabular figures** (the system's "mono"
  stand-in).
- **Shape.** Square everywhere (radius 0). Only pills (chips/badges) and the radio
  control round. Cards are `.hud-box`: 1px `#282828` border + two diagonal orange
  corner brackets (TL + BR), translucent `rgba(20,20,20,0.5)` fill.
- **Elevation.** No soft shadows. Depth = hairlines + brackets. The only glow is the
  orange bloom on the single primary CTA and on toasts.
- **Action hierarchy (critical).** Exactly **one** filled-orange CTA per
  **context/panel** (`.btn--cta`; the design system's rule is "max 1 per panel",
  REQ-UI-002 says "per context" — a screen with two independent panels may carry
  one each, but never two competing CTAs in one context). Secondary = orange
  outline; routine = neutral ghost (orange on hover); destructive = quiet → red on
  hover. **Form labels are neutral grey**, data values are bright white on a
  surface chip — orange is for action + identity only.
- **Accessible status text (binding, REQ-UI-006).** Semantic colour used AS small
  text on dark surfaces must use the text tints `--color-danger-text #F2564B`,
  `--color-info-text #6C93EF`, `--color-success-text #2EBC3D` — the canonical
  danger/info hues fail WCAG as small text on black. Fills/borders/dots keep the
  canonical hues. This applies directly to the confidence **percent text** in the
  review tables (§5.4): tinted text + canonical-hue dot.
- **Iconography.** In-house 24×24, 2px-stroke line set (`currentColor`, sizes to
  1em). No emoji. Warnings use `⚠`/the warning glyph, status uses small square dots.
- **App mark (binding).** The tool wears the **Extractor variant** of the Basetool logo
  family (design skill `assets/basetool-extractor-*`), not the DAS KARTELL org mark. It
  shares the orbit ring and the star with the web app's Basetool mark but turns the wedge
  **downwards** into a catch bracket — same DNA, mirrored statement: data is extracted out
  of the verse, so the two icons read as a family side by side. It ships as the window and
  taskbar icon (`composeResources/drawable/basetool_extractor_icon.png`), the 18 dp mark in
  the title bar, and the multi-size installer icon (`src/main/resources/app.ico`, frames
  16/24/32/48/64/128/256 so Explorer never resamples one large frame). The org mark stays on
  org-branded surfaces only — mirrors REQ-UI-019 in
  [`specs/ui-design-system.md`](specs/ui-design-system.md).
- **Motion.** Restrained: 0.2s colour/background on hover; progress bars and the
  extraction stepper animate; no bounce/parallax.
- **Scrollbars.** KRT square scrollbars; the extraction console uses `.scroll-accent`
  (orange thumb).

---

## 3. Information architecture & navigation

```
Title bar (40px, #141414, orange hairline): Extractor mark · "BASETOOL SC EXTRACTOR" · [DE|EN] · _ □ ✕
│
├─ Tab bar:  [ ⌂ START ] [ ▣ BLUEPRINTS ] [ ⚗ REFINERY ]      (active = orange underline + tint)
│
├─ Step stepper (only inside a workflow): numbered, clickable, done = green ✔, active = orange
│
└─ Content area (honeycomb texture behind)
```

- **Start** = the launcher: greeting banner + two large workflow cards (icon, name,
  description, input hint, "Öffnen →"). Footer carries the "Unofficial fan tool" chip
  + the verbatim CIG trademark notice.
- **Blueprints** steps: `Konfiguration · Extraktion · Zusammenfassung`.
- **Refinery** steps: `Vorprüfung · Bilder · Extraktion · Review · Export`.
- Each step screen ends in a footer row: quiet back/secondary action on the left, the
  one orange CTA on the right.

---

## 4. Screen specs — Blueprint workflow (existing scope, restyled)

**4.1 Konfiguration.** Two read-only mono path fields (channel folder pre-filled
with the detected `…\StarCitizen\LIVE`; output JSON), each with a ghost "Wählen"
browse button. Single CTA **„Blueprints extrahieren"** + a neutral reassurance line
("read-only · no data leaves your machine"). Two info tiles (scope = blueprints only;
"Added notification" anchor avoids over-counting).

**4.2 Extraktion (transient).** Streaming progress: a status dot, `N / 424 files`
data-value, a progress bar, and a couple of mono log lines. Auto-advances to summary.

**4.3 Zusammenfassung.** Success alert (green, 4px left border) with the written JSON
path + `schemaVersion 1 · <count> blueprints · 424 log files`. Left column: detected
player chip (`greluc · 179 BP`) + "by category" mini-bars (Weapon/Armor/Ammo/
MiningTool/Other, each tinted by a department hue used as a neutral category colour).
Right: "most recently received" table (product · category chip · received · build).
Header actions: ghost "Im Explorer zeigen" + outline "Erneut".

---

## 5. Screen specs — Refinery workflow (new, the core of #439)

**5.1 Vorprüfung & Setup** — two side-by-side `PanelCard`s (header = surface fill +
orange left bar + Lato Bold uppercase title + status dot OK/Achtung/Fehlt), plus an SC-process
banner and the footer CTA **„Weiter: Bilder laden"** (disabled until ready). All edge
cases are first-class states (in the prototype a dashed "Demo-Zustand" switch flips
between them; in the product they are driven by real detection):

- **Ollama runtime card.** Endpoint + model key/value rows. States:
  - *Reachable + model present* → green "Erreichbar · Modell vorhanden · bereit."
  - *Reachable, model missing* → warning alert with `ollama pull qwen3-vl:8b-instruct` and a
    **„Laden"** CTA → switches to an inline pull **progress bar** → present.
  - *Ollama unreachable* → danger alert with a 2-step install hint
    (download from ollama.com → `ollama serve`) + a ghost **„Erneut prüfen"**.
- **Hardware preflight card.** GPU / VRAM / RAM rows; an **auto-selected model** chip;
  a min/recommended **tier bar** (CPU · MIN 6–8GB · EMPF 10–12GB+). Below recommended
  → warning with a **radio fallback**: low-VRAM model (Phase-0 bake-off winner —
  `glm-ocr` two-stage / `qwen3-vl:4b-instruct` / `gemma4:e4b-it-qat`) **or** CPU mode
  (works but slow). Above recommended → green "full accuracy".
- **SC-running soft warning.** If `StarCitizen.exe` is detected → non-blocking warning
  ("VLM and SC share GPU/VRAM — close SC for safe extraction") + an
  **„trotzdem fortfahren"** acknowledge checkbox that re-enables the CTA. Otherwise a
  calm green "SC not running — GPU free".
- Footer note: "Throttling on · one image at a time."

**5.2 Bilder laden.** "1 folder = 1 order" framing. A folder bar (mono path +
"Ordner wählen" / "+ Bilder"), then a thumbnail grid: each tile shows a resolution
chip (e.g. `7680×4320`), the file name, a `crop` tag (`vlm` / `manuell` /
`vorgecroppt` — pre-cropped panel images skip Locate), and a remove ×. A row of
mini-stats (Bilder · Auftrag · Auflösung · Modell). CTA **„Extraktion starten"**.

**5.3 Extraktion.** Left: overall `Bild X / N` + percent + progress + a per-image
ETA from the measured hardware tier (calmer than an indeterminate spinner), then
one row per image with a per-image **Locate → Normalize → Read** stage track
(passed = green, active = orange, todo = grey) — strictly **one image active at a
time**. Right: an orange-accented **console** pane streaming stage lines
(`· Normalize — …`, `✓ … — read`). The model chip (`qwen3-vl:8b-instruct`) sits in
the header. Cancel on the left; **„Weiter: Review"** enables on completion.
**Un-quoted-state warning (new):** when a screenshot is detected in the GET-QUOTE
state (YIELD column `--`, no cost/time), the image row gets an amber ⚠ state and a
warning alert explains: "Screenshot vor GET QUOTE aufgenommen — Ausbeute/Kosten
fehlen. Im Spiel GET QUOTE drücken und erneut aufnehmen." If **every** image is
un-quoted, the Review CTA carries a blocking notice (export is still possible, but
the basetool will flag the order as `UNQUOTED_ORDER`).

**5.4 Review & Bestätigung** *(this visual language is shared with frontend #435)*.
Header badges: `SETUP` panel type + `layout 92%`. A warning banner counts flagged
fields. Four **order header** cards (Standort/Methode/Gesamtkosten/Dauer), each with a
green ✔ (matched) or amber ⚠ (needs attention) and a left status bar; `LEVSKI` shows
`hasRefinery ✓`. Then the **goods table**: Material (raw, mono) · **Match** (green ✔ +
master-data name, or red `kein Treffer` chip + a `zuordnen` action) · Qualität ·
Input · Ausbeute (green `+`) · **Refine** toggle · **Konfidenz**. Rows below threshold
or unmatched get a coloured 3px left border. **Confidence = percent + dot**, coloured
≥90% success / 75–90% warning / <75% danger — the percent **text** uses the
accessible tints (`--color-*-text`, REQ-UI-006), the dot keeps the canonical hue;
the value itself is the **derived** confidence (deterministic validation + checksum,
see the master plan §3.1 item 5), never the model's self-estimate. Unmatched rows render the
backend's ranked `suggestions` as a pick list on the `zuordnen` action.
Implementation note (frontend #435): no toggle/switch component exists yet in the
design system or the basetool frontend — implement Refine as a styled checkbox or
introduce a new switch component **with an ADR + design-skill update in the same
PR**; the frontend also lacks a global `.alert-info` variant. Below: a "stays
manual — please complete" chip row (Besitzer default `greluc`, Mission, Sonstige
Kosten, Erzverkäufe, Start) with the empty/uncertain ones amber. CTA **„Als JSON
exportieren"**.

**5.5 Export & Upload.** Green success alert with the written
`RefineryExtract.json` path + a small summary. The step's single filled CTA is now
**„An Basetool senden"** — the **one-click send** of epic
[#639](https://github.com/krt-profit/basetool/issues/639) (transport spec
[`docs/specs/desktop-ingest.md`](specs/desktop-ingest.md), `REQ-INGEST-*`;
[ADR-0018](adr/0018-desktop-ingest-gateway-device-grant.md)). Pressing it:

1. **First send only — consent.** A KRT scrim modal (never a native dialog) names
   the artifact, the destination (your own basetool account), and the exact personal
   fields that leave the machine, and is confirmed once; the choice is persisted
   (non-secret) so later sends skip it. This is the opt-in that scopes the README's
   *„nichts verlässt deinen Rechner"* promise — egress is explicit and user-triggered.
2. **Browser approval (device grant).** The modal shows a short user code and opens
   the browser; under an existing Keycloak SSO session the approval is one click.
3. **Send + open.** The exact exported bytes go to the ingest gateway over TLS (the
   gateway forwards the per-`sub` token to the backend's import endpoint and stages
   the matched draft in single-use Redis), then the browser opens the **pre-filled**
   review form via `…?handoff=<id>`. The human **review-before-commit** step (§6) is
   unchanged — nothing is persisted until the user confirms in the browser.

The earlier **manual-upload flow** (Refinery → Import order → pick the JSON → review
the pre-filled form) remains documented as the offline fallback; **„Neue Extraktion"**
is demoted to a ghost action. A side **provenance** panel mirrors the contract fields
(tool, model, schemaVersion, panelType=SETUP, generatedAt). The CLI never sends.

---

## 6. Cross-cutting requirements

- **i18n.** Every string externalised; DE primary, EN parity; design for German
  compound length (don't pin label widths). The title-bar DE/EN toggle is the
  switch.
- **No native dialogs.** Confirmations/feedback use KRT modals + corner-bracket
  toasts, never `confirm()/alert()`.
- **Review-before-commit** is the safety net — nothing is saved without explicit user
  confirmation; low-confidence/unmatched items are always visually flagged.
- **Fan-kit compliance.** Keep the *Made by the Community* logo (unaltered) + the
  verbatim CIG trademark notice wherever the SC brand shows (footer of Start).

---

## 7. Per-issue integration (paste-ready)

Append each block to the corresponding issue (or post as a comment). They add a
**Design / UI acceptance** section; they do not alter existing behavioural DoD.

### → Epic #439

```md
### Design (binding)
The GUI is specified by `docs/DESIGN_SC_EXTRACTOR.md` + the interactive prototype.
Frozen decisions: Top-Tabs navigation · comfortable density · honeycomb texture on ·
confidence shown as percent · custom KRT title bar · DE default with EN parity.
Build the rebuilt tool to match the prototype screen-for-screen.
```

### → #436 Phase 3 — Desktop (rebrand + launcher + Ollama + resource safety)

```md
### Design / UI acceptance
- [ ] App rebranded to "Basetool SC Extractor"; custom KRT title bar
      (logo + title + DE/EN toggle + min/max/close + orange hairline + resize grip).
- [ ] **Top-Tabs** launcher: Start · Blueprints · Refinery; per-workflow step
      stepper; Start screen = greeting + two workflow cards + fan-kit footer.
- [ ] Blueprint workflow restyled to spec (config / running / summary), single
      orange CTA per context/panel, neutral labels, KRT tables.
- [ ] Refinery preflight implements every state from §5.1: Ollama
      reachable/model-present/model-missing(+pull progress)/unreachable(+install
      hint); hardware GPU/VRAM/RAM + auto-selected model + min/recommended tier bar
      + below-recommended fallback radio (low-VRAM model | CPU mode); SC-running
      soft warning + acknowledge checkbox; "one image at a time" throttle note.
- [ ] Bilder-laden: 1 folder = 1 order, thumbnail grid with resolution + crop tags
      (vlm | manuell | vorgecroppt).
- [ ] Extraktion: per-image Locate→Normalize→Read tracks (one active at a time) +
      orange-accent console + model chip + per-image ETA; un-quoted (GET-QUOTE)
      screenshots get an amber ⚠ row state + re-capture warning (§5.3).
- [ ] Export screen: written-path success + manual-upload instructions + provenance.
- [ ] DE/EN parity; comfortable density; honeycomb texture (~0.32); no native dialogs.
- [ ] `CLAUDE.md` scope section widened from "blueprints only" to multi-workflow.
```

### → #435 Phase 2 — Frontend pre-filled review form + import UI

```md
### Design / UI acceptance
- [ ] Pre-filled review form uses the §5.4 visual language: order-header cards with
      matched(✔)/needs-attention(⚠) state; goods table with Material · Match
      (✔ name | red "no match" + assign with ranked suggestions) · Quality · Input ·
      Yield(+) · Refine (styled checkbox or new switch component + ADR) ·
      **Confidence = percent + colour dot** (≥90 success / 75–90 warn / <75 danger;
      percent text uses the accessible `--color-*-text` tints, REQ-UI-006); flagged
      rows get a coloured left border.
- [ ] A banner counts flagged fields; "stays manual — please complete" items
      (owner default = uploader, mission, otherExpenses, oreSales, startedAt) are
      visually marked; SETUP panel-type + layoutConfidence badges shown.
- [ ] Error/empty states covered: not-a-JSON file, wrong schemaVersion, zero
      matched rows, all rows un-quoted — each a KRT toast/inline alert with a
      recovery hint.
- [ ] Import UI mirrors the blueprint preview/apply import (hidden file input +
      styled trigger); review-before-commit enforced.
- [ ] KRT styling, action hierarchy (one orange CTA = confirm), DE/EN parity.
```

### → #434 Phase 1 — Backend import endpoint + master-data matching

```md
### Design note
No UI, but the draft DTO must carry what the review surface renders: per-field /
per-row match state + confidence, unmatched flags, and the provenance fields
(tool, model, schemaVersion, panelType, generatedAt) surfaced on the export screen.
```

### → #433 Phase 0 — Spike (model + resolution pipeline + hardware tiers)

```md
### Design note
The hardware tiers measured here feed the preflight UI (§5.1): the min/recommended
tier bar, the auto-selected model chip, and the low-VRAM/CPU fallback copy. Provide
the GB-per-model-size + CPU-speed numbers so the preflight thresholds are exact.
```

---

*Source of truth for pixels: the prototype in the Claude Design bundle
(<https://api.anthropic.com/v1/design/h/mM6v29vZctN6kiBsF62_eA>), mirrored offline at
the vendored zip `design/basetool-sc-extractor.offline.zip` (extract + open the HTML). This doc is the
written contract; where they disagree, reconcile against the prototype and update
this doc.*
