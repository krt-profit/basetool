# ADR-0165 — The frontend's layout model is opt-in, not every-handler

- **Status:** Proposed
- **Date:** 2026-09-13
- **Deciders:** @greluc (pending)
- **Related:** specs `REQ-FE-020` (new), `REQ-FE-001`…`REQ-FE-010` (the surface that pays for this) ·
  [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) ·
  [ADR-0012](0012-frontend-krtfetch-json-mutations-csrf-retry.md) (krtFetch is the interaction model) ·
  [ADR-0159](0159-the-basetool-has-no-anonymous-or-guest-surface.md) (the advices' `isAuthenticated()` guards) ·
  PR #1257 (the split into three layout advices) · PR #1870 (found the symptom)

## Context

Every Thymeleaf page in the `frontend` module is assembled from a shared layout model — the
org-unit context, the capability flags, the app title, the unread notification count, the CSRF
metas, the app version. Six `@ControllerAdvice` beans in `frontend/config` contribute it.

None of them declared a selector: no `annotations`, no `basePackages`, no `assignableTypes`. A
`@ControllerAdvice` without one applies to **every** handler in the application, and Spring's
`ModelFactory.initModel` runs ahead of the handler regardless of whether that handler returns
`@ResponseBody`. So the module's 17 `@RestController`s — `/csrf`, and the 16 JSON proxies behind
`krtFetch` — built the full layout model on every request and then discarded it, because Jackson
serialises a return value and never reads a model attribute.

Three of the six reach the backend to do it, and the guards they carry are on
`FrontendAuthHelperService.isAuthenticated()`, not on the kind of handler — so the price was paid
on exactly the requests that are authenticated, which is all of them on the member surface.
Measured on 2026-09-13 by asserting against the mocked `BackendApiClient` in a `@SpringBootTest`,
a single authenticated `GET /csrf` fired **five** backend reads before the controller's own work:

|                     Read                     |         Advice          |
|----------------------------------------------|-------------------------|
| `/api/v1/me/capabilities`                    | `CapabilityFlagsAdvice` |
| `/api/v1/notifications/unread-count`         | `LayoutMiscAdvice`      |
| `/api/v1/me/active-org-unit`                 | `OrgUnitContextAdvice`  |
| `/api/v1/me/org-units`                       | `OrgUnitContextAdvice`  |
| `CachedCatalog.SQUADRONS` (cached page-walk) | `OrgUnitContextAdvice`  |

Four of the five are uncached. In-place mutation through `krtFetch` is the app's primary
interaction model (`REQ-FE-001`…`REQ-FE-010`), so this sat on the hot path rather than at an edge.

The mechanism was diagnosed while fixing a symptom of it. PR #1870 found that
`crossorigin="use-credentials"` on the web app manifest's `<link>` made a fetch of a *public*
document authenticated, which cost those five calls per fetch. That fetch was made anonymous, which
fixed the manifest and left the mechanism untouched.

## Decision

**The layout model becomes opt-in.** A new marker annotation,
`de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel`, is applied alongside `@Controller`
on all 61 view controllers, and the five layout advices declare
`@ControllerAdvice(annotations = UsesLayoutModel.class)`.

`GlobalBindingAdvice` — the sixth — **stays unscoped, deliberately.** It contributes no model
attribute; it registers `NormalizedStringEditor` on the `WebDataBinder`, and the REST controllers
genuinely depend on it. Spring converts `@RequestParam` and `@PathVariable` values of type `String`
through `WebDataBinder.convertIfNecessary`, so that editor trims and length-caps the search terms of
`BankProxyController` and `UserProxyController`, the `handoff` of
`PersonalBlueprintImportProxyController`, the `domain` of `AuditReportProxyController` and the
`roleCode` of `OrgUnitBankProxyController`. Narrowing it would have been a silent validation change
wearing a performance change's clothes. (`@RequestBody` payloads are unaffected either way — Jackson
deserialises those and never consults the binder.)

The split is pinned from both sides. `ArchitectureTest` asserts that every `@Controller` carries the
marker and that no `@RestController` does; `LayoutModelScopeMvcTest` asserts that an authenticated
`GET /csrf` makes none of the five backend reads above.

## Alternatives rejected

- **`@ControllerAdvice(annotations = Controller.class)`.** The obvious form, and it selects the
  opposite of what is wanted: `@RestController` is meta-annotated with `@Controller`, and Spring
  matches with `AnnotationUtils.findAnnotation`, which searches meta-annotations. Every REST
  controller would still match.
- **A `basePackages` predicate**, with the proxies moved to a sibling `controller.api` package.
  Spring matches base packages with `controllerType.getName().startsWith(basePackage)`
  (`HandlerTypePredicate#test`), so a package *under* `…frontend.controller` still matches its
  parent. It could only work by moving the 61 view controllers instead of the 17 proxies, which is
  the larger move and buys nothing the marker does not.
- **`assignableTypes`.** The view controllers share no supertype, so this degenerates into a
  61-entry class literal list maintained by hand — the same coupling as the marker with none of its
  locality, and it fails open when someone forgets.
- **Meta-annotating the marker with `@Controller` and replacing the stereotype.** Tidier at each
  call site, and it would have quietly holed a security test: `ArchitectureTest`'s `@PreAuthorize`
  gate matches with ArchUnit's `isAnnotatedWith`, which is *not* meta-annotation-aware, so all 61
  controllers would have dropped out of the rule that checks they carry an authorization gate.
  Applying the marker alongside `@Controller` keeps that rule seeing everything.
- **A runtime guard inside each advice**, reading the `HandlerMethod` off the request attributes and
  returning early for `@ResponseBody` handlers. Six lines instead of 62 files, but it keeps the
  advices wired to every handler, replaces a declarative selector Spring already offers with an
  implicit one, and leaves the cost one forgotten guard away from returning.

## Consequences

- A `@RestController` in this module now costs what it looks like it costs. `BackendRoleSyncFilter`'s
  `/api/v1/users/me` read remains — it is a separate concern with its own throttle — so the honest
  claim is five backend reads removed from each authenticated JSON request, not "the request is now
  free".
- **Opt-in is the new default, and that is the point.** A controller added without the marker gets no
  layout model and costs nothing, which is right for the JSON surface. A *page* controller added
  without it renders without chrome — a failure that is loud in the browser but silent at compile
  time, which is why the ArchUnit rule exists.
- The gain does not extend to the 38 `@Controller` classes that mix view handlers with
  `@ResponseBody` ones. `@ControllerAdvice` selects per controller *type*, not per handler method, so
  their JSON handlers keep building the layout model their view handlers need. Splitting those is a
  larger refactor and is not attempted here.
- PR #1870's manifest controller inherits the fix on merge without a conflict: it is a
  `@RestController`, so it simply never opts in.
