# Becoming an approved client

The Exchange API is open only to approved third-party applications (REQ-XCH-002, ADR-0217). Approval
is public, case by case, and for the capabilities a client actually needs.

## How to apply

1. Open an issue with the **Exchange client application** template. It asks for the product, the
   publisher, a security contact, the privacy statement, the platforms, the capabilities you need,
   and your confirmation of each of the [client security requirements](client-security.md).
2. The owner reviews the application and your client against the requirements — the source where it
   is public, the behaviour of a build where it is not. Closed-source clients can be approved.
3. A pull request adds your client to the [approved-clients list][approved] and to the list the
   Keycloak provisioner reads. **Its merge is the approval.** An administrator then registers the
   client with the approved capabilities under *Connected applications* in the Basetool's
   administration.

## Criteria

- Token storage, DPoP, issuer pinning and the sync behaviour as the
  [client security requirements](client-security.md) state.
- A published privacy statement and a security contact.
- Only the capabilities the client's features need; each further capability is a new pull request to
  the approved-clients list before an administrator grants it.
- For a browser extension (Chrome, Edge or Firefox, Manifest V3): the credential storage, the
  minimal host permissions and the disconnect of the
  [browser-extension requirements](client-security.md#browser-extensions), checked against the
  manifest of the release you apply with. Give the fixed extension id of each Chrome or Edge build
  and a link to that release's `manifest.json` in the application; the gateway and the sign-in
  admit exactly those origins. The id may come from a `key` in the manifest or from a store; a
  store listing, publication and code signing are not required.
- Code signing of releases is recommended, not required.
- Trying the client against the [sandbox](sandbox.md) before applying is recommended, not required.

## After approval

- A reported token-handling flaw must be fixed in a published release within **7 days**, or the
  client is suspended, or restricted to a minimum version that has the fix.
- Administrators can suspend a client, revoke it for every member, or require a minimum version at
  any time; members can disconnect it themselves.
- Contract changes are announced; a future `v2` runs alongside `v1` for at least 12 months, and a
  minimum-version bump is announced in advance except in a security emergency.

## Terms of the integration

Access is limited to the approved capabilities; Basetool data stays on the member's own device, with
no telemetry and no transfer to third parties; tokens are handled as the security requirements
state; incidents are reported to the Basetool's security contact; the Basetool may suspend the
client at any time.

[approved]: https://github.com/krt-profit/basetool/blob/main/docs/legal/approved-clients.md
