# ADR-0411 — Unsaved create-form input is kept as a tab-scoped draft in sessionStorage

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** @greluc
- **Related:** spec REQ-FE-041 · REQ-FE-024 · REQ-FE-040 · ADR-0397 · issue #3734 ·
  `static/js/kernel/krt-drafts.js` · `kernel/layout/DraftScopeAdvice`

## Context

A member creating a refinery order types a dozen material rows. Anything that makes the page go
away loses all of it: a reload in a release's restart window, the re-authentication after an
update, a phone browser discarding the background tab, an accidental reload or tab close. The
maintenance notice (REQ-FE-040) stops the reload in the window, but not the other cases. The
input has to survive the page somewhere in the browser, and that place decides who can read it and
how long it lives.

- **`sessionStorage`** belongs to one tab and one origin. It survives a reload and a navigation
  within the tab and is cleared when the tab closes.
- **`localStorage`** survives the browser. A draft would stay on a shared or borrowed device after
  the member left, and every tab of the origin would see it.
- **The server** (a draft endpoint and table) survives devices too, but it is a write path of its
  own with tenancy, audit and retention, for input the member never saved, and it fails in exactly
  the restart window it is meant to cover.
- **IndexedDB** has the lifetime of `localStorage` and only adds an asynchronous API.

The tab may be used by another member after a sign-out and a sign-in. A key that names the member
must not reveal who that is, and it must stay the same when the same member signs in again, or the
re-authentication case loses the draft.

## Decision

1. **Drafts live in the tab's `sessionStorage`**, under `krt-draft:<scope>:<org unit>:<form
   key>:<path>`, and nowhere else. Nothing reaches the server or outlives the tab.
2. **The member scope is opaque and stable**: the first 32 hex characters of the SHA-256 of a fixed
   prefix plus the identity provider's subject, rendered by `DraftScopeAdvice` on every layout page
   (`data-krt-draft-scope`). It contains no name, no e-mail address and not the subject itself. A
   page drops the drafts of every other scope when it loads, and a logout drops all of them.
3. **Forms opt in** (`data-krt-draft`, a schema version) and a draft exists only while the form
   carries the unsaved mark of REQ-FE-024. It goes with that mark: a successful save, a reset, a
   discard, a swap that replaces the form.
4. **Security-relevant fields are never stored**: hidden fields, the CSRF token, `version` fields,
   passwords and file inputs, plus any control a form marks `data-krt-draft-skip`.
5. **Create forms first.** An edit form needs the entity's `version` in the draft and a warning
   when it changed meanwhile, so a restored draft never overwrites a peer's change silently; that is
   a decision for the change that drafts the first edit form.

## Consequences

- A reload, a re-authentication or a discarded background tab in the same tab offers the input
  back; closing the tab ends it. A member who opens the form in a second tab starts fresh there.
- Free text a member typed stays readable to scripts of the origin for the life of the tab, which
  they already are while the form is open; it is never sent anywhere and is gone with the tab.
- A browser that refuses storage (quota, a locked-down profile) keeps no draft; the form works as
  before.
- Each further form opts in with a small change of its own; forms with script-built rows register
  an adapter for them.
