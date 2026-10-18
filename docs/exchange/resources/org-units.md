# Org units

`GET /exchange/v1/me/org-units`, scope `exchange.orgunits.read`: the org units the member directly
belongs to, so a client can offer them as a choice, for example as the unit a ship belongs to. It
is read-only; there is no paging and no cursor.

## Which units

Every unit the member is a **direct member** of, of all four kinds:

| `kind` | What it is |
| --- | --- |
| `SQUADRON` | a squadron |
| `SPECIAL_COMMAND` | a special command |
| `BEREICH` | an area, the level above squadrons and special commands |
| `ORGANISATIONSLEITUNG` | the organisation's leadership |

A leadership seat does not add the units below it: a member who leads an area gets that area, not
its squadrons. An administrator's role adds nothing either; the exchange acts with the member's own
memberships only. A member of no unit gets an empty list:

```json
{
  "items": []
}
```

Treat a `kind` value you do not know as a unit of an unknown kind and keep it selectable
([versioning](../versioning.md)).

## The answer

An [`org-unit-list`](../schemas/) document of [`org-unit`](../schemas/) entries, leadership first,
then areas, squadrons and special commands, each by name:

```json
{
  "items": [
    {
      "id": "1c7a3e2f-5d2b-4f9f-8b62-4a1d6e8b0c21",
      "name": "Logistics",
      "shorthand": "LOG",
      "kind": "BEREICH"
    },
    {
      "id": "2d8b4f30-6e3c-4a0a-9c73-5b2e7f9c1d32",
      "name": "Iridium",
      "shorthand": "IRI",
      "kind": "SQUADRON"
    }
  ]
}
```

- `id` is opaque: store it and send it back, never parse it.
- `name` and `shorthand` are what the Basetool shows; both can change when the unit is renamed, so
  key anything you keep by `id`.
- This list carries no `logistician`; the units of the [org stock](org-stock.md#the-answer) do.

Read the list again when you offer the choice; a membership can end or begin at any time.
