# Vendored p4kx catalogue schema

`basetool-catalog.schema.json` is a verbatim copy of the JSON Schema of the p4kx catalogue
contract. It is a vendored file: never edit it by hand, never format it; replace it as a whole
when the Basetool moves to another p4kx release, and update this table in the same commit.

| | |
| --- | --- |
| Repository | [p4kx-org/p4kx](https://github.com/p4kx-org/p4kx) |
| Path | `schemas/basetool-catalog.schema.json` |
| Tag | `v0.4.2` (tag object `27bc54cbfaad7b66d91eb2a259abc28a66994c77`) |
| Size | 420,656 bytes |
| SHA-256 | `1534e33bf4620771023e84b0712441ad75802f9a4525c4bdf99c848ac11c8f1f` |
| Licence | `MIT OR Apache-2.0` (p4kx `Cargo.toml` at that tag); both texts are copied verbatim beside the schema as `LICENSE-MIT` and `LICENSE-APACHE` |

Readers: `GameTextClassificationTest` reads its `"x-gameText": true` annotations (REQ-P4K-033);
the P4K import tests may validate their fixtures against it. The path is fixed: other tests
refer to it.
