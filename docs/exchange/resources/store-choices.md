# Store choices

Scope `exchange.refinery.store`: the choices a refinery pickup's
[store lines](refinery-orders.md#store-lines) name, so a client can offer what the Basetool's store
dialog offers. Both reads are read-only; there is no paging and no cursor. Locations come from
[`GET /catalog/locations`](catalog.md), the member's own units from
[`GET /me/org-units`](org-units.md).

The Basetool decides both from its own records, never from a role the member holds elsewhere: an
administrator's role adds nothing.

## Receivers

`GET /exchange/v1/me/store-choices/receivers` lists the members a store line may book onto:

- the member, always, first;
- for a Logistician, the direct members of every unit they are a Logistician of, by name.

A member who withholds their name from connected applications is left out. Each receiver carries a
`ref`, `name`, `self` and the units they directly belong to, in the shape of an
[`org-unit`](../schemas/):

```json
{
  "items": [
    {
      "ref": "u3K9m2Qx7Lp4Rt8Vw1Zy5Ab6Cd0Ef2Gh3Ij4Kl5Mn6O",
      "name": "Cpt. Holder",
      "self": true,
      "orgUnits": [
        {
          "id": "0c2f8a51-7d3e-4b9a-9f10-5a6b7c8d9e01",
          "name": "Staffel Nord",
          "shorthand": "NORD",
          "kind": "SQUADRON"
        }
      ]
    }
  ]
}
```

- `ref` is your client's own pseudonym of the member, the same one the
  [org stock](org-stock.md) uses; another client sees another. Send it as a line's `receiver`.
- A line's `orgUnit` is one of the receiver's `orgUnits`, or a unit the member is a Logistician
  of. A receiver with several units needs it named.

## Job orders

`GET /exchange/v1/me/store-choices/job-orders?material=<bt>` lists the open and in-progress job
orders the member can see that need the material, as the store dialog's picker shows them. A
material that names nothing answers an empty list; a `material` that is no id is
`400 SCHEMA_INVALID`.

```json
{
  "items": [
    {
      "id": "8a1b2c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d",
      "number": 42,
      "status": "OPEN"
    }
  ]
}
```

Send an `id` as a line's `jobOrder`. The list may be kept for the session; an order that has since
closed, left the member's view or no longer needs the material refuses the line as
`JOB_ORDER_INVALID`.

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The job-order read without a `material` id. |
| `403 SCOPE_MISSING` | Either read without `exchange.refinery.store`. |
