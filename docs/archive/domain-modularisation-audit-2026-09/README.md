> **Archived 2026-10-01.** Doc type: Historical analysis — the raw evidence of the domain
> modularisation audit of 2026-09-29, frozen and no longer updated.
>
> **Current truth:** [the domain modularisation plan](../../DOMAIN_MODULARISATION_PLAN.md) and its
> appendices in [`docs/modularisation/`](../../modularisation/). Index of the archive:
> [`README.md`](../README.md).

# Domain modularisation audit — raw evidence (2026-09-29)

The per-analysis reports, the finding data and the scripts behind the
[domain modularisation plan](../../DOMAIN_MODULARISATION_PLAN.md), kept by the owner's decision
D-22 so every figure in the plan can be traced to the analysis that produced it. Code state:
`origin/main` at `95e945326`.

> [!warning] These are working papers, not results
> The reports are the first-round analyses and the adversarial verification round as they were
> written. Where the verification narrowed or refuted a claim, **the plan and its appendices carry
> the verified figure; the first-round report keeps its original one.** Examples: the ArchUnit
> exposure (30 to 33 rules, not 20 to 25), the minimum feedback arc set (136 edges, not 211), the
> forced-update mechanics (the minimum version is not rendered on every deploy tick). Read a report
> together with `reports/95-verify-*.md` and the plan, never on its own.

| Folder | Contents |
| --- | --- |
| `reports/` | `00-briefing.md` (the brief every analysis worked from), `03-synthesis-notes.md` (the condensed findings), the eleven analyses `10-` … `90-` (backend domains, cross-cutting concerns, frontend Java, frontend assets, modules and build, modern Java, web research, the September audit in two halves, the July and earlier audits, the REST API), and the four verification reports `95-verify-1` … `-4` |
| `data/` | The per-finding re-evaluation of the previous audits as JSON (`80-`, `81-`, `82-`) and the September audit's finding list extracted from the knowledge base (`sept_audit_findings.json`) |
| `scripts/` | The 139 analysis scripts (Python, three Node modules for the asset ASTs). Local absolute paths are replaced by `$REPO` (the checkout analysed), `$MAIN_CHECKOUT`, `$SCRATCHPAD` (the working directory with the intermediate outputs, which are not kept), `$VAULT`, `$ANDROID_REPO` and `$SC_FILE_READER`; comments were removed (ADR-0214). They document how each figure was produced; they are not runnable as they stand |

Not kept: the intermediate outputs (class-dependency graphs, JSON dumps, logs) and the copies of
third-party documentation the research read; the plan's §16 names the sources.
