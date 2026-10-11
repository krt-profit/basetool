# ADR-0059 — Thymeleaf `sec:authorize` role literals via the SpEL `T()` type operator

- **Status:** Accepted (owner-approved)
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-SEC-002a ([`docs/specs/security-and-access.md`](../specs/security-and-access.md)) ·
  issue #909 · [ADR-0047](0047-backend-package-acyclic-dependencies.md)

## Context

S3 (#909, PR #931) centralised every role/permission string literal into `support.Roles` /
`support.Permissions` (backend) and `frontend.kernel.security.Roles`, migrating all 266 literal-role
`@PreAuthorize` sites via compile-time-constant string splicing. Two call-site shapes were
explicitly left out of that PR as "materially different, larger changes":

1. Programmatic `hasReachableRole("ROLE_X")` / raw `GrantedAuthority` string comparisons outside
   `@PreAuthorize` (a handful of backend services and frontend controllers/filters).
2. Thymeleaf `sec:authorize="hasRole('X')"` template attributes (134 occurrences across 27
   templates at the time of this ADR) — the PR body reasoned that, because these are HTML strings
   evaluated by the Spring Security Thymeleaf dialect at render time, javac never sees them, so no
   `Roles` constant reference seemed possible without introducing a new Thymeleaf
   expression-utility object bound into every request's model.

Item 1 turned out to be a straightforward mechanical migration once scoped (same shape as the
`@PreAuthorize` sweep, just without the annotation-constant-folding constraint) and needed no
design decision. Item 2 is the one that needed this ADR: a genuine architectural question of how
(or whether) to reference a Java constant from a Thymeleaf attribute value.

## Decision

**Use Spring Expression Language's `T()` type-reference operator directly in the `sec:authorize`
attribute value**, e.g.:

```html
<div sec:authorize="hasRole(T(de.greluc.krt.profit.basetool.frontend.kernel.security.Roles).ADMIN)">
```

instead of the previous:

```html
<div sec:authorize="hasRole('ADMIN')">
```

This required **no new production component**. `sec:authorize` (via
`thymeleaf-extras-springsecurity6`) is evaluated through the same `SecurityExpressionHandler` /
`SpelExpressionParser` path as `@PreAuthorize`, and — traced directly in the
`spring-security-web` 7.1.0 sources — `DefaultHttpSecurityExpressionHandler.createEvaluationContext()`
builds a `StandardEvaluationContext`, not a restricted `SimpleEvaluationContext`. `T()` is a core,
unrestricted SpEL feature on `StandardEvaluationContext`; nothing in
`AuthorizeAttrProcessor`/`AuthUtils`/`MvcAuthUtils` sanitises or blocklists the expression string
on the (non-WebFlux) MVC path this application uses. The mechanism was verified by tracing the
actual dialect source in the resolved dependency version before the mechanical sweep, not assumed
from general SpEL documentation.

All 100 role-bearing `sec:authorize` occurrences (of 134 total; the remainder are
`isAuthenticated()` / `isAnonymous()`, which carry no role literal) were migrated with an
exact-string substitution over the closed set of 13 distinct `hasRole(...)`/`hasAnyRole(...)`
attribute values found in the templates — no template was touched beyond that one attribute.

## Consequences

- **Positive:** a renamed/typo'd role code is now a `Roles.java` compile error surfaced at
  `frontend:compileJava` time for template code too, closing the last literal-role gap the S3
  epic identified. No new Spring bean, Thymeleaf dialect extension, or model attribute was
  introduced — the fix is exactly as deep as the problem, not a bandaid layered on top of the
  existing rendering pipeline.
- **Negative / trade-offs:** the fully-qualified class name is verbose inline
  (`T(de.greluc.krt.profit.basetool.frontend.kernel.security.Roles).ADMIN` vs. the previous `'ADMIN'`),
  and `StandardEvaluationContext`'s `T()` operator is deliberately unrestricted — a template author
  could in principle reference an arbitrary type. This is an accepted trade-off: the same
  unrestricted evaluation context already backs every `@PreAuthorize` expression in the codebase,
  templates are committed source (not user input), and Checkstyle/code review remain the guard
  against misuse, same as for any other SpEL expression already in the codebase.
- **Rejected — a bound Thymeleaf expression object** (e.g. `${#roles.ADMIN}` via a custom
  `IExpressionObjectFactory`/dialect, or a `@ModelAttribute`-injected holder): this was the
  approach the original PR body anticipated as necessary, but it requires a new production
  component (a dialect registration or a global model attribute wired into every request) for no
  behavioural benefit over `T()`, which needs zero new code. Revisit only if the `T()` call sites
  become a genuine readability problem at a much larger scale.

## Follow-up applied

REQ-SEC-002a is amended to record both previously-deferred items (programmatic authority-string
comparisons and the Thymeleaf template literals) as done, referencing this ADR for the Thymeleaf
mechanism.
