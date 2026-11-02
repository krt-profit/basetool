# Frontend design system

The DAS KARTELL design system and how its submodule gets into a worktree.

The UI is a **binding requirement**: follow the DAS KARTELL design system. The rules —
brand colours, Lato-only typography (headlines = Lato Bold + uppercase), the authoritative department colours, the
sci-fi HUD style with a 4 px radius and the website palette, "no native browser dialogs", and the four responsive device
classes — live in [`docs/specs/ui-design-system.md`](../specs/ui-design-system.md). The
visual source of truth is the design skill at
[`.claude/skills/das-kartell-design/README.md`](../../.claude/skills/das-kartell-design/README.md)
(its README.md is the source of truth for colours, typography and components).

**The design system is a git submodule (`github.com/krt-profit/design-system`) and MUST be
present before any UI work.** `git worktree add` does not populate submodules, so in a fresh
worktree `.claude/skills/das-kartell-design/` is empty and the source of truth above is
unreadable. A `SessionStart` hook (`.claude/settings.json`) materialises it automatically at
session start (offline-first from the module store, with a copy from the main worktree as
fallback) **and then fast-forwards it to the design system's `origin/main`**, so a session always
reads the latest published design system (ADR-0259). It leaves the checkout alone when the fetch
fails, when it has local changes, or when it holds commits that are not on `origin/main`.
`.gitmodules` sets `ignore = all`: the moved submodule stays out of `git status` and `git add -A`,
and the recorded pin moves only on purpose (`git add --force .claude/skills/das-kartell-design`).
The hook is cross-platform via two self-guarding entries that dispatch on `command -v
pwsh`: Windows runs [`.claude/hooks/ensure-design-system.ps1`](../../.claude/hooks/ensure-design-system.ps1),
Linux/macOS/CI run the portable
[`.claude/hooks/ensure-design-system.sh`](../../.claude/hooks/ensure-design-system.sh) — exactly one
runs per host, with no error noise on the other. **If that directory is still empty when you start
UI work** — hooks disabled, or a worktree outside the harness — populate it yourself before
touching any frontend surface: `git submodule update --init .claude/skills/das-kartell-design`,
or, offline, copy it from the main worktree (find it via `git worktree list`). Never do UI work
against an empty design system and never treat its absence as "no design system applies".
