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

/** A dialog `krtModal` opens. */
const DIALOG_SELECTOR = '.krt-modal-overlay';

/** The prefix of the attributes an opener primes its dialog's fields from. */
const CONTEXT_PREFIX = 'data-field-';

/** The template of the page-level offer of a dialog draft (`kernel/fragments/draft-offer.html`). */
const NOTICE_TEMPLATE_ID = 'krt-draft-dialog-offer';

/** The offset basis and the prime of the 32-bit FNV-1a hash. */
const FNV_OFFSET = 0x811c9dc5;
const FNV_PRIME = 0x01000193;

/**
 * The opener key of the control that last opened each dialog, by dialog id.
 *
 * @type {Map<string, string>}
 */
const lastOpeners = new Map();

/**
 * Finds the dialog a form sits in.
 *
 * @param {HTMLFormElement} form the form
 * @returns {HTMLElement | null} the dialog, which carries an id; null for a form on the page
 */
export function dialogOf(form) {
    const dialog = form.closest(DIALOG_SELECTOR);
    return dialog instanceof HTMLElement && dialog.id ? dialog : null;
}

/**
 * Hashes a text to eight hex digits (32-bit FNV-1a), so an opener's context is matched without
 * keeping it.
 *
 * @param {string} text the text
 * @returns {string} the hash
 */
export function hashText(text) {
    let hash = FNV_OFFSET;
    for (let i = 0; i < text.length; i += 1) {
        hash ^= text.charCodeAt(i);
        hash = Math.imul(hash, FNV_PRIME);
    }
    return (hash >>> 0).toString(16).padStart(8, '0');
}

/**
 * Names an opener by the dialog it opens and the `data-field-*` context it primes the dialog with.
 *
 * @param {Element} opener a control carrying `data-modal-id`
 * @returns {string} the opener key; a control without context is keyed by its dialog id alone
 */
export function openerKey(opener) {
    const context = Array.from(opener.attributes)
        .filter((attribute) => attribute.name.startsWith(CONTEXT_PREFIX))
        .map((attribute) => `${attribute.name}=${attribute.value}`)
        .sort();
    return hashText([opener.getAttribute('data-modal-id') || '', ...context].join('\n'));
}

/**
 * Tells whether a control opens a dialog from outside it; the dialog's own close control names
 * the dialog too.
 *
 * @param {Element} control a control carrying `data-modal-id`
 * @param {HTMLElement} dialog the dialog
 * @returns {boolean} true for an opener of the dialog
 */
function opensFromOutside(control, dialog) {
    return control.getAttribute('data-modal-id') === dialog.id && !dialog.contains(control);
}

/** Records the control that opens a dialog, so a draft of the dialog can reopen it the same way. */
export function trackDialogOpeners() {
    document.addEventListener(
        'click',
        (event) => {
            const control =
                event.target instanceof Element ? event.target.closest('[data-modal-id]') : null;
            const dialog = control
                ? document.getElementById(control.getAttribute('data-modal-id') || '')
                : null;
            if (control && dialog && opensFromOutside(control, dialog)) {
                lastOpeners.set(dialog.id, openerKey(control));
            }
        },
        true,
    );
}

/**
 * Names the opener a draft of the dialog reopens it with.
 *
 * @param {HTMLElement} dialog the dialog
 * @returns {string} the key of the control that last opened it; the dialog id's key when none did
 */
export function openerOf(dialog) {
    return lastOpeners.get(dialog.id) ?? hashText(dialog.id);
}

/**
 * Finds an enabled control on the page that opens the dialog with the context a draft recorded.
 *
 * @param {HTMLElement} dialog the dialog
 * @param {string} key the opener key the draft recorded
 * @returns {HTMLElement | null} the opener; null when the page has none
 */
export function findOpener(dialog, key) {
    const match = Array.from(document.querySelectorAll('[data-modal-id]')).find(
        (control) =>
            control instanceof HTMLElement &&
            opensFromOutside(control, dialog) &&
            !(control instanceof HTMLButtonElement && control.disabled) &&
            openerKey(control) === key,
    );
    return match instanceof HTMLElement ? match : null;
}

/**
 * Reads the name a dialog shows in its head.
 *
 * @param {HTMLElement} dialog the dialog
 * @returns {string} its heading, else its accessible name
 */
function dialogName(dialog) {
    const heading = dialog.querySelector('.krt-modal-head h2');
    const text = heading && heading.textContent ? heading.textContent : '';
    return (text || dialog.getAttribute('aria-label') || '').trim();
}

/**
 * Shows the page-level offer of a dialog's draft at the top of the page content, naming the
 * dialog; the dialog itself stays closed until the draft is restored.
 *
 * @param {HTMLElement} dialog the dialog the draft belongs to
 * @param {boolean} stale whether the entry was changed since the draft was made
 * @returns {HTMLElement | null} the shown offer; null on a page without the offer template
 */
export function showDialogOffer(dialog, stale) {
    const template = document.getElementById(NOTICE_TEMPLATE_ID);
    const host = document.querySelector('main');
    if (!(template instanceof HTMLTemplateElement) || !host) {
        return null;
    }
    const notice = template.content.firstElementChild
        ? template.content.firstElementChild.cloneNode(true)
        : null;
    if (!(notice instanceof HTMLElement)) {
        return null;
    }
    const name = notice.querySelector('[data-krt-draft-dialog-name]');
    if (name) {
        name.textContent = dialogName(dialog);
    }
    const staleNote = notice.querySelector('[data-krt-draft-stale]');
    if (staleNote instanceof HTMLElement) {
        staleNote.hidden = !stale;
    }
    host.prepend(notice);
    return notice;
}
