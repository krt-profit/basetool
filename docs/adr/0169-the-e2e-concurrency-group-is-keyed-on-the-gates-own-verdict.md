# ADR-0169 — The E2E concurrency group is keyed on the gate's own verdict

- **Status:** Accepted — implemented. *Status corrected 2026-09-22:* it read "Proposed", but the change it decides has been on `main` since 2026-09-13 (`c489902d4`: the verdict-keyed `concurrency.group` in `e2e.yml` and `.github/scripts/check_e2e_gate_mirror.py`).
- **Date:** 2026-09-13
- **Deciders:** @greluc (pending)
- **Related:** specs `REQ-OPS-027` · [`deployment-delivery.md`](../specs/deployment-delivery.md) ·
  [`.github/workflows/e2e.yml`](../../.github/workflows/e2e.yml) ·
  [`.github/scripts/check_e2e_gate_mirror.py`](../../.github/scripts/check_e2e_gate_mirror.py) ·
  PR #1537 · PR #1871

## Context

The Playwright suite brings up a six-service stack (Postgres ×2, Keycloak, Redis, backend,
frontend) across three browser engines, so it is gated on a PR carrying the `e2e` label rather than
run on every push. Two mechanisms implement that gate, and they are evaluated at different moments:

- **`jobs.e2e.if`** decides whether the job runs. It is evaluated *after* the workflow run exists.
- **`concurrency.group`** decides which runs may cancel each other. It is evaluated when the run is
  **created** — before any gate has been consulted.

That ordering is the whole defect. **A run that will go on to skip every one of its jobs still
claims the concurrency group on its way to skipping, and with `cancel-in-progress` it cancels the
incumbent first.** The gate never gets a say in which run survives.

`gh pr create --label performance --label FE --label requirement --label e2e` fires `opened` plus
one `labeled` event **per label**, all within about a second. Every one of those five runs landed
in the single group `e2e-${{ github.workflow }}-${{ github.ref }}`, so each cancelled its
predecessor and exactly one survived — whichever label GitHub processed last. On PR #1871 that was
a `labeled` event carrying `requirement`, whose gate evaluated false, so it skipped:

|                   run                   |                event                 |   outcome   |
|-----------------------------------------|--------------------------------------|-------------|
| 34774166106                             | `opened` (no labels yet)             | cancelled   |
| 34774166536 · 34774166666 · 34774166684 | `labeled` (three of the four labels) | cancelled   |
| 34774166700                             | `labeled` (not `e2e`)                | **skipped** |

The run whose event actually carried `e2e` was cancelled by a sibling label event. Net effect: the
suite never executed, and because `gh pr checks` renders a cancelled job as `fail`, the PR showed
a red board that no reading of the diff could explain.

This is the second recorded occurrence — PR #1537 produced five runs, three cancelled and two
skipped, also with zero execution — and it is a **reintroduction of the exact footgun the `labeled`
trigger was added to close**, one layer down. The workflow's own comment calls that footgun "a
real, recurring footgun"; the concurrency group quietly re-opened it.

Two properties make the failure expensive out of proportion to its size:

1. **A silently skipped suite is indistinguishable from a passing one.** The three `E2E flows` jobs
   are *not* required checks, so a PR with no end-to-end coverage at all merges on a green board.
2. **The red half misattributes.** `cancelled` and `failure` look the same in `gh pr checks`, so
   the visible symptom points at the diff, which is where the time goes.

Until now the answer was a human workaround, written into the knowledge base as *"add `e2e` last,
on its own"*. A rule that every author must remember, on every PR, to avoid a silent loss of test
coverage is not a control.

## Decision

**The concurrency group is keyed on the gate's own verdict, not on the event that produced the
run.** The group embeds the job gate verbatim and selects one of two keys:

- every run the gate will **admit** shares the key `suite`, where `cancel-in-progress` still does
  the job it exists for — a new push supersedes an in-flight suite;
- every run the gate will **reject** is parked under `format('noop-{0}', github.run_id)`, unique per
  run, so it cancels nothing, nothing cancels it, and it reports `skipped` rather than the
  `fail`-coloured `cancelled`.

```yaml
group: >-
  e2e-${{ github.workflow }}-${{ github.ref }}-${{
  (github.event_name != 'pull_request' ||
  (github.event.action == 'labeled' && github.event.label.name == 'e2e') ||
  (github.event.action != 'labeled' &&
  contains(github.event.pull_request.labels.*.name, 'e2e')))
  && 'suite' || format('noop-{0}', github.run_id) }}
```

**Event ordering stops mattering**, because of those five runs only the one carrying `e2e` can ever
enter `suite`. The gate itself is unchanged, so the cost guard it exists for is untouched: toggling
an unrelated label still skips, and still does not spin up a 2 GB stack.

The selector must be the gate **verbatim**. A selector *stricter* than the gate is merely wasteful
(two real suites could run concurrently); a selector *looser* than the gate restores the bug
outright, by letting a run that will skip back into `suite`. Since GitHub offers no way to share one
expression between the two positions, the duplication is unavoidable and is therefore **gated**:
`check_e2e_gate_mirror.py` fails `repo-lint` when the two drift apart, and self-tests first so it
cannot pass vacuously.

## Options considered

**Key the group on the event action and label** — `…-${{ github.event.action == 'labeled' &&
github.event.label.name || 'push' }}`. Rejected. It does stop label events cancelling each other,
but it also puts `synchronize` in a different group from the label-triggered run, so a push no
longer supersedes an in-flight suite: two suites run concurrently and the older one reports a
verdict for a merge commit that no longer exists. It keys on the label as a *proxy* for the
decision; the chosen form keys on the decision.

**Drop `labeled` from the trigger and gate every action on
`contains(github.event.pull_request.labels.*.name, 'e2e')`.** Rejected, and already rejected once in
the workflow's own comment. `gh pr create --label e2e` applies the label *after* the `opened` event,
so the label state captured at open never contains it — the suite would then wait for the next push
on every PR that requested it, which is the recurring footgun the `labeled` trigger was added to
fix. Trading a silent skip for a deterministic one is not an improvement.

**Make the gate tolerant instead** — run when the event is `labeled` with `e2e` *or* when the label
is currently on the PR. Rejected on two counts. It re-arms the cost the
`github.event.label.name == 'e2e'` clause exists to prevent: every later toggle of any unrelated
label on an `e2e`-labelled PR would start a fresh three-browser run. And it fixes only half the
reported symptom — the four sibling runs are still cancelled and still render as `fail`, so the PR
keeps its unexplained red board. It treats surviving the cancellation as the goal; the cancellation
is the defect.

## Consequences

- **No run that will skip can cancel a run that will execute.** That is the defect, and it is what
  the change removes. Measured on #1877, whose `gh pr create` carried five labels and produced
  eleven runs: one executed the full three-browser matrix, eight skipped (matrix never expanded),
  and **none of the skipping runs was cancelled**.
- Two runs of that burst were still cancelled, and both had **passed** the gate — one had already
  expanded its matrix. Those are duplicate qualifying runs (the `opened` payload carried `e2e`, and
  so did the `labeled` event that followed) being superseded by the newest, which is
  `cancel-in-progress` doing exactly its job; running three identical suites side by side would be
  the defect. So a burst still leaves a `cancelled` run or two on the board, and `gh pr checks`
  still colours those like a failure. **This ADR makes the suite run; it does not make the
  `cancelled`/`failure` rendering unambiguous** — see the last consequence.
- The knowledge base's *"add `e2e` last, on its own"* rule becomes historical. Label order no longer
  changes the outcome, and the recovery dance (`gh pr edit --remove-label e2e`, wait, re-add) is no
  longer needed for this cause.
- One decision is now written in two places. That is a real maintenance cost and the reason for the
  new `e2e-gate-mirror` repo-lint job; an edit to either copy alone fails CI with a diff of the two
  expressions.
- `cancel-in-progress` is unchanged in effect for the case it was introduced for: consecutive pushes
  to an `e2e`-labelled PR still supersede one another.
- *A cancelled E2E run is still not a verdict.* Merging a PR mid-run cancels it and it still reads
  as `fail`. This ADR removes one cause of spurious cancellations, not the ambiguity of the
  rendering.
