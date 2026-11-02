# ADR-0327 — AGENTS.md is the vendor-neutral agent instructions

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Related:** REQ-OPS-055 · ADR-0214 · ADR-0259 ·
  [`AGENTS.md`](../../AGENTS.md) · [`docs/agents/`](../agents/README.md) ·
  [`scripts/check-agent-instructions.py`](../../scripts/check-agent-instructions.py)

## Context

The repository's instructions for coding agents lived in `CLAUDE.md`, `backend/CLAUDE.md`,
`frontend/CLAUDE.md` and the Claude path rule `.claude/rules/java-conventions.md`, and its skills
in `.claude/skills/`. The owner now works with OpenAI Codex next to Claude Code. Codex reads
`AGENTS.md` — the file of each directory from the repository root down to its working directory,
concatenated, up to `project_doc_max_bytes` (32 KiB by default) — and skills from
`.agents/skills/<name>/SKILL.md`. It reads neither `CLAUDE.md` nor `.claude/skills/`.

The files had grown past that limit: the root `CLAUDE.md` alone was 41.5 KB, root plus
`frontend/CLAUDE.md` 74 KB. A second copy of the rules for Codex would drift from the first within
weeks. Symbolic links are not an option: the owner's checkouts run with `core.symlinks=false`.

## Decision

We will keep one set of agent instructions, in `AGENTS.md`, and let each agent's own file point at
it.

1. **`AGENTS.md` is the source.** The root, `backend/` and `frontend/` each carry an `AGENTS.md`
   written for any agent ("the agent", not a product). Each `CLAUDE.md` imports its directory's
   `AGENTS.md` (`@AGENTS.md`) and adds only what is specific to Claude Code: the `SessionStart`
   hook, skill invocation, path-scoped rules, subagents.
2. **The files stay below the reading limit.** `AGENTS.md` holds the binding rules as summaries;
   the full text of each long section moves verbatim into a reference file under `docs/agents/`,
   which the summary names with the work it must be read before. The Java conventions move to
   `docs/agents/java-conventions.md`; the Claude rule file imports it.
   `scripts/check-agent-instructions.py` fails `repo-lint.yml` when the `AGENTS.md` files on any
   path from the root exceed 32 KiB together.
3. **Skills stay in `.claude/skills/` and get a stub.** `.agents/skills/<name>/SKILL.md` carries the
   original's `name` and `description` and points at the original. The same script fails when a
   skill has no stub, a stub's frontmatter differs from its skill's, or a stub has no skill.
4. **Agent attribution names the agent.** Every AI-authored commit carries a `Co-Authored-By:`
   trailer with the agent, its model and the vendor's no-reply address
   (`Claude <Model> <noreply@anthropic.com>`, `Codex <Model> <noreply@openai.com>`).
5. **Machine-private facts stay out.** Host addresses, credentials and workstation details other
   than the worktree location go into each agent's user-level configuration
   (`~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`), not into the repository.

## Consequences

- An agent that reads only `AGENTS.md` sees every binding rule; the detail is one link away and
  has to be read actively. The summaries name the file and the moment, so the reading is a rule,
  not a hope.
- Claude Code no longer has the module landmines in context automatically; it reads them like any
  other agent. The rules themselves are unchanged.
- A section that grows past its summary goes into `docs/agents/`; the gate makes the limit visible
  in CI instead of in an agent silently cutting its instructions short.
- A new skill is two files. The stub is generated from the original's frontmatter and compared on
  every pull request.
- The design-system submodule is populated by the Claude hook; every other agent runs
  `git submodule update --init .claude/skills/das-kartell-design` itself, as `AGENTS.md` says.

## Alternatives considered

- **Raise `project_doc_max_bytes` only.** It needs every contributor's Codex configuration to
  change, and files of 75 KB stay hard to read for an agent and a human alike. The owner's own
  configuration raises it anyway, as headroom.
- **Symbolic links from `AGENTS.md` to `CLAUDE.md`.** They break on checkouts with
  `core.symlinks=false`, and the text would still name one product.
- **Two maintained copies.** They drift; nothing could keep them equal short of generating one
  from the other, which is what the import already does.
