# ADR-0233 — Module boundaries are enforced by ArchUnit and by Spring Modulith in test scope

- **Status:** Accepted — implementation pending (plan Phase 0 steps 0.2 and 0.3)
- **Date:** 2026-09-29
- **Deciders:** @greluc (owner decision D-02)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §5.7, §6, §10 ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) ·
  [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md) ·
  [ADR-0047](0047-backend-package-acyclic-dependencies.md) (amended) ·
  [ADR-0208](0208-gradle-verifies-every-dependency-against-a-committed-sha-256.md) ·
  `ArchitectureTest` · arc42 §8.14

## Context

A module boundary that nothing checks erodes within a few pull requests. Two tools fit the
project. ArchUnit 1.5.1 is already in the catalog and carries the backend's 43 security and
structure rules; its `modules()` API and `FreezingArchRule` can express module dependencies and a
baseline of today's violations. Spring Modulith 2.1.1 (released 2026-08-26 on Boot 4.1.1 and
Framework 7.0.9) verifies module boundaries, named interfaces and allowed dependencies, generates
module documentation and runs module-scoped tests; its core is compiled against ArchUnit 1.4.2.

Three facts shape the choice. Many of today's ArchUnit rules select their subjects by layer
package, simple-name prefix or FQCN string: after a move 30 to 33 of the 43 can pass without
checking anything — ArchUnit fails a rule whose selection is empty, but not one whose target string
no longer resolves. A large share of the coupling lives in strings no class-level tool sees (SpEL
bean references, spliced JPQL scope fragments, Thymeleaf `T(…)`, native SQL, path-keyed security
lists). And the module cut starts with 150 violating class edges, which cannot all be fixed before
the gate is switched on.

## Decision

We will enforce the module rules with **both tools, each where it is strong**, plus targeted tests
for what neither sees.

1. **ArchUnit keeps the security and structure rules**, re-keyed so that a move cannot disarm them
   (plan guard G-01): subjects selected by role (`@RestController`, `@Service`, Spring Data
   `Repository`, `@Mapper`, `@Entity`) instead of layer packages, targets named by class literal
   instead of FQCN strings, a selection floor per rule equal to today's count instead of
   `allowEmptyShould(true)`, and a meta-test that every remaining FQCN string resolves.
2. **ArchUnit carries the frozen module baseline** (G-09): a `modules()` rule over the checked-in
   domain map, wrapped in `FreezingArchRule`. Today's violations are recorded in a committed store
   that **may only shrink**; a new violation fails the build, and a fixed one leaves the store in the
   same pull request. Rules "every class belongs to exactly one module" and "simple names stay
   unique" sit beside it.
3. **Security rules are never frozen.** A rule that guards authorization, tenancy, redaction, the
   bank seam, the exchange's reduced authority or audit writes either passes on today's code or
   lists each exception in a named, reviewed list in the test with a reason per entry.
4. **Spring Modulith 2.1.1 runs in test scope only**: `ApplicationModules.verify()` in a
   `ModularityTest`, `explicitly-annotated` detection, `@ApplicationModule(allowedDependencies = …)`
   per module `package-info`, `@NamedInterface` for each `api` package, the `Documenter` output for
   arc42 §5, and module-scoped integration tests where they pay off. It becomes a gate only after a
   spike proves `verify()` under ArchUnit 1.5.1. No module is declared `OPEN`, except a legacy layer
   package during the transition.
5. **Spring Modulith's event publication registry is not adopted** (ADR-0232 keeps the after-commit
   families without an outbox). Should one ever be needed: `events-jdbc` plus `events-jackson`
   without a starter, the schema by Flyway, id-only payloads, idempotent listeners.
6. **Targeted tests cover the strings** (plan §6.1): SpEL bean references (G-04), the authorization
   matrix (G-02), path-keyed controls (G-07), table ownership and triggers (G-10), the GDPR
   registries (G-11), observer and listener rules (G-12), template and session references (G-15,
   G-16). They are part of the gate, not an extra.
7. **Every new or re-keyed rule is proven able to fail once** — a planted violating fixture or a
   negative test of the rule's own logic — before it guards a move.

## Consequences

- An incremental start: the gate is on from Phase 0 and the 150 legacy edges shrink pull request by
  pull request, measured by the store.
- Nothing is added to an image: Modulith and its ArchUnit dependency stay on the test classpath, and
  their artifacts pass the dependency-verification metadata (ADR-0208) and the licence gate.
- A version gap to watch: Modulith's core is compiled against ArchUnit 1.4.2 while the project pins
  1.5.1; the spike decides whether `verify()` is a gate or only documentation.
- Two tools state overlapping dependency rules; the domain map is the one source both derive from.

## Alternatives considered

- **ArchUnit alone** — no named-interface verification, no module documentation, no module-scoped
  test slices.
- **Spring Modulith alone** — it has no security rules, and its verification cannot freeze a
  baseline, so it could not be switched on before the 150 edges are gone.
- **jMolecules** — a third vocabulary for rules ArchUnit and Modulith already state.
- **The Modulith event publication registry now** — its starters put ArchUnit and Modulith core on
  the production classpath, it creates its JDBC schema by default (Flyway owns the schema), stores
  payloads in plain text with the class name, so a moved event class makes incomplete publications
  disappear with only a warning, and its listeners run after commit, where audit can never go.
- **Freezing everything, security rules included** — a frozen security violation is an accepted
  hole that nobody re-reads.
