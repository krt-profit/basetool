# ADR-0158 — A relayed request parameter is bound to the backend's own type

- **Status:** Accepted
- **Date:** 2026-09-04
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-051](../specs/security-and-access.md)
- **Related:**
  [ADR-0032](0032-frontend-single-resilience-pass-at-webclient-filter.md) (the `WebClient` seam every
  relayed value passes through), REQ-FE-016 (the free-text term, escaped once rather than narrowed),
  REQ-API-005 (the backend's own sort-field whitelist), REQ-AUDIT-002 (the audit tab allowlist this
  generalises)
- **Source:** CodeQL `java/ssrf`, 8 critical alerts on `refs/heads/main`, 2026-09-04

## Context

The frontend is a proxy. A page or proxy controller binds a request parameter, drops it into a
`UriComponentsBuilder` or a URI template, and hands the result to `WebClient`. CodeQL's `java/ssrf`
query reports every one of those as a critical finding: user-controlled data reaching a URI sink.

Reading the eight alerts apart, they were not all the same thing.

**Two were real.**

- `PromotionProxyController#updateEvaluation` built its path with
  `UriComponentsBuilder.fromPath(template).buildAndExpand(userId, categoryId).toUriString()`, and a
  comment above it asserted that this "RFC-3986 path-encodes" the value. It does not.
  `buildAndExpand` returns `UriComponents` in the **raw** encode state, and `UriComponents`'
  `toUriString()` emits raw components verbatim — unlike `UriComponentsBuilder#toUriString()`, which
  is `build().encode().toUriString()`. The two spellings differ by one call and by whether the value
  is encoded at all.
- `AdminSyncReportsPageController#deleteOld` concatenated its `source` tab straight into the query
  string of a **DELETE that purges rows**. The same method's redirect trimmed and upper-cased the
  tab while the relayed query took the raw string, so `?source=scwiki` landed the user on the SC Wiki
  tab and sent `scwiki` to the backend — which does not recognise it and therefore purges *both*
  catalogues.

**The rest were not exploitable**, because `UriComponentsBuilder#toUriString()` percent-encodes
query values (`&`, `=`, `#`, `{` are all `Type.QUERY_PARAM`-illegal) and the two admin pages passed
their identifiers through `URLEncoder.encode`. CodeQL models neither as a sanitizer, so it saw taint
reach the sink in every case.

Dismissing those six would have been defensible and would have been wrong. The safety was
**incidental**: it depended on which of two near-identical `toUriString()` overloads a call site
happened to use, and the one real bug is exactly what that distinction costs when someone gets it
backwards. `URLEncoder` was not free either — it is *form* encoding, so a value containing a space
becomes `+`, which in a path segment is a literal plus. The file already carried two long comments
explaining that trap for the free-text `q` parameter.

## Decision

**A request parameter the frontend relays into a backend URI is bound to the type the backend's own
controller declares for it.** Where the backend says `Instant`, the proxy says `Instant`; where it
says `UUID`, the proxy says `UUID`. Where no type expresses the constraint, the value is narrowed
against the very allowlist the page renders — the audit tabs, the event types, the client ids, the
board's four sort keys — before it is relayed.

Concretely:

- **Periods** (`from`, `to`, `before`) bind as `java.time.Instant` with
  `@DateTimeFormat(iso = DATE_TIME)`, matching `AuditAdminController`, `BankAccountController` and
  `OrgUnitBankController`. What is relayed is `Instant#toString()`.
- **Member identifiers** (`userSub`, `actorUserId`, `userId`) bind as `java.util.UUID`. Keycloak
  issues the `sub` as a UUID and every backend endpoint behind these proxies already declares
  `@PathVariable UUID`, so a non-UUID never had a route through them.
- **Vocabulary filters** pass `RelayParams.oneOfOrNull(...)` against the list the surrounding
  controller renders as `<select>` options, and **sort specifications** pass
  `RelayParams.sortSpecOrNull(...)`.
- **Free text** (`q`) keeps the existing treatment: a `WebClient` URI-template variable, encoded
  exactly once across the hop (REQ-FE-016). It is the one relayed value that legitimately contains
  arbitrary characters, so it is escaped rather than narrowed.

Where the value selects something on a page, an unparseable value degrades — no member selected, no
filter applied — exactly as an unknown tab already fell back to the default tab. Where it addresses
a proxy seam, it is a `400` from Spring's own type conversion.

`URLEncoder.encode` disappears from both admin page controllers: a UUID needs no encoding, which
removes the form-encoding trap rather than documenting it a third time.

## Alternatives considered

**Dismiss the six non-exploitable alerts as false positives.** Accurate about today and useless
about tomorrow. The finding these alerts collectively point at is real — the frontend relays opaque
strings into URIs and relies on an encoding step that one call site had already got wrong — and a
dismissal records the opposite conclusion for the next reader.

**Percent-encode everything: add `.encode()` at all 50 `toUriString()` sites.** This fixes the
`buildAndExpand` bug and nothing else. It leaves every relayed value an arbitrary string one
refactor away from the same class of defect, does not narrow what reaches the backend, and would not
have silenced CodeQL either, which does not model `UriComponents#encode` as a sanitizer. Escaping
keeps a hostile value alive one hop further; typing removes it.

**Validate with regular expressions at each site instead of typing.** A regex for "a Keycloak sub"
is a worse `UUID` and a regex for "an ISO instant" is a worse `Instant` — both are hand-maintained
restatements of a constraint the JDK already expresses, and both drift from the backend signature
they are supposed to mirror. `RelayParams` keeps exactly one regex, for the Spring sort
specification, which has no type.

## Consequences

- A malformed period or member id on a proxy seam is now a `400` at the frontend rather than a `400`
  from the backend one hop later. The status the browser sees is unchanged; the log line moves.
- `/admin/audit-log?from=notadate` is a `400` error page where it used to render with an error
  banner. The filter form only ever submits `Date#toISOString()` values, so this is reachable only
  by hand-editing the URL.
- An event type or client id that is not on the active tab's list is now ignored rather than
  forwarded. The page's own `<select>` cannot produce one.
- The frontend gains one shared list of audit tabs (`AuditDomains`). The page controller and the
  export/purge proxy each kept their own copy and had already drifted: the proxy's never gained
  `MARKET`, so the Materialbörse tab rendered while its export, JSON and purge buttons answered
  `400`. That is fixed as part of this change.
- Adding a proxy endpoint now carries an obligation: look at the backend signature and mirror it.
  That is cheaper than it sounds — the signature is the specification — and it is the only part of
  this decision that needs remembering.

