# CLAUDE.md

@AGENTS.md

## Claude Code specifics

The rules above are the project's agent instructions and bind Claude Code like every other agent.
What only Claude Code has:

- **SessionStart hook.** `.claude/settings.json` runs `.claude/hooks/ensure-design-system.ps1`
  (Windows) or `.sh` (elsewhere), which populates the design-system submodule and fast-forwards it
  (ADR-0259). When the hook did not run and `.claude/skills/das-kartell-design/` is empty, run
  `git submodule update --init .claude/skills/das-kartell-design` before any UI work.
- **Skills.** The skills in `.claude/skills/` (`lint-gate`, `local-stack`, `release-notes`,
  `bank-update`, `das-kartell-design`) are invoked through the Skill tool. A new skill also gets
  its `.agents/skills/<name>/SKILL.md` stub (REQ-OPS-055).
- **Path-scoped rules.** `.claude/rules/java-conventions.md` loads
  [`docs/agents/java-conventions.md`](docs/agents/java-conventions.md) whenever a `.java` file is
  touched.
- **Subagents.** A subagent gets only its prompt: name the worktree path and the `docs/agents/`
  files that cover its task. Test-writing agents never run Gradle themselves; the parent runs the
  build once and verifies their work.
