# Exchange API — conformance fixtures

One directory per schema a route sends or receives, each with `valid/` and `invalid/` JSON files.
A directory named `<schema>--<def>` targets `<schema>.schema.json#/$defs/<def>`.

The schemas themselves live in `ingest/src/main/resources/exchange/v1/schemas/` and are served at
their `$id`, `https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json`. The
OpenAPI document is `ingest/src/main/resources/api/exchange-v1.openapi.json`.

`ExchangeContractTest` in the ingest module validates every fixture: a `valid/` file must pass, an
`invalid/` file must fail, and every schema the OpenAPI document references must have at least one
of each. A client can run the same files against its own reader and writer.
