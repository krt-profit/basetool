# Ships

The ships the member owns in the Hangar. Reading needs `exchange.hangar.read`, changing needs
`exchange.hangar.write`. Purchase data is never exchanged in either direction.

## A ship

| Field | Meaning |
| --- | --- |
| `shipId` | Opaque identifier of the server's ship. |
| `externalId` | Your id for this ship, when **this installation** linked it. |
| `version` | The ship's version; every `upsert` and `remove` of it must send the current one. |
| `shipType` | `{bt, name}`; `bt` is the ship type's Basetool id. |
| `name` | The member's name for the ship; absent when it has none. Up to 255 characters. |
| `insurance` | `{kind: LTI}` or `{kind: MONTHS, months}` with 0 to 120 months. |
| `location` | `{name, uex?}` of the warehouse location, when the ship has one. |
| `fitted` | Whether the ship is fitted. |
| `orgUnit` | `{id, name, shorthand, kind}` of the [org unit](org-units.md) the ship belongs to; absent when it belongs to none. |
| `orgUnitVersion` | The counter of the ship's org unit. It moves only when the unit changes, and `version` does not move with it. |

## Reading the ships — `GET /exchange/v1/me/ships`

A snapshot without `cursor`, the changes since it with one: the same `cursor`, `limit`, paging and
tombstones as [blueprints](blueprints.md#reading-the-set--get-exchangev1meblueprints). Only the
member's own ships are in it; a ship given to another member arrives as a tombstone.

A ship is in the feed again when its unit changes, and when its unit is renamed or gets a new
shorthand or kind; so the `orgUnit` you hold is never stale. Read `orgUnit` as the ship's unit
as it is now: it need not be one of the units `GET /exchange/v1/me/org-units` lists, for example
after the member left that unit.

## Links

A link ties one of your ids to one server ship. Links belong to the **installation**: each
installation links its own ids, so two devices with separate local databases never mistake each
other's ids. Per installation, a server ship has at most one `externalId`, and an `externalId`
points at at most one ship. The feed shows each ship's `externalId` for the calling installation
only.

**Link before you create.** The member's hangar is usually not empty — it may come from a Fleetview
import or the web. On the first sync, pull the snapshot, match your ships to the server's, and send
a `link` for every match. Only then `upsert` the ships without a match. A client that creates first
duplicates the hangar.

## Changing ships — `POST /exchange/v1/me/ships/changes`

Send an `Idempotency-Key` and a change set of 1 to 500 ops, `dryRun` optional:

```json
{
  "ops": [
    {"op": "link", "externalId": "vk-7", "shipId": "s-1"},
    {
      "op": "upsert",
      "externalId": "vk-8",
      "shipType": {"name": "Ursa Rover"},
      "name": "Pathfinder",
      "insurance": {"kind": "LTI"}
    },
    {"op": "remove", "shipId": "s-2", "version": 4}
  ]
}
```

| Op | Fields | Does |
| --- | --- | --- |
| `link` | `externalId`, `shipId` | Links your id to one of the member's ships. Re-linking an id moves it off its old ship. |
| `upsert` without `shipId` | `externalId`, `shipType`, `insurance`, optional `name`, `location`, `fitted` | Creates a ship and links it. |
| `upsert` with `shipId` | the same, plus `version` | Updates the ship and links it, if it is not linked yet. |
| `remove` | `shipId`, `version` | Deletes the ship. |

An `upsert` writes `shipType`, `name`, `insurance` and `location` as sent: leaving out `name` or
`location` clears it, so send the ship's current values — the server's, or the member's change to
them — with every `upsert`. Only an absent `fitted` keeps the ship's value, and a new ship without
it is not fitted. `shipType` resolves as a `SHIP_TYPE` reference, `location` as in
[the catalogue](catalog.md#warehouse-locations--get-exchangev1cataloglocations).

The server decides the ops in order, as if the earlier ones had already run, in one transaction; a
batch may update or remove a ship only once. How an op that is not applied is reported:

| Case | `result` | `reason` |
| --- | --- | --- |
| `link` to a ship already linked to that id | `unchanged` | — |
| `upsert` that changes nothing on an already linked ship | `unchanged` | — |
| `remove` of a ship that is already gone | `unchanged` | — |
| A `shipId` the member does not own or the server never had, or a ship type that matches nothing | `unmatched` | `UNMATCHED` |
| A ship type that matches several | `ambiguous` | `AMBIGUOUS` |
| A place without a warehouse location | `rejected` | `LOCATION_UNKNOWN` |
| `link` or `upsert` of a ship this installation links to another id | `rejected` | `LINK_TARGET_TAKEN` |
| `upsert` or `remove` with a `version` the ship no longer has, or of a ship an earlier op of the batch changed | `rejected` | `VERSION_CONFLICT` |
| `upsert` without `shipId` for an id this installation already linked | `rejected` | `VERSION_CONFLICT` |
| `upsert` of a ship the web, the app, the system, another client or another installation removed | `rejected` | `REMOVED_ELSEWHERE` |

On `VERSION_CONFLICT`, pull the feed, merge, and send the op again under a new `Idempotency-Key`; an
id already linked means the ship exists — update it by its `shipId`. On `LINK_TARGET_TAKEN`, pull and
re-link. An `upsert` naming a ship the server no longer has brings it back only when this
installation removed it, or with `override: true` after the member agreed. It comes back as a **new
ship with a new `shipId`**, linked to your id.

**Missions.** Removing a ship detaches it from the mission units it was assigned to, and the member
sees that audited. The result counts the detached units in `detachedFromMissions`:

```json
{"dryRun": false, "applied": 3, "unchanged": 0, "notApplied": 0, "results": [], "detachedFromMissions": 1}
```

**Mass changes.** A `remove`, and an `upsert` that changes both the name and the ship type, count as
removals for the [mass-change guard](../sync-guide.md#the-mass-change-guard). `dryRun: true`
decides every op and writes nothing, without asking the guard.

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The change set, `limit` or a parameter breaks the contract, for example an `upsert` with `shipId` but no `version`. |
| `400 IDEMPOTENCY_KEY_MISSING` | A change set without a well-formed `Idempotency-Key`. |
| `409 MASS_CHANGE_CONFIRMATION_REQUIRED` | The batch removes too much; the member confirms it. |
| `410 CURSOR_EXPIRED` | The cursor is older than the tombstones, or not one the server issued. |
| `413 BATCH_TOO_LARGE` | More than 500 ops, or too large to hold for the member's confirmation. |

The full list is the [error registry](../errors.md).
