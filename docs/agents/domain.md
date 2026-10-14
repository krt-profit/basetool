# Domain Docs

How the engineering skills consume this repo's domain documentation. The repo already has a glossary, decision records and specs; they are used as they are.

## Before exploring, read these

- **`docs/arc42/12-glossary.md`**: the domain glossary (German domain terms mapped to the English code names, plus the easily confused pairs). It takes the role of `CONTEXT.md`; do not create a `CONTEXT.md`.
- **`docs/adr/`**: read the ADRs that touch the area you are about to work in. The index is in `docs/adr/README.md`.
- **`docs/specs/INDEX.md`**: the requirement registry (`REQ-<AREA>-NNN`); read the spec of the area you touch. Requirements are binding.
- **`docs/arc42/`**: the architecture map; the chapter list is in `docs/arc42/README.md`.

If any of these files don't exist, proceed silently.

## Writing back

- A resolved domain term goes into `docs/arc42/12-glossary.md`, not into a new file.
- An architectural decision becomes an ADR from `docs/adr/0000-template.md`. Claim the number against `origin/main` and the open PRs at push time, and add the index row (escape every `|` in the summary).
- A behaviour change updates the matching `REQ-` in `docs/specs/` in the same PR.

## File structure

Single-context repo:

```
/
├── docs/
│   ├── arc42/12-glossary.md     ← domain glossary
│   ├── adr/                     ← decisions
│   └── specs/                   ← requirements
└── backend/ frontend/ ingest/ ...
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the glossary (OrgUnit, Staffel, Spezialkommando, Auftrag, Lager, ...). Don't drift to synonyms. If the concept you need isn't in the glossary, note it as a glossary gap.

## Flag ADR and requirement conflicts

If your output contradicts an existing ADR or REQ, surface it explicitly rather than silently overriding. A change that must violate a requirement needs the owner's prior approval and an amended requirement first.
