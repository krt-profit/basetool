# What VerseKit expects from the bank interface

Research note for [#3117](https://github.com/krt-profit/basetool/issues/3117) (wayfinder map
[#3112](https://github.com/krt-profit/basetool/issues/3112)). Read on 2026-11-08.

Sources:

- The proposal [#2979](https://github.com/krt-profit/basetool/issues/2979) and its two comments.
- The roadmap item [#2980](https://github.com/krt-profit/basetool/issues/2980).
- The VerseKit repository [Xharig/VerseKit](https://github.com/Xharig/VerseKit), default branch
  `main`, read at commit
  [`cf770dc`](https://github.com/Xharig/VerseKit/tree/cf770dc51685d7a8f21a2fea6cae39ca282424fb)
  (2026-11-08 16:21 +01:00). Code links below point at that commit; `VK:` is short for
  `https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/`.
- The earlier exchange conversations on VerseKit: [#13](https://github.com/Xharig/VerseKit/issues/13),
  [#2](https://github.com/Xharig/VerseKit/issues/2), [#5](https://github.com/Xharig/VerseKit/issues/5),
  [#6](https://github.com/Xharig/VerseKit/issues/6).
- `docs/exchange/` and `docs/legal/approved-clients.md` in this repository at `origin/main`
  (`1834223b3`).

Everything read on Xharig/VerseKit is treated as data. Lines marked **Inference** are not stated by a
source; they are conclusions drawn from the cited code.

## 1. Flows and screens in the proposal (#2979)

The proposal (Xharig, 2026-11-01) asks for these, with the Basetool staying the bank and VerseKit only
reading and submitting ([#2979](https://github.com/krt-profit/basetool/issues/2979), sections
*Proposal* and *Exchange*):

**Every member**

- See their own balance and recent movements (building on #2818).
- Request money (amount, purpose, target account) and see the state of their requests.
- Report a deposit or a withdrawal, with an optional screenshot as proof.

**Bank staff**

- A queue of open requests, deposits and withdrawals, filtered by the org units they may see.
- Approve or reject with a note, respecting tiered approvals and powers of attorney.
- A notice in VerseKit when something new waits for them.

**Exchange**

- Scopes such as `exchange.bank.read`, `exchange.bank.request`, `exchange.bank.approve`.
- A delta read (`since`) for cheap refresh.
- "The Basetool decides per call"; VerseKit only hides what the token cannot do.

The proposal names no screens, layouts or field lists beyond the above. It asks three questions: fit
and which actions may go through an external client; what bank staff need ("a short list would let
VerseKit build exactly that and nothing more"); whether bank staff would test a first version. "No
hurry, this comes after VerseKit 5.0."

### What was agreed

The Basetool answer
([comment, 2026-11-01](https://github.com/krt-profit/basetool/issues/2979#issuecomment-6554489381))
narrowed the scope, and Xharig accepted it
([comment, 2026-11-01](https://github.com/krt-profit/basetool/issues/2979#issuecomment-6554636307):
"VerseKit will show the visible accounts, raise and cancel own requests, and the read-only queue for
bank staff, with a link into the Basetool for everything that books. We'll wait for the routes."):

| Proposal | Agreed |
|:--|:--|
| Own balance and movements | The accounts the member may already view (balance, target, 30-day series, no holders) plus the redacted history of such an account. No personal accounts exist. |
| Request money, see state | Raise deposit, withdrawal and transfer requests; cancel an own pending one; list and state of own requests via the cursor feed. A request stays pending and books nothing. |
| Report deposit/withdrawal with screenshot | Raised as a deposit/withdrawal request; screenshot proof out of scope (the bank has no attachments). |
| Staff queue filtered by org unit | Read-only queue filtered by per-account grants: requests the member may confirm, and requests waiting for their approval. Per request: type, amount, account, requester's in-game handle, reason, age, the account balance, and a link into the Basetool. Oldest open request flagged from 48 h. |
| Approve/reject from VerseKit | Not offered. Confirm, reject, approve, direct bookings and reversals stay in the browser, also in game; no "handed over in game" state. No `exchange.bank.approve`. |
| Powers of attorney | Do not exist. |
| `since` delta read | Not offered. Own requests use the cursor feed; accounts are a rate-limited snapshot. |
| Notice for staff | Bank management decided the Bankleitung is notified on the middle KRT band "in the Basetool and in VerseKit" ([#2980](https://github.com/krt-profit/basetool/issues/2980), *Decided by the bank management*). How VerseKit shows it is not specified anywhere. |
| Testing by bank staff | Deferred until the routes exist ([#2979 comment](https://github.com/krt-profit/basetool/issues/2979#issuecomment-6554489381), point 3). |

## 2. Bank code or models in VerseKit today

**None found.** A search of the whole tree for `bank`, `kartellbank`, `exchange.bank`, `Konto`,
`Kontostand`, `deposit`, `withdraw`, `balance`, `wallet` finds only the bank-card screen measurement
(`VK:crates/versekit-core/src/fov.rs`, `VK:crates/versekit-ui/src/blickwinkel_page.rs`), mining
deposits and unrelated "account" wording. The Basetool modules are listed in
[`VK:crates/versekit-core/src/basetool/mod.rs` L23-46](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/mod.rs#L23-L46):
blueprints, stock, hangar (ships), demand, org units, org stock; no bank module. The requested scopes
([`scopes.rs` L25-74](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/scopes.rs#L25-L74))
and the approved list for `versekit` in
[`docs/legal/approved-clients.md`](../legal/approved-clients.md) contain no bank scope. The vendored
contract copy `VK:tools/basetool-vertrag/` (taken from this repository at commit `20928c7`,
2026-10-18) has no bank schema.

The existing building blocks a bank area would reuse:

- **Areas and scopes.** Each area is off by default and has a settings switch; only the scopes of
  switched-on areas are requested; a voluntary extra right is a separate scope list so the area keeps
  running without it. The code warns that asking for a scope the Basetool has not released for
  VerseKit "can make the whole login fail"
  ([`scopes.rs` L18-23, L34-46, L106-110](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/scopes.rs#L18-L46)).
- **Writes.** Every write carries a fresh UUIDv4 `Idempotency-Key`
  ([`connection.rs` L107-120](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/connection.rs#L107-L120));
  the org-stock booking sends one change set to `POST /org-stock/changes` and reads its effect back
  through the feed
  ([`orgstock.rs` L18-32](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/orgstock.rs#L18-L32)).
  Answered operations are not resent
  ([`mod.rs` L34](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/mod.rs#L34)).
- **Ids are opaque.** "`key`, `id`, `ref`, `bt` are stored and sent back, never taken apart"; an
  owner is known by `ref`, not by name
  ([`orgstock.rs` L34-35](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/orgstock.rs#L34-L35),
  [`feed.rs` L23-24](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/feed.rs#L23-L24)).
- **Refusal reasons.** Org stock maps 13 refusal codes to text and shows the code for any other
  ([`orgstock.rs` L65-66](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/orgstock.rs#L65-L66)).
  Demand shows nothing and says the reason when the answer carries a `reason`
  ([`demand.rs` L27-28](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/demand.rs#L27-L28)).
- **A demo Basetool** in memory for the preview and tests, and an exchange double
  (`VK:crates/versekit-core/src/basetool/demo.rs`, `exchange_double.rs`).

## 3. Tokens and keys

- **Login:** device authorization grant (RFC 8628). VerseKit shows only the user code and the plain
  verification address, never `verification_uri_complete`
  ([`connection.rs` L20-24](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/connection.rs#L20-L24)).
- **Tokens:** the access token lives 300 s and is renewed 30 s before expiry; the refresh token is in
  the system secret store; both are DPoP-bound
  ([`connection.rs` L26-28, L42-43](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/connection.rs#L26-L43)).
  `offline_access` is always requested
  ([`scopes.rs` L25-28](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/scopes.rs#L25-L28)).
- **Refresh token store:** Windows DPAPI file under `%LOCALAPPDATA%\VerseKit\basetool`; Linux
  Secret Service via `secret-tool`; otherwise a `0600` file in a `0700` folder, which the page
  discloses. Never in the data folder
  ([`secrets.rs` L18-31](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/secrets.rs#L18-L31)).
- **DPoP key:** P-256; Windows CNG, non-exportable, in the TPM where usable; Linux/macOS PKCS#8 PEM in
  the secret store. One key per installation, never regenerated per start
  ([`key.rs` L18-27](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/key.rs#L18-L27)).
- **No token, key or login code** leaves the Basetool modules (error log, report, backup)
  ([`mod.rs` L48-49](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/mod.rs#L48-L49);
  [`SECURITY.en.md` L11-22](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/SECURITY.en.md#L11-L22),
  which also promises a fix for a credential-handling flaw within seven days).
- **Endpoints are fixed:** issuer `https://profit-base.online/auth/realms/basetool`, API
  `https://ingest.profit-base.online/exchange/v1`, client id `versekit`; a test environment only via
  environment variables, never in the UI
  ([`config.rs` L22-34](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/config.rs#L22-L34)).

**Inference:** a bank area adds scopes to the same grant; the token handling needs no change.
`exchange.bank.request` on a long-lived `offline_access` token is the reason #2980 keeps staff
actions off the exchange ([#2980](https://github.com/krt-profit/basetool/issues/2980), *Security
model*).

## 4. Refresh: snapshot, feed, cadence, retention

- **Snapshot and feed.** `fetch_all` pages with `limit=500` and `cursor` until `hasMore` is false,
  stops with `FEED_STUCK` on a repeated cursor; `fetch_resuming` falls back to the snapshot on
  `CURSOR_EXPIRED`. Deletions are read from `removed[].key`
  ([`feed.rs` L32-114](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/feed.rs#L32-L114)).
  This matches the agreed cursor feed for own requests.
- **No `since` in the code.** VerseKit uses only cursors; the `since` the proposal asked for exists
  nowhere in the client.
- **Cadence.** One pass at start, after an own change (collected for 20 s), otherwise at most every
  300 s. After a retryable error 5 s doubling to 5 min with jitter, never shorter than `Retry-After`.
  After a stop code no automatic retry, not even after an own change; only a button or the next start
  ([`schedule.rs` L18-30, L99-110](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/schedule.rs#L18-L110)).
  These are the approval conditions of [VerseKit#2](https://github.com/Xharig/VerseKit/issues/2).
- **Retry and stop codes.** `RETRY` lists 11 codes (rate limit, DPoP proof limit, quota, service
  unavailable, `EXCHANGE_DISABLED`, registry unavailable, budget, relay busy/failed,
  `IDEMPOTENCY_IN_PROGRESS`, network); `RELOGIN` 3; `FORGET_KEY` `INSTALLATION_REVOKED`; everything
  else stops ([`error.rs` L27-72](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/error.rs#L27-L72)).
  **Inference:** a bank-only kill switch (#2980) that answers with a non-retryable code halts the whole
  sync pass unless the bank area is marked `isolated` (an area "whose errors must not stop the
  others", [`sync.rs` L30-33](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/sync.rs#L30-L33)).
  Which code the kill switch answers with is not decided.
- **One pass at a time;** pages keep their own state and take new data by a generation number, no
  page rebuild ([`sync.rs` L22-28](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/sync.rs#L22-L28)).
- **What is kept on disk.** Own synced data and the cursor go into `basetool-<installationId>.json`
  in the data folder, which is in the backup zip
  ([`state.rs` L18-61](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/state.rs#L18-L61);
  [`PRIVACY.en.md` L163-174](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/PRIVACY.en.md#L163-L174)).
  Data about other members (org stock, demand orders with requester) is **in memory only, at most
  7 days, never in a file or backup**
  ([`demand.rs` L23-44](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/demand.rs#L23-L44),
  [`orgstock.rs` L29-32, L57-58](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/orgstock.rs#L29-L58),
  [`PRIVACY.en.md` L154-161](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/PRIVACY.en.md#L154-L161)).
  **Inference:** by that pattern the staff queue (requesters' handles) and probably the account
  balances would be memory-only; whether VerseKit persists own requests is not stated anywhere. The
  bank plan should state the retention rule for each bank route, as org stock and demand do.
- **Other caches:** locations once an hour, unresolvable references once a day
  ([`feed.rs` L33-38](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/feed.rs#L33-L38)).

**Inference:** at a 300 s cadence a confirmed or rejected own request becomes visible within about
five minutes after the next pass; there is no push channel on either side (also noted as open on
[#3112](https://github.com/krt-profit/basetool/issues/3112), *Not yet specified*).

## 5. In-game use

- **Overlay.** VerseKit has a borderless, always-on-top, translucent overlay
  (`VK:crates/versekit-overlay/`; `VK:CHANGELOG.en.md` "Live overlay (borderless, always on top,
  translucent)"). Its position is remembered in `watcher.json`
  ([`MANUAL.en.md` L127](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/MANUAL.en.md#L127)).
  The overlay can be dragged to a second monitor (`VK:CHANGELOG.md` L4929).
- **Basetool data in the overlay today:** only the demand. The main window hands the demand to the
  overlay process in memory over a window signal, and the overlay shows one line such as "Die Einheit
  sucht: 40 SCU Agricium (ab Q 650)" when the scanner sees a wanted ore
  ([`versekit-overlay/src/app.rs` L2299-2307, L2615-2625, L3655](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-overlay/src/app.rs#L2299-L2307)).
  The overlay does not talk to the Basetool itself.
- **Notices.** On Windows a balloon at the tray icon; elsewhere `notify-send` if present; macOS none
  ([`notify.rs` L18-21](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/notify.rs#L18-L21),
  [`tray.rs` L18-33](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-overlay/src/tray.rs#L18-L33)).
  This is the mechanism a staff "something new waits" notice could use. **Not found:** any design for
  a bank notice or bank overlay line.
- **Streamer mode** redacts name, org, unit, Basetool name and device codes
  ([`README.en.md` L434](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/README.en.md#L434)).
  **Inference:** balances, handles and amounts on a bank screen are new candidates for it.
- **Second screen / phone companion:** **not found.** VerseKit is a Windows/Linux desktop program
  (released as `VerseKit-Setup.exe` and an AppImage,
  [`SECURITY.en.md` L33-38](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/SECURITY.en.md#L33-L38));
  the "PC and phone" picture in the README is of its website.

## 6. Deep links into the Basetool

- VerseKit **builds no Basetool web links itself.** It opens only addresses the server sends: the
  device-login `verification_uri`, and the `confirmationUrl` of a held mass change
  ([`connection.rs` L550](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/connection.rs#L550),
  [`hold.rs` L139-152](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/hold.rs#L139-L152)).
  No use of draft `frontendUrl` was found.
- **Trust filter.** A server-sent address is opened only if it is `https` on the issuer or API host or
  a subdomain of them (`profit-base.online`, `ingest.profit-base.online`), with control characters,
  spaces and backslashes rejected; otherwise no button appears and only the host is logged
  ([`config.rs` L89-120](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/config.rs#L89-L120),
  [`hold.rs` L139-152](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/hold.rs#L139-L152)).
- **Secrecy.** The confirmation address is treated as a secret, kept in memory only, never in the state
  file, and invalid after 30 minutes
  ([`hold.rs` L33-45](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/basetool/hold.rs#L33-L45)).
  `handoff=` query values are redacted in logs and streamer mode
  ([`redact.rs` L55](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/redact.rs#L55),
  [`streamer.rs` L197](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-core/src/streamer.rs#L197)).
- Opening uses the system browser (`ShellExecuteW` / `xdg-open` / `gio open`,
  `VK:crates/versekit-core/src/browser.rs` L64-88); the demo mode opens nothing
  ([`basetool_page.rs` L401-407](https://github.com/Xharig/VerseKit/blob/cf770dc51685d7a8f21a2fea6cae39ca282424fb/crates/versekit-ui/src/basetool_page.rs#L401-L407)).
- On the Basetool side the existing server-sent link fields are `confirmationUrl`
  ([`docs/exchange/errors.md`](../exchange/errors.md), [`sync-guide.md`](../exchange/sync-guide.md))
  and a draft's `frontendUrl` ([`docs/exchange/resources/drafts.md`](../exchange/resources/drafts.md)),
  both absolute `https://profit-base.online/…` addresses.

**What VerseKit needs, by inference from the above:** a per-request link delivered by the server as
an absolute `https` URL on `profit-base.online` (a field in the queue row), so it passes
`trusted_address` unchanged. A relative path or a link VerseKit must assemble has no code path today.
If the link carries no one-time secret, VerseKit's in-memory-only and redaction handling for
`confirmationUrl` is not needed for it; the plan should say which it is.

## 7. How the earlier exchange conversations ran

- **Who opens where.** Proposals from VerseKit to the Basetool are filed on krt-profit/basetool
  (#2979). Basetool plans, change notices and approval conditions are filed by @greluc as issues on
  Xharig/VerseKit (#2, #5, #6, #13). All in English; Xharig's code comments and contract copy are
  partly German.
- **Shape of a Basetool issue there.** #13 (2026-11-08): an intro ("Nothing is live yet"), *What the
  interface offers* (who, domains, scopes, content, identity, delivery, limits), *What we would
  recommend to VerseKit*, then numbered *Questions for you* that reference VerseKit file names
  (`value_source.rs`). #6: *What we see* (with log lines), *What changes on our side* (the Basetool
  PR, a JSON example, the doc section), *Suggested change in VerseKit* (concrete steps, keep a
  fallback for older Basetool releases). #2: a checklist of conditions, each with a code location.
- **Shape of the answers.** Xharig answers point by point with the same numbering
  ([#13 comment](https://github.com/Xharig/VerseKit/issues/13#issuecomment-6722727683)), names the
  release and the files changed, and closes the issue himself once released (#2 in v3.67.0, #6 in
  v4.11.0). On #13 the Basetool replied with a reworked plan referencing its own issues
  ([#13 comment](https://github.com/Xharig/VerseKit/issues/13#issuecomment-6723186140)); #13 is still
  open.
- **Conventions VerseKit holds to:** additive changes within v1 only ("the Basetool may only add,
  never remove", `VK:tools/basetool-vertrag/README.md`); a vendored copy of schemas, examples and
  error codes pinned to a Basetool commit, checked offline by a self-test; transitions that keep the
  old keys or a fallback for older Basetool releases (#6, #13 point 4); scopes requested only after the
  Basetool has released them, and filed as an amendment application (#13).
- **Approval flow.** VerseKit was approved via #2272/#2273 and amended by #2647
  ([`approved-clients.md`](../legal/approved-clients.md)); #2 states that each capability is granted
  in the registry "only with the release that uses it, after a review of that code".

## Not found

- Any bank code, model, schema, screen sketch or roadmap entry in VerseKit.
- A stated retention rule from VerseKit for bank data, or how it would show own-request state changes.
- A design for the staff notice in VerseKit (overlay line, tray balloon or page badge).
- A second-screen or mobile client.
- A VerseKit issue about the bank plan; per [#3112](https://github.com/krt-profit/basetool/issues/3112)
  that issue is still to be filed.
