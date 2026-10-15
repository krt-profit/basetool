# Quick start

From nothing to a first synced blueprint against the [local sandbox](sandbox.md), in about fifteen
minutes. The example is Python with the standard library and the
[DPoP reference implementation](dpop-reference/README.md); every step is plain HTTP and ports to any
language. The same steps, scripted end to end, are [`scripts/sandbox-smoke.py`][smoke].

## 1. Start the sandbox

In a checkout of [krt-profit/basetool][repo], with `host.docker.internal` resolving to `127.0.0.1`
([the issuer's host name](sandbox.md#the-issuers-host-name)):

```sh
scripts/sandbox.sh up
```

On Windows run `./scripts/sandbox.ps1 up` in PowerShell. The command returns once every service is
healthy and the seed is applied; for up to about 15 s afterwards the gateway still answers
`503 EXCHANGE_DISABLED`. The [sandbox page](sandbox.md) lists what your machine needs and, under
[troubleshooting](sandbox.md#troubleshooting), what to do when the start or a first call fails. The
sandbox's addresses:

| | |
| --- | --- |
| Keycloak (issuer) | `http://host.docker.internal:18080/auth/realms/iri` |
| Gateway | `https://localhost:11262/exchange/v1` |
| Web frontend | `https://localhost:18081` |
| Client | `sandbox-client` — public, no secret |
| Member | `sandbox-member` / `sandbox-member-pw-do-not-use-in-prod` |

The gateway serves HTTPS with the committed test certificate; trust
[`docker/test-tls/basetool-test-ca.crt`][test-ca] for the sandbox only — the sandbox page shows
[how, per language](sandbox.md#trusting-the-test-ca).

## 2. Open the installation's key

Each installation of your client has one P-256 key. Its thumbprint is the installation's identity,
and every token is bound to it. Run the examples from the repository root, so the reference package
is found:

```python
import base64, json, ssl, sys, time, urllib.error, urllib.parse, urllib.request, uuid

sys.path.insert(0, "docs/exchange/dpop-reference")
from dpop_reference import NonceCache, asks_for_nonce, authorization_header, build_proof, open_installation_key

KEYCLOAK = "http://host.docker.internal:18080/auth/realms/iri/protocol/openid-connect"
GATEWAY = "https://localhost:11262"
CLIENT = "sandbox-client"
TLS = ssl.create_default_context(cafile="docker/test-tls/basetool-test-ca.crt")

key = open_installation_key("QuickStart DPoP", key_file="quickstart-dpop.pem")
print("installation", key.thumbprint())
```

A real client keeps the key where the platform keeps keys, never in a plain file when it can avoid
it ([client security](client-security.md)).

## 3. Ask for a device code

Request only the scopes the client needs; `offline_access` gives a refresh token.

```python
def post_form(url, fields, dpop=None):
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    if dpop:
        headers["DPoP"] = dpop
    request = urllib.request.Request(url, urllib.parse.urlencode(fields).encode(), headers)
    try:
        with urllib.request.urlopen(request) as answer:
            return answer.status, json.load(answer)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


status, device = post_form(KEYCLOAK + "/auth/device", {
    "client_id": CLIENT,
    "scope": "offline_access exchange.connect exchange.blueprints.read exchange.blueprints.write",
})
print("code", device["user_code"], "- open", device["verification_uri"])
```

## 4. Let the member approve

Open `verification_uri` in a browser, sign in as `sandbox-member`, type the printed code and grant
the requested access on the consent page. A real client shows the `user_code` and the bare
`verification_uri` and lets the member type the code in their own browser; it never opens or sends
`verification_uri_complete`, which skips the page with the phishing warning
([authentication](authentication.md)), and it never asks for the password itself.

## 5. Poll for the token

Every poll carries a **new** DPoP proof for the token endpoint, without an access token. The token
that comes back is bound to the key: its `cnf.jkt` is the key's thumbprint.

```python
token = None
while token is None:
    time.sleep(device["interval"])
    status, answer = post_form(KEYCLOAK + "/token", {
        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        "device_code": device["device_code"],
        "client_id": CLIENT,
    }, build_proof(key, "POST", KEYCLOAK + "/token"))
    if status == 200:
        token = answer
    elif answer.get("error") == "slow_down":
        device["interval"] += 5
    elif answer.get("error") != "authorization_pending":
        raise SystemExit(answer)

claims = json.loads(base64.urlsafe_b64decode(token["access_token"].split(".")[1] + "=="))
assert claims["cnf"]["jkt"] == key.thumbprint()
```

## 6. Call the API

Each call sends `Authorization: DPoP <token>` and a fresh proof for its method and URL. The gateway
may answer `401` with a `DPoP-Nonce` header; then build a new proof with that nonce and send the
request once more. A write carries an `Idempotency-Key`, the same one when you repeat it.

```python
nonces = NonceCache()


def call(method, path, body=None, idempotency_key=None):
    url = GATEWAY + path
    access = token["access_token"]
    for _ in range(2):
        headers = {
            "Authorization": authorization_header(access),
            "DPoP": build_proof(key, method, url, access_token=access, nonce=nonces.get(url)),
            "Accept": "application/json",
            "User-Agent": "QuickStart/0.1.0 (+https://example.org/quickstart)",
        }
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        request = urllib.request.Request(url, data, headers, method=method)
        try:
            with urllib.request.urlopen(request, context=TLS) as answer:
                nonces.update(url, dict(answer.headers))
                return answer.status, json.load(answer)
        except urllib.error.HTTPError as error:
            nonces.update(url, dict(error.headers))
            raw = error.read()
            if asks_for_nonce(error.code, dict(error.headers)):
                continue
            return error.code, json.loads(raw) if raw else {}
    raise SystemExit("the nonce challenge repeated")


print(call("GET", "/exchange/v1"))
print(call("POST", "/exchange/v1/me/installation", {"label": "Quick start"}))
```

The service document names the member's capabilities for this client and the limits. Labelling the
installation lets the member tell it apart on *Connected applications*.

## 7. Read the first sync page

A feed without `cursor` is a snapshot, page by page. Keep the last `nextCursor`: it is the position
the next sync reads the changes from.

```python
cursor, blueprints = None, []
while True:
    status, page = call("GET", "/exchange/v1/me/blueprints" + (f"?cursor={cursor}" if cursor else ""))
    blueprints += page["items"]
    cursor = page["nextCursor"]
    if not page["hasMore"]:
        break
print(len(blueprints), "blueprints, cursor", cursor)
```

## 8. Write the first change

Resolve your own item reference to the Basetool's, then send a change set. The first sync of an
installation only adds; read the [sync guide](sync-guide.md) before anything that removes.

```python
status, resolved = call("POST", "/exchange/v1/catalog/resolve",
                        {"kind": "BLUEPRINT", "refs": [{"scRecord": "BP_CRAFT_SBXM_HELMET_01"}]})
ref = resolved["results"][0]["ref"]
status, result = call("POST", "/exchange/v1/me/blueprints/changes",
                      {"ops": [{"op": "add", "ref": ref, "provenance": {"source": "log"}}]},
                      idempotency_key=str(uuid.uuid4()))
print(status, result)
```

The answer counts `applied`, `unchanged` and `notApplied`. Sign in to the web frontend at
`https://localhost:18081` as `sandbox-member`: the Sandbox Helmet is in *My Blueprints*, and
*Connected applications* lists the client, the installation "Quick start" and the change, which the
member can undo there.

## Next

- [Authentication](authentication.md): refreshing, disconnecting, every sign-in error.
- [Sync guide](sync-guide.md): baselines, conflicts, tombstones, the mass-change guard, back-off.
- The resources: [blueprints](resources/blueprints.md), [stock](resources/stock.md),
  [ships](resources/ships.md), [org demand](resources/org-demand.md),
  [org units](resources/org-units.md), [drafts](resources/drafts.md).
- [Conformance fixtures](examples/README.md): test your requests against them, or run
  `python3 scripts/sandbox-smoke.py --conformance`.
- [Test scenarios](sandbox.md#test-scenarios): conflicts, tombstones, the mass-change guard,
  disconnects, suspension and the limits, each with the answer to expect.
- `scripts/sandbox.sh down` stops the sandbox and deletes its data.

[repo]: https://github.com/krt-profit/basetool
[smoke]: https://github.com/krt-profit/basetool/blob/main/scripts/sandbox-smoke.py
[test-ca]: https://github.com/krt-profit/basetool/blob/main/docker/test-tls/basetool-test-ca.crt
