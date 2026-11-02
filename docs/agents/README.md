# Agent reference files

The full rules behind the summaries in [`AGENTS.md`](../../AGENTS.md),
[`backend/AGENTS.md`](../../backend/AGENTS.md) and [`frontend/AGENTS.md`](../../frontend/AGENTS.md)
(ADR-0327, REQ-OPS-055). They are binding like the summaries; read the one that covers your work
before you start it.

| File | Read before |
| --- | --- |
| [`build.md`](build.md) | changing a build script, the version catalog or a dependency |
| [`requirements-and-companion-docs.md`](requirements-and-companion-docs.md) | finishing any change: what has to move with it |
| [`production-host-access.md`](production-host-access.md) | any command against the production host |
| [`lint-and-push.md`](lint-and-push.md) | calling a task done and every push |
| [`pitfalls.md`](pitfalls.md) | pushing, opening a pull request, claiming an ADR, REQ or Flyway number |
| [`git-and-commits.md`](git-and-commits.md) | committing and opening a pull request |
| [`testing.md`](testing.md) | writing tests or starting a local stack |
| [`java-conventions.md`](java-conventions.md) | editing any `.java` file |
| [`backend-module-map.md`](backend-module-map.md) | adding a backend class, package or module dependency |
| [`backend-concurrency.md`](backend-concurrency.md) | any multi-step transaction, version check, bulk update or find-or-create |
| [`backend-testing.md`](backend-testing.md) | a backend unit test with real collaborators or a `@SpringBootTest` |
| [`frontend-design-system.md`](frontend-design-system.md) | any UI work |
| [`frontend-templates-and-assets.md`](frontend-templates-and-assets.md) | changing a template, stylesheet, script tag, DOM write or dialog |
| [`frontend-live-update.md`](frontend-live-update.md) | any frontend mutation, read or live-synced section |
| [`frontend-javascript.md`](frontend-javascript.md) | writing or moving a script under `static/js` |
| [`frontend-layout.md`](frontend-layout.md) | adding or moving a frontend class, template or asset |
| [`frontend-backend-calls.md`](frontend-backend-calls.md) | adding a backend call or a `ThreadLocal` an outbound call needs |
| [`issue-tracker.md`](issue-tracker.md) | filing or editing an issue |
| [`triage-labels.md`](triage-labels.md) | triaging an issue |
| [`domain.md`](domain.md) | exploring the domain docs |
