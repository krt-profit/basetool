# ADR-0411 — Unsaved create-form input is kept as a tab-scoped draft in sessionStorage

- **Status:** Accepted
- **Date:** 2026-11-25
- **Amended:** 2026-11-25 — edit forms keep a draft against the lock versions it was made against
  (issue #3750); create dialogs keep a draft under their own mark and are offered on the page
  (issue #3747)
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

## Amendment 1 (2026-11-25) — edit forms keep a draft against its lock versions

Decision 5 left edit forms out: a restored draft submitted with the version the page was reloaded
with would overwrite a change a peer saved in between. Edit forms now opt in under one rule.

1. **The draft records its base.** An edit form marks its lock-version fields
   `data-krt-draft-base`. The draft stores their values as its base, apart from the snapshot, so
   decision 4 still holds: no version is ever a restored field value.
2. **The offer compares the base.** A form rendered with other versions still offers the draft, and
   the offer says that the entry was changed meanwhile and that a save refuses overlapping edits
   instead of overwriting them.
3. **Restore writes the base back.** The restored form submits the versions its edits were made
   against, so the optimistic lock decides: an untouched part saves, a part a peer changed ends in
   the form's existing 409 handling. A draft never raises its own versions to the current ones.
4. **The key's path names the entity.** Edit pages carry the entity id in their path, so two
   entities edited in one tab never share a draft; a form whose path does not name its entity is
   not drafted until it does.
5. **A swap while the offer stands keeps the draft.** A live update that re-renders a form with a
   standing offer offers the draft again on the new form, with a fresh comparison, instead of
   dropping it; a swap of a form holding edits still drops it with decision 3.

Issue #3750 drafts the refinery-order detail form, the mission edit form, the member edit form and
the six profile sections under this rule.

## Amendment 2 (2026-11-25) — create dialogs keep a draft under their own mark

Create dialogs did not fit decision 3: a reload closes the dialog, so an offer inside it is never
seen, and most of them are `no-track`, so they never carry the unsaved mark a draft waits for. The
bank forms are `no-track` because the leave warning once had a single page-wide mark that only a
full submit cleared, so every in-place dialog save and every cancelled dialog left a false warning;
a cancelled dialog still does. @greluc chose to keep that and to give dialogs a mark of their own.

1. **A draft mark for untracked dialogs.** A trusted edit in an untracked drafted dialog form sets
   `data-krt-draft-edited`; only the drafts read it. The leave warning and live sync stay as they
   are, and a clear of the unsaved mark (a successful save, `resetUnsavedChanges()`) removes it too.
2. **Closing keeps the draft.** Closing a dialog is not a discard: its input stays in the dialog as
   before, and a reload offers it. A page that clears the leave warning when its dialog closes
   without saving hands the tracked form's unsaved mark to the draft mark instead of clearing it,
   so the warning is off and the draft stays.
3. **The offer is on the page, restore goes through the opener.** The page shows the offer at the
   top of its content and names the dialog. Restore clicks the control that opened the dialog, so
   the page primes the dialog's context as it always does, and writes the draft once the dialog is
   open and done loading, for a page that loads the dialog's content first or marks the open
   dialog `aria-busy` while it loads it. The draft records that opener only as a hash of its id or,
   without one, of its `data-*` attributes, which name its action and context, so no hidden value
   is stored; a draft whose opener the page no longer has is dropped.
