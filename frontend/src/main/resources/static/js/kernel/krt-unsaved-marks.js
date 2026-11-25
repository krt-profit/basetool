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

/**
 * The attribute that marks a form edited since it was rendered or saved (REQ-FE-024). It lives on
 * the form, so a fragment swap that replaces the form takes the mark with it.
 */
const UNSAVED_ATTR = 'data-krt-unsaved';

/**
 * The attribute that marks a dialog form as holding edits for its draft alone (REQ-FE-041): the
 * edits of a form the guard does not track (`no-track`), or of a tracked form whose dialog closed
 * without saving. Live sync and the leave warning never read it.
 */
const DRAFT_EDIT_ATTR = 'data-krt-draft-edited';

/** The attribute that names a form's draft key; a form without it keeps no draft (REQ-FE-041). */
const DRAFT_KEY_ATTR = 'data-krt-draft';

/** The document event a cleared mark raises, so a deferred live-sync refresh can run (REQ-FE-015). */
export const UNSAVED_CLEARED_EVENT = 'krt:unsaved-cleared';

/**
 * Marks a form as holding edits that are not saved.
 *
 * @param {HTMLFormElement} form the edited form
 */
export function markUnsaved(form) {
    form.setAttribute(UNSAVED_ATTR, '');
}

/**
 * Marks an untracked dialog form as holding edits its draft keeps.
 *
 * @param {HTMLFormElement} form the edited dialog form
 */
export function markDraftEdited(form) {
    form.setAttribute(DRAFT_EDIT_ATTR, '');
}

/**
 * Tells whether a dialog form holds edits its draft alone keeps.
 *
 * @param {HTMLFormElement} form the dialog form
 * @returns {boolean} true while the form carries the draft mark
 */
export function holdsDraftEdits(form) {
    return form.hasAttribute(DRAFT_EDIT_ATTR);
}

/**
 * Clears a form's marks and raises `krt:unsaved-cleared`; a form without either mark is left
 * alone.
 *
 * @param {HTMLFormElement | null | undefined} form the saved or discarded form
 */
export function clearUnsaved(form) {
    if (!form || !(form.hasAttribute(UNSAVED_ATTR) || form.hasAttribute(DRAFT_EDIT_ATTR))) {
        return;
    }
    form.removeAttribute(UNSAVED_ATTR);
    form.removeAttribute(DRAFT_EDIT_ATTR);
    document.dispatchEvent(new CustomEvent(UNSAVED_CLEARED_EVENT, { detail: { form } }));
}

/**
 * Hands the unsaved mark of a dialog form closed without saving to its draft mark (REQ-FE-041):
 * live sync and the leave warning stop counting its edits, and its draft stays. The raised
 * `krt:unsaved-cleared` carries `draftKept`. A form that keeps no draft has its marks cleared.
 *
 * @param {HTMLFormElement} form the closed dialog's form
 */
export function keepAsDraft(form) {
    if (!form.hasAttribute(DRAFT_KEY_ATTR)) {
        clearUnsaved(form);
        return;
    }
    if (!form.hasAttribute(UNSAVED_ATTR)) {
        return;
    }
    form.removeAttribute(UNSAVED_ATTR);
    form.setAttribute(DRAFT_EDIT_ATTR, '');
    document.dispatchEvent(
        new CustomEvent(UNSAVED_CLEARED_EVENT, { detail: { form, draftKept: true } }),
    );
}

/** Clears the marks of every form on the page, for a save that did not name its form. */
export function clearAllUnsaved() {
    document.querySelectorAll(`form[${UNSAVED_ATTR}], form[${DRAFT_EDIT_ATTR}]`).forEach((form) => {
        clearUnsaved(/** @type {HTMLFormElement} */ (form));
    });
}

/**
 * Tells whether a container is, or holds, a form with unsaved edits.
 *
 * @param {Element} container the section container to test
 * @returns {boolean} true when a marked form is the container or inside it
 */
export function holdsUnsaved(container) {
    return (
        container.matches(`form[${UNSAVED_ATTR}]`) ||
        container.querySelector(`form[${UNSAVED_ATTR}]`) !== null
    );
}
