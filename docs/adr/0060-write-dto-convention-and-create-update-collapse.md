# ADR-0060 — Write-DTO convention: `…Request` in `model/dto`, collapse Create/Update into one `…WriteRequest`

- **Status:** Accepted — implemented. *Status corrected 2026-09-22:* it read "Proposed", but the change it decides has been on `main` since 2026-07-02 (`a45adf77e`: `validation.DtoConstraints`, `validation.OnUpdate` and the collapsed `…WriteRequest` records such as `PromotionTopicWriteRequest`).
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`api-conventions.md`](../specs/api-conventions.md) REQ-API-002 · issue #919 (S13, epic #905)

## Context

Write-DTO naming and packaging grew into a four-way free-for-all. The same "write" operation is
spelled as `CreateXxxDto`, `XxxCreateRequest`, `XxxCreateDto`, and `UpdateXxxRequest` across the
codebase, and the `*Request` records are split between `model/dto/` (45) and `model/dto/request/`
(37) with no rule. A suffix census counts 196 `Dto`, 45 `Request`, 15 `Response`.

Two concrete smells fall out of this:

1. **Create/Update pairs that differ only by `version`.** Five pairs are byte-identical except that
   the Update record adds `@NotNull Long version`: `Promotion{Category,Topic,LevelContent}`,
   `RankRequirement`, `MaterialExternalAlias`. (`MemberEvaluation`, `PersonalBlueprint` and
   `OrgChartPosition` write DTOs legitimately differ between create and update and are left alone.)
2. **Copy-pasted constraints with no shared source of truth** — `@Size(max = 255)` ×26,
   `max = 2000` ×13, `max = 120` ×12; the `^(https://.*)?$` `@Pattern` repeated across three Mission
   request DTOs; `@Pattern(regexp = "UEX|SCWIKI|REFINERY_SCREEN")` duplicated across the
   MaterialExternalAlias pair.

## Decision

**Convention.** A write DTO is a record named `XxxWriteRequest` (single write op) or
`XxxCreateRequest` / `XxxUpdateRequest` (when create and update genuinely diverge), living in
`model/dto/`. The suffix is **`Request`** for write inputs and **`Response`** for read outputs;
`Dto` is reserved for internal / read-model records. The migration is **incremental per feature
slice** — this ADR fixes the target, it does not require a big-bang rename of all 241 existing DTOs.

**Collapse the five confirmed pairs** into a single `XxxWriteRequest` whose optimistic-lock version
is nullable on the type and required only on update via a Bean-Validation group:

```java
public record PromotionTopicWriteRequest(
    @NotBlank @Size(max = DtoConstraints.MAX_SHORT_NAME) String name,
    @Size(max = DtoConstraints.MAX_DESCRIPTION) String description,
    @NotNull Integer sortOrder,
    @NotNull(groups = OnUpdate.class) Long version) {}
```

- The **create** endpoint validates with `@Valid` (the `Default` group) → `version` is not required.
- The **update** endpoint validates with `@Validated({Default.class, OnUpdate.class})` → the base
  field constraints **and** the required-version constraint fire, so a missing version on update
  still surfaces as the same `VALIDATION_FAILED` 400 with a `version` field error it did when Update
  was its own DTO. The empty marker group is `validation.OnUpdate`.
- MapStruct's `toEntity(WriteRequest)` builds via the entity builder, which exposes no `version`
  setter, so the now-present `version` source field is simply left unmapped and never seeds the JPA
  `@Version` at create time — no explicit ignore is needed (and adding one fails to compile).
  `updateEntity` keeps its `@Mapping(target = "version", ignore = true)` because it maps onto a
  managed entity through setters, where `version` *is* a reachable target.

**Constraints.** Rather than composed constraint annotations (`@HttpsUrl` etc.), the shared limits
and regexes move into a `validation.DtoConstraints` holder of compile-time constants referenced from
`@Size(max = …)` / `@Pattern(regexp = …)`. This centralises the source of truth **without changing
the generated `openapi.json`**: the resolved `maxLength`/`pattern` values are identical, whereas a
composed annotation risks SpringDoc dropping the constraint from the schema. The Mission `https`
pattern and the MaterialExternalAlias source-system pattern are migrated to `DtoConstraints`
constants in this slice; the broad `@Size` rollout is incremental.

## Consequences

- **openapi.json changes are limited to the collapse.** The three deleted Create schemas gain a
  nullable `version` property (now part of `XxxWriteRequest`) and the paired schemas are renamed;
  the constraint values are unchanged. `openapi.json` is regenerated in the same PR.
- A create request may now carry an (ignored) `version` field; it was previously an unknown
  property. This is intentional and harmless — the create mapper ignores it.
- Frontend request mirrors that mirror a collapsed pair (only `MaterialExternalAlias`) collapse to
  one record too, kept in lockstep by the cross-module DTO contract test (S9, #915).
- Future write DTOs and the remaining naming outliers migrate onto this convention per feature slice.
