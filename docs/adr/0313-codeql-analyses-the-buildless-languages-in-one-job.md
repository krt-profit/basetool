# ADR-0313 — CodeQL analyses the buildless languages in one job

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-044 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · amends [ADR-0246](0246-pull-requests-merge-through-a-merge-queue-not-the-strict-up-to-date-policy.md) · ADR-0314 (Java extraction) · ADR-0310 (runner budget)

## Context

`codeql.yml` ran one job per language: `actions`, `java-kotlin`, `javascript-typescript` and
`python`, of which only `java-kotlin` builds. On 2026-10-23 the three buildless jobs ran **1.1 to 1.6
minutes** each after waiting up to **18.9 minutes** for a runner, because the organisation's 20
concurrent jobs were the bottleneck (ADR-0310). In a merge group only `java-kotlin` and
`javascript-typescript` ran.

`github/codeql-action/init` accepts several languages, and `analyze` then analyses each of them in the
same job. The SARIF runs of one upload must not share a category with a run of another upload, and
each existing job uploaded under `/language:<language>`.

## Decision

**`actions`, `python` and `javascript-typescript` run in one job, `Analyze (actions, python, js)`,
with `build-mode: none` and the category `/language:buildless`.** `java-kotlin` keeps its own job,
`Analyze (java-kotlin)`, and its category `/language:java-kotlin`. Both jobs run on every event,
merge groups included, and a merge group still uploads nothing.

The required check `Analyze (javascript-typescript)` in the `main` ruleset is replaced by
`Analyze (actions, python, js)`. The ruleset changes together with the merge of this change, so the
merge queue never waits for a context that no longer reports (REQ-OPS-044).

## Consequences

- Two CodeQL jobs per event instead of four; a merge group now also analyses `actions` and `python`.
- **The three old categories stop receiving uploads.** Their open alerts stay until those
  configurations are deleted. The owner decided on 2026-10-23 that the agent deletes them through the
  API after the merge, with the owner's explicit approval for that step.
- A failure in one of the three languages fails the shared job; the job log names the language.

## Alternatives considered

- **Keep one job per language.** Rejected for the runner cost above.
- **Let GitHub derive the category.** It would be built from the workflow, the job and the matrix
  values, so a rename of the job would move the alerts again. The owner chose the fixed
  `/language:buildless` on 2026-10-23.
- **Analyse only `javascript-typescript` in the merge group, as before.** The languages share a job
  now, so leaving two out would need an event-dependent language list for no saving in runners.
