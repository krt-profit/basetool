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

/** A control that can open a dialog: a button, a `krtModal` trigger or a page action. */
const OPENER_SELECTOR = 'button, [data-modal-id], [data-trigger]';

/** The prefix of the attributes an opener carries its action and its dialog's context in. */
const DATA_PREFIX = 'data-';

/** The prefix of the kernel's own attributes, which say nothing about an opener's context. */
const KERNEL_PREFIX = 'data-krt-';

/** The state changes of a dialog that tell it opened. */
const OPEN_OBSERVATION = { attributes: true, attributeFilter: ['class', 'open'] };

/** The attribute a dialog carries as `true` while it loads what it shows. */
const BUSY_ATTRIBUTE = 'aria-busy';

/** The state changes of a dialog that tell it opened or finished loading. */
const READY_OBSERVATION = {
    attributes: true,
    attributeFilter: [...OPEN_OBSERVATION.attributeFilter, BUSY_ATTRIBUTE],
};

/** The root of a page's content: its `<main>`, or the `.page-wrapper` of a page without one. */
const PAGE_CONTENT_SELECTOR = 'main, .page-wrapper';

/** The page head of a page's content (`kernel/fragments/page-head.html`). */
const PAGE_HEAD_SELECTOR = '.page-head';

/** The tabs a page head may carry right after it. */
const PAGE_TABS_SELECTOR = '.tab-nav';

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
 * Names an opener: by its id, which is unique on the page and unaffected by the markers a page
 * script adds once it binds the control; without an id, by its `data-*` attributes, which name the
 * dialog or page action it triggers and the context it primes the dialog with, such as the account
 * or the need it acts on.
 *
 * @param {Element} opener a control that opened a dialog
 * @returns {string} the opener key
 */
export function openerKey(opener) {
    if (opener.id) {
        return hashText(`id=${opener.id}`);
    }
    const named = Array.from(opener.attributes)
        .filter(
            (attribute) =>
                attribute.name.startsWith(DATA_PREFIX) && !attribute.name.startsWith(KERNEL_PREFIX),
        )
        .map((attribute) => `${attribute.name}=${attribute.value}`)
        .sort();
    return hashText(named.join('\n'));
}

/**
 * Tells whether a dialog is open, by the state `krtModal` gives it.
 *
 * @param {HTMLElement} dialog the dialog
 * @returns {boolean} true while it is open
 */
function isOpen(dialog) {
    return dialog.classList.contains('is-open') || dialog.hasAttribute('open');
}

/**
 * The opener the member clicked last and no dialog has claimed yet; it waits for a dialog that
 * opens only after the page loaded what it shows.
 */
let lastControl = /** @type {Element | null} */ (null);

/**
 * Records the control whose click opens each of the given dialogs, so a draft of a dialog can
 * reopen it the same way.
 *
 * @param {HTMLElement[]} dialogs the dialogs that hold drafted forms
 */
export function trackDialogOpeners(dialogs) {
    document.addEventListener(
        'click',
        (event) => {
            lastControl =
                event.target instanceof Element ? event.target.closest(OPENER_SELECTOR) : null;
        },
        true,
    );
    const observer = new MutationObserver((records) => {
        records.forEach((record) => {
            const dialog = record.target;
            if (
                dialog instanceof HTMLElement &&
                isOpen(dialog) &&
                lastControl &&
                !dialog.contains(lastControl)
            ) {
                lastOpeners.set(dialog.id, openerKey(lastControl));
                lastControl = null;
            }
        });
    });
    dialogs.forEach((dialog) => {
        observer.observe(dialog, OPEN_OBSERVATION);
    });
}

/**
 * Tells whether a dialog is open and done loading: a dialog that loads what it shows after it
 * opened carries `aria-busy="true"` until it has.
 *
 * @param {HTMLElement} dialog the dialog
 * @returns {boolean} true while it is open and not busy
 */
function isReady(dialog) {
    return isOpen(dialog) && dialog.getAttribute(BUSY_ATTRIBUTE) !== 'true';
}

/**
 * Runs a step once a dialog is open and done loading: at once when it already is, else when it
 * is, as a dialog whose opener first loads what it shows, or that loads it once open, is later.
 *
 * @param {HTMLElement} dialog the dialog
 * @param {() => void} step the step to run
 */
export function whenReady(dialog, step) {
    if (isReady(dialog)) {
        step();
        return;
    }
    const observer = new MutationObserver(() => {
        if (isReady(dialog)) {
            observer.disconnect();
            step();
        }
    });
    observer.observe(dialog, READY_OBSERVATION);
}

/**
 * Names the opener a draft of the dialog reopens it with.
 *
 * @param {HTMLElement} dialog the dialog
 * @returns {string} the key of the control that last opened it; empty when none was clicked
 */
export function openerOf(dialog) {
    return lastOpeners.get(dialog.id) ?? '';
}

/**
 * Finds an enabled control outside the dialog that carries the opener key a draft recorded.
 *
 * @param {HTMLElement} dialog the dialog
 * @param {string} key the opener key the draft recorded
 * @returns {HTMLElement | null} the opener; null when the page has none
 */
export function findOpener(dialog, key) {
    if (!key) {
        return null;
    }
    const match = Array.from(document.querySelectorAll(OPENER_SELECTOR)).find(
        (control) =>
            control instanceof HTMLElement &&
            !dialog.contains(control) &&
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
 * Finds the element the page-level offer follows: the page head and the tabs right after it, where a
 * page shows its notices.
 *
 * @param {Element} host the root of the page's content
 * @returns {Element | null} the page head or its tabs; null on a page without a page head
 */
function noticeAnchor(host) {
    const head = Array.from(host.children).find((child) => child.matches(PAGE_HEAD_SELECTOR));
    if (!head) {
        return null;
    }
    const next = head.nextElementSibling;
    return next && next.matches(PAGE_TABS_SELECTOR) ? next : head;
}

/**
 * Shows the page-level offer of a dialog's draft below the page head and its tabs, where the page
 * shows its notices, or at the top of a page without a page head; the dialog itself stays closed
 * until the draft is restored.
 *
 * @param {HTMLElement} dialog the dialog the draft belongs to
 * @param {boolean} stale whether the entry was changed since the draft was made
 * @returns {HTMLElement | null} the shown offer; null on a page without the offer template
 */
export function showDialogOffer(dialog, stale) {
    const template = document.getElementById(NOTICE_TEMPLATE_ID);
    const host = document.querySelector(PAGE_CONTENT_SELECTOR);
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
    const anchor = noticeAnchor(host);
    if (anchor) {
        anchor.after(notice);
    } else {
        host.prepend(notice);
    }
    return notice;
}
