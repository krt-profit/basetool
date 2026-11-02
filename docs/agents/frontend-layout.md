# Frontend package, template and asset layout

Read this before adding or moving a frontend class, template, script or stylesheet.

## Package layout (REQ-FE-032, plan §5.9)

The Java code is packaged by domain: a new controller, view row or view helper goes into
`frontend.<domain>.web`, a DTO mirror, form or view model into `frontend.<domain>.model`, the
domain's backend calls into `frontend.<domain>.client`. Cross-cutting code goes into the kernel
package of its concern — `kernel.backend` (the backend seam, relays, caches), `kernel.security`,
`kernel.session`, `kernel.layout`, `kernel.web` (error handling, binding, request contexts, template
beans), `kernel.livesync`, `kernel.observability`, `kernel.model` (shared types and the reference
DTOs several domains share). Nothing else exists: `DomainPackageLayoutTest` fails on a main class
outside these packages.

- **The kernel takes nothing domain-specific and reaches into no domain.** Its remaining reaches
  are frozen in `KernelDomainReachTest` and may only shrink: a new one fails the build. Invert it
  instead — move the shared type into `kernel.model`, or let the domain supply the value.
- **Every DTO of the backend seam carries `@DtoMirror`** (`frontend.kernel.model.DtoMirror`): the DTO
  contract tests find mirrors by it, not by package. A `*Dto`, `*Request` or `*Response` type in a
  model package without it fails the build; anything else without it is invisible to those tests.
- **A flashed form or DTO keeps its exact name in `SessionTypeAllowList.SESSION_BOUND_TYPES`**:
  moving or renaming one changes the name its session values carry, so the entry moves in the same
  change (`SessionBoundTypeClosureTest`), and sessions written before the release drop that one
  flash attribute once (`docs/deployment.md` → *The per-domain package move*).
- Bean names are the simple class names or explicit (`moneyFormat`, `relativeDays`, `handles`,
  `markdown`). Templates name one class by its package, `T(…kernel.security.Roles)`;
  `TemplateTypeReferenceTest` fails when it moves without them.

## Template and asset layout (REQ-FE-034, plan §5.9)

The folder is the domain, with the same names as the Java packages. A page template is
`templates/<domain>/<name>.html` (view name `<domain>/<name>`; the old `admin/` and `fragments/`
sub-folders stay below the domain), a script `static/js/<domain>/<name>.js` (the domain it is listed
under in `typecheck-ratchet.json`), an area stylesheet `static/css/<domain>/<name>.css` and a page
stylesheet `static/css/pages/<domain>/<page>.css`. What no single domain owns lives in `kernel/`:
the layout and chrome fragments, the dialog wrapper, the components, the transport and live-sync
scripts, `kernel/styles.css`. The legal pages, the licences page, the SC links page and the error
pages' stylesheets are the `shell` domain's.

- **Nothing sits flat.** `AssetLayoutTest` fails on a template, script or stylesheet outside a
  domain folder; only the five error templates Spring Boot resolves by name stay at
  `templates/error*`. A new domain folder is a new frontend package first.
- **Every link resolves.** `AssetReferenceResolutionTest` resolves each `th:src` / `th:href` to a
  file and fails on a script or stylesheet nothing links; the content-hashed URLs work for any
  sub-folder, so a move needs no change to the asset serving.
- **A moved file is a `git mv` plus a rewrite of its references** (view names in controllers and
  tests, `th:replace` paths, asset links, `inputs.file` paths in `build.gradle.kts`, the ESLint
  per-file settings), never a copy. The served URL changes with the folder; the content hash does not
  depend on it.
- A stylesheet another domain's page also links stays in its owner's folder (REQ-FE-034 lists them).
  A page that needs a domain's script from another domain is a design question, not a copy.
