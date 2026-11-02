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

/* global SC_MSG, SC_CONFLICT */

import { assignFormAction, safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { wireInstantToggle } from '/js/kernel/krt-instant-toggle.js';

const modal = /** @type {HTMLElement} */ (document.getElementById('specialcommand-modal'));
const form = /** @type {HTMLFormElement} */ (document.getElementById('sc-form'));
const title = /** @type {HTMLElement} */ (document.getElementById('sc-modal-title'));
const versionInput = /** @type {HTMLInputElement} */ (document.getElementById('edit-sc-version'));
const nameInput = /** @type {HTMLInputElement} */ (document.getElementById('sc-name'));
const shorthandInput = /** @type {HTMLInputElement} */ (document.getElementById('sc-shorthand'));
const descInput = /** @type {HTMLInputElement} */ (document.getElementById('sc-desc'));

function openCreate() {
    title.textContent = SC_MSG.createTitle;
    form.action = '/admin/special-commands';
    versionInput.value = '0';
    nameInput.value = '';
    shorthandInput.value = '';
    descInput.value = '';
    krtModal.open(modal);
}

/** @param {Element} btn the clicked edit button carrying the `data-*` values */
function openEdit(btn) {
    title.textContent = SC_MSG.editTitle;
    assignFormAction(form, safeSameOriginUrl(btn.getAttribute('data-action'), form.action));
    versionInput.value = btn.getAttribute('data-version') || '0';
    nameInput.value = btn.getAttribute('data-name') || '';
    shorthandInput.value = btn.getAttribute('data-shorthand') || '';
    descInput.value = btn.getAttribute('data-desc') || '';
    krtModal.open(modal);
}

function closeModal() {
    krtModal.close(modal);
}

const deleteModal = document.getElementById('sc-delete-modal');
const deleteForm = /** @type {HTMLFormElement | null} */ (
    document.getElementById('sc-delete-form')
);
const deleteNameEl = document.getElementById('sc-delete-name');

/** @param {Element} btn the clicked delete button carrying the `data-*` values */
function openDelete(btn) {
    if (!deleteModal || !deleteForm) return;
    assignFormAction(
        deleteForm,
        safeSameOriginUrl(btn.getAttribute('data-action'), deleteForm.action),
    );
    if (deleteNameEl) deleteNameEl.textContent = btn.getAttribute('data-name') || '';
    krtModal.open(deleteModal);
}

function closeDelete() {
    if (deleteModal) krtModal.close(deleteModal);
}

/** @type {HTMLElement} */ (document.getElementById('add-sc-btn')).addEventListener(
    'click',
    openCreate,
);
document.addEventListener('click', (e) => {
    const target = /** @type {Element} */ (e.target);
    const editBtn = target.closest('.edit-sc-btn');
    if (editBtn) {
        openEdit(editBtn);
        return;
    }
    const deleteBtn = target.closest('.delete-btn');
    if (deleteBtn) openDelete(deleteBtn);
});
/** @type {Element} */ (
    document.querySelector('#specialcommand-modal .close-sc-modal')
).addEventListener('click', closeModal);
document
    .querySelectorAll('#sc-delete-modal .close-sc-delete-modal')
    .forEach((btn) => btn.addEventListener('click', closeDelete));
modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
});
if (deleteModal) {
    deleteModal.addEventListener('click', (e) => {
        if (e.target === deleteModal) closeDelete();
    });
}

const SC_FILTER_PREF_KEY = 'admin_special_commands_filter';
const includeInactive = /** @type {HTMLInputElement | null} */ (
    document.getElementById('includeInactive')
);
if (includeInactive) {
    const persistScFilter = function () {
        try {
            localStorage.setItem(
                SC_FILTER_PREF_KEY,
                JSON.stringify({ includeInactive: includeInactive.checked }),
            );
        } catch (_e) {}
    };
    includeInactive.addEventListener('change', () => {
        persistScFilter();
        const url = `/admin/special-commands${
            includeInactive.checked ? '?includeInactive=true' : ''
        }`;
        krtFetch.swap({ url, container: '#sc-results', history: true });
    });

    if (/[?&]includeInactive=/.test(window.location.search)) {
        persistScFilter();
    } else {
        let saved;
        try {
            saved = JSON.parse(/** @type {string} */ (localStorage.getItem(SC_FILTER_PREF_KEY)));
        } catch (_e) {
            saved = null;
        }
        if (saved && typeof saved === 'object') {
            const want = saved.includeInactive === true;
            if (want !== includeInactive.checked) {
                includeInactive.checked = want;
                includeInactive.dispatchEvent(new Event('change'));
            }
        }
    }
}

function reswapScResults() {
    const url = `/admin/special-commands${
        includeInactive && includeInactive.checked ? '?includeInactive=true' : ''
    }`;
    krtFetch.swap({ url, container: '#sc-results', history: false });
}

/**
 * @param {HTMLFormElement} theForm the form to submit
 * @param {string} successMessage the localized success toast text
 * @param {(() => void) | null} onSuccess runs after a successful write, before the list swap
 */
function scWrite(theForm, successMessage, onSuccess) {
    krtFetch.submitForm({
        form: theForm,
        successMessage,
        errorMessage: SC_MSG.error,
        conflict: SC_CONFLICT,
        onSuccess() {
            if (typeof onSuccess === 'function') {
                onSuccess();
            }
            reswapScResults();
        },
    });
}

if (form) {
    form.addEventListener('submit', (e) => {
        e.preventDefault();

        scWrite(form, SC_MSG.saved, closeModal);
    });
}
if (deleteForm) {
    deleteForm.addEventListener('submit', (e) => {
        e.preventDefault();

        scWrite(deleteForm, SC_MSG.deleted, closeDelete);
    });
}
document.addEventListener('submit', (e) => {
    const actForm = /** @type {HTMLFormElement | null} */ (
        /** @type {Element} */ (e.target).closest('form[action*="/activate"]')
    );
    if (!actForm) {
        return;
    }
    e.preventDefault();

    scWrite(actForm, SC_MSG.saved, null);
});

wireInstantToggle({
    selector: '.sk-profit-toggle',
    idAttribute: 'data-sk-id',
    urlFor: (id) => `/api/proxy/special-commands/${encodeURIComponent(id)}/profit-eligible`,
    bodyFor: (checked) => ({ eligible: checked }),
    labels: {
        on: SC_MSG.profitOn,
        off: SC_MSG.profitOff,
        saved: SC_MSG.profitSaved,
        error: SC_MSG.profitError,
    },
    conflict: SC_CONFLICT,
});
