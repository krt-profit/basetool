# Bank

The Kartellbank's accounts, bookings and balance series as the member may see them in the
Basetool, under scope `exchange.bank.read`. Every route lies below `/exchange/v1/bank`; while the
bank is switched off for connected applications they all answer `503 BANK_EXCHANGE_DISABLED` with
`Retry-After: 300`. Pause the bank area only and keep syncing every other area ([errors](../errors.md)).

No money moves through a client, and nothing a client does here is a bank-staff action. The Basetool
decides every call anew from the member's bank roles, memberships and grants, so a withdrawn grant
hides an account at once. An administrator sees through a client only what their bank roles,
grants and memberships give them, never the whole bank.

The scopes `exchange.bank.request` (raising, correcting and cancelling the member's own requests)
and `exchange.bank.queue` (the read-only bank-staff queue) exist; their routes are not published yet.

## Retention

| Route | Retention class | What a client may do with it |
| --- | --- | --- |
| `GET /exchange/v1/bank/accounts` | foreign bank data | keep it in memory only, for at most 7 days |
| `GET /exchange/v1/bank/accounts/{id}/bookings` | foreign bank data | keep it in memory only, for at most 7 days |
| `GET /exchange/v1/bank/accounts/{id}/balance-series` | foreign bank data | keep it in memory only, for at most 7 days |

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

## Refusals

| Answer | When |
| --- | --- |
| `404 NOT_FOUND` | The account does not exist, is closed, or the member may not view it. All three answer alike. |
| `400 SCHEMA_INVALID` | The account id is not an id, or a bound is malformed, missing or too far apart. |
| `410 CURSOR_EXPIRED` | The cursor is not one the server issued, or its account is gone for the member. |
| `403 SCOPE_MISSING` | The token or the client's approval lacks `exchange.bank.read`. |
| `503 BANK_EXCHANGE_DISABLED` | The bank is switched off for connected applications. |

The rest of the [error registry](../errors.md) applies as on every route.
