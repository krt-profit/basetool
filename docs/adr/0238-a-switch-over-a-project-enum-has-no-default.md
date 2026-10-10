# ADR-0238 — A switch over a project enum has no `default`

- **Status:** Accepted — implemented 2026-10-10 for every existing switch (plan §8.1)
- **Date:** 2026-10-01
- **Deciders:** @greluc (owner decision D-15)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §8.1 ·
  [ADR-0223](0223-only-final-java-features-and-no-preview-flags.md) (final features only) ·
  [ADR-0235](0235-errors-are-a-sealed-kernel-of-kinds-and-per-module-problem-codes.md) ·
  `config/checkstyle/google_checks.xml` (`MissingSwitchDefault`) · `CLAUDE.md` → *Java conventions*

## Context

Checkstyle's `MissingSwitchDefault` requires a `default` in every switch **statement**. The audit
of 2026-09-29 found 20 such statements; on nine of them every enum constant is already covered, so
the forced `default` hides the next constant: a new `BankAccountType`, `OrgUnitKind` or
`SelectorKind` would ship as a 400, an exception or a silent no-op instead of failing the build at
every decision site.

javac 25 checks a switch **expression**, and a switch statement that uses `case null` or a pattern
label, for exhaustiveness: a missing enum constant is a compile error. Checkstyle 14.3.0's
`MissingSwitchDefault` does not ask for a `default` in either form (probed during the audit).

## Decision

1. **A switch over a project enum carries no `default`**, unless it handles a deliberate subset of
   the constants; then the `default` is the decision for the rest and says so in what it does.
2. **An exhaustive switch is written so the compiler checks it**: as a switch expression, or — where
   a statement reads better — as a statement with a `case null ->` arm, which makes it an enhanced
   switch javac requires to be exhaustive. The `case null` arm keeps the site's behaviour for a
   `null` selector (today a `NullPointerException`) unless the change decides otherwise.
3. **Behaviour on a project enum** is written as `switch (this)` without `default` inside the enum,
   with a test over `values()` per predicate (precedent: `OperationStatus.canTransitionTo`).
4. **A switch over a sealed type** names every permitted subtype instead of a `default`, within one
   package (ADR-0235).
5. Switches over enums the project does not own (JDK, Spring, Jackson) keep their `default`, because
   a new constant there arrives with a dependency bump the project does not control.
6. The rule is a line in the root `CLAUDE.md` Java conventions; `MissingSwitchDefault` stays on for
   plain switch statements.

## Consequences

- Adding a constant to a project enum fails the build at every exhaustive site, which is the point;
  the sites with a deliberate `default` are the ones a reviewer has to read.
- `case null ->` is an unfamiliar idiom; the switch-expression form is preferred where the switch
  produces a value.
- Existing switches move when their file is touched; the conversion changes no behaviour.

## Implementation (2026-10-10)

The 51 `default` arms in `main` were classified. Nine sat on switches that already named every
constant of a project enum (`BankAccountType`, `BankAccountViewGranteeKind`,
`BlueprintImportStatus`, three over `InventoryAllocationDimension`, `SelectorKind`, `OrgUnitKind`,
`MembershipDeltaRequest.Action`); their `default` became `case null -> throw new
NullPointerException(…)`, which keeps the former behaviour for a `null` selector and makes javac
check the switch. The deliberate subsets keep their `default`: the deposit/withdrawal/transfer
labels of the two bank statement reports over `BankTransactionType`, the two org-chart switches over
`MembershipRole` and `OrgChartPositionType`, and the squadron-rank check over `MembershipRole`.
The exchange switches follow with the exchange vocabulary (plan §8.1, sealed types). Every other
`default` switches over a string, an `int`, a JDK or Spring type. Decision 3 starts with
`OrgUnitKind.isTenantUnit()` (three `==` chains) and `FinanceType.signed(BigDecimal)` (two payout
totals; a third site became an exhaustive switch with `case null -> {}`, the former skip of an
untyped entry), each tested over `values()`; single-constant comparisons stay as they are.

## Alternatives considered

- **Keep the forced `default`** — the default branch silently absorbs every new constant.
- **Switch `MissingSwitchDefault` off** — plain statements over foreign enums and strings would lose
  their `default` without any compiler check taking its place.
- **A `default -> throw new IllegalStateException()`** — fails at run time, on the first request
  that reaches the new constant, instead of at compile time.
