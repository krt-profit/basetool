# ADR-0164 — The web app is installable, and deliberately has no service worker

- **Status:** Accepted — implemented (owner-requested 2026-09-13)
- **Date:** 2026-09-13
- **Deciders:** @greluc (asked for the PWA after reading the Apple assessment), Claude (analysis,
  measurement, implementation)
- **Related:** specs [`ui-design-system.md`](../specs/ui-design-system.md) `REQ-UI-020` (this
  decision's requirement), `REQ-UI-019` (the icon family it reuses), `REQ-SEC-031` (`no-store` on
  sensitive reads), `REQ-SEC-052` (the public-path table) ·
  [ADR-0159](0159-the-basetool-has-no-anonymous-or-guest-surface.md) ·
  `basetool-android/docs/APPLE_PLATFORM_FEASIBILITY.md` (the analysis that produced the question)

## Context

The tool has a native Android companion and **nothing for iPhone or iPad**. The question of whether
the Android app could follow to Apple devices was assessed on 2026-09-13, and the answer was that the
code would port while the *channel* would not: Apple's Web Distribution — the only route shaped like
the Android app's "signed artifact from GitHub Releases" delivery — requires, from 2026-10-01, one of
seven eligibility bars, among them a USD 1 000 000 stand-by letter of credit or a million annual
installs. A GPL-3.0 fan companion for one Star Citizen organisation meets none of them and cannot
reach any by doing better engineering. What remains on that platform is the App Store (review per
release, plus a standing approved account in the production realm handed to a third-party reviewer)
or nothing.

So for those members the web app **is** the mobile client, and the only question left is whether it
arrives as an icon or as a bookmark. Three things were measured in this repository before deciding,
and all three lowered the cost:

|                           Claim                           |                                                                         Evidence (2026-09-13)                                                                          |
|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The layout already survives a phone viewport              | `fragments/head.html` ships the `width=device-width` viewport; `styles.css` carries 16 media queries, `personal-inventory.css` 7, `bank.css` 6, `materialboerse.css` 3 |
| The home-screen icon already exists and is already public | `apple-touch-icon` → `logos/basetool-appicon-512.png` (REQ-UI-019), and `/logos/**` is permitAll and already exempt from the role-sync and terms gates                 |
| Nothing was missing but the manifest                      | `find` returns no web app manifest and no service worker anywhere in the frontend                                                                                      |
| The CSP does not stand in the way                         | `SecurityHeaders` sets `default-src 'self'` and no `manifest-src`, so a same-origin manifest inherits `'self'`                                                         |

The interesting decision is therefore not "should the tool be installable" — it is what *kind* of
installable, because the word "PWA" carries a service worker with it by convention, and a service
worker is a client-side cache.

## Decision

**Ship the manifest and the `<head>` contract. Ship no service worker.**

Four parts, all of them in `REQ-UI-020`:

1. **`/manifest.webmanifest` is rendered by a controller**, not served from `static/`. Its
   user-visible strings (`name`, `short_name`, `description`) come from the message bundles, because
   every user-visible string in this application does; its media type is `application/manifest+json`,
   which Spring's static-resource handler does not know; and it must answer `200` and never `302`.
   That third property is not theoretical — it is exactly how `/.well-known/assetlinks.json` broke
   the Android login once, and both paths now sit in the same `SecurityConfig` allow-list.
2. **The manifest link carries `crossorigin="use-credentials"`.** A manifest is fetched *without*
   cookies by default and the locale lives in `KRT_LOCALE`, so without it every install would be
   labelled in the default language. The consequence is that the fetch arrives **authenticated**,
   which is why the path is also exempted in `TermsAcceptanceGateFilter` — a member who has not
   accepted the terms would otherwise install an app whose manifest is the consent page — and in
   `BackendRoleSyncFilter`, which would otherwise spend a `/api/v1/users/me` round trip per fetch.
3. **`theme-color` is the header fill (`#141414`), not the black page background.** On a phone the
   status bar sits directly above the header, and matching the header is what removes the seam. The
   value exists twice — a `<meta>` tag cannot read a CSS custom property — and the two are pinned
   together by a test rather than by a comment.
4. **No service worker.** A worker that cached navigations would put member data — balances, rosters,
   stock — into a second store outside every path that clears the first, while the backend marks
   those reads `no-store` (REQ-SEC-031) precisely so they are not copied. The same reasoning already
   governs the Android app, which ships with no HTTP cache for exactly this reason.

## Alternatives rejected

- **A caching service worker, for offline use.** Rejected on the data posture above, not on effort.
  A worker restricted to static assets only (CSS, JS, fonts, icons — never a navigation, never an
  API response) would be compatible with it, and remains open as a later, separately-argued step; it
  buys a faster repeat load and nothing else, since the pages themselves are server-rendered and
  cannot be useful offline.
- **A static `manifest.webmanifest` under `static/`.** Cheaper by one class, and wrong on three
  counts: hardcoded user-visible strings, the wrong content type, and a `302` into OAuth for the
  anonymous fetch that happens on the landing page.
- **Dropping `crossorigin="use-credentials"` as "unnecessary same-origin".** It reads like noise and
  is the difference between a localised install and a permanently German one.
- **Declaring the icon `maskable`.** Android crops a maskable icon to its own shape and guarantees
  only the inner ~40 %; the existing artwork was not drawn with that safe zone, so the claim would
  cut into the mark. A dedicated maskable asset is a design-system request, recorded in the spec's
  Open questions rather than asserted here.
- **`black-translucent` for the iOS status bar.** It looks better in a screenshot and needs safe-area
  padding across the whole layout to avoid drawing content under the clock. Out of scope for a
  requirement about installability.
- **Porting the Android app to iOS instead.** The subject of the assessment above; the channel makes
  it a decision about App Review, not about Kotlin. Unchanged and still not taken.

## Consequences

- **iPhone and iPad members get a home-screen app** with no Apple account, no App Review, no
  90-day expiry, and — a property the native route cannot match — **no token at rest on the device**,
  because the web app is a server-rendered BFF whose session lives in Redis.
- **Chromium will not offer its own install prompt.** Chrome, Edge and Android Chrome require a
  fetch-handling service worker before showing one, so on those platforms the app installs only
  through the browser menu. This is the accepted price of decision 4 and is written into the
  requirement so it is not later "fixed" by adding a worker without an ADR.
- **A new public surface exists**, and it was evaluated for monitoring rather than instrumented
  reflexively. The `http_2xx` blackbox module follows redirects, so adding the manifest to
  `blackbox-http` would produce a probe that passes even when the endpoint has regressed into the
  login page — worse than no probe, because it reads as coverage. The property is instead gate-
  enforced at build time by `WebAppManifestControllerTest`, which runs the real security filter
  chain and asserts an anonymous `200`. The general gap is worth naming: **no probe module asserts
  "a public path stays 200 without following a redirect"** for `/`, `/impressum`, `/privacy`,
  `/terms`, `/robots.txt` or `assetlinks.json` either. Closing it is one module plus one job plus one
  alert, and it is a separate change.
- **Installed apps cache the manifest.** `Cache-Control: private, max-age=3600` with `Vary: Cookie`
  keeps a shared cache from handing a German manifest to an English reader and lets a corrected
  wording reach devices the same day.
- **The tool gains no dependency**, no build step and no JavaScript. The whole feature is one
  controller, six `<head>` lines, three bundle keys and four allow-list entries.

