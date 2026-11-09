# Testing rules

The full test rules, above all the credential rule for tests and local stacks.

- **Every new feature ships with tests.** No exceptions.
- **Never use production / real credentials in tests or local test stacks.** This is a hard rule. It applies to every kind of test (Mockito unit tests, MockMvc, `@SpringBootTest`, TestContainers integration tests) and to every manually-started local stack used to verify a change. Forbidden inputs include — non-exhaustively — the production `.env` at the repo root, the shared `keystore.p12` at `backend/src/main/resources/keystore.p12`, the shared `realm-export.json` Keycloak dump, real OIDC client secrets, real database passwords, real SMTP credentials, real Keycloak admin passwords, real JWT signing keys. Use dedicated test artifacts instead: `.env.test` (gitignored via `.env.*`), the **committed** TLS material in [`docker/test-tls/`](../../docker/test-tls/README.md), a stripped `realm-export.json` with rotated client secrets and a synthetic test user, the `docker-compose.test.yml` override. **The committed TLS material is not an exception to this rule but an application of it** ([ADR-0139](../adr/0139-shared-committed-tls-material-for-the-test-stack.md)): the rule forbids bringing a *real* credential into a test stack, and this is a throwaway built to be published — the CA key that signed it was destroyed at generation time, the server key can only serve loopback and docker-network names, and it is bound by a hardcoded path so `BASETOOL_KEYSTORE_HOST_PATH` (which still selects the production keystore) cannot be pointed at a test stack by accident. Deliberately publishing a worthless artefact and leaking a real one are opposite acts; do not read one as licence for the other. The README's `Running the local test stack` section carries the full `.env.test` variable set and the realm-rewrite command; the TLS material needs no command at all. Reason: anything that enters a worktree, a CI log, a container volume, a screenshot artifact, an MCP-preview snapshot or an editor backup has to be assumed leaked and rotated — and the recovery is cheaper if it never had to happen in the first place. When you spin a local stack up to verify a UI change, always source `.env.test` (never `.env`), point Docker Compose at `--env-file .env.test`, and tear the stack down with `down --volumes` after the verification.

## Managed E2E runtime artifacts

The standard Gradle E2E harness accepts the test-only `E2E_REALM_PATH` environment variable for
its generated synthetic realm. It passes the same absolute path to `docker-compose.e2e.yml`;
when unset, the output and mount remain `<repoRoot>/realm-export.json`. This override never changes
the production/base Compose configuration or the committed throwaway TLS files. Set the output
inside the agent's task directory when local temporary-file rules require it. The ignored
`frontend/build/e2e` runtime/auth/log directory may likewise point into that directory; ordinary
Gradle build outputs retain their standard paths.
