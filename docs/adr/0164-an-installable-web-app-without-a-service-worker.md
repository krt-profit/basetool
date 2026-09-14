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
   user-visible strings come from the message bundles, because every user-visible string in this
   application does; its icon URL is content-hashed at request time, because `/logos/**` is served
   `immutable` for a year; its media type is `application/manifest+json`, which Spring's
   static-resource handler does not know; and it must answer `200` and never `302`. That last
   property is not theoretical — it is exactly how `/.well-known/assetlinks.json` broke the Android
   login once, and both paths now sit in the same `SecurityConfig` allow-list. The mapping carries
   **no** `produces`: that would put content negotiation in front of a public document, and
   `application/json` is not compatible with `application/manifest+json`, so a caller asking for
   JSON got a `500` from the `Exception` catch-all instead of the manifest.
2. **The manifest link carries the locale in its URL, and no `crossorigin` attribute.** The page is
   server-rendered and already knows the reader's locale, so it emits
   `@{/manifest.webmanifest(locale=${#locale.language})}` and the response is a pure function of its
   URL — publicly cacheable, with no `Vary`. The controller clamps the parameter to the shipped
   bundles, so `lang` and the strings can never disagree.

   > [!warning] Corrected 2026-09-13, before merge — this decision originally said the opposite
   > The first revision set `crossorigin="use-credentials"` so the fetch would carry `KRT_LOCALE`,
   > and called it load-bearing. Review measured it and it was not:
   >
   > - **It cost five backend round trips per fetch.** `use-credentials` makes the request
   >   *authenticated*, and the frontend's three layout `@ControllerAdvice` beans declare no scope,
   >   so Spring's `ModelFactory` runs them before **every** handler — `@RestController`s included,
   >   which discard the model they just paid for. One manifest fetch fired
   >   `/api/v1/me/capabilities`, `/api/v1/notifications/unread-count`, a cached catalogue read,
   >   `/api/v1/me/active-org-unit` and `/api/v1/me/org-units`. The `BackendRoleSyncFilter` carve-out
   >   this ADR pointed at as the mitigation removed exactly one of the six.
   > - **It bought nothing on the platform this ADR exists for.** `pwa.name` and `pwa.short_name` are
   >   byte-identical in both bundles (the product name is a proper noun), Safari implements neither
   >   `lang` nor `description`, and the iOS home-screen label comes from
   >   `apple-mobile-web-app-title` on the page itself. `description` reaches only Android's install
   >   prompt.
   > - **It did not even help a first-time English reader.** `CookieLocaleResolver` is pinned to
   >   German and ignores `Accept-Language`, so with no `KRT_LOCALE` cookie yet the manifest was
   >   German regardless of the attribute.
   >
   > Both filter exemptions are therefore **removed** rather than kept: a public document that needs
   > no session should not be fetched with one. Re-adding `use-credentials` needs a new ADR.

3. **`theme-color` is the header fill (`#141414`), not the black page background.** On a phone the
   status bar sits directly above the header, and matching the header is what removes the seam. The
   value exists in three places — a `<meta>` tag cannot read a CSS custom property, and the
   controller can read neither — so the test pins all three: the rendered tag, the controller
   constant, and `--color-bg-dark-gray` parsed out of `styles.css`. The stylesheet is the one that
   actually paints the header, and it was the one nothing checked.
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
- **`crossorigin="use-credentials"`, so the manifest fetch carries `KRT_LOCALE`.** Tried, shipped in
  the first revision of this branch, and reverted before merge — see the correction under decision 2.
  It made a public document's fetch authenticated, which cost five backend round trips and two
  filter carve-outs, in exchange for a `description` and a `lang` that the target platform does not
  render.
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
- **A new public surface exists, and it is probed.**

  > [!warning] Corrected 2026-09-13, before merge — the original reason for skipping the probe was wrong
  > This ADR argued that `http_2xx` follows redirects, so a probe would pass even after the endpoint
  > regressed into the login page, and concluded that a build-time MockMvc assertion had to stand in
  > for one. The first half is true; the conclusion did not follow. `monitoring/blackbox/blackbox.yml`
  > already contained **five** modules with `follow_redirects: false`, two of which pin an exact
  > status (`http_deny_404`, `http_members_only_redirect`), each with its own scrape job and alert.
  > The obstacle was a property of one module, not of the exporter.
  >
  > The substitute was also the one `monitoring/prometheus/prometheus.yml` rejects in writing, a few
  > lines above where the new job now sits: *"the app's own sweeps run against MockMvc and would stay
  >
  >> green while a cache rule, a stale upstream or a mis-ordered NPM location served the old page."*
  >
  > So the module was written: `http_public_200_no_redirect`, the `blackbox-public-surface` job, and
  > the `EdgePublicSurfaceNot200` alert. It covers the manifest **and** the six paths named below, so
  > the general gap is closed rather than recorded.

  Kept for the record, because the gap was real until this change: **no probe module asserted
  "a public path stays 200 without following a redirect"** for `/`, `/impressum`, `/privacy`,
  `/terms`, `/robots.txt` or `assetlinks.json` either. All seven are targets of the new job.

- **The manifest is publicly cacheable**, `Cache-Control: max-age=3600, public`, with no `Vary`:
  the locale is in the URL, so the body depends on nothing else. The earlier `private` +
  `Vary: Cookie` pair was doing neither job — `private` already forbids a shared cache, and varying
  on the whole `Cookie` header threw the browser's copy away on every `SESSION` rotation, i.e.
  at login, which is exactly when an install happens.

  **What the TTL does not buy:** this ADR previously claimed one hour was "short enough that a
  corrected wording reaches installed apps the same day". That is false on the platform this exists
  for. iOS captures `short_name`, the title and the icon **at add-to-home-screen time** and does not
  rename or re-icon an existing installation when the manifest changes; the member has to delete and
  re-add it. Chromium does honour manifest updates, which is what the `id` member buys. Plan wording
  and icon changes accordingly.

- **The icon is emitted at its content-hashed URL.** `/logos/**` is served `immutable` for a year,
  so the bare path would have pinned every installed home screen to a URL no browser revalidates —
  a redesigned icon would have reached the `apple-touch-icon` on the next page load and the
  installed app never.

- **The installed sign-in flow is unverified.** `scope` is the app origin and cannot be anything
  else, but both `/oauth2/authorization/keycloak` and the logout redirect navigate to the Keycloak
  origin. On iOS an out-of-scope navigation opens in an in-app browser whose storage the standalone
  app does not share, which would strand the PKCE/state values written before the hop. No test can
  cover this; it needs a device. If it fails, the fix is reverse-proxying Keycloak onto the app
  origin at the edge — a separate ADR. Recorded in REQ-UI-020's Open questions.

  > **Superseded 2026-09-13 by [ADR-0166](0166-identity-moves-onto-the-app-origin.md).** The fix
  > named here was taken without waiting for the device test: Keycloak serves at `/auth` on the app
  > origin, so both navigations stay in scope. The consequence above stands as the reasoning that led
  > there; it no longer describes the deployment.

- **The tool gains no dependency**, no build step and no JavaScript. The whole feature is one
  controller, six `<head>` lines, three bundle keys, one `permitAll` entry and one access-log skip.

