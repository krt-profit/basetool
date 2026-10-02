# Local sandbox

The sandbox is the Profit Basetool on your own machine: the Exchange API gateway, the backend, the
web frontend and a Keycloak realm with a test client, synthetic members and seeded data. Build and
test your client against it before you apply (REQ-XCH-029). It needs no account of ours, never talks
to production, and everything in it is thrown away when you stop it.

**Nothing in the sandbox is a real credential.** Every password, client secret and key in it is a
throwaway committed to the public repository on purpose, named `…-do-not-use-in-prod` where it is
ours to name. The TLS certificates are the committed test material whose signing key was destroyed
when they were made ([ADR-0139][adr-0139]). Never use any of these values anywhere else, and never
put a real credential into the sandbox.

On this page:

- [What you need](#what-you-need): software, memory, disk, ports, and the issuer's host name on
  Windows, Linux, macOS and WSL.
- [Start, reset, stop](#start-reset-stop), and [versions and updates](#versions-and-updates).
- [Addresses](#addresses) and [trusting the test CA](#trusting-the-test-ca) in your language.
- [Accounts](#accounts), [clients](#clients) and [seeded data](#seeded-data).
- [Test scenarios](#test-scenarios): what to run, and the answer to expect.
- [Troubleshooting](#troubleshooting), and
  [what the sandbox does not do](#what-the-sandbox-does-not-do).

## What you need

### Software

| | Windows | Linux | macOS |
| --- | --- | --- | --- |
| Docker | Docker Desktop with the WSL 2 backend | Docker Engine | Docker Desktop |
| Compose | 2.24.4 or later — Docker Desktop ships it | the Compose plugin, 2.24.4 or later | 2.24.4 or later — Docker Desktop ships it |
| Start script | `scripts/sandbox.ps1`, PowerShell 7 or Windows PowerShell 5.1 | `scripts/sandbox.sh`, bash | `scripts/sandbox.sh`, bash |
| Tested by us | yes — Windows 11, Docker Desktop with Engine 29.8 and Compose 5.5 | yes — the CI smoke test on Ubuntu | **no** |

Check the Compose version with `docker compose version`. Older versions fail on the `!override` and
`!reset` tags of the compose files. You also need a checkout of [krt-profit/basetool][repo]: the
compose files, the seed and the start scripts live there, and the scripts run from its root. The
[smoke test](#checking-the-sandbox) and the [quick start](quickstart.md) need Python 3.10 or later
and nothing else.

On Windows, Windows PowerShell 5.1 refuses to run a script under its default execution policy. Run
the script as `powershell -ExecutionPolicy Bypass -File scripts/sandbox.ps1 up` — this applies to
that one process only — or use PowerShell 7 (`pwsh`), which runs a script from a checkout under
its default policy.

The images are published for `linux/amd64` and `linux/arm64`, so an Apple silicon Mac and an arm64
Linux machine run them natively; Docker picks the right one. Each platform's image is built on its
own platform and passes the same checks before it is published, but we run the sandbox itself on
`amd64` only: the arm64 images are **not tested by us** beyond those checks.

`--build` builds the images for your machine's platform. It needs Compose 2.37 or later, which
builds one service's image on top of another's (`additional_contexts: service:`).

### Memory and disk

- **Memory.** The seven containers use about 2.3 GB once started — the backend about 0.9 GB,
  Keycloak about 0.7 GB. Give Docker at least **4 GB**; Docker Desktop sets its limit under
  *Settings > Resources* (with the WSL 2 backend, in the `.wslconfig` of your Windows user).
- **Disk.** The images take about **5 GB**. The databases start at a few hundred megabytes and are
  deleted by `down` and `reset`.

### Ports

The sandbox publishes seven ports, **each bound to `127.0.0.1` only**. All seven must be free.

| Port | Service | What you use it for |
| --- | --- | --- |
| 18080 | Keycloak, plain HTTP | the issuer, the device page, the Keycloak admin console |
| 11262 | the Exchange API gateway, HTTPS | every API call of your client |
| 18081 | the web frontend, HTTPS | *Connected applications*, confirmations, drafts, the admin pages |
| 11261 | the backend REST API, HTTPS | nothing — the gateway relays to it; a client never calls it |
| 15432 | PostgreSQL of the backend | nothing, unless you want to look into the database |
| 15433 | PostgreSQL of Keycloak | nothing |
| 6379 | Redis | nothing |

The stack also creates its own bridge networks in `172.28.0.0/16` (`172.28.0.0/24` to
`172.28.9.0/24` and `172.28.12.0/24`); they must not overlap a network of your machine or another
Docker network. [Troubleshooting](#troubleshooting) shows how to find what holds a port.

### The issuer's host name

The sandbox issuer is `http://host.docker.internal:18080/auth/realms/iri`. The containers reach
Keycloak under that name inside Docker; your client and your browser must reach it under the same
name, on the loopback. Every page, redirect and form action of Keycloak names it, so a browser
cannot sign in without it.

Add this line to the hosts file of the machine the sandbox runs on:

```text
127.0.0.1 host.docker.internal
```

- **Linux.** The name does not exist on a Linux host. Add the line to `/etc/hosts` (it needs root).
- **Windows.** Docker Desktop maps the name to your network address, where the sandbox does not
  listen. Add the line to `C:\Windows\System32\drivers\etc\hosts` as an administrator, **above** the
  block Docker Desktop maintains, or turn off *Add the \*.docker.internal names to the host's
  etc/hosts file* in Docker Desktop's settings and add the line.
- **macOS** (not tested by us). Docker documents `host.docker.internal` as a name Docker Desktop
  gives its containers; the Mac itself does not resolve it. Add the line to `/etc/hosts` with
  `sudo`.
- **WSL.** A WSL distribution writes its `/etc/hosts` from the Windows hosts file, so add the line
  on the Windows side, then run `wsl --shutdown` once. On our Windows 11 machine the sandbox's
  ports answered at `localhost` inside a WSL 2 distribution. Running the start script inside WSL
  needs Docker Desktop's WSL integration for that distribution; we have not tested it.

The line changes the name for programs on your machine only; containers keep resolving it through
Docker. Check the result:

| | Command | Expected |
| --- | --- | --- |
| Windows | `[System.Net.Dns]::GetHostAddresses('host.docker.internal')` | `127.0.0.1` |
| Linux, WSL | `getent hosts host.docker.internal` | `127.0.0.1 host.docker.internal` |
| macOS | `dscacheutil -q host -a name host.docker.internal` | `ip_address: 127.0.0.1` |

Both start scripts check the name and warn when it does not resolve to the loopback; without
`getent`, as on macOS or in Git Bash, `sandbox.sh` says it cannot check.

## Start, reset, stop

| | Linux, macOS, WSL | Windows (PowerShell) |
| --- | --- | --- |
| Start and seed | `scripts/sandbox.sh up` | `./scripts/sandbox.ps1 up` |
| Start from scratch | `scripts/sandbox.sh reset` | `./scripts/sandbox.ps1 reset` |
| Stop and delete all data | `scripts/sandbox.sh down` | `./scripts/sandbox.ps1 down` |

Run them from the repository root. `up` pulls the public sandbox images, starts the stack, waits
until every service is healthy and applies the seed; with the images already pulled it takes about
a minute. `reset` is `down` followed by `up`. `down` removes the containers **and the volumes**, so
the next start is a clean sandbox. `up` on a running sandbox keeps its data and applies the seed
again, which adds nothing that is already there but turns the exchange switch back on.

To build the images from your checkout instead, add `--build` (Linux, macOS) or `-Build`
(Windows). The first build takes several minutes. Locally built images are tagged `local`. They are
built the way the published ones are, the application image with the sandbox marker on top, so
they refuse the `prod` profile too.

The scripts run this command line, which you can also use directly:

```bash
docker compose --env-file docker/sandbox/sandbox.env \
  -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml \
  --profile sandbox up -d --wait redis-dev db-backend-dev db-keycloak-dev keycloak-dev \
  backend-dev frontend-dev ingest-dev
docker compose --env-file docker/sandbox/sandbox.env \
  -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml \
  --profile sandbox run --rm sandbox-seed
```

For local images, add `-f docker-compose.sandbox-build.yml` after the sandbox file and run `build`
first. Stop with the same files and `down --volumes`. Always pass the sandbox's `--env-file`:
without it Compose would read a `.env` in the checkout instead. The Compose project is always called
`basetool-sandbox`, so its containers are named `basetool-sandbox-<service>-1`.

### Versions and updates

The images are `ghcr.io/krt-profit/basetool-sandbox-{backend,frontend,ingest,keycloak}`. They carry
only test values and refuse to start as production: the application images stop under the `prod`
profile, and the Keycloak image runs only as `start-dev` — any other command, such as `start`, ends
at once with exit code 64.

| Tag | What it is |
| --- | --- |
| `edge` | The default. Built from `main` after every change to what the images contain, so it trails `main` by the time a build takes. A change to these pages or the start scripts publishes nothing: those come from your checkout. A change under `docker/sandbox/`, the seed included, republishes `edge` with a new revision even when the images' content is unchanged; the seed itself still comes from your checkout. |
| `X.Y.Z`, such as `1.13.0` | Built from the release tag `vX.Y.Z` — without the `v` — and only when that tag's commit is on `main`. Published with every release from the first one that contains the sandbox; until then `edge` is the only tag. |
| `latest` | The newest release. |

Pick a tag with `BASETOOL_SANDBOX_VERSION`:

```sh
BASETOOL_SANDBOX_VERSION=1.13.0 scripts/sandbox.sh up
```

```powershell
$env:BASETOOL_SANDBOX_VERSION = '1.13.0'; ./scripts/sandbox.ps1 up
```

**Use images that match your checkout.** The seed comes from the checkout and the application from
the images: the seeded accounts accept the Terms of Use version of the checkout, and the seed writes
tables the images' database schema must have. Check out the release tag for an `X.Y.Z` image. On
`main`, `edge` is the closest image — and when `main` has moved on since it was published, `--build`
gives you exactly your checkout.

**Which commit an image is.** Every image carries the commit it was built from in the label
`org.opencontainers.image.revision`, and the build time in `org.opencontainers.image.created`:

```sh
docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
  ghcr.io/krt-profit/basetool-sandbox-backend:edge
git merge-base --is-ancestor <that commit> HEAD && echo "the image is part of your checkout"
```

A published image without the label is older than the label itself; `reset` pulls the current one.
Images you build with `--build` carry no revision: they are your checkout.

**Updating.** `up` pulls the chosen tag every time, so after `git pull` run `reset`: it pulls the
current images and starts with a fresh database. `up` alone replaces the containers whose image
changed but keeps the old data.

## Addresses

| | |
| --- | --- |
| Issuer | `http://host.docker.internal:18080/auth/realms/iri` |
| Discovery | `http://host.docker.internal:18080/auth/realms/iri/.well-known/openid-configuration` |
| Device authorization | `http://host.docker.internal:18080/auth/realms/iri/protocol/openid-connect/auth/device` |
| Token | `http://host.docker.internal:18080/auth/realms/iri/protocol/openid-connect/token` |
| Gateway | `https://localhost:11262/exchange/v1` |
| Web frontend | `https://localhost:18081` — German by default; `https://localhost:18081/?lang=en` switches it to English |
| *Connected applications* | `https://localhost:18081/connected-apps` |
| Client registry (admin) | `https://localhost:18081/admin/exchange-clients` |
| Keycloak admin console | `http://host.docker.internal:18080/auth/admin/` — `admin` / `sandbox-keycloak-admin-pw-do-not-use-in-prod` |

The gateway and the web frontend serve HTTPS with the committed test certificate; see
[trusting the test CA](#trusting-the-test-ca). Keycloak's pages follow the browser's language,
English or German.

Unlike production, the sandbox gateway compares `htu` with the URL of the request itself — scheme,
host **and port** as you called them, so `https://localhost:11262/exchange/v1/me/blueprints` for the
address above. `https://127.0.0.1:11262` works as well, as long as the proof names the same host and
port the request went to; the [smoke test][smoke] uses it. Keep to the Keycloak addresses above. The
sign-in itself works as in production: [authentication](authentication.md).

## Trusting the test CA

The gateway's and the web frontend's certificate is issued by the committed test CA
[`docker/test-tls/basetool-test-ca.crt`][test-ca], a PEM file. Its names include `localhost`,
`127.0.0.1` and `host.docker.internal`. Keycloak on port 18080 is plain HTTP and needs no trust.

Trust the CA **in sandbox mode only, and only for the sandbox's connections**: give it to the HTTP
client your sandbox switch builds — as that client's only trust anchor, which is all the sandbox
needs — and never to the client that talks to production. **Do not install it into your operating
system's or browser's trust store.** Every program would then trust it for every site, although
the certificate is public; its key was destroyed, but an extra root in the system store is one
nobody remembers to remove. For the browser, accept the certificate warning of
`https://localhost:18081` once instead.

Each example below reads the file relative to the repository root and fetches
`https://localhost:11262/exchange/v1`, which answers `401 UNAUTHENTICATED` without a token — a
`401` means the TLS handshake succeeded. Each was run against the sandbox.

**Python, standard library:**

```python
import ssl
import urllib.request

sandbox_tls = ssl.create_default_context(cafile="docker/test-tls/basetool-test-ca.crt")
urllib.request.urlopen("https://localhost:11262/exchange/v1", context=sandbox_tls)
```

**Python, requests** (`verify` replaces the default bundle for this call only):

```python
requests.get("https://localhost:11262/exchange/v1", verify="docker/test-tls/basetool-test-ca.crt")
```

**C# / .NET 7 or later** — the test CA as the only root, for this handler only:

```csharp
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;

var sandboxCa = X509Certificate2.CreateFromPem(File.ReadAllText("docker/test-tls/basetool-test-ca.crt"));
var handler = new SocketsHttpHandler
{
    SslOptions = new SslClientAuthenticationOptions
    {
        CertificateChainPolicy = new X509ChainPolicy
        {
            TrustMode = X509ChainTrustMode.CustomRootTrust,
            CustomTrustStore = { sandboxCa },
            RevocationMode = X509RevocationMode.NoCheck,
        },
    },
};
using var sandboxHttp = new HttpClient(handler);
```

The test CA publishes no revocation list, so revocation checking is off in this handler — which is
one more reason to keep it for the sandbox only.

**Java** — a trust store holding only the test CA, for this `HttpClient` only:

```java
Certificate ca;
try (InputStream in = Files.newInputStream(Path.of("docker/test-tls/basetool-test-ca.crt"))) {
  ca = CertificateFactory.getInstance("X.509").generateCertificate(in);
}
KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
trust.load(null, null);
trust.setCertificateEntry("basetool-sandbox-ca", ca);
TrustManagerFactory tmf =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trust);
SSLContext sandboxTls = SSLContext.getInstance("TLS");
sandboxTls.init(null, tmf.getTrustManagers(), null);
HttpClient sandboxHttp = HttpClient.newBuilder().sslContext(sandboxTls).build();
```

**Node.js** — per request with `node:https`:

```js
import { readFileSync } from "node:fs";
import https from "node:https";

const ca = readFileSync("docker/test-tls/basetool-test-ca.crt");
https.get("https://localhost:11262/exchange/v1", { ca }, (answer) => { /* ... */ });
```

For `fetch`, start the process with `NODE_EXTRA_CA_CERTS=docker/test-tls/basetool-test-ca.crt`,
which adds the CA to the process's trust store; set it only when you run against the sandbox.

**curl:**

```sh
curl --cacert docker/test-tls/basetool-test-ca.crt https://localhost:11262/exchange/v1
```

On Windows, curl verifies through Schannel, which also asks for the revocation status the test CA
cannot give, and fails with "schannel: the revocation status is unknown". Add `--ssl-no-revoke`
there.

## Switching your client to the sandbox

Pin the production issuer. Select the sandbox only through a developer environment variable of your
client, never in its user interface ([client security](client-security.md)). One variable should
switch everything at once: the issuer, the gateway's base URL and the extra trust anchor — for
example `BASETOOL_EXCHANGE_SANDBOX=1`. A release build may ignore it altogether.

## Accounts

Sign in with these on the device page. They exist only in the sandbox realm; none of them uses
Discord.

| Username | Password | Id (`sub`) | What it is |
| --- | --- | --- | --- |
| `sandbox-member` | `sandbox-member-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-000000000001` | a member of two squadrons, IRIDIUM and Sandbox Squadron, with data |
| `sandbox-member-2` | `sandbox-member-2-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-000000000002` | a member of IRIDIUM only, for isolation checks |
| `sandbox-admin` | `sandbox-admin-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-000000000003` | an administrator, for the registry page in the web frontend |
| `sandbox-load-01` … `sandbox-load-16` | `sandbox-load-NN-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-0000000002NN` | members of IRIDIUM without data, for our load test (`scripts/sandbox-load.py`); you do not need them |

All of them are approved and have accepted the Terms of Use version of your checkout, which is why
the image tag should match it. `sandbox-member` has the RSI handle `Sandbox_Member` stored for the
account check; the others have none.

## Clients

`sandbox-client` is a third-party client exactly as the production provisioner creates an approved
one: public, device grant only, consent required, DPoP-bound tokens, no redirect URI, every
`exchange.*` scope and `offline_access` optional. The registry lists it `ACTIVE` with every
capability and the default limits.

`sandbox-suspended-client` has the same shape, but the registry lists it `SUSPENDED`, with only
`exchange.connect` and `exchange.blueprints.read`: every call with its token is refused with
`403 CLIENT_SUSPENDED`.

Sign in with `sandbox-admin` on the web frontend and open `/admin/exchange-clients` to suspend a
client, change its capabilities, limits or minimum version, undo its writes, or switch the whole
exchange off. The gateway sees a change within about 5 seconds.

## Seeded data

- **Catalogue.** A manufacturer; Sandbox City (UEX city id `990001`) and Sandbox Station (UEX space
  station id `990002`) with one warehouse location each, Sandbox City Hangar and Sandbox Station
  Storage; the ship types Sandbox Hauler and Sandbox Miner; the materials Sandbox Ore (Raw) (raw,
  refines into Sandbox Metal), Sandbox Metal (refined), Sandbox Trade Goods (a commodity) and
  Sandbox Component (counted in pieces); the items Sandbox Rifle and Sandbox Helmet; blueprints
  with the scmdb tags `BP_CRAFT_SBXM_RIFLE_01`, `BP_CRAFT_SBXM_HELMET_01` and
  `BP_CRAFT_SBXM_KNIFE_01`; and the Basetool's eight default blueprints under their names, without
  an scmdb tag: S-38 Magazine (20 cap), P4-AR Magazine (40 cap), Field Recon Suit Arms, Core, Helmet
  and Legs, S-38 Pistol and P4-AR Rifle. Nothing else: the catalogue imports of production do not
  run.
- **Blueprints.** `sandbox-member` owns Sandbox Rifle and Sandbox Knife, `sandbox-member-2` owns
  Sandbox Helmet. The default blueprints (`isDefault: true` in the feed) are granted to every member
  within a minute of the start.
- **Stock of `sandbox-member`.** Sandbox Metal in the IRIDIUM pool and in the Sandbox Squadron pool
  at the same place and quality, so the two rows form one lot; Sandbox Trade Goods, Sandbox
  Component and Sandbox Ore (Raw) in no pool; one Sandbox Rifle — all personal. Beside the personal
  Sandbox Ore (Raw), 10 SCU of it at the same place and quality are shared in the Sandbox Squadron
  pool, so that lot holds 30 SCU. Part of the IRIDIUM row is offered on the Material Exchange.
  `sandbox-member-2` holds one lot of Sandbox Metal.
- **Ships.** Two for `sandbox-member`, one for `sandbox-member-2`.
- **Open orders.** A material order and an item order for IRIDIUM and a material order for Sandbox
  Squadron, with minimum qualities, so `GET /exchange/v1/me/org-demand` answers. Sandbox Squadron
  takes part in the profit sharing and IRIDIUM does not, so `sandbox-member` gets the demand of
  both squadrons and `sandbox-member-2` gets the withheld answer with `"reason": "NOT_PERMITTED"`
  ([whose demand](resources/org-demand.md#whose-demand)).
- **The exchange switch is on.** The seed sets it in the database. The backend copies it to the
  gateway's registry mirror within 10 s and the gateway re-reads the mirror at most every 5 s, so
  for up to about 15 s after `up` every call answers `503 EXCHANGE_DISABLED` with `Retry-After`.
  Honour it, as a client must in production too.

## Test scenarios

These are the situations a client meets in production, in an order that builds on itself. Each was
run against the sandbox — the web steps through the requests their pages send — and the answers
below are what it gave, unless a scenario says otherwise.

- Use `sandbox-member` unless a scenario says otherwise.
- Send a `User-Agent` with your version, such as
  `ExampleClient/1.2.0 (+https://example.org/client)`, and a fresh `Idempotency-Key` with every
  write.
- A change set is answered `200` even when an op is refused: look at `applied`, `unchanged`,
  `notApplied` and `results[]`.
- The web steps sign in at `https://localhost:18081`, a member as themselves and the admin steps as
  `sandbox-admin` — sign out in between, or use a private window.
- [`reset`](#start-reset-stop) brings every seeded value back.

### Sign-in and connect

**Device login.** Ask for a device code with the scopes you need and `offline_access`.

- Expect `200` with `user_code`, `verification_uri_complete`, `expires_in` 600, `interval` 5 and
  `verification_uri` `http://host.docker.internal:18080/auth/realms/iri/device`.
- Show the member the `user_code` and the bare `verification_uri` only. The code-entry page warns
  "Only enter your own code". After the sign-in, the consent page repeats the warning and shows the
  code of this login, which must match the one your client shows.
- Poll the token endpoint with a new DPoP proof each time: `400 authorization_pending` until the
  member consents, then `200` with `token_type` `DPoP` and `cnf.jkt` equal to your key's thumbprint.
- Declining on the consent page gives `400 access_denied`.

**Label and installation id.** `POST /exchange/v1/me/installation` with `{"label": "ExampleClient
Windows"}` answers `200` with `installationId`, `firstSeenAt` and `lastSeenAt`; the service
document `GET /exchange/v1` carries the same `installationId`. A label with an en dash, such as
`ExampleClient – Windows`, is refused `400 SCHEMA_INVALID` with `errors[]` at `/label`. The member
sees the label on *Connected applications*.

**Account check.** `POST /exchange/v1/me/account-check`:

| Account | `handle` | `result` |
| --- | --- | --- |
| `sandbox-member` | `Sandbox_Member`, in any case | `match` |
| `sandbox-member` | any other handle, such as `Someone_Else` | `mismatch` |
| `sandbox-member-2` | any handle | `unknown` |

The eleventh check of one member within an hour is `429 RATE_LIMITED` with `Retry-After`.

### Reading

**Snapshot and feed.** Read `GET /exchange/v1/me/blueprints`, `/me/stock`, `/me/ships` and
`/me/org-demand`, and `GET /exchange/v1/catalog/locations` — each `200`. Page the snapshot until
`hasMore` is `false`, keep `nextCursor`, and read again with `?cursor=<it>`: without changes in
between, the feed is empty. Resolve with `POST /exchange/v1/catalog/resolve`, for example
`{"kind": "BLUEPRINT", "refs": [{"scRecord": "BP_CRAFT_SBXM_HELMET_01"}]}`,
`{"kind": "SHIP_TYPE", "refs": [{"name": "Sandbox Miner"}]}` or
`{"kind": "MATERIAL", "refs": [{"name": "Sandbox Metal"}]}`.

**Isolation.** Sign in as `sandbox-member-2`: the feeds show only that member's own data — the
Sandbox Helmet besides the default blueprints, one lot of Sandbox Metal and SBX Hauler Two.

**Expired cursor.** `GET /exchange/v1/me/blueprints?cursor=forged-cursor` answers
`410 CURSOR_EXPIRED`. Recover with a full snapshot reconciled against your last baseline
([sync guide](sync-guide.md#full-resync-after-a-cursor-expired)).

### First sync

**Blueprints.** Add the Sandbox Helmet, which `sandbox-member` does not own:
`{"ops": [{"op": "add", "ref": {"scRecord": "BP_CRAFT_SBXM_HELMET_01"}, "provenance": {"source":
"log"}}]}` gives `applied: 1`; the same op again gives `unchanged: 1`.

**Stock.** Add a lot the server does not have with `expectedQuantity` 0, for example 3 pieces of
Sandbox Component at Sandbox City Hangar, quality 0 (`applied: 1`). The same material in `SCU` is
refused per op as `UNIT_MISMATCH`, a place without a warehouse location as `LOCATION_UNKNOWN`.
Lowering the Sandbox Metal lot at Sandbox City Hangar, quality 700, below its offered amount
answers `offersRemoved` or `offersReduced` above 0 — show the member what happened.

**Ships: link before create.** Match your ships to the snapshot first: `{"op": "link",
"externalId": "<yours>", "shipId": "<SBX Hauler One's shipId>"}` gives `applied: 1`, and the feed
then shows your `externalId` on that ship. Only then `upsert` the ships without a match.

### Conflicts and removals

**Stale stock quantity.** Send a `set-quantity` whose `expectedQuantity` differs from the lot's
amount — as after a change in the web or from a second installation. The op is refused:
`results: [{"index": 0, "result": "rejected", "reason": "VERSION_CONFLICT"}]`. Pull, merge and
resend in a new batch.

**Stale ship version.** An `upsert` or `remove` with an older `version` than the feed's is refused
the same way, `VERSION_CONFLICT`.

**Removed elsewhere.** Connect a second installation (a second DPoP key, another device login) for
the same member, and remove the Sandbox Rifle there — or remove it on *My Blueprints* in the web.
The first installation's feed then carries a tombstone, for the second installation:

```json
{"key": "sandbox rifle", "removedAt": "…",
 "removedBy": {"channel": "client", "clientId": "sandbox-client", "installationId": "<the other>"}}
```

An `add` of the Sandbox Rifle from the first installation is refused per op as
`REMOVED_ELSEWHERE`. The same op with `"override": true` is applied — send it only after the member
agreed.

**A default blueprint.** Once the defaults are granted, remove one, by its feed `key` or by name:
`{"ops": [{"op": "remove", "key": "s-38 pistol"}]}` or
`{"ops": [{"op": "remove", "ref": {"name": "S-38 Pistol"}}]}`. The op is refused:
`results: [{"index": 0, "result": "rejected", "reason": "DEFAULT_NOT_REMOVABLE"}]`. Keep the entry
and do not send the removal again; a dry run gives the same answer.

### The mass-change guard

1. Create six ships in one change set (`upsert` without `shipId`), then pull them.
2. Remove all six in one change set, each with its `shipId` and `version`.
3. Expect `409 MASS_CHANGE_CONFIRMATION_REQUIRED` with a `confirmationUrl`, and nothing written.
   The same batch with `"dryRun": true` answers `200` with `applied: 6`, since a dry run does not
   ask the guard.
4. Show the member the URL and do not resend. The member opens it while signed in to the web
   frontend, reviews the batch and confirms it.
5. Your next pull shows the six ships removed.

### Disconnecting

**One installation.** On *Connected applications*, disconnect one installation of the client. Its
next call answers `401 INSTALLATION_REVOKED`, and keeps doing so with refreshed tokens; delete its
tokens and its key. Another installation of the same member carries on with `200`.

**The whole client.** Choose *Disconnect the application*. Every installation's next call answers
`401 CLIENT_REVOKED`, and a refresh answers `400 invalid_grant`. A new device login connects the
client again.

Both take effect within a few seconds.

### Registry and admin

Sign in as `sandbox-admin` and open `https://localhost:18081/admin/exchange-clients`. Each change
reaches the gateway within about 5 seconds; undo it afterwards, or `reset`.

| Do | Expect |
| --- | --- |
| Call anything with a token of `sandbox-suspended-client` | `403 CLIENT_SUSPENDED` |
| *Suspend* `sandbox-client` | `403 CLIENT_SUSPENDED` on every call; *Activate* restores `200` |
| *Edit*, *Minimum version* `2.0.0`, with your `User-Agent` at `1.0.0` | `403 CLIENT_VERSION_UNSUPPORTED`, also for a `User-Agent` without a version; `minClientVersion` in the service document |
| *Edit*, *Requests per minute and member* `2` | `limits.requestsPerMinute` 2 in the service document, `RateLimit: limit=2, …` and `RateLimit-Policy: 2;w=60`, then `429 RATE_LIMITED` with `Retry-After` |
| *Edit*, *Writes per day and member* `1` | after the first write of the UTC day, `429 QUOTA_EXCEEDED` with `Retry-After` until midnight UTC |
| *Data exchange*, *Turn off* | `503 EXCHANGE_DISABLED` with `Retry-After: 30` for every client; *Turn on* restores it |

**Bulk undo.** Write something with your client, then choose *Undo* on `sandbox-client`, pick a
time before the write, *Check* — the preview counts members and entries — and *Start undo*. The
client is suspended first: `403 CLIENT_SUSPENDED` while the undo runs and afterwards, until the
admin activates it again. After that your feed shows the undone entries as tombstones with
`removedBy` `{"channel": "web"}`; apply them like any web edit and do not push the undone state back
([undo](sync-guide.md#undo)).

### Drafts

`POST /exchange/v1/me/drafts/blueprints` with a `basetool.blueprints` envelope of `formatVersion`
`1.0` answers `200` with `frontendUrl`, `handoffId` and `kind` `BLUEPRINT`; the member opens the
URL (see the scheme note above) and reviews the import.

A `formatVersion` whose major is not 1, such as `2.0`, is refused `400 SCHEMA_INVALID` with
`errors[]` at `/formatVersion` ([formats](formats.md)) — but only by images built since that refusal
was added. An older image still answers `200`; if yours does, check its
[revision](#versions-and-updates), `reset` to pull the current `edge`, or build from your checkout
(`--build`).

### The member's proof cap

A member may hold 600 live DPoP proofs at a time, each for about 30 seconds
([live proofs](authentication.md#live-proofs-per-member)). To see the refusal, send more than 600
requests for one member within 30 seconds, each with a fresh proof — a tight loop over
`GET /exchange/v1` does it.

- Expect `200` until the client's per-member limit runs out, then `429 RATE_LIMITED`; those requests
  still count their proofs.
- Then `429 DPOP_PROOF_LIMIT` with `Retry-After`, the seconds until the member's oldest proof no
  longer counts. Every client of that member is refused until then.

`python3 scripts/sandbox-smoke.py --proof-limit` does it: on our machine the cap answered after 585
requests in 19 seconds — 144 `200`, 440 `429 RATE_LIMITED`, then `429 DPOP_PROOF_LIMIT`.

The sandbox gateway allows 6000 requests a minute per address; production allows 120 by default
([rate limits](sync-guide.md#rate-limits-quota-and-back-off)), checked before the token. Production
therefore refuses such a flood from one address with
`429 RATE_LIMITED` long before the proof cap — the cap guards a member whose clients call from
several addresses. Keep to the [rate limits](sync-guide.md#rate-limits-quota-and-back-off), and
handle both answers.

## Checking the sandbox

[`scripts/sandbox-smoke.py`][smoke] signs in as `sandbox-member` through the device flow with a DPoP
key — opening the bare `verification_uri`, typing the code and checking that the code-entry and
consent pages carry the phishing warning and the consent page the code — reads every resource,
resolves one entry per kind, syncs one blueprint, stock lot and ship, and checks that a default
blueprint cannot be removed. With `--conformance` it also sends every change-set fixture of the
[conformance examples](examples/README.md): valid ones as dry runs, which must be accepted, and
invalid ones, which must be refused. With `--proof-limit` it finally floods the gateway until
[the proof cap](#the-members-proof-cap) answers; the member is then refused for about 30 seconds.
It needs only Python 3 and runs again on the same data; `--user` and `--password` pick another
account, and `--demand withheld` expects the withheld org demand of `sandbox-member-2`. CI runs it
against the published images after every publish and once a week.

```sh
python3 scripts/sandbox-smoke.py --conformance
```

It talks to Keycloak at `http://127.0.0.1:18080` and maps the redirects to it, so it runs without
the hosts entry too; on Windows run it as `python scripts/sandbox-smoke.py`. Its code is also a
worked example of a scripted device login.

## Troubleshooting

**See what runs and read the logs.** The services are `keycloak-dev`, `backend-dev`,
`frontend-dev`, `ingest-dev` (the gateway), `db-backend-dev`, `db-keycloak-dev` and `redis-dev`:

```sh
docker compose --env-file docker/sandbox/sandbox.env \
  -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml \
  --profile sandbox ps
docker compose --env-file docker/sandbox/sandbox.env \
  -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml \
  --profile sandbox logs --tail 200 ingest-dev
```

`docker logs basetool-sandbox-ingest-dev-1` shows the same. The gateway logs one line per request
with its `correlationId`, which every answer carries as `X-Correlation-Id`.

**Start from scratch.** `scripts/sandbox.sh reset` or `./scripts/sandbox.ps1 reset` deletes every
container and volume of the sandbox and starts it again.

**A port is already in use.** `up` stops with an error naming the port, such as "port is already
allocated" or "ports are not available". Find what holds it:

| | Command |
| --- | --- |
| Docker | `docker ps --filter publish=18080` |
| Windows | `Get-Process -Id (Get-NetTCPConnection -LocalPort 18080 -State Listen).OwningProcess` |
| Linux | `sudo ss -ltnp 'sport = :18080'` |
| macOS | `sudo lsof -nP -iTCP:18080 -sTCP:LISTEN` |

On Windows every port Docker publishes is held by `com.docker.backend`; ask `docker ps` which
container it is.

**Another Basetool stack is running.** The Basetool's own development and test stacks use the same
ports and the same subnets, so the sandbox cannot run beside them. Stop that stack first; the error
"invalid pool request: Pool overlaps with other one on this address space" means a Docker network
of it, or of any other project, uses a subnet of the sandbox in `172.28.0.0/16`.
`docker network ls` and `docker network inspect <name>` show which.

**`host.docker.internal` does not resolve, or resolves to your network address.** The browser
cannot open the device page, or your client cannot reach the issuer. Add the hosts line from
[the issuer's host name](#the-issuers-host-name); on Windows it must stand above Docker Desktop's
block.

**TLS errors.** "certificate verify failed", "unable to find valid certification path" or
`UntrustedRoot` mean your HTTP client does not trust the test CA: see
[trusting the test CA](#trusting-the-test-ca). "The revocation status is unknown" is Windows' curl;
add `--ssl-no-revoke`. Call the gateway as `localhost`, `127.0.0.1` or `host.docker.internal`; a
name the certificate does not carry, such as your machine's name, fails the host check.

**`401 DPOP_INVALID` on every call.**

- The proof's `htu` differs from the URL you called: `https://localhost:11262/…` and
  `https://127.0.0.1:11262/…` are different strings, and so is `https://localhost/…` without the
  port. Build `htu` from the very URL you send.
- The proof's `iat` is more than 30 s away from the gateway's clock. Compare your clock with the
  `Date` header of any answer.
- The proof lacks the server nonce: the first call is answered `401` with `DPoP-Nonce`, which your
  client must retry once with.

**`503 EXCHANGE_DISABLED` right after `up`.** Normal for up to about 15 s, until the gateway sees
the seeded switch; wait at least `Retry-After`. Later it means an admin turned the exchange off at
*Data exchange*.

**`403 TERMS_NOT_ACCEPTED`.** The images and the checkout disagree on the Terms of Use version: see
[versions and updates](#versions-and-updates).

**The token endpoint refuses the proof.** Keycloak answers `400 invalid_request`:

- "DPoP proof is not active" — the `iat` is outside its window, from about 25 s before to 15 s
  after its own time; a clock that runs ahead fails the sign-in itself.
- "DPoP HTTP URL mismatch" — the `htu` is not the token endpoint's URL as you called it. Keycloak,
  too, compares it with the request: `http://host.docker.internal:18080/…/token` when you call that
  address, `http://127.0.0.1:18080/…/token` when you call this one.

**A scripted login loses its session at Keycloak.** Keycloak marks its cookies `Secure` even when
it is called over plain HTTP at `127.0.0.1`, so a cookie jar that follows the rules drops them on
the next plain-HTTP request. A browser at `host.docker.internal` is not affected. A script that
drives the pages itself has to keep the cookies, as the [smoke test][smoke] does.

**The consent page shows the wrong account, or no sign-in page appears.** A device login joins the
browser's session: if the browser is signed in to the web frontend as `sandbox-admin`, the device
login consents for `sandbox-admin`. Sign out at the web frontend or use a private window. The
consent page itself appears on every device login.

**A browser forces HTTPS on other `localhost` sites afterwards.** The gateway and the web frontend
send `Strict-Transport-Security`, which a browser may remember for `localhost`. Delete the entry
for `localhost` in the browser's HSTS settings (`chrome://net-internals/#hsts` in Chrome and Edge).

**Nothing helps.** Collect the output of `docker compose … ps` and `logs` from above, the
`correlationId` of the failing answer, the image tag and `docker compose version`, and open an
issue at [krt-profit/basetool][issues]. Never attach a credential of anything but the sandbox.

## What the sandbox does not do

- **No Discord sign-in.** Production members sign in through Discord; the sandbox accounts are
  plain Keycloak accounts with a password.
- **No e-mail.** Neither the Basetool nor Keycloak sends mail.
- **No production data.** No member, ship or blueprint of the real organisation, and no catalogue
  import: only the [seeded data](#seeded-data) and what you write.
- **One global switch, as in production.** The exchange switch turns the whole exchange off for
  every client and member; the seed turns it on. It is not a per-client setting — suspending a
  client is.
- **Not reachable from other machines.** Every port is bound to `127.0.0.1`. A phone or a second PC
  cannot use it.
- **No persistence.** `down` and `reset` delete every account change, write and registry change.
- **Not production's addresses.** The issuer, the gateway's address and the `htu` rule differ from
  production ([authentication](authentication.md)), which is why your client must pin the
  production issuer and switch everything together.
- **Not production's per-address limit.** The sandbox gateway allows 6000 requests a minute per
  address, production 120 by default, so that [the proof cap](#the-members-proof-cap) can be
  reached from one machine. Test your back-off against the per-member limits, which are
  production's.

## Maintaining the sandbox

The realm, [`docker/sandbox/keycloak/realm-iri.json`][realm], is generated by
[`scripts/build-sandbox-realm.py`][generator] from the test realm base
[`scripts/keycloak/test-realm-base.json`][base], the production provisioner's clients, scopes and
realm settings, and Keycloak's built-in client scopes in
[`scripts/keycloak/builtin-client-scopes.json`][builtin]. The same run writes the realm of our own
E2E stack, which uses the same Keycloak image. Regenerate both after changing any of these with
`python3 scripts/build-sandbox-realm.py`; CI fails on a stale file (`--check`). The built-in
scopes are read from a realm that the pinned Keycloak version created on its own; renew them when
Keycloak is upgraded. The seed is [`docker/sandbox/seed.sql`][seed], safe to run twice.

The images are built by [`sandbox-images.yml`][images-workflow] from
[`docker-compose.sandbox-build.yml`][build-compose], the same file `--build` uses, for each platform
on a runner of that platform. Before anything is published, each application image must refuse the
`prod` profile, the Keycloak image every command but `start-dev`, and Trivy must find no secret;
the published tag must hold both platforms with the run's commit as their revision.

[adr-0139]: https://github.com/krt-profit/basetool/blob/main/docs/adr/0139-shared-committed-tls-material-for-the-test-stack.md
[repo]: https://github.com/krt-profit/basetool
[issues]: https://github.com/krt-profit/basetool/issues
[test-ca]: https://github.com/krt-profit/basetool/blob/main/docker/test-tls/basetool-test-ca.crt
[realm]: https://github.com/krt-profit/basetool/blob/main/docker/sandbox/keycloak/realm-iri.json
[generator]: https://github.com/krt-profit/basetool/blob/main/scripts/build-sandbox-realm.py
[base]: https://github.com/krt-profit/basetool/blob/main/scripts/keycloak/test-realm-base.json
[builtin]: https://github.com/krt-profit/basetool/blob/main/scripts/keycloak/builtin-client-scopes.json
[seed]: https://github.com/krt-profit/basetool/blob/main/docker/sandbox/seed.sql
[smoke]: https://github.com/krt-profit/basetool/blob/main/scripts/sandbox-smoke.py
[images-workflow]: https://github.com/krt-profit/basetool/blob/main/.github/workflows/sandbox-images.yml
[build-compose]: https://github.com/krt-profit/basetool/blob/main/docker-compose.sandbox-build.yml
