# Git, commits and pull requests

The full Git rules. [`AGENTS.md`](../../AGENTS.md) carries the summary; this file is the reference.
The workflow traps that cost real CI rounds are in [`pitfalls.md`](pitfalls.md).

## Destructive commands

Do not run destructive Git commands without explicit user instruction: `git reset --hard`, `git clean -fd`, `git push --force[-with-lease]`, `git rebase` on shared/remote branches, `git branch -D`, `git tag -d`, `git stash drop`, or anything that rewrites/discards commits or remote history. Read-only and additive operations (`status`, `log`, `diff`, `add`, `commit`, non-force `push`) are fine when the task needs them.

## Worktrees and temporary files

**Worktrees and temporary files live outside the checkout**, and on the owner's workstation every
agent keeps them in a folder of its own (`<agent>` = `claude`, `codex`, …):

- **Worktrees:** `D:\Coding\Agents\Worktrees\<agent>\basetool\<name>` and nowhere else — never
  inside the checkout (no `.claude/worktrees`, no `.worktrees`, which also slow `spotlessApply`),
  never next to it.
- **Temporary files:** `D:\Coding\Agents\Temp\<agent>\<task>`, one folder per piece of work, for
  scratch scripts, intermediate output, a throwaway `GRADLE_USER_HOME`, a `git archive` extract,
  commit-message and PR-body files. Not `%TEMP%`, `/tmp` or the repository.
- **Clean up:** remove the task folder when the work is done, and a worktree once its branch is
  merged or abandoned. Never touch another agent's folder.

A tool default that points elsewhere does not change this; pass the path explicitly.

## Before every push

**Before every `push`, run `./gradlew spotlessApply` (whole repo) — no exceptions, even for a one-line edit.** Spotless is gate-enforced in CI; a narrower local check (`compileE2eJava`, `checkstyleMain`, …) does not cover every source set and will let a formatting violation reach CI. The full checklist is [`lint-and-push.md`](lint-and-push.md).

## Identity and DCO sign-off

**Every commit MUST carry a DCO `Signed-off-by:` trailer — always use `git commit -s` (or `-S -s` when GPG-signing).** No exceptions, including AI-generated commits. **The signing identity is `Lucas Greuloch (greluc) <lucas.greuloch@gmail.com>` — that address, not any other, belongs in the `Signed-off-by:` trailer.** The owner uses this one address for both the git identity and public contact (`SECURITY.md`, `CODE_OF_CONDUCT.md`, package metadata); the owner's former second (Proton, `pm.me`) address was retired on 2026-09-11 and must not appear anywhere — a trailer carrying it fails the DCO gate, and a file or template that still names it is stale and gets fixed. Some tooling may still report the retired address as the account address; that does not make it current. Never hand-write the trailer at all: `-s` derives it from `git config user.name` / `user.email`, which is already correct — typing it out by hand is exactly how the wrong address gets in. The trailer's `Name <email>` must match the commit's author identity case-insensitively on the email; the `dco /` step of [`.github/workflows/commit-gates.yml`](../../.github/workflows/commit-gates.yml) rejects any PR commit lacking a matching sign-off. Bot exemptions (Dependabot / Renovate / GitHub Actions) do NOT apply to commits authored under a real user identity, even if an agent generated the body. If you forget the `-s` flag and the commit is still local (not yet pushed), fix it before pushing: `git commit --amend --signoff --no-edit` for the last commit, `git rebase --signoff main` for the whole branch. For already-pushed commits, ask the user before force-pushing the rewrite — `git push --force-with-lease` falls under the destructive-ops rule above. Full policy and the DCO 1.1 text: [`CONTRIBUTING.md → Developer Certificate of Origin (DCO) sign-off`](../../CONTRIBUTING.md#developer-certificate-of-origin-dco-sign-off).

## Commit messages go through a file

**Every commit message is written to a file and passed with `git commit -S -s -F <file>`**, the
short ones included; PR and issue bodies go through `gh … --body-file <file>`. No heredoc and no
`-m "…"` for content: shells on Windows mangle heredocs (a doubled backslash arrives single, a long
one dies with nothing written), and backticks or `$` inside a double-quoted `-m` run as command
substitution and silently delete words. The file lives with the other temporary files, outside the
checkout. After writing through a shell anyway, verify the result (`git log -1 --format=%B`).

The last paragraph of a message must consist of trailers only (`Key: value` lines): a bare line
such as `Refs ADR-0123` in that block strands the trailers above it.

## AI attribution: `Co-Authored-By`

**Every commit an AI agent authors MUST include a `Co-Authored-By:` trailer naming the agent and its model — no exceptions, no inconsistency.** This is a transparency requirement that is independent of the DCO sign-off: `Signed-off-by:` attests the human contributor's legal grant; `Co-Authored-By:` discloses AI involvement so reviewers, auditors, and future archaeologists can see which commits had AI in the loop. Use the form of your agent, replacing `<Model Name>` with the human-readable model identifier the session runs on:

```text
Co-Authored-By: Claude <Model Name> <noreply@anthropic.com>
Co-Authored-By: Codex <Model Name> <noreply@openai.com>
```

The placeholder is not a literal value: the rule is "name the model that actually wrote the commit" (e.g. `Claude Sonnet 4.6`, `Claude Haiku 4.5`, or the model a Codex session reports), never a model copied from an example or an earlier commit. The address stays the vendor's no-reply address regardless of model; an agent from another vendor uses that vendor's name and no-reply address in the same form. Apply this rule to **every** commit an agent composes the message for or makes substantive edits to, including one-line typo fixes, changelog-only commits, and commits where the diff originated from a verbatim user instruction. The bar is "an agent touched this commit", not "an agent wrote most of the diff". When two agents worked on one commit, each adds its own trailer. If an agent is only running `git` commands the user typed and not authoring anything, the trailer is not required — but when in doubt, include it. Forgetting the trailer is fixed the same way as forgetting `-s`: `git commit --amend --no-edit` (after appending the trailer to the message) for the last commit, locally only, before pushing.

## Language

**Always write Git, GitHub, and in-code prose in English — no exceptions.** This covers every piece of text you author for Git or GitHub, regardless of the language the user speaks to you in: commit messages, branch names, tag names and tag messages, PR titles, PR descriptions/bodies, PR review comments and replies, issue titles and bodies, issue comments, GitHub Discussions posts, release notes, and any inline comment you write on someone else's behalf via `gh`. It **equally** covers all prose inside the code itself — Javadoc, inline `//` and block comments, developer-facing log messages, and any other comment or annotation text in source files. If the user prompts you in German (or any other language), translate the substance into English before committing, posting, or writing it into the code. There are exactly two carve-outs: (1) verbatim quoting of existing non-English content (e.g. quoting a user-reported error message in an issue) — the surrounding prose you author stays English; and (2) the **Basetool wiki** (the `basetool.wiki` git repo), which is authored in German by design and is the single exception to the English-on-GitHub rule. This rule does **not** change the i18n contract: user-visible UI strings still live in the DE + EN `messages*.properties` bundles — "code in English" governs developer-facing prose, not the localized end-user text.

## Pull requests

**Every PR you open MUST be assigned and labelled.** Set the assignee to the repo owner: `gh pr create --assignee greluc ...` (`@me` resolves to the same account, since `gh` runs under it). Then apply the labels that match the PR's content, picking **only** from labels that already exist (`gh label list` — `gh` errors on an unknown label, so never invent one inline). Map the Conventional-Commit type of the change to a label: `feat` → `enhancement`, `fix` → `bug`, `docs` → `documentation`; and add one or more UPPERCASE functional-area labels drawn from the `REQ-<AREA>-*` vocabulary (e.g. `BANK`, `NOTIF`, `MISSION`, `ORG`, `SEC`, `DATA`, `UI`, `BE`, `FE`) when one exists and fits. Apply the **`e2e`** label whenever the change touches end-to-end-relevant surface (frontend flows, auth/session, controllers, migrations) — CI gates the full Playwright suite on that label in [`e2e.yml`](../../.github/workflows/e2e.yml), so without it the E2E run is skipped on the PR; a PR that touches the frozen exchange path fails `e2e.yml`'s `Exchange changes carry the e2e label` job without it (REQ-XCH-036). Do **not** hand-apply the bot-owned labels `dependencies`, `github-actions`, `docker`, `automated`, or `release` — Dependabot, the `refreshVersions` job, and the release workflow manage those.
