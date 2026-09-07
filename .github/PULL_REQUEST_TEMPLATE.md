<!--
Thanks for the PR. Please fill in the required sections below and work
through the checklist as far as it applies. Sections marked "(if affected)"
are optional — the reviewer will check them against the code.

If this is a draft PR: mark it as Draft and prefix the title with "WIP".
-->

## What's this about?

<!--
1-3 sentences: what does this PR change and WHY (not WHAT — the diff shows
that). Link related issues (e.g. `closes #123`, `refs #456`).
-->

## Type of Change

- [ ] Bug fix (non-breaking, fixes a specific problem)
- [ ] Feature (non-breaking, adds functionality)
- [ ] Breaking change (existing API/behavior changes — new API version or migration required)
- [ ] Refactor / tech debt (no user-visible change)
- [ ] Documentation
- [ ] Build / CI / Dependencies
- [ ] Test coverage / test infrastructure

## Affected Modules

- [ ] `backend`
- [ ] `frontend`
- [ ] Both
- [ ] Other (build / Compose / docs / workflows)

## How was this tested?

<!--
Be concrete: what was verified? Which profiles, which stack variant
(Gradle bootRun, dev Compose, test stack with `.env.test`)? For UI changes:
which device classes (Smartphone / Tablet / Desktop / Ultra-wide) and
which browsers? If UI tests were not possible, say so EXPLICITLY —
don't use "tests pass" as a stand-in.
-->
- 

## Checklist

### General

- [ ] PR title follows Conventional Commits (`feat(...)`, `fix(...)`, `chore(...)`, `docs(...)`, `refactor(...)`, `test(...)`).
- [ ] Branch is up to date with `main` (rebased or merged, no stale diff).
- [ ] `CHANGELOG.md` has been updated under `## [Unreleased]` (correct category: Added / Changed / Fixed / Removed / Security).
- [ ] Related issues are linked (`closes #...`, `refs #...`).
- [ ] No real secrets, tokens, passwords, keystores, or personal data in the diff.
- [ ] I have signed the [Contributor License Agreement](../CLA.md) (one-time; see [`docs/cla-signatures.md`](../docs/cla-signatures.md) or the CLA-Assistant flow).
- [ ] **Every** commit in this PR carries a `Signed-off-by:` trailer (DCO via `git commit -s`; see [CONTRIBUTING.md](../CONTRIBUTING.md#developer-certificate-of-origin-dco-sign-off)).

### Code Quality

- [ ] `./gradlew check` passes locally (Checkstyle, SpotBugs, tests).
- [ ] All Checkstyle and SpotBugs findings in the **changed code** are fixed — no new warnings on top.
- [ ] No `@SuppressWarnings` / `@SuppressFBWarnings` / Checkstyle suppressions without a one-line comment that explains the justification for this specific call site.
- [ ] Constructor injection via Lombok `@RequiredArgsConstructor`, no field `@Autowired`.
- [ ] Loggers exclusively via `@Slf4j`, not instantiated manually.
- [ ] Records for DTOs and immutable config wrappers, no POJO boilerplate.
- [ ] Javadoc on every new/changed class, interface, enum, record, and public/protected method — concrete and code-specific, no generic "Returns the value".

### Tests

- [ ] New features / fixes have tests (naming: `*Test`, Given/When/Then structure).
- [ ] Tests run **exclusively** via `./gradlew test` — no IDE test runner.
- [ ] For concurrency/locking changes: optimistic-lock paths tested, `*WithinTransaction` pattern respected (see CLAUDE.md > Concurrency).
- [ ] No production credentials in tests or in locally spun-up test stacks (use `.env.test` + throwaway keystore + stripped realm).

### API & Database (if affected)

- [ ] New REST endpoints under `/api/v1/...`; breaking changes produce `/api/v2/...` plus `@ApiDeprecation(sunset=..., replacement=...)` on the old endpoint.
- [ ] Every new/changed `@RestController` endpoint carries `@PreAuthorize` and complete SpringDoc annotations (`@Operation` with summary/description, `@ApiResponses` with domain descriptions per status code).
- [ ] DTOs (records) at the controller boundary, MapStruct mapper for Entity<->DTO — **no JPA entities** in the response.
- [ ] `@Valid` on every `@RequestBody` (POST/PUT/PATCH); write DTOs carry Jakarta validation annotations.
- [ ] List endpoints accept `Pageable` and return `PageResponse`; sort fields are limited via a **whitelist** in the service (no user input passed directly into `Sort`).
- [ ] Timestamps as `Instant` / `OffsetDateTime` in UTC; timezone conversion happens exclusively in the display layer.
- [ ] `backend/src/main/resources/api/openapi.json` is in sync with the controller changes.
- [ ] New DB changes are provided as a `V<n>__<desc>.sql` Flyway migration; `ddl-auto` stays `validate`.
- [ ] Destructive DB operations follow the two-phase pattern from `backend/src/main/resources/db/migration/README.md`.

### Security & Data Isolation (if affected)

- [ ] Read/write operations filter by JWT `sub` in the service layer, unless the caller has an elevated role (`ADMIN`, `OFFICER`, ...).
- [ ] For a member below Logistician: sensitive fields are explicitly removed in the controller layer (`cleanup…ForPeer`-style, REQ-SEC-007).
- [ ] No direct `SecurityContextHolder` access outside the auth-helper service (enforced via ArchUnit).
- [ ] Frontend does not depend on Spring Data JPA and does not access the DB or Keycloak Admin API directly (ArchUnit rule).
- [ ] No tokens, emails, or real names in logs.

### UI / Frontend (if affected)

- [ ] Layout works on Smartphone (<=768px), Tablet (768-1024px), Desktop (1024-1600px), and Ultra-wide (1600px+); touch targets >= 44px.
- [ ] Styleguide respected (brand orange `#E77E23`, `Lato`-only type — headlines = Lato Bold + uppercase, department colors semantically correct).
- [ ] No `confirm()` / `alert()` / native browser dialogs — KRT modals/toasts instead.
- [ ] Every user-visible string comes from `messages.properties` (de + en + fallback); umlauts in `.properties` as `\uXXXX`, in Markdown literal UTF-8.
- [ ] DOM `data-version` attributes are consistently propagated after AJAX updates to **all** related elements (edit buttons, modals, action buttons in the same `<tr>`/container) — otherwise 409 on the next click.
- [ ] Resilience4j paths considered for new backend calls (Timeout / Retry / CircuitBreaker / Bulkhead).

### Configuration & Dependencies (if affected)

- [ ] Dependency updates exclusively in `versions.properties` / `gradle/libs.versions.toml` (not directly in `build.gradle.kts`).
- [ ] New env vars / properties are documented (`README.md`, `application-*.yml`, `@ConfigurationProperties` with `@Validated`).
- [ ] Refresh-Versions / Dependabot reviewers do not have competing open PRs on the same configuration area.

## Migration and Deployment Notes

<!--
If this PR needs special attention on deploy, note it here:
- new required env var
- rollout ordering (e.g. Flyway step 1 before backend deploy, step 2 after frontend deploy)
- cache to invalidate / Redis keys to delete
- manual steps for admins (Keycloak realm, UEX API key, ...)
- backwards-compat window and sunset date

If there is nothing to consider: "None."
-->

## Screenshots / Demos (optional)

<!-- For UI changes: before/after, ideally per device class. -->

## Reviewer Notes

<!--
What should the reviewer pay particular attention to? Which parts are
subtle? Where were trade-offs made deliberately? What is explicitly NOT
part of this PR and comes later (with a link to the follow-up issue)?
-->
