# Star Citizen patch and wipe persistence — what survives, and what that means for a patch change

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-10 for [#3248](https://github.com/krt-profit/basetool/issues/3248) (map
> [#3246](https://github.com/krt-profit/basetool/issues/3246)). Nothing here is binding; the patch
> change gets its own `REQ-…` and ADRs.
>
> **Sources:** RSI's own channels — Spectrum announcements and patch notes (read through the public
> Spectrum thread API, which returns the posts verbatim), Comm-Link patch notes (client-rendered on
> RSI, so their text was read through the star-citizen.wiki API mirror) and the RSI knowledge base.
> Community and fan sources were used only to find primary ones, and are marked **(secondary)**
> wherever a claim rests on them.

## Summary

- **Three kinds of patch change exist, and they keep different things.** CIG marks each LIVE build
  with a "Long Term Persistence" (LTP) status:
  1. **Full wipe** ("Disabled", "Not Preserved"): aUEC, reputation, every item and every
     in-game-bought ship are reset. Only account-bound things survive: pledged ships and items
     (always), and earned blueprints (4.8). Full wipes: 3.15 (Nov 2021), 3.17.2 (Jul 2022, items and
     wallet only), 3.18 (Mar 2023), 4.0 (Dec 2024), 4.8 (May 2026). 3.23 (May 2024) wiped aUEC only.
  2. **LTP rebuild** ("Enabled", "Preserved"; characters "built from LTP data"): the normal major
     patch. aUEC, reputation, stored non-consumable items and stored in-game-bought ships survive;
     consumables, rentals, unstowed in-game ships, custom loadouts and **in-progress refinery jobs
     are lost**. Every major 4.x patch between the wipes was one (4.0.1–4.7, 4.9–4.10.2).
  3. **Hotfix-style point release**: no character rebuild, everything stays where it was (4.8.1,
     4.8.2; secondary sources say the same of 4.7.1 and 4.8.3). CIG does not announce in advance
     which kind a point release is.
- **Refinery work orders never survive a rebuild or a wipe.** CIG lists them as not persisted in
  every LTP note from 3.19.1 through 4.7; no later note says that changed.
- **Materials are the least stable category.** Ore and commodity cargo was outside LTP until 4.10
  (Aug 2026), when CIG added ore and commodity containers — not retroactively, and not ore pods that
  ship with a vehicle. One patch cycle of evidence.
- **Home-location consolidation**: since 3.15 the residence a player picks at first login decides
  where their ships are; at each LTP rebuild players pick a residence again and what persisted is
  delivered there (items into the residence's local inventory, ships to its hangar, custom
  components stripped into the inventory). CIG's notes confirm the parts (3.15, 3.19, 3.20, 3.24,
  4.9 PTU); no single CIG post states the whole rule, and the ship side changed with 4.8/4.9.
- **Stable enough for a default wipe selection**: everything except pledged ships and blueprints in
  a full wipe; consumables only in an LTP rebuild; refinery orders end in both. See
  [§9](#9-default-wipe-selection-candidates).

## 1. Long Term Persistence in brief

- **Introduced in 3.8.2** (patch notes dated February 2020): an early LTP that kept FPS item
  purchases, ship component purchases, ship purchases and aUEC earnings across database wipes.
- **Split into three pillars in 3.15** — Items, Wallet (aUEC), Reputation — so CIG can wipe one
  without the others ("Currency Hotfix and Upcoming Wipes", 2021-09-15). 3.17.2 used this: items and
  wallet wiped, reputation kept.
- **A record has to exist for a thing to persist.** Looted items that never entered an owned
  inventory, and in-game ships never retrieved and stored, were not written to LTP (3.19.1 and 3.20
  LTP posts). Storing items at a station or landing zone, or in an owned ship, created the record.
- **Account-bound things are outside LTP's reach**: pledged ships, subscriber flair and rewards are
  restated as safe in every wipe announcement (3.15, 3.17.2, 4.0, 4.8). A pledge item lost in game
  returns at the next database reset (KB *Character Repair*); since 4.8 a "Claim Mode" at the home
  location's freight elevator recovers ship-related pledge items in between (KB *In-Game Pledge Item
  Recovery*, 4.8 hotfix notes).

## 2. Patch history, 3.15 to 4.10

| Version (LIVE date) | LTP status in the build info | What was reset |
|---|---|---|
| 3.15.0 (2021-11-10) | Disabled — full LTP wipe | items, wallet, reputation |
| 3.17.2 (2022-07-28) | Disabled — reputation preserved | items, wallet |
| 3.18.0 (2023-03-09) | Disabled — full wipe and reset | everything (start 200,000 aUEC) |
| 3.19.0 (2023-05-16) | Enabled; database reset "resetting player locations" | player locations, non-LTP state |
| 3.20.0 – 3.22.x (2023-09 – 2024-02) | Enabled; short-term database reset | non-LTP state |
| 3.23.0 (2024-05-10) | Enabled; full aUEC wipe | wallet, non-LTP state |
| 3.24.0 – 3.24.3 (2024-08 – 2024-11) | Enabled, built from LTP | non-LTP state |
| 4.0 Preview (2024-12-19) | Disabled — full persistence wipe | items, wallet, reputation |
| 4.0.1 – 4.7 (2025-01-28 – 2026-03-25) | Enabled, built from LTP | non-LTP state |
| 4.8 (2026-05-13) | Not Preserved — wipe keeping only earned blueprints | aUEC, in-game ships, items, resources, reputation, Wikelo and Executive Hangar rewards |
| 4.8.1, 4.8.2 (2026-06) | Enabled (hotfix builds) | nothing announced |
| 4.9 – 4.10.2 (2026-07-15 – 2026-10-09) | Preserved | non-LTP state |
| 4.10.3 (PTU, 2026-11-09) | Enabled on PTU | — |

"Non-LTP state" is the exclusion list of [§3](#3-items-by-kind)–[§6](#6-refinery-work-orders).
As of 2026-11-10 no further wipe is announced. CIG's stated intent since 4.0 is to wipe only when
necessary; a CIG reply of 2025-01-29 still keeps wipes possible during the Alpha.

## 3. Items by kind

| Kind | LTP rebuild | Full wipe | Evidence |
|---|---|---|---|
| Food, drinks, medpens / utility pens, ammunition | **lost** | lost | 3.19.1, 3.20, 3.23, 3.24.x, 4.0.1; 4.0.2–4.7 build info names in-game-bought consumables |
| Magazines | **lost** (as ammunition) | lost | CIG lists ammo; the FPS magazine is the ammo item — an interpretation, not CIG's word |
| Armour, FPS weapons, clothing, attachments (non-consumable) | **kept** if stored in an owned inventory | lost (pledged ones return) | 3.20 LTP post lists non-consumables such as looted armour and weapons |
| Loose ship components and vehicle weapons | **kept**, delivered to the home inventory | lost | in LTP since 3.8.2; 3.20: weapons and components are stripped off customised ships into the home inventory |
| Components fitted to a ship | **reset**: ship returns with its default loadout, parts go to the home inventory | lost | 3.20; with 4.8 the "insured loadout" saved at an ASOP terminal is the recoverable configuration |
| Hangar decorations | **kept**, returned to the home inventory | lost | 3.24.0 – 3.24.3 build info |
| Blueprints | kept | **kept** (4.8) | 4.8 wipe announcement and notes |
| Crafted items | kept with their stats since 4.9 **(secondary)** | lost | fan guide; players reported stat loss on rebuilds before 4.9 |

Caveats that hold across 4.x:

- **Unreliability is a documented property, not an exception.** The KB's 4.10.2 known-issues page
  carries "Various Inventory Items Are Missing After a Patch", and CIG's 2025-01-31 status post
  called LTP unreliable, with frequent reports of lost items, ships and money. A wipe default
  should therefore be what CIG *intends*, and members need a way to correct their stock after the
  patch.
- Items held in hand when logging out away from the primary residence can vanish across a patch
  (KB 4.10.2 known issues).
- From 4.8 the build info shows only the LTP status; the exclusion list is gone from the notes.
  Nothing announced a change to the exclusions, so this note assumes they still apply (medium
  confidence for 4.8+).

## 4. Materials: ores, minerals, refined goods, gases

- **Before 4.10, commodity cargo was outside LTP.** The 4.0.1 notes list "some types of cargo"
  among the losses; players reported losing all refined and high-quality crafting materials at the
  4.9 rebuild (Spectrum, 2026-07-16/17, **secondary**).
- **4.10 (2026-08-26) added ore and commodity containers to LTP**, for cargo obtained from 4.10 on;
  ore pods that come with a ship fall under item recovery and do not keep their ore, while pods
  bought in game do. Source: a CIG comment on Issue Council report STARC-128308 — the official IC
  page is client-rendered, so the comment was read on a third-party mirror (**secondary access to a
  primary statement**).
- A fan guide adds that refined ores and gems keep their quality grade from 4.10.2 (**secondary**;
  not in the abridged 4.10.2 notes).
- Gases (hydrogen, nitrogen, …) are commodity cargo in game and follow the same rule; no CIG
  statement singles them out.
- Material sitting in a refinery order is lost with the order ([§6](#6-refinery-work-orders)).
- Full wipes reset materials ("resources" in the 4.8 announcement).

## 5. Ships: bought in game versus pledged

- **Pledged ships** (RSI website, "My Hangar") are never wiped and are delivered to the home
  location at a patch (3.20 LTP post); this matches the decision already taken on the map.
- **Ships bought with aUEC** are in LTP since 3.8.2 (a fix in 3.20 made them persist reliably). They
  survive an LTP rebuild **only when stored**: destroyed ships and their fitted parts are lost (3.20),
  and from 4.2.1 to 4.7 the build info names "unstowed in-game purchased ships" among the losses.
  Every full wipe resets them; 4.8 names in-game purchased vehicles and the Wikelo and Executive
  Hangar reward ships explicitly.
- **Rented ships** never persist (3.19.1, 3.20, 3.24.x).
- **4.8 changed ship recovery**: two loadouts per ship (manufacturer and insured), a claim timer
  that runs in the background, item bricking of the previous copy on a claim, and a cap on ships
  per local hangar. Since 4.9, ships stored before the patch are shown as deliverable at the ASOP
  terminal with their insured loadout (**secondary**: fan guide; players reported problems for
  anyone who changed home location at 4.9).

## 6. Refinery work orders

- **Not persisted, at every rebuild and every wipe.** CIG says so in the 3.19.1 and 3.20 LTP posts,
  the 3.23, 3.24.x and 4.0.1 notes, the 2025-01-29 CIG reply, and the build info of every LIVE
  release from 4.0.2 to 4.7. From 4.8 the notes no longer list exclusions; no note announced that
  refinery jobs became persistent, and fan guides for 4.10 still advise finishing refining before an
  update (**secondary**).
- Hotfix-style point releases do not rebuild characters; whether a running order survives one is
  not documented. Treat "end running refinery orders" as an effect of a rebuild or a wipe, not of a
  hotfix.

## 7. aUEC and reputation

- **aUEC**: in LTP since 3.8.2, its own pillar since 3.15. Reset by 3.15, 3.17.2, 3.18, 3.23
  (wallet only), 4.0 and 4.8; kept by every other release. The restart balance is a base amount
  (20,000 aUEC since 3.19; 200,000 in 3.18) plus the account's unbundled UEC (KB *Currencies*). The KB
  says CIG tries not to wipe wallets and that the patch notes say when it does.
- **Reputation**: wiped by 3.15, 4.0 and 4.8; explicitly kept by 3.17.2. Not tracked by the
  Basetool; listed for completeness.

## 8. Home-location consolidation

**How it works** (assembled from CIG's notes; the parts are primary, the whole is inferred):

1. The **primary residence** (home location) is picked at first entry into the universe; it decides
   where the character starts and where all starting items, ships and vehicles are (KB *Personal
   Inventory*). Since **3.15** the initial spawn decides the location of all ships (3.15.0 notes);
   pledge and subscriber items go into the home location's local inventory (3.15.0 notes). Since
   **3.24** the personal hangar sits at the residence and cannot be moved (KB *Hangar FAQs*).
2. During a patch cycle a player moves things themselves; the KB says items must be relocated
   manually once in game. Only the medical respawn point can be changed in game, at a clinic or
   hospital bed (KB *Personal Inventory*).
3. At an **LTP rebuild** characters are rebuilt from LTP data and every player picks a residence
   again at first login (3.19.0 reset player locations; secondary sources describe re-selection at
   every major patch). What persisted is delivered there: items into the home inventory (3.19.0:
   items in the home location inventory are safe and recovered), pledged ships to the home location
   with custom weapons and components stripped into the home inventory (3.20), hangar decorations
   into the home inventory (3.24.x). The 4.9 PTU carried the known issue that a player's primary
   residence might not contain their items — the expected state being that it does.
4. At a **full wipe** the same happens with an empty stock: the new residence receives only the
   account-bound items (and blueprints in 4.8).

**How it differed across 4.x:**

| Release | Consolidation behaviour |
|---|---|
| 4.0 Preview, 4.8 | full wipe — fresh residence pick, only pledged items (and blueprints in 4.8) arrive |
| 4.0.1 – 4.7 | rebuild — residence re-picked, persisted items to the home inventory, ships returned with default loadouts |
| 4.7 | Nyx People's Service Stations become selectable home locations, so the set of residences is not fixed |
| 4.7.1, 4.8.1 – 4.8.3 | no rebuild — no re-pick, nothing moves (4.8.1/4.8.2 build info has no "built from LTP" line; the rest **secondary**) |
| 4.8 | insured loadouts; Claim Mode for pledge items only at the home location's freight elevator |
| 4.9 | ship persistence across the rebuild reworked: stored ships deliverable with their insured loadout (**secondary**) |
| 4.10 | ore and commodity containers join LTP, so cargo now also arrives after a rebuild |

For the Basetool this matches the planned relocation effect: one new home location per member,
every holding rebooked there. Two consequences: the set of selectable home locations changes
between patches (4.7), so `Location.homeLocation` must stay maintainable, and a hotfix-style patch
needs no relocation at all.

## 9. Default wipe selection candidates

"Rebuild" is an LTP rebuild patch, "full" is a full wipe. **Default** is the proposed preselection
in the admin's patch-change form; the admin can change every row.

| Category (Basetool term) | Default — rebuild | Default — full wipe | Confidence | Basis |
|---|---|---|---|---|
| Consumables: food, drinks, medpens, ammunition / magazines | **wipe** | **wipe** | High to 4.7, medium for 4.8+ | §3 |
| FPS armour, weapons, clothing, attachments (non-consumable) | keep (relocate) | **wipe** | High | §3 |
| Ship components and vehicle weapons (loose stock) | keep (relocate) | **wipe** | High | §3 |
| Raw materials, ores, refined goods, gases | keep (relocate) | **wipe** | Low for rebuilds (persistent only since 4.10), high for wipes | §4 |
| In-game-bought ships | keep (relocate) | **wipe** | High | §5 |
| Pledged ships | never wiped | never wiped | High (decided on the map) | §5 |
| Blueprints | keep | **keep** | High for 4.8, a single data point | §3 |
| Running refinery orders | **end** | **end** | High | §6 |
| aUEC / bank balances (REQ-BANK-013) | keep | reset — offered, off by default as decided | High that aUEC resets in a full wipe | §7 |

Two notes for the wipe-selection ticket:

- The **patch kind is the better default switch** than item kinds alone: a form that starts from
  "rebuild" or "full wipe" can preselect the columns above, and a hotfix needs no effect.
- "Consumable" is not one Basetool category today. `GameItemKind` has `FOOD`, but magazines are
  `WEAPON_ATTACHMENT` beside scopes and barrels, and medpens are likely `GENERIC`. Preselecting
  consumables needs a finer classification than `GameItemKind`; that is a design question for the
  wipe-selection ticket, not settled here.

## 10. Open uncertainties

- **No single CIG statement of the consolidation rule.** It is assembled from 3.15, 3.19, 3.20, 3.24
  and 4.9 PTU notes plus consistent player reports.
- **Exclusions after 4.8 are implicit.** The build info dropped the "not saved between releases"
  list; whether consumables and refinery jobs are still excluded rests on the absence of any
  announced change.
- **Material persistence is new.** Ore and commodity containers persist only since 4.10, and the
  CIG statement was read on a mirror of the Issue Council. Quality grades persisting (4.10.2) is
  secondary only.
- **Ships across a rebuild since 4.8/4.9** (insured loadouts, "deliverable" ships) are documented
  only by secondary sources.
- **Patch kind is not announced ahead.** Players asked before 4.7.1 whether it would rebuild
  characters; no CIG answer was posted in that thread. The admin will sometimes record a patch
  change before knowing its kind.
- **Next wipe**: none announced on 2026-11-10; 4.10.3 is on the PTU with LTP enabled.

## Sources

All accessed 2026-11-10. Spectrum threads were read through the public thread API
(`/api/spectrum/forum/thread/nested`), which returns the post as published.

**Primary — RSI Spectrum announcements and CIG replies**

- Currency Hotfix and Upcoming Wipes (2021-09-15):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/currency-hotfix-and-upcoming-wipes>
- Alpha 3.17.2 Database Wipe (2022-06-29):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/alpha-3-17-2-database-wipe>
- Star Citizen Alpha 3.19.1: Long Term Persistence Update (2023-05-31):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/star-citizen-alpha-3-19-1-important-long-term-pers>
- Star Citizen Alpha 3.20: Long Term Persistence Update (2023-09-16) — LTP excludes
  "Refinery Jobs in progress":
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/star-citizen-alpha-3-20-0-long-term-persistence-up>
- Star Citizen Alpha 4.0 - Database Wipe (2024-11-20):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/star-citizen-alpha-4-0-database-wipe>
- CIG reply in "CIG, will there be more wipes in 4.0.2 and 4.1?" (2025-01-29):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/cig-will-there-be-more-wipes-in-4-0-2-and-4-1>
- Alpha 4.0.1: Current Issues & Updates (2025-01-31):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/alpha-4-0-1-current-issues-amp-updates-1>
- Star Citizen Alpha 4.8 - Wipe Update (2026-05-12):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/star-citizen-alpha-4-8-wipe-update>
- Alpha 4.8: Vehicle Insurance and Item Bricking Update (2026-05-14):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/1/thread/alpha-4-8-vehicle-insurance-and-item-bricking-upda>

**Primary — patch notes (Spectrum patch-notes forum unless noted)**

- 3.8.2 Comm-Link (Feb 2020), read via the mirror <https://api.star-citizen.wiki/api/comm-links/17496>;
  RSI: <https://robertsspaceindustries.com/comm-link//17496-Star-Citizen-Alpha-382>
- 3.15.0 LIVE (2021-11-10) — "your initial spawn chooses the location of all your ships":
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-15-0-live-7865275-patch-notes>
- 3.17.2 LIVE (2022-07-28):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-17-2-live-8148301-patch-notes>
- 3.18.0 LIVE (2023-03-09):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-18-0-live-8389318-patch-notes>
- 3.19.0 LIVE (2023-05-16):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-19-0-live-8478820-patch-notes>
- 3.20.0 LIVE (2023-09-18):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-20-0-live-patch-notes>
- 3.23.0 LIVE (2024-05-10):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-23-0-live-patch-notes>
- 3.24.0 LIVE (2024-08-28) and 3.24.3 LIVE (2024-11-20):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-24-0-live-patch-notes>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-3-24-3-live-patch-notes>
- 4.0 Preview (2024-12-19):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-0-release-notes>
- 4.0.1 LIVE (2025-01-28):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-0-1-live-release-notes>
- 4.0.2 LIVE (2025-02-28):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-0-2-live-release-notes>
- 4.1, 4.1.1, 4.2, 4.3.0 – 4.3.2, 4.4.0, 4.5.0, 4.6 LIVE (2025-03-27 – 2026-01-28), same build info:
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-1-live-release-notes>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-4-0-live-release-notes>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-6-live-release-notes>
- 4.2.1 LIVE (2025-07-17), first to name unstowed in-game ships:
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-2-1-live-release-notes>
- 4.7 LIVE (2026-03-25) — "in-progress refinery jobs, and in-game purchased consumables are not
  saved":
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-7-live-release-notes>
- 4.8 LIVE (2026-05-13):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-8-live-release-notes>;
  full Comm-Link via the mirror <https://api.star-citizen.wiki/api/comm-links/21168>, RSI:
  <https://robertsspaceindustries.com/en/comm-link/Patch-Notes/21168-Star-Citizen-Alpha-48>
- 4.8 LIVE Hotfix Central (Claim Mode):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-8-live-hotfix-central>
- 4.8.1 and 4.8.2 LIVE hotfixes (2026-06-06, 2026-06-17):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-8-1-live-hotfix>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-8-2-live-hotfix>
- 4.9 PTU (2026-06-24, 2026-07-02), primary-residence known issue:
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-9-ptu-patch-notes-1>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-9-ptu-patch-notes-6>
- 4.9, 4.10, 4.10.1, 4.10.2 LIVE (2026-07-15 – 2026-10-09):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-9-live-release-notes>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-10-live-release-notes>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-10-1-live-release-notes>,
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-10-2-live-release-notes>
- 4.10.3 PTU (2026-11-09):
  <https://robertsspaceindustries.com/spectrum/community/SC/forum/190048/thread/star-citizen-alpha-4-10-3-ptu-patch-notes-5>

**Primary — RSI knowledge base**

- Personal Inventory: <https://support.robertsspaceindustries.com/hc/en-us/articles/4409996738327-Personal-Inventory>
- Hangar FAQs: <https://support.robertsspaceindustries.com/hc/en-us/articles/115013325128-Hangar-FAQs>
- Currencies of Star Citizen: <https://support.robertsspaceindustries.com/hc/en-us/articles/360006492734-Currencies-of-Star-Citizen-UEC-aUEC-REC-Store-Credit>
- Character Repair: <https://support.robertsspaceindustries.com/hc/en-us/articles/360041509433-Character-Repair>
- In-Game Pledge Item Recovery: <https://support.robertsspaceindustries.com/hc/en-us/articles/41746979742359-In-Game-Pledge-Item-Recovery>
- Star Citizen Alpha 4.10.2 Known Issues: <https://support.robertsspaceindustries.com/hc/en-us/articles/360056254754-Star-Citizen-Alpha-4-10-2-Known-Issues>

**Primary statement, secondary access**

- Issue Council STARC-128308, "Commodity containers not saved in LTP", CIG comment on 4.10.
  Official page (client-rendered, not readable here):
  <https://issue-council.robertsspaceindustries.com/projects/STAR-CITIZEN/issues/STARC-128308>;
  read on the third-party mirror <https://jira.external-share.com/issue/ef2df845-155d-47eb-bcb0-2c90b7e8e638>

**Secondary — used to locate sources or flagged in the text**

- Spectrum community threads: "Can we get clarity on whether 4.7.1 is a hotfix-style patch or a full
  LTP-style update?" <https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/can-we-get-clarity-on-whether-4-7-1-is-a-hotfix-st>;
  "CIG, we need a response!!!" <https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/cig-we-need-a-response>;
  "Future improvements to LTP and mining yields?" <https://robertsspaceindustries.com/spectrum/community/SC/forum/3/thread/future-improvements-to-ltp-and-mining-yields>
- Star Citizen Wiki, Primary Residence: <https://starcitizen.tools/Primary_Residence>
- Start Star Citizen, "What is a Star Citizen Wipe?": <https://startstarcitizen.com/what-is-a-wipe%3F>
- TimeSaver, Alpha 4.10 patch notes summary: <https://timesaver.gg/blog/star-citizen-alpha-4-10-patch-notes-siege-of-orison>
