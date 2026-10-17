# ADR-0276 — Every `main` push gets its own CI run, and a superseded one skips

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) (its 2026-09-23
  amendment is the pattern this follows) ·
  [ADR-0246](0246-pull-requests-merge-through-a-merge-queue-not-the-strict-up-to-date-policy.md) and
  spec `REQ-OPS-044` (the merge queue, which prevents what this only detects) · spec `REQ-OPS-049` ·
  `.github/workflows/ci.yml`, `scripts/ci-superseded.sh`

## Context

`ci.yml` grouped its runs on `ci-<workflow>-<ref>` with `cancel-in-progress` false for a push. Every
push to `main` therefore shared **one** concurrency group, and such a group holds one running and one
pending run: a newly triggered run **cancels the run that was already pending**.

On 2026-10-17 seven merges landed between 08:33 and 08:51 while `2adc4320d`'s run held the running
slot from 07:43:25 to 08:55:03. Each of the seven was cancelled within one second of its successor
being created — `6f4121f1f`, `979f9fc9b`, `cd46559b6`, `902d99caa`, `aa02272aa`, `d93b2275f` and
`3049df8c9` in turn. Over the sixty most recent `main` runs, 24 ended `cancelled`, 8 `failure` and 28
`success`: four in ten produced no verdict at all. `cancelled` is not `failure`, so neither the commit
nor any alert showed it. The eviction also falls hardest on the longest workflow, because it occupies
the pending slot longest — which is `ci.yml` at roughly nineteen minutes.

Two of the commits left unverified that morning were `aa02272aa`, which required the scrape credential
on the management port (ADR-0265) and left `SecurityFilterChainProductionShapeTest` expecting the
previous chain, and `3049df8c9`, which set the ADR-0267 size caps that two already-grown controllers
exceeded. Both breakages surfaced only later, against `27735681c` — the first commit of that morning
whose run was not evicted.

What is supposed to keep such a combination off `main` is not this run at all: REQ-OPS-044 has `main`
advanced only through the merge queue, which builds `main` plus the pull request before the merge, and
the strict up-to-date policy is off because of it. Those merges did not go through the queue. The
`main`-push run is the backstop behind it, and the backstop was silent.

## Decision

**Every push to `main` gets its own CI run, and a run whose commit is no longer `main`'s tip skips
instead of being cancelled.** Two parts, mirroring ADR-0137's amendment:

1. **The concurrency group is per commit for a push** —
   `ci-${{ github.workflow }}-${{ github.event_name == 'push' && github.sha || github.ref }}`. A push
   can no longer evict another push's run. Pull requests keep the per-ref group and keep cancelling a
   superseded run, which is what `cancel-in-progress` already said.
2. **A `Superseded check` job decides, and `Build, Test & Lint` runs unless it says skip.** The
   decision is `scripts/ci-superseded.sh`: a push to `refs/heads/main` whose commit is a strict
   ancestor of `main`'s current tip skips, and the run ends green with a `::notice` and a job-summary
   line naming the tip. The script's `--decide` mode is pure and self-tested; `--check` gathers the
   tip and the ancestry from `repos/{repo}/commits/main` and `repos/{repo}/compare/{sha}...{tip}`.

**It fails open in every direction.** Anything other than a confirmed `ahead` comparison builds: a
tip that cannot be read, a comparison that cannot be made, a commit that is not an ancestor (a force
push), and any event that is not a `main` push. A comparison value that is not one of GitHub's four
is normalised to `unknown`, so a failed call's error body cannot reach the reason text. The gate's own
failure builds too, because the build job's condition is
`!cancelled() && needs.supersede.outputs.skip != 'true'` — only an explicit skip decision stops it. A required context that nothing reports wedges the merge queue (REQ-OPS-044), and
no failure mode of this gate may cause that.

## Consequences

- Every push to `main` now ends with either a verdict or an explicit "skipped — superseded" note. No
  `main` commit's run can be cancelled by a later merge, and `cancelled` stops being a status that
  hides whether `main` was evaluated.
- **The verdict arrives sooner.** The tip's run no longer waits in the pending slot behind runs of
  commits that are already out of date, and superseded runs consume no build capacity.
- **This detects; it does not prevent.** A superseded commit is still never built on its own, so which
  merge of a burst broke `main` is still not attributed — the tip's run covers the *content*, since a
  superseded commit is an ancestor of the tip, and bisection remains how a burst is attributed. The
  combination that breaks `main` is prevented only by the merge queue.
- **A red `main` is still not announced.** Nothing watches `main`'s verdict and nothing stops a merge
  onto a red `main`; on 2026-10-17 `main` was red from 07:43 until 14:26 with roughly fifteen merges
  landing during it. That gap is left open deliberately and is not what this decision closes.
- `Build, Test & Lint` waits for the gate job, about half a minute, as ADR-0137's amendment accepted
  for `plan`. The gate also runs on every pull request and merge group, where it always decides
  "build".
- **Two `main` runs can now overlap**, where the shared group serialised them. Both write the Gradle
  caches, since `cache-read-only` is false on `main`: `gradle/actions/setup-gradle` writes distinct
  cache entries, a duplicate key is rejected rather than corrupted, and `cache-janitor.yml` prunes.
- One extra API read per push, with `contents: read`, which the workflow already has.

## Alternatives considered

- **A per-commit group with no skip.** One line, and ADR-0137's amendment already records the cost:
  about 35 merges queued 29 "Release Images" runs, runner capacity became the bottleneck, "PR CI and
  E2E starved behind builds of commits that were already out of date", and 28 were cancelled by hand.
  On the repository's longest workflow that trades a lost verdict for a blocked queue.
- **Keeping one group and alerting on `cancelled`.** A cancelled run cannot report anything, so the
  fact that `main` went unevaluated would have to be reconstructed from outside the run — and the
  verdict still would not exist.
- **Relying on the merge queue alone.** Primary and correct, and it is the written requirement. But it
  leaves `main`'s own verdict destroyed whenever a merge bypasses the queue, which is exactly how the
  two 2026-10-17 breakages arrived.
- **Re-checking supersession during the build and aborting.** Saves a little capacity, costs a
  cancelled run again — the status this decision exists to stop producing.
