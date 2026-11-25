/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import {
    draftKey,
    readDraft,
    removeDraft,
    removeDrafts,
    writeDraft,
} from '/js/kernel/krt-draft-store.js';
import {
    readBase,
    restoreBase,
    restoreForm,
    sameBase,
    snapshotForm,
} from '/js/kernel/krt-draft-snapshot.js';
import {
    dialogOf,
    findOpener,
    openerOf,
    showDialogOffer,
    trackDialogOpeners,
    whenOpen,
} from '/js/kernel/krt-draft-dialog.js';
import {
    holdsDraftEdits,
    holdsUnsaved,
    markDraftEdited,
    UNSAVED_CLEARED_EVENT,
} from '/js/kernel/krt-unsaved-marks.js';

/** The pause after the last edit before a draft is written, in milliseconds. */
const SNAPSHOT_DELAY_MS = 400;

/** A form that keeps tab-scoped drafts; `data-krt-draft` holds its form key (REQ-FE-041). */
const DRAFT_FORM_SELECTOR = 'form[data-krt-draft]';

/** The first control a restore or a discard hands the focus to. */
const FIRST_FIELD_SELECTOR =
    'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])';

/**
 * The draft state of one form key on the page.
 *
 * @typedef {object} DraftState
 * @property {HTMLFormElement} form the form the key belongs to now
 * @property {string} key the storage key
 * @property {ReturnType<typeof setTimeout> | null} timer the pending snapshot
 * @property {KrtDraft | null} offered the stored draft the restore offer stands for
 * @property {HTMLElement | null} notice the page-level offer of a dialog form's draft
 */

/** @type {Map<string, KrtDraftAdapter>} */
const adapters = new Map();

/** @type {Map<string, DraftState>} */
const states = new Map();

/** Whether the member is signing out, after which the page writes no draft any more. */
let signingOut = false;

/** Whether a restore offer of the page has already been shown. */
let revealed = false;

/** The opaque per-member key once the page's drafts are wired; empty before. */
let pageScope = '';

/** The restore offers whose buttons are wired, so an offer shown again is not wired twice. */
const wiredOffers = new WeakSet();

/**
 * Registers the part of a form's draft that the page's own script keeps, such as the number of
 * its script-built rows. The adapter restores that part before the control values are written.
 *
 * @param {string} formKey the form's `data-krt-draft` key
 * @param {KrtDraftAdapter} adapter the page's serializer and restorer
 */
export function registerDraftAdapter(formKey, adapter) {
    adapters.set(formKey, adapter);
}

/**
 * Reads an attribute of the first element that carries it.
 *
 * @param {string} name the attribute name
 * @returns {string} its value; empty when no element carries it
 */
function pageAttribute(name) {
    const holder = document.querySelector(`[${name}]`);
    return (holder && holder.getAttribute(name)) || '';
}

/**
 * Names the storage key of a drafted form for the signed-in member and the active org unit.
 *
 * @param {HTMLFormElement} form the drafted form
 * @param {string} scope the opaque per-member key
 * @returns {string} the storage key
 */
function keyOf(form, scope) {
    return draftKey(
        scope,
        pageAttribute('data-active-org-unit-context'),
        form.dataset.krtDraft || '',
        window.location.pathname,
    );
}

/**
 * Finds the drafted form an event target belongs to.
 *
 * @param {EventTarget | null} target the event target
 * @returns {HTMLFormElement | null} the drafted form, or null
 */
function draftedFormOf(target) {
    if (!(target instanceof Element)) {
        return null;
    }
    const form = target.closest(DRAFT_FORM_SELECTOR);
    return form instanceof HTMLFormElement ? form : null;
}

/**
 * Hands the focus to the form's first editable control, so it does not fall to the page when the
 * offer it sat on is hidden.
 *
 * @param {HTMLFormElement} form the drafted form
 */
function focusFirstField(form) {
    const field = form.querySelector(FIRST_FIELD_SELECTOR);
    if (field instanceof HTMLElement) {
        field.focus();
    }
}

/**
 * Hides a form's restore offer and forgets the draft it stood for.
 *
 * @param {DraftState} state the form's draft state
 */
function closeOffer(state) {
    state.offered = null;
    const offer = state.form.querySelector('[data-krt-draft-offer]');
    if (offer instanceof HTMLElement) {
        offer.hidden = true;
    }
    if (state.notice) {
        state.notice.remove();
        state.notice = null;
    }
}

/**
 * Tells whether a drafted form holds edits its draft keeps: the unsaved mark of a tracked form or
 * the draft mark of an untracked dialog form.
 *
 * @param {HTMLFormElement} form the drafted form
 * @returns {boolean} true while the form carries either mark
 */
function holdsEdits(form) {
    return holdsUnsaved(form) || holdsDraftEdits(form);
}

/**
 * Writes the form's current values as its draft while it holds unsaved edits; a dialog form's
 * draft also names the opener that reopens the dialog.
 *
 * @param {DraftState} state the form's draft state
 */
function capture(state) {
    state.timer = null;
    const form = state.form;
    if (signingOut || !form.isConnected || !holdsEdits(form)) {
        return;
    }
    const adapter = adapters.get(form.dataset.krtDraft || '');
    const dialog = dialogOf(form);
    writeDraft(state.key, {
        version: form.dataset.krtDraftVersion || '',
        snapshot: snapshotForm(form),
        extra: adapter ? adapter.serialize(form) : null,
        base: readBase(form),
        opener: dialog ? openerOf(dialog) : undefined,
    });
}

/**
 * Schedules a snapshot after the edits pause; nothing is written while a restore offer stands.
 *
 * @param {DraftState} state the form's draft state
 */
function schedule(state) {
    if (state.offered) {
        return;
    }
    if (state.timer !== null) {
        clearTimeout(state.timer);
    }
    state.timer = setTimeout(() => {
        capture(state);
    }, SNAPSHOT_DELAY_MS);
}

/** Writes every pending snapshot at once, before the page may be discarded. */
function flushAll() {
    states.forEach((state) => {
        if (state.timer !== null) {
            clearTimeout(state.timer);
            capture(state);
        }
    });
}

/**
 * Drops a form key's draft and its pending snapshot.
 *
 * @param {DraftState} state the form's draft state
 */
function forget(state) {
    if (state.timer !== null) {
        clearTimeout(state.timer);
        state.timer = null;
    }
    closeOffer(state);
    removeDraft(state.key);
}

/**
 * Restores the offered draft: the adapter rebuilds the script-built parts, then the control values
 * are written and announced, which arms the unsaved-changes guard, the lock versions the edits were
 * made against are written back, and the adapter finishes what those announcements would undo.
 *
 * @param {DraftState} state the form's draft state
 */
function restoreOffered(state) {
    const draft = state.offered;
    closeOffer(state);
    if (!draft) {
        return;
    }
    const adapter = adapters.get(state.form.dataset.krtDraft || '');
    if (adapter) {
        adapter.restore(state.form, draft.extra);
    }
    restoreForm(state.form, draft.snapshot);
    restoreBase(state.form, draft.base);
    if (dialogOf(state.form) && !holdsUnsaved(state.form)) {
        markDraftEdited(state.form);
    }
    if (adapter && adapter.finish) {
        adapter.finish(state.form, draft.extra);
    }
    focusFirstField(state.form);
}

/**
 * Wires the two buttons of a restore offer.
 *
 * @param {HTMLElement} element the offer
 * @param {DraftState} state the form's draft state
 * @param {{restore: () => void, discard: () => void}} actions what the buttons do
 */
function wireOfferActions(element, state, actions) {
    element.addEventListener('click', (event) => {
        const button =
            event.target instanceof Element
                ? event.target.closest('[data-krt-draft-action]')
                : null;
        if (!button || !state.offered) {
            return;
        }
        if (button.getAttribute('data-krt-draft-action') === 'restore') {
            actions.restore();
        } else {
            actions.discard();
        }
    });
}

/**
 * Shows a form's restore offer for its stored draft and wires its two buttons. The offer says when
 * the entry was changed since the draft's edits were made. The first offer of the page asks the
 * form's adapter to reveal the form, for a page that shows one of several forms.
 *
 * @param {DraftState} state the form's draft state
 * @param {KrtDraft} draft the stored draft
 */
function offer(state, draft) {
    const element = state.form.querySelector('[data-krt-draft-offer]');
    if (!(element instanceof HTMLElement)) {
        return;
    }
    const stale = element.querySelector('[data-krt-draft-stale]');
    if (stale instanceof HTMLElement) {
        stale.hidden = sameBase(draft.base, readBase(state.form));
    }
    const adapter = adapters.get(state.form.dataset.krtDraft || '');
    state.offered = draft;
    if (!revealed) {
        revealed = true;
        if (adapter && adapter.reveal) {
            adapter.reveal(state.form);
        }
    }
    element.hidden = false;
    if (wiredOffers.has(element)) {
        return;
    }
    wiredOffers.add(element);
    wireOfferActions(element, state, {
        restore: () => restoreOffered(state),
        discard() {
            forget(state);
            focusFirstField(state.form);
        },
    });
}

/**
 * Shows the page-level offer of a dialog form's draft, since a reload closed the dialog; restore
 * clicks the same opener, which primes the dialog, and writes the draft once the dialog is open,
 * which an opener that first loads what it shows does later. A draft whose opener the page no
 * longer has is dropped, as the dialog cannot be reopened in its context.
 *
 * @param {DraftState} state the form's draft state
 * @param {KrtDraft} draft the stored draft
 * @param {HTMLElement} dialog the dialog the form sits in
 */
function offerDialog(state, draft, dialog) {
    const opener = draft.opener || '';
    if (!findOpener(dialog, opener)) {
        removeDraft(state.key);
        return;
    }
    const notice = showDialogOffer(dialog, !sameBase(draft.base, readBase(state.form)));
    if (!notice) {
        return;
    }
    state.offered = draft;
    state.notice = notice;
    wireOfferActions(notice, state, {
        restore() {
            const current = findOpener(dialog, opener);
            if (current) {
                current.click();
            }
            whenOpen(dialog, () => restoreOffered(state));
        },
        discard: () => forget(state),
    });
}

/**
 * Registers a drafted form and offers its stored draft back while the form is pristine, on the
 * page for a form in a dialog; a draft of another schema version is dropped.
 *
 * @param {HTMLFormElement} form the drafted form
 * @param {string} scope the opaque per-member key
 */
function bindForm(form, scope) {
    const state = stateOf(form, scope);
    const draft = readDraft(state.key);
    if (!draft) {
        return;
    }
    if (draft.version !== (form.dataset.krtDraftVersion || '')) {
        removeDraft(state.key);
        return;
    }
    if (holdsEdits(form)) {
        return;
    }
    const dialog = dialogOf(form);
    if (dialog) {
        offerDialog(state, draft, dialog);
    } else {
        offer(state, draft);
    }
}

/**
 * Finds the draft state of a drafted form, following a fragment swap that put a new form under
 * the same key.
 *
 * @param {HTMLFormElement} form the drafted form
 * @param {string} scope the opaque per-member key
 * @returns {DraftState} the form's draft state
 */
function stateOf(form, scope) {
    const key = keyOf(form, scope);
    const known = states.get(key);
    if (known) {
        known.form = form;
        return known;
    }
    /** @type {DraftState} */
    const state = { form, key, timer: null, offered: null, notice: null };
    states.set(key, state);
    return state;
}

/**
 * Turns the draft of a form that both creates and edits on under its form key, or off while it
 * edits (REQ-FE-041). The form carries the key when the page loads. Turning the draft off withdraws
 * a standing offer and keeps the stored draft; turning it on again offers that draft back while the
 * form is pristine.
 *
 * @param {HTMLFormElement} form the form
 * @param {string | null} formKey the form key while the form creates; null while it edits
 */
export function setDraftKey(form, formKey) {
    if ((form.dataset.krtDraft ?? null) === formKey) {
        return;
    }
    if (formKey !== null) {
        form.dataset.krtDraft = formKey;
        if (pageScope) {
            bindForm(form, pageScope);
        }
        return;
    }
    const state = pageScope ? states.get(keyOf(form, pageScope)) : undefined;
    if (state) {
        if (state.timer !== null) {
            clearTimeout(state.timer);
            state.timer = null;
        }
        closeOffer(state);
    }
    delete form.dataset.krtDraft;
}

/**
 * Tells whether a submitted form signs the member out.
 *
 * @param {HTMLFormElement} form the submitted form
 * @returns {boolean} true for a form posting to `/logout`
 */
function isLogout(form) {
    const action = form.getAttribute('action') || '';
    return new URL(action, window.location.href).pathname.endsWith('/logout');
}

/**
 * Wires the drafts of the page (REQ-FE-041): drops other members' drafts, offers each pristine
 * drafted form its stored draft, keeps a draft while a form holds unsaved edits and removes it on
 * a save, a reset, a discard, a swap that replaces the form and a logout. A swap that replaces a
 * form whose offer still stands offers the draft again on the new form.
 */
function initKrtDrafts() {
    const scope = pageAttribute('data-krt-draft-scope');
    if (!scope) {
        return;
    }
    pageScope = scope;
    removeDrafts(scope);
    /** @type {HTMLElement[]} */
    const dialogs = [];
    document.querySelectorAll(DRAFT_FORM_SELECTOR).forEach((form) => {
        const dialog = form instanceof HTMLFormElement ? dialogOf(form) : null;
        if (dialog && !dialogs.includes(dialog)) {
            dialogs.push(dialog);
        }
    });
    trackDialogOpeners(dialogs);
    document.querySelectorAll(DRAFT_FORM_SELECTOR).forEach((form) => {
        if (form instanceof HTMLFormElement) {
            bindForm(form, scope);
        }
    });

    /** @param {Event} event an edit anywhere on the page */
    const onEdit = (event) => {
        const form = draftedFormOf(event.target);
        if (!form) {
            return;
        }
        if (event.isTrusted && dialogOf(form) && !holdsUnsaved(form)) {
            markDraftEdited(form);
        }
        const state = stateOf(form, scope);
        if (state.offered && event.isTrusted) {
            forget(state);
        }
        schedule(state);
    };
    document.addEventListener('input', onEdit);
    document.addEventListener('change', onEdit);

    const rows = new MutationObserver((records) => {
        const forms = new Set(records.map((record) => draftedFormOf(record.target)));
        forms.forEach((form) => {
            if (form) {
                schedule(stateOf(form, scope));
            }
        });
    });
    states.forEach((state) => {
        rows.observe(state.form, { childList: true, subtree: true });
    });

    document.addEventListener(UNSAVED_CLEARED_EVENT, (event) => {
        const detail = /** @type {CustomEvent<{form?: HTMLFormElement} | null>} */ (event).detail;
        const form = detail && detail.form ? detail.form : null;
        if (form && form.matches(DRAFT_FORM_SELECTOR)) {
            forget(stateOf(form, scope));
        }
    });

    document.addEventListener('krt:swapped', () => {
        /** @type {HTMLFormElement[]} */
        const present = [];
        document.querySelectorAll(DRAFT_FORM_SELECTOR).forEach((form) => {
            if (form instanceof HTMLFormElement) {
                present.push(form);
            }
        });
        states.forEach((state) => {
            if (state.form.isConnected) {
                return;
            }
            const successor = present.find((form) => keyOf(form, scope) === state.key);
            if (state.offered && successor) {
                state.offered = null;
                bindForm(successor, scope);
            } else {
                forget(state);
            }
        });
        present.forEach((form) => {
            rows.observe(stateOf(form, scope).form, { childList: true, subtree: true });
        });
    });

    document.addEventListener(
        'submit',
        (event) => {
            const form = event.target;
            if (!(form instanceof HTMLFormElement)) {
                return;
            }
            if (isLogout(form)) {
                signingOut = true;
                removeDrafts(null);
                return;
            }
            const drafted = draftedFormOf(form);
            if (drafted && stateOf(drafted, scope).offered) {
                forget(stateOf(drafted, scope));
            }
        },
        true,
    );

    window.addEventListener('pagehide', flushAll);
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') {
            flushAll();
        }
    });
}

// The page's module scripts all run before DOMContentLoaded, so their adapters are registered when
// the offers are made.
if (document.readyState === 'complete') {
    initKrtDrafts();
} else {
    document.addEventListener('DOMContentLoaded', initKrtDrafts);
}
