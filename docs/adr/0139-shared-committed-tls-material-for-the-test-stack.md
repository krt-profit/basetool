# ADR-0139 — The test stack's TLS material is committed and shared

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** @greluc
- **Related:** ADR-0138, `REQ-APP-AUTH-011` (basetool-android), CLAUDE.md § Testing

## Context

The local test stack serves HTTPS. Until now every developer generated their own
`keystore.p12` with a throwaway password, per the hard rule that production artefacts never
enter a test stack. That rule is not in question and does not change.

What the per-developer keystore cost was invisible until the Android app arrived. A client
has to *trust* the certificate, and a certificate that exists only on the machine that
generated it cannot be trusted by anything shipped in a repository. The Android dev build
therefore had to trust the emulator's **user certificate store**, which meant every
developer installing their own CA on every AVD by hand.

That step turned out not to be automatable. On a Play-Store system image `adb root` is
refused, the Settings search does not accept synthetic text input, `CertInstallerMain`'s
document picker opens empty under automation, and the Files app has no handler for `.crt`.
Everything the wider ecosystem documents for API 34+ solves a different problem — writing to
the **system** store, which since Android 14 lives in the signed, immutable
`com.android.conscrypt` APEX and needs Magisk or a non-Play image.

So the manual step was not a small inconvenience. It was a gate that CI could never pass,
which is why `REQ-APP-AUTH-011`'s acceptance item *"a dev build reaching the test-stack
backend over HTTPS"* had stayed open.

## Decision

**The test stack's TLS material is generated once, committed, and shared by every test
stack, every CI run and the Android dev build.**

`docker/test-tls/` holds:

|             File             |                                                                                     Contents                                                                                      |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `basetool-test-ca.crt`       | the trust anchor — a certificate, no key                                                                                                                                          |
| `basetool-test-keystore.p12` | alias `basetool`: server key + chain; alias `ca`: the anchor again, so the same file also serves as the truststore that frontend and ingest validate `https://backend:11261` with |
| `generate-test-tls.sh`       | regenerates both, for expiry or a new hostname                                                                                                                                    |

**The CA private key is destroyed at generation time and is committed nowhere.** This is the
decision's load-bearing half, because `krt-profit/basetool` is public. A CA key in a public
repository would let anyone mint a certificate that every dev build trusts. Without it, the
published material can do exactly one thing: impersonate the names in the leaf's SAN list —
`localhost`, `backend`, `backend-dev`, `frontend`, `frontend-dev`, `ingest`, `ingest-dev`,
`host.docker.internal`, `127.0.0.1`, `10.0.2.2` — to builds that are `debuggable`.

The test stack binds the file by a **hardcoded path** rather than through
`IRI_KEYSTORE_HOST_PATH`. That variable still selects the *production* keystore in the base
compose file, and a test stack must not be one typo away from mounting it.

## Consequences

**The manual CA install is gone.** Nothing to generate, nothing to install, no per-AVD
setup, and the acceptance item above is closed by an automated test
(`TestStackTlsHandshakeTest`) that completes a real handshake on a device — the one
assertion that cannot be made on the JVM or under Robolectric, because the network security
config is a property of the running process.

**A private key is world-readable.** Stated plainly because it is the price. It is a
purpose-built test artefact, not a production credential leaked into one: it never protected
anything, it can only serve loopback and docker-network names, and a release APK neither
contains the anchor (dev source set) nor would honour it (`<debug-overrides>` requires
`android:debuggable="true"`). Guards: `.gitignore` still refuses the exact name
`keystore.p12` so the production file cannot land beside it, `.gitleaks.toml` allowlists
only these two paths, and a unit test fails if anything resembling key material ever appears
in the app's bundled anchor.

**The leaf can never be re-issued from this anchor**, since the key that would sign it is
gone. Rotation means running the script and updating both repositories together — hence the
20-year validity, and a test that fails on expiry with the regeneration command in its
message rather than leaving everyone to debug a handshake on a date nobody is watching.

**The rule about production artefacts is unchanged.** It said *never use production or real
credentials*; deliberately publishing a throwaway one is the opposite of that, and CLAUDE.md
now says so in as many words.

## Alternatives considered

**Keep the user certificate store.** Rejected: the manual step is unautomatable on the
images actually in use, so CI could never verify the path, and every developer pays it again
on every new AVD.

**Serve the backend over cleartext HTTP to the emulator.** Genuinely simpler — one
environment variable, no secret at all, and the network security config already permits
cleartext to those three loopback hosts for Keycloak. Rejected because the dev build would
then never exercise TLS, so an OkHttp or pinning misconfiguration would first surface in a
release build. Trust-anchor upkeep is a smaller cost than that blind spot.

**Commit a CA key with X.509 name constraints** and let each stack generate its own leaf.
Rejected: it makes the whole guarantee rest on every client enforcing `nameConstraints`,
which is a subtler bet than simply not publishing a usable CA key.

**Magisk / Cert-Fixer to reach the system store.** Rejected: it solves for the system store,
which a `<debug-overrides>` anchor does not need, and adds a rooted-emulator dependency to
everyone's setup.
