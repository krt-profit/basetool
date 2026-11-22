# What VerseKit can already do for a job-order area

Research for [#3609](https://github.com/krt-profit/basetool/issues/3609), part of the map
[#3607](https://github.com/krt-profit/basetool/issues/3607). Status: findings as of 2026-11-22.
Facts only; nothing here is decided.

## Sources

Primary sources only. Each claim below names its file and lines.

- **VerseKit source**, public, GPL-3.0: <https://github.com/Xharig/VerseKit>, `main` at commit
  [`8a22d5839d2d6924aca9727cb65d1d168b292b67`](https://github.com/Xharig/VerseKit/tree/8a22d5839d2d6924aca9727cb65d1d168b292b67)
  (2026-11-21, "VerseKit 5.5.0"). Read as data; nothing was built or run.
  - `basetool/x.rs` is short for `crates/versekit-core/src/basetool/x.rs`, `orgstd/x.rs` for
    `crates/versekit-core/src/orgstd/x.rs`, `ui/x.rs` for `crates/versekit-ui/src/x.rs`. Line
    numbers are at that commit.
- **VerseKit documentation** at the same commit: `PRIVACY.en.md`, `CHANGELOG.en.md`,
  `tools/basetool-vertrag/README.md` (the vendored exchange contract), `tools/orga-standard/v1/openapi.yaml`
  (VerseKit's own org standard).
- **VerseKit issues** [#1–#14](https://github.com/Xharig/VerseKit/issues?q=is%3Aissue): none is
  about job orders.
- **Basetool**, `origin/main` at `cbcc56763a6463d6833ab09cbe2d75702f6db265`:
  `docs/exchange/errors.md`, `docs/exchange/sync-guide.md`, `docs/exchange/resources/`
  (`org-demand.md`, `org-units.md`, `store-choices.md`, `refinery-orders.md`, `bank.md`,
  `drafts.md`, `org-stock.md`), `docs/specs/external-exchange.md` (REQ-XCH-047, REQ-XCH-054) and
  `docs/legal/approved-clients.md`.
- **Earlier research**, compared below: `research/versekit-reads.md` (branch
  `research/versekit-reads`, VerseKit `b10ffb4`, 2026-11-08) and
  `docs/research/versekit-bank-expectations.md` (branch `research/versekit-bank-expectations`,
  VerseKit `cf770dc`, 2026-11-08). Both commits are ancestors of `8a22d58`.

## The short answer

- **No job-order area exists.** VerseKit has no job-order route, scope, model, page or setting,
  and nothing that reads or writes aUEC orders. Job orders appear only inside other areas: as
  org-stock earmark targets and booking lines, and as the demand details' `orders[]` (MATERIAL
  and ITEM only).
- **The sync machinery is generic and reusable:** per-area switches and scope lists, voluntary
  scopes, snapshot plus cursor to the end, `CURSOR_EXPIRED` fallback, one pass at a time at most
  every 300 s, back-off from 5 s doubling to 5 min and never shorter than `Retry-After`, isolated
  areas that fail and wait alone, and published data with a generation number.
- **Two write patterns exist.** Change sets (`POST …/changes`, up to 500 ops) behind the
  mass-change hold and a sparing memory of answered ops; and, in the bank only, single writes
  whose `Idempotency-Key` is held in memory until a clear answer comes. A key kept on disk across
  a lost answer exists for the refinery change-set route, with a `clientKey` written into the
  local job before the create, and for a stock share into a unit's pool that did not get through,
  retried under its key for up to 23 hours.
- **No conditional requests.** VerseKit sends no `If-Match` and reads no `ETag`; it has no
  handling of `412` or `428`. The Basetool exchange has neither status either; versions travel in
  the body and a mismatch is `409 VERSION_CONFLICT`.
- **Retention follows one rule:** other members' data (org stock, demand, the whole bank, the unit
  list) in memory only, at most 7 days; own synced data and cursors in
  `basetool-<installationId>.json` in the data folder, which is in the backup; the refresh token
  and the DPoP key in the OS secret store.
- **Server-built links:** the login page and a held change set's `confirmationUrl` pass a host
  check (`https` on the issuer or API host or a subdomain); the bank queue's per-request `link`
  is opened with only a generic web-address check. Drafts' `frontendUrl` is not used.
- **Org-unit choices** come from `GET /me/org-units` (id, name, shorthand, kind; no writer or
  Logistician flag), offered in settings for new ships and new refinery orders and on ship and
  stock-sharing forms. The refinery store choices (`exchange.refinery.store`) are approved for
  VerseKit but never requested. The demand details (`exchange.demand.details`) are requested as a
  voluntary scope and shown as an order list, without unit ids or links.

## 1. How VerseKit syncs a resource

**Areas, switches and scopes** (`basetool/scopes.rs`):

- Every area is off by default; only scopes of switched-on areas are requested, and the module
  warns that a scope the Basetool has not released "can make the whole login fail" (L18-23).
  `exchange.connect` and `offline_access` are always requested (L25-28).
- Seven areas with their settings switch and scope list: blueprints, stock, hangar, demand, org
  stock, refinery, bank (`AREAS`, L82-91; switch names L56-73).
- **Voluntary scopes** ride on an area: `exchange.demand.details` with the demand,
  `exchange.orgstock.details` and (with a second switch) `.write` with the org stock,
  `exchange.bank.request` and (with a second switch) `.queue` with the bank. Without them the area
  runs without the extra (`optional_scopes`, L128-148; `missing_optional`, L168-176).
- `exchange.orgunits.read` is added when hangar, stock or refinery is on (`units_wanted`,
  L123-126; L158-161), as a scope of its own so the hangar sync does not stop without it (L34-37).
- Scopes the issuer's discovery document does not list in `scopes_supported` are left out of the
  device login (`basetool/connection.rs` L490-498, L502-530).

**A pass** (`basetool/sync.rs` L372-509): service document (installation id, `capabilities`,
`minClientVersion`; L381-405), the installation's name, the account check, then every
switched-on area whose scopes are all granted, in order. An area without its scopes is told to
`forget()` (L442-445). **One pass at a time** (L27-28); pages take new data by a generation
number and keep their own state (L22-25, `Shared<T>` L88-137).

**The `Area` trait** (`basetool/sync.rs` L213-238): `runs`, `isolated` ("errors must not stop the
others"), `forget_after(action)`, `private` (errors logged with code, status and an id-free path
only), `run`, `forget`. Isolated today: org units, org stock, refinery, bank. The demand area is
not isolated: "An error stops the pass" (`basetool/demand.rs` L412-413). **New since `cf770dc`:**
an isolated area answered with `Retry-After` waits alone until then, the others go on
(`basetool/sync.rs` L446-452, L470-481).

**Snapshot plus cursor** (`basetool/feed.rs`): `fetch_all` pages with `limit=500` and `cursor`
until `hasMore` is false and ends with `FEED_STUCK` on a repeated cursor (L73-102);
`fetch_resuming` falls back to the snapshot on `CURSOR_EXPIRED` (L104-114). `pull_generic`
applies a feed on top of the last stand kept in the state, removing deletion marks by `key` or
`id` (`basetool/area_support.rs` L138-195). Ids `key`, `id`, `ref`, `bt` are opaque, "never taken
apart" (`basetool/feed.rs` L23-24).

**Cadence and back-off** (`basetool/schedule.rs`): at the start, after an own change collected
for 20 s, otherwise at most every 300 s (L18-32). After a retryable error 5 s doubled up to
5 min with jitter, never shorter than `Retry-After`; after a stop code no automatic retry, not
even after an own change, only a button or the next start (L99-111). A `Retry-After` on a send
brings the next pass forward to its end but never back (`resume_at`, L113-118).

**Error classes** (`basetool/error.rs` L55-74, L119-136): `RETRY` lists 12 codes
(`BANK_EXCHANGE_DISABLED`, `RATE_LIMITED`, `DPOP_PROOF_LIMIT`, `QUOTA_EXCEEDED`,
`SERVICE_UNAVAILABLE`, `EXCHANGE_DISABLED`, `REGISTRY_UNAVAILABLE`, `EXCHANGE_BUDGET_EXHAUSTED`,
`RELAY_BUSY`, `BACKEND_RELAY_FAILED`, `IDEMPOTENCY_IN_PROGRESS`, `NETWORK`), plus any status of
500 and up or no answer; `RELOGIN` 3; `FORGET_KEY` `INSTALLATION_REVOKED`;
`CLIENT_VERSION_UNSUPPORTED` asks for an update; everything else stops.

**Reads outside the pass:** org-stock targets are read when a form needs them
(`basetool/orgstock.rs` L561-564); an account's bank history on a click, reused for 300 s
(`basetool/bank.rs` L58-59, L498-505). The bank's queue is a second paged read inside the bank
area, and a `NOT_BANK_STAFF` answer means "no queue", not an error (`basetool/bank.rs` L608-638).

## 2. How VerseKit sends writes

**`Idempotency-Key`:** a fresh UUIDv4 per logical write (`basetool/connection.rs` L107-120); the
request keeps it across the one nonce retry and the one token refresh (L711-777, header at
L744-746). Every non-2xx becomes an `ApiError` with the problem document's `code` (or
`HTTP_<status>`) and `Retry-After` (L753-768).

**Change sets** (`basetool/feed.rs` L246-280, `basetool/area_support.rs` L271-373): one
`POST` per set, at most 500 ops (`basetool/refinery_sync.rs` L41-42, L334-343). Routes the
mass-change guard can hold: `/me/blueprints/changes`, `/me/stock/changes`, `/me/ships/changes`,
`/me/refinery-orders/changes` (`basetool/scopes.rs` L75-80); the org-stock booking
`POST /org-stock/changes` goes out from the page, one set per click
(`basetool/orgstock.rs` L1146-1155).

- **Key per set:** fresh for every set, except the refinery route, whose key is kept in the
  state per hash of the set until an answer comes, so a resend after a lost answer goes under the
  first key (`KEPT_KEY_ROUTES`, `basetool/area_support.rs` L197-250). New since `cf770dc`.
- **Own key in the record:** a refinery job gets a `clientKey` (`vk-rf-…`, random) written into
  its local file **before** the create goes out; `ALREADY_EXISTS` adopts the existing order
  (`basetool/refinery_sync.rs` L168-173, L316-328; `basetool/refinery_area.rs` L28-29, L138-169,
  L189-195).
- **Sparing:** an op answered without being applied (refused or `unchanged`) is not sent again for
  24 h while the plan holds it unchanged; a sync button releases it. A `Retry-After` on a send
  stops every send route until then, kept in the state across a restart
  (`basetool/pacing.rs` L18-43, L74-86).
- **Per-op refusals** are read from `results[]` by `index`; refused ops stand in the next plan
  again (`basetool/area_support.rs` L333-369, L120-132).

**Single writes** exist only in the bank (`basetool/bank.rs` L780-876): `POST /bank/requests`,
`…/{id}/correction`, `…/{id}/cancel`, each with `version` in the body where it applies. The write
is held with its key in memory (`Held`) until a clear answer; `settled` treats a 4xx that stops
as final and anything else (no answer, 5xx, `IDEMPOTENCY_IN_PROGRESS`, `RATE_LIMITED`) as
unclear, so the same write goes again under the same key (L818-823). A raise whose answer was
lost counts as answered once the feed shows a new own request of the same kind, account and
amount (L572-580, L806-815). The page refuses a different write while an unclear one is held,
once with a warning (`ui/bank_page.rs` L424-457), and stores a `Retry-After` as a write wait
(L361-390). A held write survives the area's `clear()` "so that it is never sent under a new key"
(`basetool/bank.rs` L440-447) but not a restart (memory only). Sharing from "My stock" into a
unit's pool sends single-op sets to `/me/stock/changes` (`basetool/stock_split.rs` L435-492); a
share that got no answer or hit a full budget is kept with its op and key in the sync state
(`share_later`) and goes out again under the same key, after `Retry-After` or 60 s, for at most
23 hours, "less than the 24 hours the Basetool keeps the answer to a key"
(`ui/stock_share.rs` L94-114, L882-907).

**`409` handling:**

- `MASS_CHANGE_CONFIRMATION_REQUIRED`: the set is held, nothing on that route goes out for 30 min,
  the same set is not resent by itself afterwards; the `confirmationUrl` is kept in memory only
  (`basetool/hold.rs` L18-45, L154-177). The org-stock page shows the link
  (`basetool/orgstock.rs` L1151; `ui/org_stock_page.rs` L841).
- `VERSION_CONFLICT`: the stock sync solves it "by fetching, never by repeating blindly"
  (`basetool/stock_sync.rs` L33); the bank page shows a text and asks for a pass
  (`ui/bank_page.rs` L384-386); the org stock and the stock sharing give it a text as a per-op
  reason (`basetool/orgstock.rs` L65-80; `basetool/stock_split.rs` L407-410).
- `IDEMPOTENCY_IN_PROGRESS` is retryable (`basetool/error.rs` L68).
- `BANK_OPEN_REQUEST_CAP`, `BANK_JUSTIFICATION_REQUIRED`, `BANK_SELF_TRANSFER`,
  `BANK_REQUEST_NOT_PENDING`, `BANK_REQUEST_ALREADY_APPROVED` have own texts
  (`basetool/bank.rs` L676-690).
- `PESSIMISTIC_LOCK` is not named anywhere in VerseKit; by `action()` it stops (409, not in
  `RETRY`).

**`412` and `428`:** not handled and not reachable. The request sends only `User-Agent`,
`Accept`, `Authorization`, `DPoP`, `Content-Type` and `Idempotency-Key`
(`basetool/connection.rs` L735-746); no `If-Match`, `If-None-Match` or `ETag` appears in the
Basetool modules. The Basetool's error catalogue lists neither status
(`docs/exchange/errors.md`), and no `If-Match` or `PRECONDITION_*` exists in the Basetool's Java
or Markdown sources at `cbcc567`.

**Test write guard** (`basetool/guard.rs` L18-30, L45-74): under `VERSEKIT_BASETOOL_WRITE_MAX` a
run may send only `…/changes` sets within the budget and three harmless posts
(`/me/installation`, `/me/account-check`, `/catalog/resolve`); every other write, a bank single
write included, is refused in that mode.

## 3. How VerseKit keeps fetched data

| Data | Where | How long | Source |
| --- | --- | --- | --- |
| Own synced blueprints, stock, ships, refinery orders; cursors, baselines, holds, kept keys, pending shares, send waits | `basetool-<installationId>.json` (and `.bak.json`) in the data folder, which goes into the backup zip | until disconnect, which deletes both files | `basetool/state.rs` L18-61; `PRIVACY.en.md` L331-353 |
| Own refinery orders as the feed sends them, money fields included | the state file (`refinery.server`), raw items | while open | `basetool/refinery_area.rs` L73-82; `PRIVACY.en.md` L304-308, L337-342 |
| Key or id of the Basetool order per refinery job | `raffinerie-auftraege.json` | with the job | `PRIVACY.en.md` L339-342, L350-352 |
| Org stock (other members' entries) | memory only | at most 7 days | `basetool/orgstock.rs` L29-32, L57-58 |
| Demand, demand details included | memory only | at most 7 days | `basetool/demand.rs` L23-28, L42-44, L266-274 |
| Bank: accounts, own requests, queue, history, the feed cursor | memory only; snapshot again after a start | at most 7 days | `basetool/bank.rs` L22-25, L46-47, L416-424 |
| Unit list (`/me/org-units`) | memory only | fetched every pass, dropped after any error | `basetool/orgunits.rs` L22-23, L101-105 |
| `confirmationUrl` of a held set | memory only | 30 min | `basetool/hold.rs` L33-45 |
| Refresh token | Windows DPAPI file under `%LOCALAPPDATA%\VerseKit\basetool`; Linux Secret Service; otherwise a `0600` file in a `0700` folder; never the data folder | until disconnect | `basetool/secrets.rs` L18-31 |
| DPoP key | Windows CNG, not exportable, in the TPM where usable; elsewhere PKCS#8 PEM in the secret store | per installation, never per start | `basetool/key.rs` L18-27 |
| Org-standard orders (VerseKit's own standard) | memory only; connections in the user folder | fetched on page open when older than 10 min | `orgstd/data.rs` L22-23; `PRIVACY.en.md` L240-267 |

Streamer mode hides unit names and shorthands, requester handles and bank amounts
(`basetool/orgunits.rs` L61-62; `basetool/demand.rs` L161-162; `ui/bank_page.rs` L495-499).
The approved-clients entry permits own bank requests to persist; VerseKit keeps them in memory
only (`docs/legal/approved-clients.md`, `versekit` row).

## 4. How VerseKit opens server-built links

- **Host-checked:** `Config::trusted_address` passes a URL only when it is `https` on the issuer
  or API host or a subdomain, with control characters, spaces and backslashes refused
  (`basetool/config.rs` L88-119). It filters the device login's `verification_uri` (otherwise the
  issuer's own `/device` page; `basetool/connection.rs` L550-561) and a held set's
  `confirmationUrl` (otherwise no button, only the host is logged; `basetool/hold.rs` L139-152).
  `verification_uri_complete` is never shown (`basetool/connection.rs` L20-24).
- **Generic check only:** the bank queue entry's `link` (`basetool/bank.rs` L244-245, L266) is a
  button "in the Basetool" that calls `browser::open_in_browser(&entry.link)` directly
  (`ui/bank_page.rs` L856-865). `open_in_browser` accepts any `http` or `https` address with a host
  and refuses other schemes (`crates/versekit-core/src/browser.rs` L25-54); it does not apply
  `trusted_address`. New since `cf770dc`.
- **Not used:** no code reads a draft's `frontendUrl` or calls `/drafts`; `handoff=` values are
  still redacted from logs and streamer mode (`crates/versekit-core/src/redact.rs` L55,
  `crates/versekit-core/src/streamer.rs` L197).
- VerseKit **builds** no Basetool web link itself; opening uses `ShellExecuteW`, `open`,
  `xdg-open` or `gio open` (`browser.rs` L62-111). The settings page opens nothing in demo mode
  (`ui/basetool_page.rs` L419-425).
- On the Basetool side the per-request `link` is an absolute `https://profit-base.online/…`
  address (`docs/exchange/resources/bank.md`, queue section).

## 5. Org-unit choices and the demand details

**`/me/org-units`** (`basetool/orgunits.rs`): `GET /me/org-units` parsed to `{id, name,
shorthand, kind}`, an entry without `id` dropped, an unknown `kind` kept (L32-67); label
`Name (SHORT)` (L69-73). The area runs with hangar, stock or refinery and is isolated (L80-122).
The Basetool's list carries no Logistician or writer flag (`docs/exchange/resources/org-units.md`,
"This list carries no `logistician`"); the only Logistician flag VerseKit holds comes from the
org-stock pages' units (`basetool/orgstock.rs` L375-410).

Where VerseKit offers the units today:

- settings: the unit of new ships and the unit of new refinery orders, each shown only with two
  or more units, a unit the member left falling back to none (`ui/basetool_page.rs` L1081-1140);
- the refinery area takes the chosen unit while the member is in it, else the only one, and
  creates nothing without a unit (`basetool/refinery_area.rs` L58-71;
  `basetool/refinery_sync.rs` L305-308);
- the ship card, with two or more units (`ui/hangar_page.rs` L327-336), and sharing stock into a
  unit's pool (`ui/stock_share.rs` L347-349).

**Refinery store choices** (`GET /me/store-choices/receivers` and `…/job-orders?material=`,
REQ-XCH-054): not read. `exchange.refinery.store` "is never asked for: the stock is booked by the
stock sync alone, a pickup only closes the order" (`basetool/scopes.rs` L47-49; test L202-206);
a `pickup` goes without store lines (`basetool/refinery_sync.rs` L26-28). The scope is in
VerseKit's approval (`docs/legal/approved-clients.md`).

**Demand details** (`exchange.demand.details`, REQ-XCH-047): requested as the demand's voluntary
scope (`basetool/scopes.rs` L40-41, L132-134). When `orders[]` is present VerseKit parses per
order `id`, `number`, `type`, `status`, `priority`, `unit.name`, `requestingUnit.name`, `handle`
(at most 200 characters), `comment` (at most 2000) and the material and item lines with open
quantity and quality floor; it sorts by priority, then number (`basetool/demand.rs` L82-106,
L130-166, L205-212). It keeps names only: `unit.id`, `requestingUnit.id`, `material.bt`,
`item.bt` and `component` are not kept. The "Unit demand" page lists the orders with number,
handle, status, priority, requesting unit, up to six lines and the comment cut short
(`ui/demand_page.rs` L293-349, L415-425). No order carries a link, and none is opened.

## 6. What already touches job orders or aUEC

**Job orders, through the org stock** (`exchange.orgstock.*`):

- a lot's and an entry's earmarks: `earmarked.jobOrders`, per entry `jobOrders[]` with `id`,
  `number`, `quantity`, `delivered`, and `free.jobOrders` (`basetool/orgstock.rs` L198-259);
- the earmark targets `GET /org-stock/targets?unit=&material=`: `jobOrders[]` with `id`, `number`,
  `status`, and missions (`basetool/orgstock.rs` L545-581);
- writes: `allocation-add|change|remove` naming a `jobOrder`, and `fromJobOrders[]` lines on a
  booking that takes earmarked stock (`basetool/orgstock.rs` L921-944, L1070-1092;
  `ui/org_stock_page.rs` L1174-1200);
- `EARMARK_TARGET_INVALID` and `STOCK_EARMARKED` have texts (`basetool/orgstock.rs` L65-80).

**Job orders, through the demand:** the order list above (MATERIAL and ITEM only).

**VerseKit's own org standard** (`versekit-org` v1, VerseKit 5.4.0, commit `786ab1f`,
2026-11-16): a client for any org server that follows it (`orgstd/mod.rs` L18-32), with scopes
`orders:read` and `orders:write` (L53-54). Its order is `{id, title, status open|done|cancelled,
location, due, note, items[{item, quantity, unit, delivered}]}`, goods only, no money
(`orgstd/data.rs` L87-122, L206-255; `tools/orga-standard/v1/openapi.yaml` L294-330, L519-527).
The client reads orders and books a delivery with an `Idempotency-Key` made when the form opened
(`orgstd/client.rs` L344-361; `orgstd/hub/write.rs` L18-29). Orders are created, edited, closed
and reopened only in the org server's web panel by its Logistics role
(`crates/versekit-orgserver/src/admin.rs` L184-195). It is separate from the Basetool connection.

**aUEC through the Basetool:** only the bank. Amounts are whole aUEC (`i64`), at most
1 000 000 000 000 per raise (`basetool/bank.rs` L78-84, L716-719); request types `DEPOSIT`,
`WITHDRAWAL`, `TRANSFER` (L692-714). Unknown booking types are shown as "other" with their code;
`AUEC_ORDER` is not named (`ui/bank_page.rs` L522-532). Refinery orders carry `expenses`,
`otherExpenses` and `oreSales` in aUEC (`docs/exchange/resources/refinery-orders.md`); VerseKit
neither parses nor sends them (`basetool/refinery_sync.rs` L48-108, L316-328) but keeps the raw
feed items, these fields included, in the state file (section 3).

**aUEC outside the Basetool:** game data only, such as fixed contract rewards
(`crates/versekit-core/src/contract_rewards.rs` L18-25) and UEX buy and sell prices
(`crates/versekit-core/src/raw_prices.rs`); none of it reaches a Basetool route.

**In-game surfaces used for Basetool data today:** the overlay gets the demand line, the bank
balances, the pending own requests and the queue news from the main window in memory, never a
file (`basetool/demand.rs` L342-358; `basetool/bank.rs` L916-939); refinery orders created in the
web become local jobs "so the overlay reports them" (`PRIVACY.en.md` L304-308).

## What changed since the earlier research

Against `cf770dc` (bank research) and `b10ffb4` (reads research):

- **Bank:** "None found" then; now a bank area since VerseKit 5.3.0 (commit `0605663`,
  2026-11-13) with three scopes, single writes and an isolated, private area
  (`basetool/bank.rs`).
- **Refinery orders:** a two-way area since 5.3.0 (`basetool/refinery_area.rs`,
  `basetool/refinery_sync.rs`), with the first kept-key route and a `clientKey` per job.
- **Isolated areas wait alone after `Retry-After`** (`basetool/sync.rs` L446-452): new.
- **`RETRY` grew from 11 to 12 codes** with `BANK_EXCHANGE_DISABLED` (`basetool/error.rs` L56-58).
- **Server-sent links:** the bank queue `link` joins `verification_uri` and `confirmationUrl`, and
  it is not host-checked (section 4). The earlier finding "builds no Basetool web links itself"
  still holds.
- **Org standard and org server** (5.4.0): new, with an order model of its own (section 6).
- **Unchanged:** the vendored contract copy is still pinned at Basetool commit `20928c7`
  (2026-10-18) and holds no bank, refinery-order or job-order schema
  (`tools/basetool-vertrag/README.md` L10; `tools/basetool-vertrag/v1/schemas/`). Cadence,
  back-off, the trust filter, where tokens and keys live and the in-memory rule for other
  members' data are unchanged in substance; `secrets.rs`, `key.rs` and `config.rs` changed only
  in renames and in how a secret file is replaced.

## Not found

- Any job-order or aUEC-order route, scope, model, page, setting, overlay line or notice.
- Any use of `/me/store-choices/*`, `exchange.refinery.store` or a draft's `frontendUrl`.
- Any conditional request (`If-Match`, `If-None-Match`, `ETag`) or a `412` / `428` path.
- Any unit writer or Logistician flag on the `/me/org-units` list VerseKit keeps.
- A VerseKit issue or roadmap entry about Basetool job orders.

## Open questions for the VerseKit maintainers

These cannot be answered from the source and are not guessed here.

1. **Shape of the area:** would a job-order area be a synced area (switch, scope list, run every
   pass, isolated) like the bank, or a read on opening a page like the bank history and the
   org-stock targets?
2. **Retention:** orders of the member's units carry other members' data (requesting unit,
   requester, comment). Would VerseKit keep all of them in memory only for at most 7 days, as with
   the demand and the bank, or persist orders the member created, as with refinery orders?
3. **Creating an aUEC order:** would VerseKit send it as a single write with the key held in memory
   (the bank pattern) or as a change set with a kept key and an own `clientKey` written before the
   send (the refinery pattern)? How should it recognise its order after a lost answer?
4. **Unit choice for a create:** `/me/org-units` carries no writer or Logistician flag. Would
   VerseKit offer every direct unit and show the server's refusal, or does it want a read of the
   units it may create for, as the store choices are for refinery pickups? Per form or as a setting
   like the refinery unit?
5. **Demand details:** VerseKit drops `unit.id`, `requestingUnit.id` and the `bt` keys. Would a
   job-order area link a demand order to its full order by the shared `id`, or replace the order
   list of the demand page?
6. **Links:** would VerseKit open a per-order link into the Basetool, and should such links pass
   the same host check as `confirmationUrl`? The bank queue `link` uses the generic web-address
   check today.
7. **Refusals:** which order-specific codes should get their own texts? `PESSIMISTIC_LOCK` stops
   today instead of retrying; is that intended for a single write?
8. **Store choices:** `exchange.refinery.store` is approved but never requested, so the job-order
   picker of a refinery pickup is unused. Does VerseKit plan to name job orders anywhere besides
   org-stock earmarks?
9. **Progress notices:** the bank queue drives an overlay notice. Would changes of an order the
   member follows (accepted, funded, completed, cancelled) be wanted as notices or overlay lines?
10. **Contract copy:** will `tools/basetool-vertrag/` be refreshed beyond `20928c7` to cover the
    bank, refinery-order and a future job-order schema in the offline self-test?
11. **Two order models:** the org standard has orders of its own. Would Basetool job orders show on
    the same page as org-standard orders or on a page of their own?
12. **Volume:** the daily write quota is 2000 per member and reads run at most every 5 minutes.
    What request volume would an order area add, per member and pass?
