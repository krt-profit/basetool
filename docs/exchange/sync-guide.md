# Sync guide

How a client keeps its local data and the member's Basetool in step without losing or resurrecting
anything. The member edits the same data in the web, in the app and possibly in a second copy of your
client, so the server's state is never "yours". The rules below are what makes a sync safe; the
resource pages have the details of each route.

## Before the first sync

1. Read the [service document](resources/connect.md#service-document--get-exchangev1) and offer only
   the features whose scopes it lists.
2. [Label the installation](resources/connect.md#label-the-installation--post-exchangev1meinstallation)
   and store `installationId`.
3. Run the [account check](resources/connect.md#account-check--post-exchangev1meaccount-check) with
   the handle from the game log. Sync only on `match`, or after the member confirmed the account.
4. Settle names with [`catalog/resolve`](resources/catalog.md) and keep the `bt` it answers.

## The baseline

Keep, per resource and per installation, the **baseline**: the server's state as of your last
completed sync, and the feed cursor that goes with it. Every decision to remove something is made
against the baseline, never against a guess.

## Pull before push

Every sync cycle starts by reading the feed to its end — until `hasMore` is `false` — and applying it
locally, tombstones included. Only then compare, and push what changed locally since the baseline.
After a push, read the feed again: it shows your own writes as the server stored them, and that state
with its cursor is the new baseline. A change result carries no cursor in `v1`: the schema reserves
a `cursor` property, but the server does not send it, so do not wait for one.

## The first sync is add-only

On the first sync there is no baseline, so the client cannot tell "the member removed this" from
"this was never here". Therefore the first sync only **adds**:

- blueprints the server does not have;
- stock lots the server does not have, with `expectedQuantity` 0; a lot both sides have with
  different quantities is for the member to decide, not for the client;
- ships only after the link step below.

It removes nothing and lowers nothing. After it, the snapshot plus your additions is the baseline.

## Ships: link before create

The member's hangar usually already exists. On the first sync, match your ships to the server's
snapshot and send a `link` for each match before any `upsert` without `shipId`; otherwise every
matched ship is created twice. Links belong to one installation, so a second installation links its
own ids. See [ships](resources/ships.md#links).

## Removing

Remove on the server only what is **in the baseline and gone locally**: a diff against the last
synced state. Something on the server that is not in your baseline was added elsewhere — take it in
locally, never remove it. Something in your baseline that the feed reports removed was removed
elsewhere — drop it locally.

## Tombstones and never re-adding

A tombstone's `removedBy` names who removed the entry: `{channel, clientId?, installationId?}`, with
`channel` one of `web`, `app`, `client` and `system`, and for `client` the client's id and its
installation's `installationId`; a channel you do not know counts as a removal elsewhere
([reading tolerantly](versioning.md#reading-tolerantly)). When `installationId` is your own, it is
your own removal. Any other tombstone means the member removed the entry elsewhere: remove it locally
and **do not add it back**. The server refuses such an add per op with `REMOVED_ELSEWHERE` while the
tombstone lives (90 days). If you believe the entry should come back, ask the member; only after they
agree resend the op with `override: true`.

## Full resync after a cursor expired

`410 CURSOR_EXPIRED` means the tombstones since your cursor are gone — or the cursor is not one the
server issued. Take a new snapshot and reconcile it against the **last baseline**, entry by entry.
This is **not** add-only:

| In the baseline | In the new snapshot | Changed locally since the baseline | Do |
| --- | --- | --- | --- |
| yes | no | — | It was removed elsewhere: remove it locally. Do not re-add it. |
| no | yes | — | It was added elsewhere: add it locally. |
| yes | yes, different | no | It was changed elsewhere: take the server's state. |
| yes | yes | yes | Merge the two; push the result with the snapshot's quantity or version as the expected one. |
| no | no | added locally | Push it as an add. |

The snapshot is then the new baseline. A client without any baseline (a new install, lost local
data) treats the resync as a first sync: add-only.

## Conflicts

A stock op carries `expectedQuantity`, and a ship `upsert` or `remove` carries `version`: the value
from your baseline. When the server holds something else, the op is refused per op as
`VERSION_CONFLICT` and the rest of the batch is applied. Pull, merge the server's value with your
local change, and resend the op in a new batch. The server locks what it checks, so of two
concurrent writes to one lot or ship, one wins and the other gets the conflict.

## Idempotency keys

Every change set and draft carries an `Idempotency-Key` of 8 to 128 characters of `A–Z`, `a–z`,
`0–9`, `.`, `_`, `~` and `-`; a random UUID is a good key.

- **One key per logical write.** Reuse a key only to retry the identical request after a timeout, a
  dropped connection, a `5xx`, a `429` or `409 IDEMPOTENCY_IN_PROGRESS`.
- For 24 hours the server answers the same request under the same key with its first answer and
  `Idempotency-Replayed: true`. That includes `2xx`, `400`, `404`, `409`, `410` and `422` answers, so
  a request you changed — after a conflict, after a merge — needs a new key.
- The same key with a different request — another route or another body — is
  `422 IDEMPOTENCY_KEY_REUSED`.
- Keys are kept per client and member, not per installation: two installations of your client for
  one member share them. Random keys never collide.

`401`, `403`, `413`, `415`, `429`, `5xx`, `409 MASS_CHANGE_CONFIRMATION_REQUIRED` and the
`400 SCHEMA_INVALID` for a body that is not a JSON document are never cached, and
neither are the answers about the key itself — `400 IDEMPOTENCY_KEY_MISSING`,
`409 IDEMPOTENCY_IN_PROGRESS` and `422 IDEMPOTENCY_KEY_REUSED` — so a retry after
`IDEMPOTENCY_IN_PROGRESS` gets the first request's answer once it is stored. An answer the server
could not store — its store failed, or the answer exceeded 32 KiB — is not replayed either: a retry
under the key runs the request again.

## Batches

A change set holds one resource and at most **500 ops**; more is `413 BATCH_TOO_LARGE`. A request
body is at most 2 MiB (`413 PAYLOAD_TOO_LARGE`). A batch is applied in one transaction, and every op
has its own result. Split a large sync into several batches, each under its own key — but keep a stock
move, the fall and its rise, in the same batch.

The gateway relays at most **four change sets of more than 100 ops** at a time, over all clients
and members. A fifth is not relayed but answered `503 RELAY_BUSY` with `Retry-After: 10`; nothing
was written, so retry the same request under the same key after the wait. A batch of at most 100
ops is never refused this way. Send the large batches of one member one after another rather than
in parallel.

## The mass-change guard

A client that suddenly removes much of a member's data is stopped until the member confirms it. The
guard counts, per **client** (all its installations), member and resource, the removals of the last
24 hours that were not undone, plus the batch's own. A batch is held when that total is

- above **25**, or
- at least **5** and more than **a fifth** of the resource's current count plus the removals already
  in the window.

What counts as a removal:

| Resource | Removal |
| --- | --- |
| Blueprints | an applied `remove` |
| Ships | a `remove`, and an `upsert` that changes both the name and the ship type |
| Stock | a lot set to 0, or cut to at most a tenth of what it held before your client's first change to it within the 24 hours — unless the batch's rises of the same material or item cover the fall (a move) |
| Org stock | a `book-out`, `sell`, `move-pool` or `move-holder` into another pool, counted per pool instead of per resource, against the pool's entries ([org stock](resources/org-stock.md#the-mass-change-guard)) |

For example, with 40 blueprints and no removals in the window, a batch may remove 8 (8 × 5 = 40, not
more than 40); a batch removing 9 is held. Spreading removals over many small batches does not help,
since the window counts them all.

A held batch writes nothing and answers `409 MASS_CHANGE_CONFIRMATION_REQUIRED` with a
`confirmationUrl`:

```json
{
  "status": 409,
  "code": "MASS_CHANGE_CONFIRMATION_REQUIRED",
  "confirmationUrl": "https://profit-base.online/connected-apps/confirm?handoff=abc"
}
```

The URL carries a one-time handoff id, like a draft's `frontendUrl`: only the member the batch was
held for can open it, but treat the id and the URL as a secret all the same — never log, store
beyond the 30 minutes or share them, and redact them from diagnostics.

Show the member the URL and **do not resend the batch**. The member reviews it in the browser within
30 minutes and confirms or discards it; a newer held batch of your client replaces your older one for
that member, while other clients' held batches stay. A held batch is
not applied once the member disconnected your client or installation, or your client was
suspended, after it was held back. A confirmed batch is applied as your installation's own write,
and your next pull shows it. `dryRun` does not ask the guard.

## Several installations

A member may run your client on several devices. Each is its own installation with its own
`installationId`, DPoP key, ship links, cursors and baseline. The server treats the others like any
other channel: an entry another installation removed is `REMOVED_ELSEWHERE` for this one. What the
installations share is the member's rate limit, the daily quota, the idempotency keys and the
mass-change window of your client.

## Rate limits, quota and back-off

| Limit | Default | Counted per |
| --- | --- | --- |
| Requests | 120 per minute, or the service document's `limits.requestsPerMinute` | client and member |
| Requests | 1200 per minute | client, over all its members |
| Requests | 120 per minute | source IP address, over all members and clients, anonymous schema reads included |
| Account checks | 10 per hour | client and member |
| Writes (change sets and drafts; an org-stock change set counts each op) | 500 per UTC day, or `limits.writesPerDay` | client and member |
| Live DPoP proofs | 600 at a time, each live until just after 30 seconds past its `iat` (`429 DPOP_PROOF_LIMIT`, [details](authentication.md#live-proofs-per-member)) | member, over all clients |
| Live DPoP proofs | 100 000 at a time (`503 SERVICE_UNAVAILABLE`) | gateway, over all members |

Every attempt counts, retries and replays included — except a `503 EXCHANGE_BUDGET_EXHAUSTED`,
which gives its write back. An org-stock change set that is not a dry run counts once more for every
op after its first once it matches its schema; one that does not fit into the day's rest is refused
`429 QUOTA_EXCEEDED` and keeps only its first count, and a replay of its `Idempotency-Key` counts
once. Admitted answers carry `RateLimit` and `RateLimit-Policy` headers for the member's
per-minute limit; slow down before it runs out. The per-IP limit is checked first, before the
token is read: every member and client behind one address shares it, so the per-client 1200
cannot be reached from a single address, and its `429 RATE_LIMITED` carries neither `RateLimit`
headers nor a `DPoP-Nonce`.

The per-minute limits and the account-check limit are counted by each gateway instance on its own,
as is the DPoP `jti` replay check; the daily write quota is shared. The Basetool runs a single
gateway instance, so the table above is what you get; still treat the `RateLimit` headers, not the
table, as the limit, and send a fresh DPoP proof with every request, retries included.

- `429 RATE_LIMITED`, `429 DPOP_PROOF_LIMIT` and `429 QUOTA_EXCEEDED` carry `Retry-After` in
  seconds — for the quota, until the next UTC day. Wait at least that long.
- Every `503` of the gateway carries `Retry-After` too: 30 seconds for `EXCHANGE_DISABLED` and
  `REGISTRY_UNAVAILABLE`, for `EXCHANGE_BUDGET_EXHAUSTED` the seconds until enough of the full
  budget expires (at most an hour), 10 for `RELAY_BUSY`, and for `SERVICE_UNAVAILABLE` 60 when a
  store cannot be reached, 30 when the daily write quota cannot be counted, 5 when the identity
  provider cannot be reached, and the seconds until the earliest live proof no longer counts when
  all members together hold the gateway's cap of live DPoP proofs. Wait at least that long and
  retry the same request under the same key; read the header rather than these numbers.
- `502 BACKEND_RELAY_FAILED`, a `503` without `Retry-After` and a network error: back off as below
  and retry under the same key.

### Back-off and sync cadence

These numbers are binding; an application is checked against them
([client security](client-security.md#sync-behaviour)).

- **Back-off.** After a refused or failed request, the first wait is **5 seconds**, and each
  further failure of the same request doubles it, up to at most **5 minutes**. Add random jitter to
  every wait, so that installations do not retry in step. Every wait is **at least the
  `Retry-After`** of the answer, when it carries one, even where that is longer than 5 minutes (the
  daily quota). A success ends the back-off; the next failure starts again at 5 seconds.
- **Sync cadence.** Sync on start and after a local change; beyond that, a timed sync runs **at
  most every 5 minutes** while the client is open. Coalescing local changes that arrive close
  together into one sync is recommended.

Never retry in a tight loop, and never sync more often than the member's use needs.

## Undo

The member can undo your client's writes from *Connected applications*. An undo reaches your feed like
any other change made in the web; apply it like one and do not push the undone state back.

After a faulty or malicious release, an admin can undo your client's writes for every member at
once. Your client is suspended first, so every request is refused until the Basetool re-activates
it; after that the undone entries reach your feed like web edits too. Fix the release before you
ask for re-activation, and do not replay the writes that were undone.
