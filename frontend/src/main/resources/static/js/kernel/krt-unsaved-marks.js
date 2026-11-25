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
 * Clears a form's mark and raises `krt:unsaved-cleared`; a form without the mark is left alone.
 *
 * @param {HTMLFormElement | null | undefined} form the saved or discarded form
 */
export function clearUnsaved(form) {
    if (!form || !form.hasAttribute(UNSAVED_ATTR)) {
        return;
    }
    form.removeAttribute(UNSAVED_ATTR);
    document.dispatchEvent(new CustomEvent(UNSAVED_CLEARED_EVENT, { detail: { form } }));
}

/** Clears the mark of every form on the page, for a save that did not name its form. */
export function clearAllUnsaved() {
    document.querySelectorAll(`form[${UNSAVED_ATTR}]`).forEach((form) => {
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
