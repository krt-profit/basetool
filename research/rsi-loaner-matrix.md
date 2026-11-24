# The RSI Loaner Ship Matrix as a source

Research for [What the RSI Loaner Ship Matrix holds and how a sync could read it](https://github.com/krt-profit/basetool/issues/3667),
a child of [Wayfinder: loaners of pledged ships that are not in the game](https://github.com/krt-profit/basetool/issues/3666);
the request behind it is
[Hangar: show the loaners of pledged ships that are not in the game (loaner matrix)](https://github.com/krt-profit/basetool/issues/3665).

Facts only; nothing here is decided. Where a line is an inference rather than a reading, it says so.

## Sources read

Everything was read on **2026-11-24 between 06:51 and 07:10 UTC**, anonymously, with a generic
`User-Agent` and no personal data.

| Source | URL | State read |
| --- | --- | --- |
| RSI Knowledge Base article 360003093114 "Loaner Ship Matrix", Zendesk Help Center API | <https://support.robertsspaceindustries.com/api/v2/help_center/en-us/articles/360003093114.json> | `updated_at` = `edited_at` = 2026-11-12T17:18:59Z; header "Last Updated: November 12th, 2026 \| 4.10.3-live.13165946"; 52 rows |
| The same article as HTML | <https://support.robertsspaceindustries.com/hc/en-us/articles/360003093114-Loaner-Ship-Matrix> | the same table, 52 body rows |
| Translations of the article | <https://support.robertsspaceindustries.com/api/v2/help_center/articles/360003093114/translations.json> | one: `en-us` |
| Help Center locales | <https://support.robertsspaceindustries.com/api/v2/help_center/locales.json> | `en-us` (default), `es-419` |
| Help Center article search for "loaner" | <https://support.robertsspaceindustries.com/api/v2/help_center/articles/search.json?query=loaner> | 12 hits |
| KB "Loaner Ships FAQ" (115013350528) | <https://support.robertsspaceindustries.com/hc/en-us/articles/115013350528-Loaner-Ships-FAQ> | `edited_at` 2026-02-17 |
| KB "Included Vessels - Snub Fighters and Rovers" (4408770370455) | <https://support.robertsspaceindustries.com/hc/en-us/articles/4408770370455-Included-Vessels-Snub-Fighters-and-Rovers> | `edited_at` 2026-10-20 |
| KB "Where is the ship that I pledged for?" (360042417814) | <https://support.robertsspaceindustries.com/hc/en-us/articles/360042417814-Where-is-the-ship-that-I-pledged-for> | `edited_at` 2026-04-07 |
| KB "Arena Commander" (360003091714) | <https://support.robertsspaceindustries.com/hc/en-us/articles/360003091714-Arena-Commander> | `edited_at` 2025-06-10 |
| KB "I missed a chance to pledge for a limited stock ship!" (360059709013) | <https://support.robertsspaceindustries.com/hc/en-us/articles/360059709013-I-missed-a-chance-to-pledge-for-a-limited-stock-ship-What-can-I-do> | `edited_at` 2026-02-10 |
| KB searches for "third party tools", "rules and regulations", "automated" | `https://support.robertsspaceindustries.com/api/v2/help_center/articles/search.json?query=…` | 15, 24 and 4 hits |
| `robots.txt` of the support site | <https://support.robertsspaceindustries.com/robots.txt> | as served |
| RSI Terms of Service (international version) | <https://robertsspaceindustries.com/tos> | last updated and effective 2026-10-30 |
| Zendesk developer docs: rate limits; Articles API | <https://developer.zendesk.com/api-reference/introduction/rate-limits/>, <https://developer.zendesk.com/api-reference/help_center/help-center-api/articles/> | as served |
| Wayback Machine CDX index of the HTML article and of the API path | <https://web.archive.org/cdx/search/cdx?url=support.robertsspaceindustries.com/hc/en-us/articles/360003093114&matchType=prefix&output=json> | 147 captures of the HTML article, 114 of them HTTP 200; the API JSON was never captured |
| UEX API 2.0 | <https://api.uexcorp.space/2.0/vehicles_loaners>, <https://api.uexcorp.space/2.0/vehicles> | 113 loaner rows; 285 vehicles, 45 of them `is_concept` |
| SC Wiki API vehicle list | <https://api.star-citizen.wiki/api/vehicles> (`page[number]`, `page[size]` ≤ 50, 7 pages) | 311 vehicles, every one stamped `4.10.3-LIVE.13165946`; newest `updated_at` 2026-11-24T00:39:26Z |
| Basetool name matching | `docs/specs/personal-hangar-overview.md` (REQ-HANGAR-019), `ShipManufacturerWords`, `ShipTypeMatcher`, at `main` 78a52dd8f | as committed |

## Summary

- **Reading it.** The article is public through the Zendesk Help Center API without a key. `body`
  is one HTML string holding a single two-column table of 52 free-text rows. The JSON response
  carries a weak `ETag` and answers `If-None-Match` with `304` and an empty body; the HTML page
  carries neither `ETag` nor `Last-Modified`. The article exists only in `en-us`. The header line,
  `updated_at` and `edited_at` agree on 2026-11-12.
- **History.** The Wayback Machine holds 43 distinct "Last Updated" headers from 2018-10-29 to
  2026-11-12, five to seven a year from 2021 to 2025 and three so far in 2026 (April 8, August 26,
  November 12). The table held 44 to 68 rows over the years; the 2026 revisions removed 19 rows,
  added 6 and replaced every Aurora MR / LN Arena Commander loaner with the Mustang Alpha. Row
  names change too ("Kraken (+ Privateer)" split into two rows).
- **Its rows.** By the SC Wiki's 4.10.3 vehicle list: 23 rows name a ship the game does not list
  and give it PU loaners (one of them, Arrastra, also gets the Arena Commander loaner Arrow); 17
  rows name a game ship whose only loaner is the Mustang Alpha (the Arena Commander pattern); 9
  rows give included vessels; 1 row (Javelin) names a ship the game data carries as `in-concept`;
  1 row is a dated temporary loaner (Mantis → Guardian QI); 1 row is red-marked without an
  explanation (S-65 Stingray → Starfarer). The no-stacking rule is stated twice; loaners of
  loaners are not addressed in any article, yet five loaners have rows of their own.
- **Resolving its names** against the 311 in-game names with the REQ-HANGAR-019 stages: of the 52
  ship cells 25 resolve to one ship (6 of them silently to only one member of a grouped form), 3
  are ambiguous and 24 find nothing — the 24 are exactly the ships the game does not list. 11 cells
  are grouped forms that stand for 29 ships. Of the 38 distinct loaner names 36 resolve, "F7C-M
  Super Hornet" is ambiguous (Mk I, Mk II) and "MPUV Passenger" finds nothing (in-game "Argo MPUV
  Personnel"). Every loaner names a ship the game lists as flight ready.
- **Against UEX.** Of the 52 rows 30 agree with UEX, 21 differ and 1 (MXC) has no UEX vehicle.
  The differences: UEX gives the Aurora Mk I MR where RSI gives the Mustang Alpha (12 rows), UEX
  adds loaners (5 rows: 600i Explorer, Hull D/E, Kraken, Kraken Privateer, Orion), UEX splits the
  Carrack rows by hull instead of by package (2 rows), UEX has no loaner (2 rows: Pitbull, UTV).
  Among the 23 rows whose ship UEX marks as concept, 4 differ.
- **Terms.** The ToS bans automated means such as bots, spiders and scrapers while using or
  playing the RSI Services, and copying RSI content without authorisation. Neither the ToS nor
  the Knowledge Base mentions the Help Center API, an API key or a `User-Agent` requirement;
  `robots.txt` does not disallow the article or its API path.

## 1. Reading it

### The Help Center API

- `GET https://support.robertsspaceindustries.com/api/v2/help_center/en-us/articles/360003093114.json`
  answers `200` without authentication and returns `{"article": {…}}` (17,214 bytes). The Zendesk
  docs list anonymous users among those allowed to read Help Center articles
  ([Articles API](https://developer.zendesk.com/api-reference/help_center/help-center-api/articles/)).
- The `article` object carries `id`, `url`, `html_url`, `title` ("Loaner Ship Matrix"),
  `created_at` (2018-04-13T19:37:33Z), `updated_at` and `edited_at` (both 2026-11-12T17:18:59Z),
  `locale` and `source_locale` (`en-us`), `outdated` (`false`), `section_id` (203695537),
  `label_names` (flight ready, ship, loaner, matrix, concept, loan, missing, wrong ship),
  `comments_disabled` (`true`), vote counts and `body`. Its `url` names the Zendesk account
  behind the support site: `https://cloudimperiumservicesllc.zendesk.com/api/v2/help_center/en-us/articles/360003093114.json`.
- Zendesk defines `edited_at` as the time the title or body was last edited in the displayed
  locale, and `updated_at` as the time the article was last updated
  ([Articles API](https://developer.zendesk.com/api-reference/help_center/help-center-api/articles/)).
- The incremental endpoint the same docs describe,
  `GET /api/v2/help_center/incremental/articles.json?start_time=…`, answers `401` ("Couldn't
  authenticate you") to an anonymous call.

### The shape of `body`

`body` is one HTML string of 15,664 characters
([API](https://support.robertsspaceindustries.com/api/v2/help_center/en-us/articles/360003093114.json)):

1. a header image, three paragraphs and a bulleted list of rules (section 2 below);
2. a bordered box on the Public Test Universe;
3. `<h1>Loaner Ship Matrix</h1>`, then the "Last Updated" paragraph;
4. a bordered "Known Issues" box with one dated entry (2026-06-17, Mantis, Issue Council
   STARC-130910);
5. `<figure class="wysiwyg-table"><table>` with a `<thead>` of two `<th>` cells, "YOUR SHIP" and
   "OUR LOANER(S)", and a `<tbody>` of **52 `<tr>` rows of two `<td>` cells each**;
6. a section "The Ship Pipeline" and two video links.

Details a parser meets:

- The header paragraph is
  `<strong>Last Updated: </strong>November 12th, 2026 | 4.10.3-live.<span data-teams="true">13165946</span>`:
  an ordinal date in English, a pipe, and the build, whose number sits in a stray `<span>`.
- Cells are free text. The loaner cell lists loaners separated by commas; the ship cell is one
  name or a grouped form ("Hull D, E", "Idris-M & P", "Pulse (+ LX)", "X1 (+ Velocity, Force)",
  "Cyclone Variants", "Carrack / Carrack Expedition", "600i Explorer and Executive"). "Hull D, E"
  uses the same comma as the loaner list.
- Four loaner cells carry a trailing space ("Constellation Andromeda ", "Mole ", "Cyclone ",
  "Avenger Titan "); `&amp;` and `&nbsp;` occur; spelling varies inside one cell ("w/ C8X" and
  "w/C8X").
- Two loaner cells are wrapped in `<span style="color: #CC0000;">`: Mantis → Guardian QI and
  S-65 Stingray → Starfarer. Only the Mantis one is explained (Known Issues box).
- Some rows carry inline `style` and `height` attributes, five do not; the order is alphabetical
  except "S-65 Stingray" after "SRV".
- The HTML page holds the same table: 53 `<tr>` including the header row
  ([HTML](https://support.robertsspaceindustries.com/hc/en-us/articles/360003093114-Loaner-Ship-Matrix)).

### Caching, conditional requests and rate limits

| Response | `ETag` | `Last-Modified` | `Cache-Control` | Other |
| --- | --- | --- | --- | --- |
| API JSON | `W/"f5773cdcf0055380e3c1c3bd0efc6482"` | none | `max-age=0, private, must-revalidate` | `x-rate-limit: 700`, `x-rate-limit-remaining`, `cf-cache-status: BYPASS` |
| API JSON with `If-None-Match` of that `ETag` | same | none | same | **`304 Not Modified`, empty body** |
| HTML page | none | none | `max-age=0, public` | `cf-cache-status: HIT`, `Age: 70` |

- Across eleven API calls within one minute, `x-rate-limit-remaining` read 696, 692, 699, 694, 697,
  696, 695, 694, 693, 692, 691 — not one decrement per call. *Inference:* the counter is shared
  with other clients of the same Zendesk account, not kept per caller.
- Zendesk documents the Help Center API limit as the Support plan's (700 per minute on
  Enterprise), with `429 Too Many Requests` and a `Retry-After` header when it is exceeded
  ([rate limits](https://developer.zendesk.com/api-reference/introduction/rate-limits/)).
- `robots.txt` disallows, among others, `/hc/*/search`, `/search` and
  `/api/v2/help_center/*/articles/*/stats/view`; it disallows neither `/hc/en-us/articles/…` nor
  `/api/v2/help_center/en-us/articles/…`
  ([robots.txt](https://support.robertsspaceindustries.com/robots.txt)).

### Other locales

The Help Center has the locales `en-us` and `es-419`
([locales](https://support.robertsspaceindustries.com/api/v2/help_center/locales.json)), but the
article has a single translation, `en-us`
([translations](https://support.robertsspaceindustries.com/api/v2/help_center/articles/360003093114/translations.json)).
`/api/v2/help_center/es-419/articles/360003093114.json` and the same path under `de` answer `404`
`RecordNotFound`.

### Change history

- **Zendesk keeps only the current state public.** The article exposes `created_at`, `updated_at`
  and `edited_at`; no revision list is public, and the incremental endpoint needs authentication.
- **Wayback Machine.** The CDX index holds 147 captures of the HTML article between 2018-12 and
  2026-11, 114 of them HTTP 200; the API JSON path was never captured (only `…/stats/view.json`).
  110 distinct captures were read raw (`https://web.archive.org/web/<timestamp>id_/<url>`); one
  (20240415210406) answered `403` three times
  ([CDX](https://web.archive.org/cdx/search/cdx?url=support.robertsspaceindustries.com/hc/en-us/articles/360003093114&matchType=prefix&output=json)).
- **43 distinct "Last Updated" headers** were captured, from "October 29th, 2018 | 3.2.2-LIVE.846694"
  to "November 12th, 2026 | 4.10.3-live.13165946", plus one content change without a new header
  (between the captures of 2023-03-27 and 2023-05-14). Captures are irregular, so the counts are
  lower bounds.

| Year | Header dates captured | Body rows |
| --- | --- | --- |
| 2018 | 1 | (bulleted list, not a table) |
| 2019 | 5 | 44–46 (bulleted list) |
| 2020 | 3 | 51–53 |
| 2021 | 7 | 50–52 |
| 2022 | 6 | 53–62 |
| 2023 | 6 | 60–64 |
| 2024 | 5 | 61–68 |
| 2025 | 7 | 64–68 |
| 2026 | 3 (April 8, August 26, November 12) | 54, 54, 52 |

- **The header follows LIVE builds, but not one per build**: March 27th, March 31st and April 10th,
  2025 all name 4.1.0-live.9650658. Its spelling drifts: "LIVE" and "live", "April 18, 2019"
  beside ordinals, a space inside the build ("3.10.0-live. 5789362", most versions 2020–2025), and
  the build number inside a stray `<span>` (2023-11 and now).
- **Layout.** Until at least the 2019-10 capture the matrix was a bulleted list "Ship - Loaner";
  from the 2020-08 capture on it is the table.
- **Row keys are not stable.** "Kraken (+ Privateer)" became the rows "Kraken" and "Kraken
  Privateer" on 2026-08-26; "Ironclad (+ Assault)" and "ROC (+ ROC DS)" were rows of their own and
  are gone.

The three 2026 revisions, read from the captures of 2025-11-30, 2026-04-15, 2026-10-09 and
2026-11-14:

- **April 8th, 2026 | 4.7.1-live.11592622** (65 → 54 rows): twelve rows removed (400i, 600i
  Touring, Caterpillar, Corsair, Hull B, Hull C, Polaris, RAFT, Retaliator, Terrapin (+ Medic),
  Valkyrie, Vulture), UTV added (Aurora MR), the 325a dropped from 600i Explorer and Executive and
  from 890 Jump, the Arrow dropped from Hull D, E.
- **August 26th, 2026 | 4.10.0-live.12519617** (54 rows): 16 Aurora MR loaners and the SRV's
  Aurora LN replaced by the Mustang Alpha; Pitbull, Odin, S-65 Stingray, Kraken and Kraken
  Privateer added; Ironclad (+ Assault), Kraken (+ Privateer), Mole, Railen and Redeemer removed;
  Mantis changed from Aurora LN to Guardian QI; the Prospector dropped from Arrastra and Orion;
  Liberator changed from Hercules M2, F7C-M Super Hornet to Ironclad Assault, F7C-M Super Hornet.
- **November 12th, 2026 | 4.10.3-live.13165946** (52 rows): Galaxy (→ Carrack) and Liberator (→
  Ironclad Assault, F7C-M Super Hornet) removed. Both ships stay in the SC Wiki's 4.10.3 list with
  exactly those loaners in their game data.

## 2. Its rows

### Classification

The kind is derived, not stated by RSI: the ship's presence in the SC Wiki's 4.10.3 vehicle list
(game data), the "Included Vessels" table, and the article's own rules. Kinds: **A** ship not in
the game data, PU loaners; **B** game ship, Mustang Alpha only (Arena Commander pattern); **C**
included vessels (listed in "Included Vessels"); **D** ship in the game data as `in-concept`;
**E** dated temporary loaner; **F** red-marked, unexplained.

| # | YOUR SHIP (verbatim) | OUR LOANER(S) (verbatim) | Ship in the SC Wiki 4.10.3 list | Kind |
| --- | --- | --- | --- | --- |
| 1 | 600i Explorer and Executive | Cyclone | Origin 600i, Origin 600i Executive Edition | C |
| 2 | 890 Jump | 85x | Origin 890 Jump | C |
| 3 | Arrastra | Mole, Arrow | — | A (Arrow is the article's own Arena Commander example) |
| 4 | Carrack / Carrack Expedition | C8 Pisces, URSA Rover | Anvil Carrack, Anvil Carrack Expedition | C |
| 5 | Carrack w/ C8X / Carrack Expedition w/C8X | C8X Pisces Expedition, URSA Rover | the same two | C |
| 6 | Centurion | Mustang Alpha | Anvil HM6 Centurion | B |
| 7 | Constellation Andromeda | P-52 Merlin | RSI Constellation Mk IV Andromeda | C |
| 8 | Constellation Aquila | P-52 Merlin, URSA Rover | RSI Constellation Mk IV Aquila | C |
| 9 | Constellation Phoenix | P-72 Archimedes, Lynx Rover | RSI Constellation Mk IV Phoenix | C |
| 10 | Constellation Phoenix Emerald | P-72 Archimedes, Lynx Rover | — (UEX lists it, not as concept) | C |
| 11 | Crucible | Constellation Andromeda | — | A |
| 12 | CSV-SM | Mustang Alpha | Argo CSV-SM | B |
| 13 | Cyclone Variants | Mustang Alpha | Tumbril Cyclone, AA, MT, RC, RN, TR | B |
| 14 | Dragonfly | Mustang Alpha | Drake Dragonfly, Drake Dragonfly Yellowjacket | B |
| 15 | E1 Spirit | A1 Spirit | — | A |
| 16 | Endeavor | Starfarer, Cutlass Red | — | A |
| 17 | Expanse | Prospector, Reliant Kore | — | A |
| 18 | Fury Variants | Mustang Alpha | Mirai Fury, Fury LX, Fury MX | B |
| 19 | G12 Variants | Lynx | — | A |
| 20 | Genesis Starliner | Hercules C2 | — | A |
| 21 | Hull D, E | Hull C, Hercules C2 | — | A |
| 22 | Idris-M & P | F7C-M Super Hornet, MPUV Passenger | Aegis Idris-M, Aegis Idris-P | C ("Included Vessels" lists only MPUV-Personnel) |
| 23 | Javelin | Idris-P, MPUV Cargo | Aegis Javelin, `in-concept` | D ("Included Vessels": not available in game; MPUV-Cargo is its included vessel) |
| 24 | Kraken | Polaris, Ironclad Assault, Buccaneer | — | A |
| 25 | Kraken Privateer | Polaris, Ironclad, Buccaneer | — | A |
| 26 | Legionnaire | Vanguard Hoplite | — | A |
| 27 | Lynx | Mustang Alpha | RSI Lynx | B |
| 28 | Mantis | Guardian QI (red) | RSI Mantis | E (Known Issues, 2026-06-17) |
| 29 | Merchantman | Hull C, Defender, Hercules C2 | — | A |
| 30 | MPUV-Tractor | Mustang Alpha | Argo MPUV Tractor | B |
| 31 | MXC | Mustang Alpha | — (UEX has no vehicle either) | A (its loaner follows the B pattern) |
| 32 | Mule | Mustang Alpha | Drake Mule | B |
| 33 | Nautilus | Polaris, Avenger Titan | — | A |
| 34 | Nova | Mustang Alpha | Tumbril Nova | B |
| 35 | Nox | Mustang Alpha | Aopoa Nox, Aopoa Nox Kue | B |
| 36 | Odin | Idris-P | — | A |
| 37 | Odyssey | Carrack, Reliant Kore | — | A |
| 38 | Orion | Mole | — | A |
| 39 | Pioneer | Caterpillar, Nomad | — | A |
| 40 | Pitbull | Mustang Alpha | Drake Pitbull | B |
| 41 | Pulse (+ LX) | Mustang Alpha | Mirai Pulse, Mirai Pulse LX | B |
| 42 | Ranger CV | Cyclone | — | A |
| 43 | Ranger RC | Cyclone RC | — | A |
| 44 | Ranger TR | Cyclone TR | — | A |
| 45 | SRV | Mustang Alpha | Argo SRV | B |
| 46 | S-65 Stingray | Starfarer (red) | Kruger S-65 Stingray | F |
| 47 | Storm Variants | Mustang Alpha | Tumbril Storm, Storm AA | B |
| 48 | STV | Mustang Alpha | Greycat STV | B |
| 49 | UTV | Mustang Alpha | Greycat UTV | B |
| 50 | Vulcan | Starfarer | — | A |
| 51 | X1 (+ Velocity, Force) | Mustang Alpha | Origin X1, X1 Force, X1 Velocity | B |
| 52 | Zeus Mk II MR | Zeus Mk II ES | — | A |

**Counts:** A 23, B 17, C 9, D 1, E 1, F 1. The rows whose ship RSI or the game data treats as
not available — A plus Javelin — are 24. The Mustang Alpha is granted by 18 rows (the 17 of B
plus MXC). Sources: matrix
([API](https://support.robertsspaceindustries.com/api/v2/help_center/en-us/articles/360003093114.json)),
[Included Vessels](https://support.robertsspaceindustries.com/hc/en-us/articles/4408770370455-Included-Vessels-Snub-Fighters-and-Rovers),
[SC Wiki vehicles](https://api.star-citizen.wiki/api/vehicles).

The "Included Vessels" table lists parents the matrix does not: the Constellation Mk V Centaurus
with the Skylark. It gives the 600i an included G12 that is a Cyclone for the time being, which
matches row 1.

### Rule text

Paraphrased from the matrix article unless another source is named.

- **Not a 1:1 substitute.** Loaners are not meant as the best-case replacement of the ship.
- **Game package required.** An account receives loaners only with a valid game package (also the
  [Loaner Ships FAQ](https://support.robertsspaceindustries.com/hc/en-us/articles/115013350528-Loaner-Ships-FAQ)).
- **Loaners change.** When a ship that is a better substitute becomes flight ready, the loaner is
  updated.
- **Arena Commander.** Because of Arena Commander's restrictions some ships get a secondary loaner
  for its modes; the article's example is the Arrastra, which gets an Anvil Arrow. The FAQ says a
  single-seat fighter is granted where a ship does not suit Arena Commander; the
  [Arena Commander](https://support.robertsspaceindustries.com/hc/en-us/articles/360003091714-Arena-Commander)
  article says multi-crew ships cannot be used in ranked modes and an AC-appropriate loaner is
  granted.
- **Temporary; what happens at flight ready.** Loaners are not permanent additions, except
  included vessels. Once a ship is flight ready, the loaner is replaced by the purchased ship and
  the ship is removed from the list. The FAQ says the same: the loaner is removed from the hangar
  when the ship becomes flight ready.
- **No stacking.** A player never receives more than one of each loaner ship, however many ships
  grant it (example: four Pioneers give one Caterpillar); several concept ships granting the same
  loaner give one. The second example names an **Aurora MR** for a Cyclone plus a Dragonfly,
  while the table gives both the **Mustang Alpha**. "Included Vessels" states the same rule for
  included vessels and adds that this may change once they leave the loaner system.
- **No swaps.** RSI Support does not swap loaners on request; the FAQ adds that all owners of the
  same concept ship receive the same loaner.
- **Test servers.** Loaners on the PTU, EPTU and Tech Preview may not be current.
- **Time-limited loaners.** No article states a time limit. The only dated loaner is the Mantis
  entry of the Known Issues box (2026-06-17), a temporary Guardian QI while the quantum
  interdiction device has an issue.
- **Included vessels** depend on the parent ship in the hangar, are not listed in the pledge,
  and are lost when the parent is upgraded away or reclaimed
  ([Included Vessels](https://support.robertsspaceindustries.com/hc/en-us/articles/4408770370455-Included-Vessels-Snub-Fighters-and-Rovers)).
- **Loaners of loaners.** No article read states whether a loaner itself grants loaners. The data
  has such chains: five loaners have rows of their own — Cyclone (rows 1, 42; row 13 "Cyclone
  Variants" → Mustang Alpha, which also covers Cyclone RC and TR of rows 43, 44), Lynx (rows 9,
  10, 19; row 27 → Mustang Alpha), Constellation Andromeda (row 11; row 7 → P-52 Merlin), Carrack
  (row 37; row 4 → C8 Pisces, URSA Rover), Idris-P (rows 23, 36; row 22 → F7C-M Super Hornet,
  MPUV Passenger).

## 3. Resolving its names

### Method

A script mimics `ShipTypeMatcher#resolveImport` with `ShipManufacturerWords` (REQ-HANGAR-019) over
the SC Wiki's 311 `game_name` values, the in-game names (REQ-P4K-032):

- manufacturer words: the first word of each manufacturer name, the initials of a name of two or
  more words ("and", "of", "the" skipped), and the manufacturer `code` as the abbreviation — 50
  words from the Wiki's manufacturers. The Wiki has no nickname field; the catalogue's words may
  differ;
- stages in order: exact full name, exact short name (manufacturer word dropped), normalised full,
  normalised short, then the token subsets of the short name (ship tokens ⊇ name tokens, else
  ship tokens ⊆ name tokens); the first stage that finds anything answers;
- rows whose short names agree are one ship (the Wiki has several rows for one ship, e.g. three
  "Aegis Idris-P");
- the live/retired split and the slug fallback are left out: the Wiki has neither.

Each ship cell is matched as written; each loaner cell is split at commas first.

### Ship cells

| Result | Count | Rows |
| --- | --- | --- |
| One ship, the one the cell names | 19 | 890 Jump, Centurion (→ Anvil HM6 Centurion), Constellation Andromeda / Aquila / Phoenix (→ RSI Constellation Mk IV …), CSV-SM, Dragonfly, Javelin, Lynx, Mantis, MPUV-Tractor, Mule, Nova, Nox, Pitbull, SRV, S-65 Stingray, STV, UTV |
| One ship, but only one member of a grouped form | 6 | 600i Explorer and Executive → Origin 600i; Carrack / Carrack Expedition → Anvil Carrack Expedition; Cyclone Variants → Tumbril Cyclone; Fury Variants → Mirai Fury; Pulse (+ LX) → Mirai Pulse LX; Storm Variants → Tumbril Storm |
| Ambiguous | 3 | Carrack w/ C8X / Carrack Expedition w/C8X (Carrack, Carrack Expedition); Idris-M & P (Idris-M, Idris-P); X1 (+ Velocity, Force) (X1, X1 Force, X1 Velocity) |
| Nothing found | 24 | Arrastra, Constellation Phoenix Emerald, Crucible, E1 Spirit, Endeavor, Expanse, G12 Variants, Genesis Starliner, Hull D, E, Kraken, Kraken Privateer, Legionnaire, Merchantman, MXC, Nautilus, Odin, Odyssey, Orion, Pioneer, Ranger CV, Ranger RC, Ranger TR, Vulcan, Zeus Mk II MR |

Stages that answered: 14 exact short, 2 normalised short, 12 tokens. The 24 cells that find
nothing are the 24 ships the Wiki does not list; for them no in-game name exists to resolve
against. Matched the same way against UEX's `name_full` instead, 45 ship cells resolve to one
vehicle, 6 are ambiguous and 1 (MXC) finds nothing.

Two single resolutions cover only part of what the row may mean: "Dragonfly" and "Nox" resolve by
exact name to Drake Dragonfly and Aopoa Nox, while the Wiki also lists Drake Dragonfly
Yellowjacket and Aopoa Nox Kue with the same Mustang Alpha loaner.

### Grouped forms

11 ship cells are grouped forms. The members are the ships the Wiki (or, where the Wiki has none,
UEX) lists under those names; one alias per member gives 29 aliases.

| Ship cell | Matcher result | Members | Aliases |
| --- | --- | --- | --- |
| 600i Explorer and Executive | Origin 600i only | Origin 600i (ship-matrix name "600i Explorer"), Origin 600i Executive Edition | 2 |
| Carrack / Carrack Expedition | Anvil Carrack Expedition only | Anvil Carrack, Anvil Carrack Expedition | 2 |
| Carrack w/ C8X / Carrack Expedition w/C8X | ambiguous | the same two game ships; "w/ C8X" names a pledge package, not a game name | 2 |
| Cyclone Variants | Tumbril Cyclone only | Cyclone, Cyclone AA, MT, RC, RN, TR | 6 |
| Fury Variants | Mirai Fury only | Fury, Fury LX, Fury MX | 3 |
| G12 Variants | nothing | G12, G12-A, G12-R (UEX, concept) | 3 |
| Hull D, E | nothing | Hull D, Hull E (UEX, concept) | 2 |
| Idris-M & P | ambiguous | Idris-M, Idris-P | 2 |
| Pulse (+ LX) | Mirai Pulse LX only | Pulse, Pulse LX | 2 |
| Storm Variants | Tumbril Storm only | Storm, Storm AA | 2 |
| X1 (+ Velocity, Force) | ambiguous | X1, X1 Force, X1 Velocity | 3 |

The two Carrack rows map to the same two game ships with different loaners (C8 Pisces against
C8X Pisces Expedition); the game names cannot tell them apart.

### Loaner cells

72 loaner entries, 38 distinct names:

- **36 resolve to one ship**, 31 by exact short name and 5 by tokens: "85x" → Origin 85X Limited,
  "Constellation Andromeda" → RSI Constellation Mk IV Andromeda, "Hercules C2" → Crusader C2
  Hercules Starlifter, "Lynx Rover" → RSI Lynx, "URSA Rover" → RSI Ursa.
- **"F7C-M Super Hornet" is ambiguous**: Anvil F7C-M Super Hornet Mk I and Mk II. The Wiki's own
  loaner field of the Idris-M and Idris-P names the Mk I; UEX names the Mk II.
- **"MPUV Passenger" finds nothing**: the in-game name is "Argo MPUV Personnel" ("Included
  Vessels" writes "MPUV-Personnel").
- **No named loaner is itself missing from the game**: all 35 ships the loaner names resolve to
  (plus MPUV Personnel) are in the Wiki list with `production_status` `flight-ready`.

## 4. Against UEX

### Method

Each matrix row was expanded to the UEX vehicles it names (the grouped forms by hand, as in the
table above; "Nautilus" also covers "Aegis Nautilus Solstice Edition", "Nox" also "Aopoa Nox
Kue", "Dragonfly" both Dragonflies), and the row's loaners were compared with each vehicle's
`ids_vehicles_loaners`, the field `/2.0/vehicles_loaners` expands into `loaners` objects. Two
loaner names were mapped by hand: "MPUV Passenger" → Argo MPUV Personnel, "F7C-M Super Hornet" →
Mk I or Mk II. A row agrees when every expanded vehicle has exactly the row's loaners.

### Result

30 rows agree, 21 differ, 1 has no UEX vehicle (MXC). The differing rows:

| Row | UEX vehicle(s) | RSI only | UEX only |
| --- | --- | --- | --- |
| 600i Explorer and Executive | Origin 600i Explorer | — | Origin 325a |
| Carrack / Carrack Expedition | Anvil Carrack Expedition | C8 Pisces | C8X Pisces Expedition |
| Carrack w/ C8X / Carrack Expedition w/C8X | Anvil Carrack | C8X Pisces Expedition | C8 Pisces |
| Hull D, E | MISC Hull D, MISC Hull E | — | Anvil Arrow |
| Kraken | Drake Kraken | — | Crusader C2 Hercules Starlifter, Drake Caterpillar |
| Kraken Privateer | Drake Kraken Privateer | — | Crusader C2 Hercules Starlifter, Drake Caterpillar |
| Orion | RSI Orion | — | MISC Prospector |
| Pitbull | Drake Pitbull | Mustang Alpha | — (no loaner) |
| UTV | Greycat UTV | Mustang Alpha | — (no loaner) |
| Centurion, Cyclone Variants (6), Dragonfly (2), Fury Variants (3), Lynx, MPUV-Tractor, Mule, Nova, Nox (2), Pulse (+ LX) (2), Storm Variants (2), X1 (+ Velocity, Force) (3) — 12 rows | as named | Mustang Alpha | RSI Aurora Mk I MR |

- Among the 23 rows whose ship UEX marks `is_concept`, 4 differ (Hull D, E; Kraken; Kraken
  Privateer; Orion). UEX assigns the Carrack's included vessels by hull (Carrack → C8 Pisces,
  Carrack Expedition → C8X), RSI by pledge package.
- **19 of the 21 differences match an earlier state of the matrix** (Wayback captures, section 1):
  the Aurora MR was the Arena Commander loaner until the August 26th, 2026 revision; the 325a of
  the 600i Explorer and the Arrow of Hull D, E left with the April 8th, 2026 revision; the
  Prospector of the Orion and the Hercules C2 and Caterpillar of "Kraken (+ Privateer)" left on
  August 26th, 2026; UTV and Pitbull got their rows on April 8th and August 26th, 2026. The
  Carrack split by hull matches no captured state: the table has assigned by package since its
  first capture in 2020.
- **UEX keeps loaners of rows the matrix dropped.** At least 28 UEX vehicles with loaners match a
  row only earlier versions hold, by token match of the names — e.g. 400i → 325a and Caterpillar →
  Buccaneer (last in the November 26th, 2025 version), MOLE → Prospector (last in the April 8th,
  2026 version), Liberator → F7C-M Super Hornet Mk I, Ironclad Assault (last in the August 26th,
  2026 version). Others match no captured row and look like game-data loaners, e.g. Zeus Mk II ES
  and CL → C1 Spirit, which the SC Wiki lists the same way.
- 85 of UEX's 113 loaner-carrying vehicles are not concept vehicles; besides the matrix's own
  game-ship rows they include the dropped rows above. One concept vehicle with loaners has no row
  in any captured matrix version: MISC Starlancer BLD → MISC Freelancer MIS.
- UEX has 45 concept vehicles; 28 carry loaners. The 17 without are 11 modules and pods, Argo
  CSV-FM, four Argo ATLS IKTI variants and Anvil Auxilia.
- The `loaners` objects carry no game UUID (0 of 146). 171 of the 285 UEX vehicles carry a
  `uuid`, 1 of the 45 concept vehicles.
- `ids_vehicles_loaners` and the `loaners` objects agree in all 113 rows. `/2.0/vehicles_loaners`
  answered with `Cache-Control: public, max-age=43200, must-revalidate`.

### The SC Wiki's loaner field, for comparison

The request's source table says none of the Wiki's loaner-carrying vehicles is a concept ship.
Correction: 90 of the 311 Wiki vehicles carry loaners, and one of them is the **Aegis Javelin**,
`production_status` `in-concept`, with Idris-P and MPUV Cargo — the same as the matrix. Hull D,
Kraken, Odin, Pioneer and Endeavor are absent from the Wiki, as the request says. For the 28 rows
whose ship the Wiki lists, its loaner field agrees with the matrix except for the Carrack pair:
the Wiki gives Anvil Carrack C8X Pisces Expedition and Ursa, and Anvil Carrack Expedition no
loaner. The Wiki's Arena Commander loaner is the Mustang Alpha, as in the matrix. Wiki loaner
entries carry the loaner's `uuid` ([SC Wiki](https://api.star-citizen.wiki/api/vehicles)).

The request calls the Wiki's loaners of game ships that the article lacks probably game-mode
loaners. Several of them are rows the matrix carried before (Wayback, section 1): Hull C → Arrow
until the April 8th, 2026 revision removed it, Galaxy → Carrack and Liberator → Ironclad Assault,
F7C-M Super Hornet until the November 12th, 2026 revision, and a former "Reliant Variants" row
(→ Avenger Titan; the Wiki gives the Reliants the Avenger Titan Renegade).

## 5. Terms

- **Scope.** The ToS define the "RSI Services" as the website robertsspaceindustries.com, the
  Game and any other product or online service RSI offers; "RSI Terms" include rules posted in the
  Knowledge Base under "Rules and Regulations"
  ([ToS § I](https://robertsspaceindustries.com/tos)). The support site is
  `support.robertsspaceindustries.com`, run on the Zendesk account `cloudimperiumservicesllc`
  (the article's `url` field).
- **Automated means.** § IV "Rules of Conduct" lists among prohibited conduct: "Use automated
  measures like “bots”, “spiders”, “scrapers” or similar means" while using or playing any RSI
  Service, naming gold farming, "F5 abuse", automated refreshing, data scraping or harvesting and
  harvesting information about other users. The same list bans connections through programs or
  tools RSI has not approved ([ToS § IV](https://robertsspaceindustries.com/tos)).
- **Content.** § XIII B forbids copying or downloading RSI Content from the RSI Services unless RSI
  authorises it in writing or the RSI Terms allow it ([ToS § XIII](https://robertsspaceindustries.com/tos)).
- **No API rules.** Neither the ToS nor the Knowledge Base articles read mention the Help Center
  API, an API key, a rate limit or a `User-Agent` requirement. KB searches for "third party
  tools", "rules and regulations" and "automated" return no such article; the only hit on bots
  says RSI monitors bots and scripted activity on the back end, in the context of limited-stock
  pledges
  ([limited stock](https://support.robertsspaceindustries.com/hc/en-us/articles/360059709013-I-missed-a-chance-to-pledge-for-a-limited-stock-ship-What-can-I-do)).
- **What the server accepted.** Every read of a public article, translation list, locale list or
  search of this research answered `200` to a generic `User-Agent`
  (`Mozilla/5.0 (compatible; research-client/1.0)`) with no cookie and no key; the only refusals
  were the authenticated incremental endpoint (`401`) and the missing locales (`404`). Cloudflare
  set `__cf_bm` cookies but challenged nothing. `robots.txt` leaves the article and
  its API path open (section 1).
- **UEX and the SC Wiki** were read anonymously as well; their terms were not part of this
  question.
