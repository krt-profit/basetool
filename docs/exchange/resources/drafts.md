# Drafts

A draft stages data for the member to review **in the browser**. Nothing is written to the Basetool
until the member confirms it there — unlike a change set, which writes at once. Both draft routes
are writes: they need an `Idempotency-Key` and count against the daily write quota.

## Blueprint draft — `POST /exchange/v1/me/drafts/blueprints`

Scope `exchange.drafts.blueprints`. The body is a `basetool.blueprints`
[envelope](../formats.md) of at most 2000 items, each `{ref, acquiredAt?, provenance?}`:

```json
{
  "format": "basetool.blueprints",
  "formatVersion": "1.0",
  "generator": {"name": "Example Client", "version": "1.2.0"},
  "generatedAt": "2026-09-27T12:00:00Z",
  "items": [
    {"ref": {"name": "Oracle Helmet"}, "acquiredAt": "2026-09-26T12:00:00Z"}
  ]
}
```

The server resolves each `ref` as [`catalog/resolve`](catalog.md) does. A resolved one enters the
review under the product's name; any other enters under the name you sent, or its first key, among
the unmatched rows for the member to pick by hand. A blueprint sent twice appears once, with the
earlier `acquiredAt`. The review is the web's blueprint import — the same one the member reaches by
uploading an offline file in this format. An item's `provenance` is accepted but not read: every
blueprint taken over from the review is recorded with the source `import`.

## Refinery draft — `POST /exchange/v1/me/drafts/refinery-orders`

Scope `exchange.drafts.refinery`. The body is a [`refinery-draft`](../schemas/), the SC Extractor's
refinery extract: `schemaVersion` 1 and one to five `orders`. Only the first order becomes the draft,
and it must be a `SETUP` panel; further orders are reported in the review. The review is the web's
refinery-order form, filled in.

The draft stays for an order the member reviews in the browser first. To write the member's orders
directly — create, update, pick up and store them — send a change set to
[`POST /exchange/v1/me/refinery-orders/changes`](refinery-orders.md#changing-orders--post-exchangev1merefinery-orderschanges)
instead.

## The draft result

Both routes answer a [`draft-result`](../schemas/):

```json
{
  "frontendUrl": "https://profit-base.online/refinery-orders/create?handoff=abc",
  "handoffId": "abc",
  "kind": "REFINERY"
}
```

Open `frontendUrl` in the member's browser; the member signs in there if needed and reviews the
draft. The handoff works **once** and expires after 30 minutes. Treat `handoffId`, and the URL that
carries it, as a secret: never log or share it. Your client holds at most ten live drafts per
member, in slots of their own apart from the SC Extractor's uploads and other clients' drafts;
staging an eleventh drops your client's oldest.

A retry under the same `Idempotency-Key` replays the first result, whose handoff may already be
used. To stage the draft again, send it under a new key.

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` with `errors[]` | The body breaks its schema, or a blueprint draft's `formatVersion` has a major other than `1` (`pointer` `/formatVersion`, `message` `unsupported major version`). |
| `400 SCHEMA_INVALID` without `errors[]` | The body matches the schema, but the Basetool refuses its content: a `schemaVersion` other than 1, no order, or a first order that is not a `SETUP` panel. |
| `413 PAYLOAD_TOO_LARGE` | The body exceeds 2 MiB, or the prepared draft is too large to hand off (256 KiB by default). Send fewer entries. |
| `503 EXCHANGE_BUDGET_EXHAUSTED`, `503 SERVICE_UNAVAILABLE` | The draft cannot be staged now. Retry after `Retry-After`. |

The full list is the [error registry](../errors.md).
