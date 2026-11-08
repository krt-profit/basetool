# UEX and Star Citizen Wiki terms for onward delivery

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-08 for [#3083](https://github.com/krt-profit/basetool/issues/3083) (wayfinder map
> [#3072](https://github.com/krt-profit/basetool/issues/3072)). **Not legal advice.** It reads the
> sources' own published terms; where they are silent, it says so instead of guessing.
>
> **Question:** may the Basetool pass values it took from UEX and from the Star Citizen Wiki on to
> third-party exchange clients (e.g. VerseKit) through the catalogue interface?

## 1. Short answer

| Source | Onward delivery | Conditions | Facts vs texts |
|---|---|---|---|
| **UEX** (`api.uexcorp.space`) | **Not granted.** The terms neither permit nor forbid it; they grant API users no licence at all. | Treat as needing UEX's **written consent**. Without it: keep UEX values out of the catalogue contract. Even with it: non-commercial, credit UEX. | The terms draw no line. Prices and flags are user-contributed facts; UEX holds only a licence to them from the contributors. |
| **Star Citizen Wiki API** (`api.star-citizen.wiki`) | **Allowed for non-commercial use**, with credit. | Credit `api.star-citizen.wiki`; no commercial use; respect the rate limit. | Game facts (types, classifications, blueprint and mining facts) come from CIG game files, not from the wiki. Description texts are CIG text, and the German ones are a CC BY-NC-SA translation. |
| **starcitizen.tools** (wiki pages) | **Allowed** under CC BY-SA 4.0. | Attribution, licence notice and link, share-alike on adaptations, no added restrictions. | CC BY-SA covers what the wiki's editors wrote. Content the wiki did not author stays with its owners. |

For the catalogue contract this means: **Wiki-sourced facts may leave** (non-commercial, with credit);
**Wiki-sourced texts may leave only with their licence and attribution attached**, and the `de_DE`
descriptions only on non-commercial, share-alike terms; **UEX-sourced values drop out** until UEX
says yes in writing.

## 2. UEX

### 2.1 What applies

- **UEX Terms and Privacy**, <https://uexcorp.space/about/terms>, page footer "Updated: Jul 2, 2026",
  retrieved 2026-11-08. Section 5 is the "UEX API Terms of Service".
- **UEX API documentation**, <https://uexcorp.space/api/documentation/>, retrieved 2026-11-08. It
  links the terms page and states the quota; it carries no licence of its own.
- **UEX Resources**, <https://uexcorp.space/about/resources>, retrieved 2026-11-08: an "API Badges"
  section and colour guidelines, with no text requiring a badge or credit.

### 2.2 What the terms say

- Binding on API use (§5.1): "By accessing or using the UEX API … you agree to comply".
- Website use (§1.4): "personal, non-commercial purposes only."
- Data origin (§1.3): UEX "is a community-driven platform, and its data are updated by users."
- Access is revocable (§5.7): UEX may "terminate or restrict access to the API for any reason".
- No accuracy promise (§5.6): UEX "does not guarantee the accuracy, completeness, or reliability".
- Contributor licence (§3.7) runs **to UEX only**: "a non-exclusive, worldwide, royalty-free license
  to store, display, reproduce, and distribute" the content.
- Quota (API docs): "172800 requests (or 120 requests per minute)" per day.

### 2.3 What the terms do not say

The terms contain **no clause** on redistribution, onward delivery, sublicensing, caching for third
parties, attribution or commercial use of API data (searched for "redistribut", "resell",
"attribut", "scrap", "commercial" on the retrieved page). In particular, nothing grants an API user
a licence to pass the data on.

### 2.4 Reading

- **Onward delivery is not granted.** Silence is not a licence: UEX holds a contributor licence
  (§3.7) and passes none of it to API users, and it can cut access at will (§5.7).
- **Facts vs texts:** the terms do not distinguish. Single prices, flags, ids and names are facts and
  are probably not protected by copyright on their own; a systematic re-delivery of the whole price
  set could touch the EU database right of UEX as its maker (Directive 96/9/EC, Art. 7). Whether
  that right exists for UEX here is **uncertain** and is the kind of question this note cannot
  settle.
- **URLs** pointing at UEX pages are links, not UEX data; delivering a link is the least problematic
  item, but it still exposes which UEX record a row came from.
- **Recommendation:** ask UEX (support e-mail or the contact form named in §5.10) for written consent
  covering: which fields, to which clients, attribution wording, and whether the clients may be
  commercial. Until then, the catalogue contract leaves out every field whose `source_systems` is
  UEX only.

## 3. Star Citizen Wiki

Two surfaces belong to the same project: the API the Basetool calls (`api.star-citizen.wiki`) and the
MediaWiki at `starcitizen.tools`. Their terms differ.

### 3.1 The API (`api.star-citizen.wiki`)

Sources, all retrieved 2026-11-08:

- Developer page <https://api.star-citizen.wiki/developers>, section "Usage":
  - "Please credit api.star-citizen.wiki in public projects."
  - "Commercial use is not permitted under the RSI Fandom FAQ."
  - "Search is rate-limited to 60 requests/min/IP."
- Site footer (every page): "All content on this site not authored by its host or users are property
  of their respective owners."
- Source repository <https://github.com/StarCitizenWiki/API> (README and `LICENSE`):
  - The **code** is MIT. The MIT licence covers the software, not the data it serves.
  - Game data comes from `StarCitizenWiki/scunpacked-data`, "Unpacked Star Citizen game data".
  - German translations are by the Star Citizen Community Translation Team, under
    [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
  - For the rest: "verify licensing independently for commercial use."
- The RSI "Star Citizen Fankit and Fandom FAQ" the developer page points to
  (<https://support.robertsspaceindustries.com/hc/en-us/articles/360006895793-Star-Citizen-Fankit-and-Fandom-FAQ>)
  sits behind a Cloudflare browser challenge and **could not be read** for this note. Its content is
  therefore **unverified** here; the Wiki's own summary of it is the non-commercial rule above.

### 3.2 The wiki pages (`starcitizen.tools`)

Sources, retrieved 2026-11-08:

- [Star Citizen Wiki:Copyrights](https://starcitizen.tools/Star_Citizen_Wiki:Copyrights), last
  edited 14 April 2023:
  - Footer: "Content is available under Creative Commons Attribution-ShareAlike unless otherwise
    noted."
  - CIG/RSI content: CIG granted reuse of "public Comm-Link art and text for fansites"; other CIG
    work needs CIG's permission.
- [Star Citizen Wiki:General disclaimer](https://starcitizen.tools/Star_Citizen_Wiki:General_disclaimer),
  last edited 11 April 2020: use is governed by "Creative Commons Attribution-Sharealike 4.0
  International License (CC-BY-SA)"; trademarks stay with their owners.
- Licence text: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/legalcode) — share
  and adapt for any purpose, on condition of attribution (creator, licence notice, link, note of
  changes), share-alike on adaptations, and no additional restrictions on recipients (§2, §3).

### 3.3 Facts vs texts

| What the Basetool takes | Who owns it | Onward delivery |
|---|---|---|
| Types, classifications, sizes, blueprint and mining facts (from the API's game-data import) | Extracted from CIG game files; not authored by the wiki | Facts as such are not copyrightable. The Wiki asks for credit and forbids commercial use; CIG's own fan terms apply on top (unverified, see 3.1). |
| `description` texts, `en_EN` (`ScWikiItemDto`, `ScWikiVehicleDto`) | Imported with the game data, so most likely CIG's in-game text rather than wiki-authored, and then **not** under the wiki's CC BY-SA | Only within what CIG allows fans (unverified); non-commercial; credit. |
| `description` texts, `de_DE` | Community translation, CC BY-NC-SA 4.0 | Attribution, licence link, non-commercial, share-alike — and the recipient takes the same terms. |
| Wiki article prose from `starcitizen.tools` | Wiki editors, CC BY-SA 4.0 | Any purpose, with attribution and share-alike. |

### 3.4 Reading

- **Onward delivery of Wiki values is allowed** for a non-commercial Basetool, provided the catalogue
  interface carries the credit ("api.star-citizen.wiki") and, for every text, its licence.
- **Share-alike and non-commercial travel with the text.** Delivering a `de_DE` description to
  VerseKit hands VerseKit a CC BY-NC-SA work: the Basetool must pass the licence notice on and may
  not add terms that restrict it further. Whether VerseKit itself is commercial is outside this note;
  if it is, the `de_DE` texts and, per the Wiki's API notice, the API-sourced values should not go to
  it.
- **Uncertain:** the CIG fan terms behind the "RSI Fandom FAQ" could not be read; the Wiki's
  one-line summary is the only primary statement available here.

## 4. What this means for the catalogue contract

- Wiki-sourced fields stay in, with an attribution element on the interface naming
  `api.star-citizen.wiki` (and `starcitizen.tools` where page content is involved).
- Every delivered text carries its licence id (`CC-BY-SA-4.0`, `CC-BY-NC-SA-4.0`, or "CIG,
  all rights reserved") so a client can honour it; `source_systems` alone does not tell a client the
  licence.
- UEX-only fields drop out until UEX gives written consent; the request to UEX is a follow-up for
  the map.
- Clients receive the non-commercial condition in the interface terms.
