# Architecture documentation (arc42)

**Doc type:** living spec · **Baseline:** the system **as it stands after the Podman cutover**
(`docs/archive/PODMAN_CUTOVER_RUNBOOK.md`) · **Last reviewed:** 2026-09-21

This folder documents the architecture of the **Profit Basetool** along the
[arc42](https://arc42.org) template. It is the map, not the territory: where a subject already has
a canonical home, this folder **links** rather than restates it, because two copies of a fact drift
and the reader cannot tell which one is current.

| Where the truth lives | What it holds |
| --- | --- |
| [`docs/specs/`](../specs/INDEX.md) | Durable, binding requirements — `REQ-<AREA>-NNN`, registry in `INDEX.md` |
| [`docs/adr/`](../adr/README.md) | Every architecturally significant decision, with its context and consequences |
| [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md) | The role and permission matrix |
| [`README.md`](../../README.md) | Product overview, tech stack, how to build and run |
| `basetool-knowledge` (Obsidian vault) | Operational reality: hosts, incidents, runbooks, values, why something broke once |

## The sections

| § | Document | Answers |
| --- | --- | --- |
| 1 | [Introduction and goals](01-introduction-and-goals.md) | What the system is for, for whom, and which qualities drive it |
| 2 | [Architecture constraints](02-architecture-constraints.md) | What was not free to choose |
| 3 | [Context and scope](03-context-and-scope.md) | The system boundary and every neighbour across it |
| 4 | [Solution strategy](04-solution-strategy.md) | The handful of decisions the rest follows from |
| 5 | [Building block view](05-building-block-view.md) | Static decomposition, level by level |
| 6 | [Runtime view](06-runtime-view.md) | What actually happens during the scenarios that matter |
| 7 | [Deployment view](07-deployment-view.md) | The host, the runtime, the units, the delivery path |
| 8 | [Cross-cutting concepts](08-crosscutting-concepts.md) | The rules that hold across every module |
| 9 | [Architecture decisions](09-architecture-decisions.md) | How decisions are recorded, and the ones that shape the whole |
| 10 | [Quality requirements](10-quality-requirements.md) | Quality goals, scenarios, and what measures them |
| 11 | [Risks and technical debt](11-risks-and-technical-debt.md) | What is known to be fragile or unfinished |
| 12 | [Glossary](12-glossary.md) | The German domain vocabulary, and the terms that are easy to confuse |

## Why the baseline is stated at the top

This documentation describes the **post-cutover** system: rootless Podman with Quadlet units on
Rocky Linux, an nginx edge with its own ACME client, three observability components moved out of
containers and onto the host, and cAdvisor gone. A reader who takes this folder for the Docker-era
system will misread §7 completely, and parts of §8 with it. The Docker-era shape is not lost — it
is recorded in the migration plan and the cutover runbook, which exist precisely so the transition
is legible in both directions.

> [!important] This folder is binding, and updating it is part of the change
> [`CLAUDE.md`](../../CLAUDE.md) states the rule explicitly: **the arc42 documentation moves with the
> change whenever it is affected**, in the same PR — a module boundary or a new building block (§5),
> a runtime scenario (§6), the host, a deployment unit, a timer or the delivery path (§7), a
> cross-cutting rule (§8), a quality scenario (§10), a known risk (§11), a new domain term (§12), or
> the system boundary and its neighbours (§3).
>
> Because this folder links rather than restates, most changes touch nothing here — and the ones
> that do are exactly the ones a later reader cannot reconstruct from the diff. **Nothing gates it.**
> No build fails on a stale chapter, and a stale architecture document still reads as authoritative,
> which is why the rule is written down instead of assumed.
