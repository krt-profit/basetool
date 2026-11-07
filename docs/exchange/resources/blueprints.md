# Blueprints

The member's blueprint set: which products they can craft. Reading needs
`exchange.blueprints.read`, changing needs `exchange.blueprints.write`.

## A blueprint

A [`blueprint`](../schemas/) is one product in the set:

| Field | Meaning |
| --- | --- |
| `key` | Opaque identifier of the product. It equals `ref.bt`: the Basetool's product key, or `h:` and a SHA-256 when that key is longer than 128 characters. |
| `ref` | `{guid, bt, name}`; `guid` is the GUID of the item the product produces, its key, and is absent while the Basetool knows the product only by its name; `bt` is deprecated ([formats](../formats.md)); `name` is the product's display name, cut to 200 characters. |
| `acquiredAt` | When the member got it, when known. |
| `provenance` | `{source}` when the Basetool recorded where the blueprint came from. |
| `isDefault` | Granted to every member by default. It cannot be removed. |
| `note` | The member's note. Read-only in `v1`. |

## Reading the set — `GET /exchange/v1/me/blueprints`

| Parameter | Meaning |
| --- | --- |
| `cursor` | Opaque position from an earlier page. Without it the answer is a snapshot. |
| `limit` | Page size, 1 to 1000, default 500. Anything else is `400 SCHEMA_INVALID` at `/limit`. |

The answer is a page: `items`, `removed`, `nextCursor` and `hasMore`.

**Snapshot.** Without `cursor`, the pages walk through the whole set; `removed` is always empty.
While `hasMore` is `true`, ask again with `nextCursor`. The last page has `hasMore: false` and a
`nextCursor` that is now a feed position: store it.

**Feed.** With a feed cursor, a page answers every product that changed after it, each once: its
current state in `items`, or a tombstone in `removed` when it is gone. `nextCursor` is always set;
`hasMore: true` means more changes are waiting. A change made while a snapshot was read follows in
the feed, so a product can arrive in both — apply entries by `key`.

```json
{
  "items": [
    {"key": "k1", "ref": {"bt": "k1", "name": "CF-337 Panther Repeater"}, "isDefault": false}
  ],
  "removed": [
    {
      "key": "k2",
      "removedAt": "2026-09-26T12:00:00Z",
      "removedBy": {"channel": "client", "clientId": "versekit", "installationId": "inst-7f3c2a9e"}
    }
  ],
  "nextCursor": "c-18",
  "hasMore": false
}
```

A [`tombstone`](../schemas/) names who removed the entry: `channel` is `web`, `app`, `client` (with
`clientId`, and `installationId` of the removing installation) or `system` — default-grant changes,
administrative clean-ups and account deletion. A tombstone whose `installationId` is your own is
your own removal.

Tombstones are kept 90 days. A cursor older than that, or one the server did not issue, answers
`410 CURSOR_EXPIRED`: start over as the
[sync guide](../sync-guide.md#full-resync-after-a-cursor-expired) describes. A feed page that
reaches the end moves the cursor up to the present, so a client that reads its feed at least once
every 90 days never loses its cursor.

## Changing the set — `POST /exchange/v1/me/blueprints/changes`

Send an `Idempotency-Key` and a
[`change-set`](../schemas/) of 1 to 500 ops, `dryRun` optional:

```json
{
  "ops": [
    {
      "op": "add",
      "ref": {"bt": "bp-cf-337-panther-repeater", "name": "CF-337 Panther Repeater"},
      "provenance": {"source": "log", "observedAt": "2026-09-26T12:00:00Z"}
    },
    {"op": "remove", "key": "k9", "opId": "r1"}
  ]
}
```

| Op | Fields |
| --- | --- |
| `add` | `ref`, optional `acquiredAt`, `provenance`, `override` |
| `remove` | `key` from the feed (preferred) or `ref` |

Every op may carry an `opId` of your own, echoed in its result. The server resolves the references
as [`catalog/resolve`](catalog.md) does, decides the ops in order as if the earlier ones had already
run, and writes the batch in one transaction through the same paths the web uses: the member sees the
change live, audited under your client's name. `add` records `provenance.source`, and a client's
`default` is stored as `other`.

How an op that is not applied is reported:

| Case | `result` | `reason` |
| --- | --- | --- |
| `add` of a product the member already has | `unchanged` | — |
| `remove` of a product the member does not have | `unchanged` | — |
| The reference matches nothing, or an `h:` key no product hashes to | `unmatched` | `UNMATCHED` |
| The reference matches several products | `ambiguous` | `AMBIGUOUS` |
| `remove` of a default-granted blueprint | `rejected` | `DEFAULT_NOT_REMOVABLE` |
| `add` of a product whose last change was its removal by the web, the app, the system, another client or another installation | `rejected` | `REMOVED_ELSEWHERE` |

`REMOVED_ELSEWHERE` holds while that removal is within the 90 days the feed keeps it. An
installation may re-add what it removed itself. To re-add something removed elsewhere, ask the
member first, then send the `add` again with `override: true`; the override is recorded.

The answer is a [`change-result`](../schemas/):

```json
{
  "dryRun": false,
  "applied": 1,
  "unchanged": 0,
  "notApplied": 1,
  "results": [{"index": 1, "opId": "r1", "result": "rejected", "reason": "DEFAULT_NOT_REMOVABLE"}]
}
```

`applied` counts the ops that changed something, `unchanged` those that found what they asked for,
`notApplied` the rest. `results[]` lists every op that was not applied, `unchanged` ones included,
by its `index` in the batch. `warnings[]` reports fields the schema does not declare as
`UNKNOWN_FIELD`.

**`dryRun: true`** decides every op and answers the same counts, but writes nothing and does not ask
the mass-change guard — a dry run cannot tell you whether the real batch will need the member's
confirmation.

**Mass changes.** Removals count against the mass-change guard. A batch that trips it writes nothing
and answers `409 MASS_CHANGE_CONFIRMATION_REQUIRED` with a `confirmationUrl`; see the
[sync guide](../sync-guide.md#the-mass-change-guard).

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The change set, `limit` or a parameter breaks the contract. |
| `400 IDEMPOTENCY_KEY_MISSING` | A change set without a well-formed `Idempotency-Key`. |
| `409 MASS_CHANGE_CONFIRMATION_REQUIRED` | The batch removes too much; the member confirms it. |
| `410 CURSOR_EXPIRED` | The cursor is older than the tombstones, or not one the server issued. |
| `413 BATCH_TOO_LARGE` | More than 500 ops, or too large to hold for the member's confirmation. |

The full list is the [error registry](../errors.md).
