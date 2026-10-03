# Versioning

## The promise

Everything under `/exchange/v1` only ever **grows**. Within `v1` the Basetool adds routes, optional
fields, enum values and error codes; it never removes, renames or narrows anything a published schema
allows, and a published schema `$id` never changes. CI compares every schema with the previous
release's and fails a change that removes or narrows.

One narrowing is not a breaking change: correcting a schema to refuse what the Basetool has always
refused, so that no request the schema newly refuses ever succeeded. Each such correction is listed
in the [changelog](changelog.md).

A breaking change becomes `/exchange/v2`, served beside `v1` for at least **12 months**. Answers of
the old version then carry `Deprecation` and `Sunset` headers, and the service document lists the
deprecation.

## Reading tolerantly

Write a client that keeps working as `v1` grows:

- **Ignore fields you do not know**, at any depth.
- **Treat an enum value you do not know as unknown**, not as an error.
- **Treat identifiers and cursors as opaque strings.** Do not parse them, compare them for order or
  build them yourself.
- **Keep error handling keyed on `code`**, and handle an unknown `code` by its HTTP status.

The gateway is tolerant in the other direction too: a request field its schema does not declare is
ignored and, in the resolve and change results, reported as an `UNKNOWN_FIELD` warning with its JSON
Pointer, so a client notices a typo. A field whose pointer would exceed 200 characters cannot be
reported; such a body is refused with `400 SCHEMA_INVALID` before anything is written.

## Extensions

Data only one client needs goes into `extensions` under the client's own reverse-DNS key — see
[formats](formats.md#extensions--extensions). Never add top-level fields of your own.

## Minimum client version

The registry can hold a minimum version per client. Send `User-Agent: <Product>/<semver> (+<url>)`
on every request. A release below the minimum is refused with `403 CLIENT_VERSION_UNSUPPORTED`; ask
the member to update. A missing or unparseable `User-Agent` counts as older, and a pre-release of
the minimum itself (`2.4.0-beta.1` against `2.4.0`) is below it.

## Where changes are announced

Every contract change is listed, dated, in the [changelog](changelog.md).
