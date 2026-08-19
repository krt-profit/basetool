# Test stack TLS material

The certificate and keystore the local test stack serves HTTPS with. **Committed on purpose**,
so every developer's stack, every CI run and the Android dev build speak TLS with the same
material and nobody has to generate or install anything.

|             File             |                                                                                                         What it is                                                                                                         |
|------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `basetool-test-ca.crt`       | the trust anchor — a certificate, no key. Bundled by the Android dev build at `app/src/dev/res/raw/basetool_test_ca.crt`.                                                                                                  |
| `basetool-test-keystore.p12` | alias `basetool`: the server key and its chain. Alias `ca`: the anchor again, so the same file also works as the **truststore** that frontend and ingest validate `https://backend:11261` with. Password: `basetool-test`. |
| `generate-test-tls.sh`       | regenerates both. You almost certainly do not need to run it.                                                                                                                                                              |

## Why this is not a leaked credential

It never protected anything. It was built to be published, and three properties keep it that way:

- **The CA private key does not exist.** It is destroyed at the end of `generate-test-tls.sh`
  and is committed nowhere, so nothing can mint a further certificate that a dev build would
  trust. This repository is public; that is the reason.
- **The server key can only serve local names** — `localhost`, `backend`, `backend-dev`,
  `frontend`, `frontend-dev`, `ingest`, `ingest-dev`, `host.docker.internal`, `127.0.0.1`,
  `10.0.2.2`. There is no real hostname in the SAN list, so it cannot impersonate anything that
  exists outside a local stack.
- **A release build of the app neither contains the anchor nor would honour it.** It lives in the
  app's `dev` source set only, and inside `<debug-overrides>`, which Android applies only when
  `android:debuggable="true"`.

The subject of both certificates says `NOT FOR PRODUCTION`, and a unit test in the app repository
fails if anything resembling key material ever appears in the bundled anchor.

**This changes nothing about the rule it looks like it contradicts.** CLAUDE.md's *never use
production or real credentials in tests or local test stacks* stands unchanged — deliberately
publishing a throwaway artefact is the opposite of leaking a real one. Production keeps its own
keystore, bind-mounted at runtime, and `.gitignore` still refuses the exact name `keystore.p12`
so it cannot land beside these files.

## Regenerating

Only when the material expires (20 years from generation) or a service needs a hostname that is
not in the SAN list.

```bash
docker/test-tls/generate-test-tls.sh
```

It rebuilds both files. Because the old anchor's key is gone, the new leaf does **not** chain to
the old anchor: copy `basetool-test-ca.crt` to `app/src/dev/res/raw/basetool_test_ca.crt` in the
`basetool-android` repository in the same change, or every dev build stops trusting the stack.

The alias `basetool` is not cosmetic — `backend/src/main/resources/application.yml` pins it and
does not read it from the environment. A keystore built with any other alias fails start-up with
*"Alias name [basetool] does not identify a key entry"*, which reads like a code fault and is not.

See [ADR-0139](../../docs/adr/0139-shared-committed-tls-material-for-the-test-stack.md).
