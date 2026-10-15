# ADR-0259 — The design-system submodule follows its `main`

- **Status:** Accepted
- **Date:** 2026-10-15
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) ·
  [`frontend/CLAUDE.md`](../../frontend/CLAUDE.md) · arc42 [§8.6](../arc42/08-crosscutting-concepts.md)

## Context

The DAS KARTELL design system lives in its own repository, `krt-profit/design-system`, and reaches
this repository as the git submodule `.claude/skills/das-kartell-design`, where Claude Code reads it
as the `das-kartell-design` skill. The submodule was pinned: every design-system release needed a
separate "bump the submodule" commit and PR here (five of them between 2026-09-23 and 2026-10-12),
and until that PR merged, a session worked against an older design system than the one that was
published. Nothing else reads the submodule — no Gradle task, no test, no CI workflow, no image — so
the pin protected no build.

## Decision

1. **The `SessionStart` hook fast-forwards the submodule to `origin/main`** of the design-system
   repository after materialising it (`.claude/hooks/ensure-design-system.ps1` and its portable twin
   `ensure-design-system.sh`). A session therefore always reads the latest published design
   system.
2. **The hook never discards work.** It leaves the checkout as it is when the fetch fails (offline:
   the current state stays), when the checkout has local changes, when `HEAD` holds commits that
   are not on `origin/main`, or when a checked-out branch cannot fast-forward.
3. **`.gitmodules` declares `branch = main` and `ignore = all`.** The moved submodule does not show
   in `git status` and is not staged by `git add -A` or `commit -a`, so no PR carries an accidental
   pin change.
4. **The recorded gitlink stays as the fallback** — what a plain `git submodule update --init` checks
   out before the hook moves it, and what an offline first checkout receives. It is moved
   deliberately, with `git add --force .claude/skills/das-kartell-design`, and no longer has to be
   moved for every design-system release.

## Consequences

- A design-system change reaches every session at its next start without a PR in this repository.
- Two sessions started at different times can read different design-system states; a PR that cites
  a mockup names the design-system commit it followed.
- Session start needs one `git fetch` against GitHub; a slow or failing network costs at most the
  low-speed timeout and is otherwise ignored.
- `git submodule update` without `--remote` moves the checkout back to the recorded gitlink until
  the next session start.
