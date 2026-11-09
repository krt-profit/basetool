# Bank

The Kartellbank's accounts, bookings and balance series as the member may see them in the
Basetool, and the member's own booking requests, under scope `exchange.bank.read`. Every route
lies below `/exchange/v1/bank`; while the bank is switched off for connected applications they
all answer `503 BANK_EXCHANGE_DISABLED` with `Retry-After: 300`. Pause the bank area only and keep
syncing every other area ([errors](../errors.md)).

No money moves through a client, and nothing a client does here is a bank-staff action. The Basetool
decides every call anew from the member's bank roles, memberships and grants, so a withdrawn grant
hides an account at once. An administrator sees through a client only what their bank roles,
grants and memberships give them, never the whole bank.

The read-only [bank-staff queue](#staff-queue) lies under its own scope `exchange.bank.queue`. The
scope `exchange.bank.request` (raising, correcting and cancelling the member's own requests) exists;
its routes are not published yet.

## Retention

| Route | Retention class | What a client may do with it |
| --- | --- | --- |
| `GET /exchange/v1/bank/accounts` | foreign bank data | keep it in memory only, for at most 7 days |
| `GET /exchange/v1/bank/accounts/{id}/bookings` | foreign bank data | keep it in memory only, for at most 7 days |
| `GET /exchange/v1/bank/accounts/{id}/balance-series` | foreign bank data | keep it in memory only, for at most 7 days |
| `GET /exchange/v1/bank/queue` | foreign bank data | keep it in memory only, for at most 7 days |
| `GET /exchange/v1/bank/requests` | the member's own data | persist it |
| the answers of the request writes | the member's own data | persist it |

The retention class is a condition of the approval ([becoming a client](../onboarding.md)).

## Accounts

`GET /exchange/v1/bank/accounts` — a snapshot of the active accounts the member views: the KRT
account first, then the rest by account number. Page with `cursor` and `limit` (1 to 1000, default
500) as in the [sync guide](../sync-guide.md); there is no feed, so `removed` is always empty and the
last page has `"nextCursor": null`. A cursor whose account the member no longer views answers
`410 CURSOR_EXPIRED`: start again without one.

Each item is a [`bank-account`](../schemas/) (the `sparkline` shortened here):

```json
{
  "accountId": "5d0e9c1a-2b3f-4a6d-8e71-9c0b1a2d3e4f",
  "accountNo": "KB-0042",
  "name": "Staffel Nord",
  "type": "ORG_UNIT",
  "orgUnit": { "id": "0c2f8a51-7d3e-4b9a-9f10-5a6b7c8d9e01", "name": "Staffel Nord",
               "shorthand": "NORD", "kind": "SQUADRON" },
  "balance": 1250000,
  "balanceTarget": 2000000,
  "delta30d": 250000,
  "sparkline": [1000000, 1000000, 1250000],
  "requestable": true,
  "ownLimit": 1000
}
```

- Money is an integer of whole aUEC.
- `type` is `ORG_UNIT`, `AREA`, `CARTEL` (the KRT account), `CARTEL_BANK` or `SPECIAL`; treat a value
  you do not know as an account of an unknown type ([versioning](../versioning.md)).
- `orgUnit` is the [`org-unit`](../schemas/) that owns the account, or `null` for one without.
- `sparkline` holds the 30 end-of-day balances of the last 30 days, oldest first, ending with the
  current balance; `delta30d` is the net change over them.
- `requestable` tells whether the member may raise a withdrawal or transfer from the account;
  `ownLimit` is the member's own limit above which such a request needs an approval, or `null` when
  none applies.

No holders, custody, approval-limit tables or ceilings leave the Basetool.

## Bookings

`GET /exchange/v1/bank/accounts/{id}/bookings` — the bookings of one account the member views,
newest first, paged with `cursor` and `limit`. `from` and `to` (both optional, inclusive,
`date-time`) bound the booking time; they are a query bound, not a feed.

Each item is a [`bank-booking`](../schemas/), redacted as on the member's account page in the
Basetool: no holder and no staff note.

```json
{
  "postingId": "9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
  "transactionId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "type": "DEPOSIT",
  "amount": 250000,
  "createdAt": "2026-11-08T19:30:00Z",
  "note": "Friday's haul",
  "justification": null,
  "reversedTransactionId": null,
  "counterAccount": null,
  "intraAccount": false,
  "transferFee": null,
  "counterpartyHandle": "Cpt. Holder",
  "counterpartyOrgUnitName": "Staffel Nord"
}
```

- `amount` is this account's signed leg: negative for money leaving the account.
- `counterAccount { no, name }` names the other account of a transfer; `transferFee` is the fee the
  debited account bore, or `null`.
- `counterpartyHandle` is the member who handed the money in or received it. It is `null` when none
  was recorded or when that member withholds their name from connected applications; the booking
  stays.

## Balance series

`GET /exchange/v1/bank/accounts/{id}/balance-series?from=…&to=…` — one end-of-day balance per UTC day
of the period, oldest first, with the account's `balanceTarget`. Both bounds are required, `from` may
not lie after `to`, and the period spans at most 365 days; otherwise `400 SCHEMA_INVALID`.

```json
{
  "points": [
    { "date": "2026-11-07", "balance": 899500 },
    { "date": "2026-11-08", "balance": 1149500 }
  ],
  "balanceTarget": 2000000
}
```

## Own requests

`GET /exchange/v1/bank/requests` — every booking request the member raised, in the Basetool or
through any client, as a snapshot followed by a change feed, exactly as in the
[sync guide](../sync-guide.md): page the snapshot with `cursor` and `limit` (1 to 1000, default
500); its last page hands over a feed cursor. Each feed page answers every request changed since
the cursor once, with its current state, and a request that no longer exists as a tombstone in
`removed`. A cursor older than the retained changes answers `410 CURSOR_EXPIRED`: start a new
snapshot. Raising, correcting or cancelling a request in the Basetool, a bank decision, a renamed
account or a new org-unit shorthand all reach the feed.

Every page, snapshot and feed alike, also carries `openRequestCap`: the most pending requests the
member may hold (20 today). Count the `PENDING` items you hold; once they reach the cap, a raise
answers `409 BANK_OPEN_REQUEST_CAP`, so hide or disable your `new request` action until one is
decided or cancelled.

Each item is a [`bank-request`](../schemas/):

```json
{
  "id": "7f3e2d1c-0b9a-4876-9543-210fedcba987",
  "version": 1,
  "status": "PENDING",
  "createdAt": "2026-11-08T20:15:00Z",
  "decidedAt": null,
  "type": "WITHDRAWAL",
  "amount": 15000,
  "note": "Refuel at Port Olisar",
  "justification": "Fuel for the Friday op",
  "account": { "id": "5d0e9c1a-2b3f-4a6d-8e71-9c0b1a2d3e4f", "no": "KB-0042",
               "name": "Staffel Nord", "orgUnitShorthand": "NORD" },
  "targetAccount": null,
  "requiresOwnerApproval": true,
  "requiredApprover": "RESPONSIBLE_HOLDER",
  "ownerApprovalGranted": false,
  "rejectReason": null,
  "client": null,
  "splitEnabled": false,
  "splitPercent": null
}
```

- `status` is `PENDING`, `CONFIRMED`, `REJECTED` or `CANCELLED`; `type` is `DEPOSIT`, `WITHDRAWAL`
  or `TRANSFER`. Treat a value you do not know as unknown ([versioning](../versioning.md)).
- `account` is the deposit's target or the withdrawal's or transfer's source; `targetAccount` is the
  destination of a transfer, or `null`.
- `requiresOwnerApproval` tells whether the amount exceeded the member's limit; `requiredApprover`
  (`RESPONSIBLE_HOLDER`, `BANK_MANAGEMENT` or `ORGANISATIONSLEITUNG`) is who must sign off before
  the bank confirms, and `ownerApprovalGranted` whether they did.
- `rejectReason` is the bank's reason for a rejection; `decidedAt` the time of the decision or the
  cancellation.
- `splitEnabled` and `splitPercent` describe a deposit that distributes a whole percent of the
  amount across the squadron accounts.
- `client` names the connected application that raised the request, by its registry id; it is
  `null` for a request raised in the Basetool.
- `version` is the value a correction or a cancel echoes.

No staff note, no bank-staff identity, no counterparty and no purpose leave the Basetool. These are
the member's own data: a client may keep them.

## Raising, correcting and cancelling a request

Three writes under the scope `exchange.bank.request`. Each needs an `Idempotency-Key` and counts
one unit of the daily write quota ([limits](../sync-guide.md#rate-limits-quota-and-back-off)). The
Basetool applies the same validation and approval routing as in its own pages; nothing is booked
until the bank confirms the request there.

`POST /exchange/v1/bank/requests` raises a request:

```json
{
  "type": "WITHDRAWAL",
  "sourceAccountId": "11111111-2222-4333-8444-555555555555",
  "amount": 15000,
  "note": "Refuel at Port Olisar",
  "justification": "Fuel for the Friday op"
}
```

- `type` is `DEPOSIT`, `WITHDRAWAL` or `TRANSFER`; `amount` is whole aUEC, 1 to 10¹².
- A deposit may go to any active account, also one the member does not view; it carries no
  justification and may be split with `splitEnabled` and `splitPercent` (1 to 100).
- A withdrawal or transfer needs an account the member views and a `justification` where the
  account asks for one. Only a transfer names a `targetAccountId`, any active account but the
  source.
- `note` and `justification` are plain text of at most 500 characters. Bank staff see them as text,
  links are not clickable, and the request carries a badge with your application's name.

The answer is `201` with only the request's id, status, version and amount:

```json
{ "id": "33333333-4444-4555-8666-777777777777", "status": "PENDING", "version": 0, "amount": 15000 }
```

Read the rest — the approval need, the accounts — from the [own requests](#own-requests), where the
new request follows in the feed.

`POST /exchange/v1/bank/requests/{id}/correction` corrects a pending, not yet approved own
request, raised anywhere: `amount`, `note`, `justification`, `targetAccountId` and the `version`
last read. Every
field replaces the stored one; the type and the source account stay. The approval need is derived
anew as for a raise; a deposit never needs one. A request another Basetool module raised for a
purpose, such as a purchasing tranche, keeps its amount and destination: a correction that changes
them answers `400 SCHEMA_INVALID`, so correct only its note and justification. The answer is `200`
like the one above.

`POST /exchange/v1/bank/requests/{id}/cancel` with `{ "version": 2 }` cancels a pending own
request, raised anywhere, and answers `200 { id, status, version }`.

There is no undo: a request books nothing, so cancel it instead.

## Staff queue

`GET /exchange/v1/bank/queue` under scope `exchange.bank.queue` — the pending booking requests the
member works on as bank staff or approver, read-only and decided anew on every call. Two sections,
both oldest first:

- `open` — the requests of the member's staff queue in the Basetool: every pending request on an
  account the member holds a grant on, every pending request of the bank for the Bankleitung.
  `mayConfirm` tells whether the member's bank rights cover the request type there and the member
  did not raise it; the member's own requests are listed with `mayConfirm: false`.
- `toApprove` — the requests waiting for the member's approval, exactly those the member got an
  "approval needed" notice for in the Basetool.

Page with `cursor` and `limit` (1 to 500 per section, default 500): one cursor continues both
sections, and `hasMore` stays true while either continues. `oldestOpenAt` is when the oldest request
of either section was raised, `oldestOverdue` is true once it is 48 hours old.

```json
{
  "oldestOpenAt": "2026-11-06T09:30:00Z",
  "oldestOverdue": true,
  "open": [
    {
      "id": "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
      "type": "WITHDRAWAL",
      "amount": 2500,
      "createdAt": "2026-11-06T09:30:00Z",
      "ageHours": 52,
      "account": { "id": "5d0e9c1a-2b3f-4a6d-8e71-9c0b1a2d3e4f", "no": "KB-0007",
                   "name": "Staffel Nord", "orgUnitShorthand": "NORD" },
      "targetAccount": null,
      "requesterHandle": "nova",
      "justification": "Fuel for the convoy",
      "note": null,
      "requiresOwnerApproval": false,
      "requiredApprover": null,
      "ownerApprovalGranted": false,
      "mayConfirm": true,
      "accountBalance": 1250000,
      "client": null,
      "link": "https://profit-base.online/bank/requests/7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    }
  ],
  "toApprove": [],
  "nextCursor": null,
  "hasMore": false
}
```

- `requesterHandle` is `null` when the requester withholds their name from connected applications.
- `accountBalance` is the account's balance for the Bankleitung, and for an employee on an account
  they hold a grant on; otherwise `null`.
- `requiredApprover` is `RESPONSIBLE_HOLDER`, `BANK_MANAGEMENT` or `ORGANISATIONSLEITUNG` for a
  request over the requester's limit, or `null`.
- `client` names the connected application that raised the request, or `null`.
- `link` opens the request in the Basetool. Confirming, rejecting and approving happen there only;
  build no links of your own.

No staff note, holder, custody balance or handle other than the requester's leaves the Basetool.

## Refusals

| Answer | When |
| --- | --- |
| `404 NOT_FOUND` | The account does not exist, is closed, or the member may not view it; for a correction or a cancel, the request is not the member's or does not exist. All answer alike. |
| `409 BANK_OPEN_REQUEST_CAP` | The member already holds 20 pending requests, from any channel. Wait for the bank's decisions or cancel one. |
| `409 BANK_JUSTIFICATION_REQUIRED` | A withdrawal or transfer from this account needs a justification. |
| `409 BANK_SELF_TRANSFER` | Source and destination of a transfer are the same account. |
| `409 BANK_REQUEST_NOT_PENDING` | The request was decided or cancelled already. |
| `409 BANK_REQUEST_ALREADY_APPROVED` | The request was approved; it can no longer be corrected, only cancelled. |
| `409 VERSION_CONFLICT` | The request changed since it was read; read it again and retry. |
| `400 SCHEMA_INVALID` | The account id is not an id, a bound is malformed, missing or too far apart, a queue `limit` lies outside 1 to 500, or a write's body breaks the schema or the Basetool's rules (a destination on a non-transfer, a split on a non-deposit). |
| `410 CURSOR_EXPIRED` | The cursor is not one the server issued, its account is gone for the member, or a feed cursor is older than the retained changes. |
| `403 NOT_BANK_STAFF` | The queue was asked for by a member with no bank role, no grant and no request to approve. |
| `403 SCOPE_MISSING` | The token or the client's approval lacks `exchange.bank.read`, `exchange.bank.queue` for the queue, or `exchange.bank.request` for the writes. |
| `503 BANK_EXCHANGE_DISABLED` | The bank is switched off for connected applications. |

The rest of the [error registry](../errors.md) applies as on every route.
