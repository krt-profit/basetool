# ADR-0138 — The Terms-of-Use wording is a backend resource, readable without a token

- **Status:** Proposed
- **Date:** 2026-08-19
- **Related:** [ADR-0136](0136-external-contract-set-for-shipped-clients.md) (the contract set this
  joins) · [ADR-0135](0135-public-api-vhost-not-a-gateway.md) (the allow-list this appears on) ·
  specs `REQ-SEC-028`, `REQ-API-009` · app repo `REQ-APP-AUTH-009` ·
  `TermsDocumentStructureTest`, `SecurityTest`

## Context

The Terms of Use are versioned and enforced: `TermsAcceptanceAccessFilter` refuses the API until the
member has accepted the wording in force, and the version is a **content digest** of the text
(`generateTermsVersion`), so editing a clause re-prompts everybody automatically. That design has
one unstated assumption — that whoever displays the text and whoever records the consent are looking
at the same document.

They were, as long as there was one client. The wording lived in the frontend's
`messages_de.properties`, and `terms-body.html` rendered it for both the public `/terms` page and
the consent gate. A parity test pinned every key as declared-and-rendered.

The Android app breaks that assumption, and there was no good way to keep it:

- **Bundle the text in the APK.** The version digest is computed from the server's copy, so the app
  would show the wording it shipped with and accept whatever the server currently has in force. A
  member reading v2.1 and consenting to v2.2 is not a display bug — it is consent to a document they
  were never shown, on a build distributed through GitHub Releases and Obtainium where adoption is
  slow and uneven. Drift is not a risk here; it is the steady state.
- **Send the member to a browser mid-consent.** No drift, but the gate's one job is to obtain
  informed consent, and the reading happens one app away from the checkbox. It also loses the
  scrollable in-app document the design specifies (chapter 04).
- **Keep two copies in step by discipline.** A legal text maintained in two repositories, with the
  digest hashing only one of them.

## Decision

**The wording moves to the backend and is served as structured data at
`GET /api/v1/terms/document`, anonymously.**

- The `terms.*` keys **move** — not copy — from the frontend bundles into the backend's. The digest
  task now hashes the backend bundle, because a digest must hash the text that is actually served.

  **No member re-consents because of this change, and that is enforced rather than observed.** The
  digest is the acceptance key: `TermsAcceptanceService` matches a stored `terms_version` against
  the version in force, so an unchanged digest leaves every recorded acceptance valid. Three facts
  chain into that guarantee, and CI holds the second one:

  1. `terms-version.properties` is **byte-identical to `main`** in this change — the version in
     force is still `07d8b5ff678b80a2`.
  2. `TermsVersionParityTest` re-derives the digest from the backend bundle and fails the build when
     the committed value disagrees. Since it passes, the 52 moved clauses still hash to
     `07d8b5ff678b80a2` — which is a proof that the wording is byte-identical, not an assurance that
     somebody looked.
  3. An unchanged version means `hasAcceptedCurrentTerms` answers exactly as before for every
     existing row.

  Had the move altered so much as a trailing space in one clause, step 2 would have failed the build
  rather than re-prompting the squadron in production.

- The response is **structure, not HTML**: title, intro, ordered sections, each with paragraphs and
  their bullets. A Thymeleaf page and a Compose screen cannot share a markup blob, and shipping HTML
  would force the app to parse and sanitise a document it only needs to display.

- The **key convention is the schema**. `terms.h1_4` *is* the declaration that a fourth section
  exists; `TermsDocumentService` walks the numbering. A clause added to the bundle therefore appears
  in both clients with no code change, which is the property that makes one source worth having.

- It carries the **version** alongside the text, so a client can display and accept in one exchange
  without the two referring to different wordings.

- It joins the **frozen external contract set** (ADR-0136): a field dropped from this response
  blanks a legal document on a build nobody can redeploy.

### Why anonymous

This is the part that grows the anonymous surface, which the guest-mode decision deliberately froze,
so it is stated plainly rather than left to the SecurityConfig entry.

**A text everybody must be able to read before agreeing to anything cannot require having agreed.**
The public `/terms` page is reachable with no session today and must stay so; it now fetches this
endpoint anonymously. Nothing new becomes public: the identical wording is already served to the
world at `/terms`, and this endpoint publishes the same bytes through a different door.

The split is between the *document* and the *record*: `/api/v1/terms/document` is anonymous,
`/status` and `/acceptance` stay authenticated, and they live in **separate controllers** so the
difference is visible at the top of a file rather than hidden as a method-level override. A
permitAll written one segment too short — `/api/v1/terms/**` — would have opened the consent record
too; `SecurityTest` pins both halves.

## Consequences

**Accepted cost: a backend outage takes the public `/terms` page with it**, where before it rendered
from a local bundle. This was weighed and accepted. A fallback copy in the frontend would reintroduce
exactly the drift being removed, and a fallback that silently serves *older* terms than the ones
being accepted is worse than a page that is briefly unavailable. It is also the failure mode every
other page in the frontend already has.

**The consent gate fails loudly when the wording cannot be read**, while the *status* read stays
tolerant. The asymmetry is deliberate: a stale "not accepted" costs one extra click, but rendering
the gate without its text would ask a member to agree to a blank page.

**A gap in the numbering truncates the document silently.** The walk stops at the first missing key,
so `terms.p_4_7` without `terms.p_4_6` drops every clause after it — present in the repository, inside
the version digest, invisible to every reader. `TermsDocumentStructureTest` carries what the
frontend's parity test used to and fails the build on exactly that, plus on a translation whose shape
differs from the German original.

**The privacy-policy hyperlink left the legal text.** Section 11 carried an inline `<a>` to
`/privacy`. A document shared with a Compose client cannot carry markup, so the link is now a page
affordance rendered beneath the document. The clause still names the Datenschutzerklärung in its own
words — which is what made the anchor a convenience rather than part of the wording in the first
place.

**The response is not cached by the frontend.** It varies by `Accept-Language`, and `CachedCatalog`
is keyed by URI and documented as global-only, so caching it there would eventually serve one member
the other language.
