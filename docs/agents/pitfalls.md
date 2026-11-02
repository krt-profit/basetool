# Workflow pitfalls

Traps in this repository's workflow that each cost real CI rounds or broken work. Every entry says
what goes wrong and what to do instead. Read it before you push or open a pull request.

## Numbers: ADR, REQ and Flyway are claimed at push time

ADR numbers, `REQ-<AREA>-NNN` ids and Flyway `V<n>` versions are shared counters, and several
agents and humans work on the repository at once. A number that was free when the work started is
often taken by the time it is pushed.

- Before you push, fetch and re-grep **`origin/main` and every open pull request** for the number:
  `git fetch origin`, then `git ls-tree -r --name-only origin/<branch> -- docs/adr` (or
  `git grep REQ-OPS-0NN origin/<branch> -- docs/specs`) for each open branch from
  `gh pr list --state open`.
- Claim the next number free in **all** of them, and rename the file and every reference in the
  same commit. Keep the Flyway order: a migration below the highest applied version fails.

## The ADR numbering gate can skip its base check without saying so

`scripts/check-adr-numbering.sh` compares the working tree against `ADR_BASE_REF`. When that
variable is unset or does not resolve, it prints only a `::notice`, skips the comparison with the
base branch and passes. The workflow sets it; a local run usually does not (a PowerShell
`$env:ADR_BASE_REF` does not always reach the Git Bash that runs the script, and Git Bash's `git`
may not resolve the refs of a worktree at all, which ends in the same notice). A local green run
therefore proves only that the working tree has no duplicate, not that `main` or an open pull
request has not taken the number. Check the number by hand as above.

## Gradle piped into `tail` hides a failed build

`./gradlew … | tail -n 50` exits with `tail`'s status, which is 0. A `BUILD FAILED` at the top of
the cut-off output is lost and the step looks green. Read the whole log, or search it for
`BUILD SUCCESSFUL` / `BUILD FAILED` before you trust the exit code.

## Never `gradlew --stop` next to other agents

`./gradlew --stop` stops **every** daemon of that Gradle user home, including the builds of other
agents working in parallel worktrees, which then fail for no reason of their own. Do not run it
while others may be building. For a long run, give your build its own `GRADLE_USER_HOME` (outside
the checkout); when you abort a build of your own, stop only that daemon.

## Check the pull request's state before every push

The owner merges pull requests while agents are still working. A push to a branch whose pull
request was merged and deleted recreates the branch (`[new branch]` in the push output is the tell)
and leaves the commit orphaned. Before pushing to an existing branch, run
`gh pr view <n> --json state,mergedAt`; when it is merged, branch afresh from `origin/main`.

## A runbook-only edit leaves `:backend:test` UP-TO-DATE

A test that reads a file outside its module (a runbook under `docs/`, a spec, a script) sees it
only when the file is a declared input of the test task. After a change to such a file alone,
`:backend:test` reports UP-TO-DATE and the guard that reads it never runs — a green build that
checked nothing. When you change only a file a test reads, run that test with `--rerun`
(`./gradlew :backend:test --tests '<TestClass>' --rerun`).

## The exchange docs gate runs before every push

`:frontend:checkExchangeDocs` (no German text, every relative link resolving, markdownlint with
100-character lines, the DPoP reference tests; REQ-XCH-002) is a separate CI workflow, and several
pull requests failed it after a green local build. Run it before every push that touches
`docs/exchange/` or the exchange code, and again after resolving a merge conflict there: a long line
from a conflict fix blocks the Pages deployment.
