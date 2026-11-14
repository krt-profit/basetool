# Patch change

When a Star Citizen patch wipes what players held or delivers it to a new home location, a Basetool
admin records a **patch change** once, for everyone. Its **live time**, when the patch went live in
the game, decides what it touches: only stock, ships and refinery orders created or started before
it. The changes it makes reach the feeds from the channel `patch`
([patch changes](../sync-guide.md#patch-changes)).

This resource lets a client follow the patch change and make the member's home-location choice, so
the client takes the server's result instead of relocating on its own:

| Scope | Allows |
| --- | --- |
| `exchange.patch.read` | `GET /exchange/v1/me/patch-change`: the latest patch change with its options, and the member's home-location prompt |
| `exchange.patch.write` | `POST /exchange/v1/me/patch-change/home-location`: the member's choice, which relocates the member; granted only together with `exchange.patch.read` |

**The server is the only relocator.** Do not move synced lots or ships to a home location yourself:
either make the choice here, or let the member choose in the Basetool and follow the result in the
feeds.

## Reading — `GET /exchange/v1/me/patch-change`

A [`patch-change`](../schemas/) document. There is no paging and no cursor; read it on start, after
a sync that brought entries from the channel `patch`, and before you offer the choice.

```json
{
  "patchChange": {
    "id": "3f0c2b1a-9e8d-4c7b-a6f5-e4d3c2b1a098",
    "label": "4.4",
    "kind": "REBUILD",
    "liveAt": "2026-11-12T18:00:00Z",
    "options": {
      "wipeGroups": [
        {
          "group": "CONSUMABLES",
          "selected": true,
          "types": [
            { "source": "ITEM_SUB_TYPE", "value": "WeaponAttachment:Magazine" },
            { "source": "ITEM_TYPE", "value": "Food" }
          ]
        },
        { "group": "OTHER_ITEMS", "selected": false, "types": [] }
      ],
      "wipeExceptions": [
        {
          "entry": { "bt": "7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f", "name": "MedPen (Hemozal)" },
          "target": "GAME_ITEM",
          "mode": "KEEP"
        }
      ],
      "cancelRefineryOrders": true,
      "currencyWipe": false,
      "homeLocationPrompt": true
    }
  },
  "homeLocation": {
    "state": "PENDING",
    "patchChange": { "id": "3f0c2b1a-9e8d-4c7b-a6f5-e4d3c2b1a098", "label": "4.4" },
    "liveAt": "2026-11-12T18:00:00Z",
    "locations": [
      { "id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "name": "Lorville", "system": "Stanton",
        "uex": { "kind": "CITY", "id": 4 } }
    ],
    "preview": [
      {
        "location": { "name": "Port Tressler", "uex": { "kind": "SPACE_STATION", "id": 9 } },
        "rows": 12,
        "pools": [ { "orgUnit": { "id": "0c2f8a51-7d3e-4b9a-9f10-5a6b7c8d9e01",
                                  "name": "Staffel Nord" }, "rows": 4 } ],
        "ships": 2
      }
    ]
  }
}
```

- `patchChange` is the **latest** patch change; it is absent before the first. Its `id` is the same
  in every feed entry it caused, so you can take its changes over in one step. `label` is the patch
  version, which an admin may correct later; `kind` is `REBUILD` or `FULL_WIPE` and only preset the
  options.
- `cancelRefineryOrders` tells whether running refinery orders started before the live time were
  cancelled ([orders a patch ended](refinery-orders.md#orders-a-patch-ended)), `currencyWipe`
  whether the Kartellbank was reset as of the live time ([bank](bank.md#own-requests)), and
  `homeLocationPrompt` whether the members were asked for a new home location.
- Treat a value you do not know as unknown ([versioning](../versioning.md)).

### The wipe list

`wipeGroups` lists **every** wipe group in a fixed order, with `selected` telling whether the patch
change wiped it, so you can apply the same list to your own items and members of both tools lose
the same things. A group claims catalogue types by key:

| `source` | Compared with |
| --- | --- |
| `MATERIAL_TYPE` | a material's type: `RAW`, `REFINED`, `NO_REFINE`; every quality |
| `ITEM_SUB_TYPE` | an item's P4K type and sub-type, joined by a colon, e.g. `WeaponAttachment:Magazine` |
| `ITEM_TYPE` | an item's P4K type, e.g. `Food` |
| `ITEM_TYPE_PREFIX` | the start of an item's P4K type, e.g. `Char_Armor_` |
| `WIKI_CLASSIFICATION_PREFIX` | the start of an untyped item's SC Wiki classification, e.g. `FPS.Consumable` |
| `ITEM_KIND` | an untyped item's Basetool kind, e.g. `FOOD` |
| `SHIP_ORIGIN` | a ship's `origin`: `IN_GAME` ([ships](ships.md#a-ship)) |

Every item falls into exactly one group. An item with a P4K type is matched by its type and
sub-type, then by its type, then by the longest matching type prefix; the wiki classification and
the kind decide only for an item without a type. An item no group claims is in `OTHER_ITEMS`, which
lists no types. A material is in the group of its type. `IN_GAME_SHIPS` wipes only ships whose
origin is `IN_GAME`; a pledged ship and one of unknown origin are never wiped.

`wipeExceptions` names single items, and materials in every quality, that the patch change kept
although their group was wiped (`KEEP`) or wiped although it was not (`WIPE`). An exception's entry
follows the exception, never its group. `entry` names it as the [stock](stock.md) feed names a lot's
`material`; `target` is `GAME_ITEM` or `MATERIAL`. An exception whose item or material was removed
from the catalogue since stays in the list: its `entry` keeps the `bt` it had and carries no
`name`.

Only stock rows and ships **created before `liveAt`** were wiped; what was booked or added later
stayed. Personal inventory, blueprints and the bank are never part of the wipe.

### The member's home location

`homeLocation` describes the member in the latest patch change that asked for a new home location,
which need not be the latest patch change: a later patch change without the prompt leaves the open
phase open.

| `state` | Meaning |
| --- | --- |
| `NOT_ASKED` | No phase asks the member: none ever ran, or the account was created after the live time. |
| `PENDING` | The phase is open and the member has not chosen. Their lots and ships from before the live time are locked. |
| `RELOCATED` | The member chose and was relocated; `chosen` and `chosenAt` say where and when. |
| `PHASE_ENDED` | An admin ended the phase before the member chose; nothing more relocates in it. |

- `patchChange` and `liveAt` name the asking patch change; both are absent when none ever asked.
- `locations` are the curated home locations on offer, by name, each with the `id` a choice sends.
  A location carries its `name`, its star `system`, its `uex` place and its starmap `guid` as the
  [catalogue](catalog.md) describes them, so you can match it to your own places. The list is empty
  while an admin has curated none; do not offer a choice then.
- `preview` is filled only while the member is `PENDING`: per current location, the stock `rows`
  from before the live time, own and pool rows together, the share held for each org unit's
  `pools`, and the `ships`. An entry without `location` counts ships that have none. What already
  lies at the location the member chooses stays there and does not count as moved.
- `chosen` is absent before the choice, and once the chosen location was deleted.

While the member is `PENDING`, pause your own relocation and the sync of those lots and ships, show
the member the offered locations with the preview, and either send the choice or point the member
to the Basetool.

## Choosing the home location — `POST /exchange/v1/me/patch-change/home-location`

The member's choice, with the web's rules: only a home location the read offers, once per member and
phase, **final**. The request needs an `Idempotency-Key` and counts one unit of the daily write
quota ([limits](../sync-guide.md#rate-limits-quota-and-back-off)). Send it only after the member
picked the location; never choose on the member's behalf.

```json
{ "location": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f" }
```

The Basetool relocates the member in the same request and answers with the chosen location and
what moved:

```json
{
  "location": { "id": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "name": "Lorville",
                "system": "Stanton", "uex": { "kind": "CITY", "id": 4 } },
  "rows": 12,
  "ships": 3
}
```

The member's stock rows, own and pool rows, and ships created before the live time move to the
chosen location; the lock ends. Then pull the stock and ship feeds: a moved lot arrives as a
tombstone for the old key and an item for the new key, both from the channel `patch` with the patch
change's `id`, and a moved ship with its new location and a new `version`
([patch changes](../sync-guide.md#patch-changes)). Read the patch change again for the member's new
state.

A retry under the same key within the hour gets the first answer. A choice made in the meantime,
in the Basetool or by another installation, answers `409 HOME_LOCATION_ALREADY_CHOSEN`: read the
patch change again and take the server's result.

## Pushing against what a patch change removed

A lot the patch change moved or wiped is empty under its old key. A stock op that expects stock
there or fills it, without `override`, is refused per op as `REMOVED_ELSEWHERE`, whatever its
`expectedQuantity`, and the result item names the patch change:

```json
{
  "index": 0,
  "result": "rejected",
  "reason": "REMOVED_ELSEWHERE",
  "patchChange": { "id": "3f0c2b1a-9e8d-4c7b-a6f5-e4d3c2b1a098", "label": "4.4" }
}
```

A ship `upsert` naming a ship the patch change wiped is refused the same way. Drop your pending
change for that lot or ship and pull the feed.

A lot that kept rows from after the live time is not empty; an op against it with a stale
`expectedQuantity` is `VERSION_CONFLICT`. A moved ship keeps its id and has a new `version`, so a
stale `upsert` or `remove` of it is `VERSION_CONFLICT` too. When the lot's or the ship's last
change came from a patch change, that conflict names it as well, in the same `patchChange`
property; pull the feed and show the member why their view was stale. A conflict after any other
change carries no `patchChange`.

[Org-stock](org-stock.md#the-result) ops name single entries, so the Basetool judges each entry by
its own last change in its pool. An op on an entry the patch change wiped is `REMOVED_ELSEWHERE`
with the `patchChange`. A stale op on an entry it moved to its holder's new home location is
`VERSION_CONFLICT` with the `patchChange`. The pool's other entries are not affected: a change the
patch change made to another entry of the same lot, or a lot the patch change only announced again,
names it for no entry. Every other gone entry stays `NOT_PERMITTED`.

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The choice names no location, or its `location` is no id. |
| `400 HOME_LOCATION_NOT_OFFERED` | The location is not one of the home locations on offer; nothing moved. |
| `403 SCOPE_MISSING` | Reading without `exchange.patch.read`, or choosing without `exchange.patch.write`. |
| `409 HOME_LOCATION_ALREADY_CHOSEN` | The member chose in this phase already, anywhere; the choice is final. |
| `409 HOME_LOCATION_NOT_ASKED` | No open phase asks the member: none is open, an admin ended it, or the account was created after the live time. |
| `409 RELOCATION_PENDING` | Not from this resource: a change set on the stock or ship feeds that writes a locked lot or ship while the member is `PENDING` ([errors](../errors.md#request-errors)). |
