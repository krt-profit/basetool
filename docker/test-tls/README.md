# Test stack TLS material

The certificates and keystores the local test stack serves HTTPS with. **Committed on purpose**,
so every developer's stack, every CI run and the Android dev build speak TLS with the same
material and nobody has to generate or install anything.

Since REQ-SEC-TBD04T (ADR-TBD04T) it has **the shape production has**: one private CA, one leaf
per service, a CA-only truststore — minted by the same `scripts/mint-internal-tls.sh` the
production host runs. Password of every `.p12`: `basetool-test`.

|               File                |                                                                          What it is                                                                          |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `basetool-test-ca.crt`            | the trust anchor — a certificate, no key. Bundled by the Android dev build at `app/src/dev/res/raw/basetool_test_ca.crt`.                                    |
| `basetool-test-backend.p12`       | alias `basetool`: the backend's key and its chain (leaf, CA). Alias `ca`: the anchor as a trusted entry. Mounted into `backend-dev` by the test/E2E stacks. |
| `basetool-test-frontend.p12`      | the same for the frontend (`frontend-dev`).                                                                                                                  |
| `basetool-test-ingest.p12`        | the same for the ingest gateway (`ingest-dev`).                                                                                                              |
| `basetool-test-keycloak.p12`      | the same for Keycloak. The test stack runs Keycloak on plain HTTP and does not mount it; it exists so the set matches production's.                       |
| `basetool-test-truststore.p12`    | alias `ca` only — what a client pins. The E2E seeder trusts the backend through it, with hostname verification on.                                          |
| `generate-test-tls.sh`            | regenerates all of them. You almost certainly do not need to run it.                                                                                        |

## Why this is not a leaked credential

It never protected anything. It was built to be published, and three properties keep it that way:

- **The CA private key does not exist.** The mint script destroys it at the end of the run and it
  is committed nowhere, so nothing can mint a further certificate that a dev build would trust.
  This repository is public; that is the reason.
- **Each key can only serve local names** — its own service's docker alias and `-dev` alias, plus
  `localhost`, `host.docker.internal`, `127.0.0.1` and `10.0.2.2`. There is no real hostname in
  any SAN list, so none of them can impersonate anything that exists outside a local stack.
- **A release build of the app neither contains the anchor nor would honour it.** It lives in the
  app's `dev` source set only, and inside `<debug-overrides>`, which Android applies only when
  `android:debuggable="true"`.

The subject of every certificate says `NOT FOR PRODUCTION`, and a unit test in the app repository
fails if anything resembling key material ever appears in the bundled anchor.

**This changes nothing about the rule it looks like it contradicts.** CLAUDE.md's *never use
production or real credentials in tests or local test stacks* stands unchanged — deliberately
publishing a throwaway artefact is the opposite of leaking a real one. Production mints its own
material on the host (`docs/deployment.md` → *Internal TLS*), bind-mounted at runtime, and
`.gitignore` still refuses the exact name `keystore.p12` so it cannot land beside these files.

## Regenerating

Only when the material expires (20 years from generation) or a service needs a hostname that is
not in its SAN list. Needs `keytool` (any JDK) and a POSIX `sh`.

```bash
docker/test-tls/generate-test-tls.sh
```

It rebuilds every file. Because the old anchor's key is gone, the new leaves do **not** chain to
the old anchor: copy `basetool-test-ca.crt` to `app/src/dev/res/raw/basetool_test_ca.crt` in the
`basetool-android` repository in the same change, or every dev build stops trusting the stack.

The alias `basetool` is not cosmetic — `backend/src/main/resources/application.yml` pins it and
does not read it from the environment. A keystore built with any other alias fails start-up with
*"Alias name [basetool] does not identify a key entry"*, which reads like a code fault and is not.

See [ADR-0139](../../docs/adr/0139-shared-committed-tls-material-for-the-test-stack.md).
