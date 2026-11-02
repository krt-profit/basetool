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

/* global SQUADRON_MSG, SQUADRON_CONFLICT */

import { assignFormAction, safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { wireInstantToggle } from '/js/kernel/krt-instant-toggle.js';

const SQUADRON_FILTER_PREF_KEY = 'admin_squadrons_filter';

/**
 * Builds the query string of the inactive toggle.
 *
 * @returns {string} the query string without a leading question mark
 */
function filterParams() {
    const toggle = /** @type {HTMLInputElement | null} */ (
        document.getElementById('includeInactiveSquadrons')
    );
    return `includeInactiveSquadrons=${toggle ? toggle.checked : false}`;
}

const squadrons = krtPageSections.create({
    sections: {
        squadrons: { container: '#squadrons-results', fragmentValue: 'squadrons-results' },
    },
    pageUrl() {
        return `/admin/org-structure/squadrons?${filterParams()}`;
    },
});

/**
 * Looks up a control of the squadron dialog by id.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement} the control
 */
function dialogField(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
}

/**
 * Submits a squadron form through the section seam and re-renders the table.
 *
 * @param {HTMLFormElement} form the form to submit
 * @param {string} successMessage the already-localized success toast
 * @param {(() => void) | null} onSuccess runs after a successful save and the re-render
 */
function squadronWrite(form, successMessage, onSuccess) {
    squadrons.submitForm({
        form,
        submitter: form.querySelector('button[type="submit"]'),
        successMessage,
        errorMessage: SQUADRON_MSG.error,
        conflict: SQUADRON_CONFLICT,
        refresh: ['squadrons'],
        onSuccess() {
            if (typeof onSuccess === 'function') onSuccess();
        },
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('squadron-modal');
    const form = /** @type {HTMLFormElement} */ (document.getElementById('squadron-form'));
    const title = /** @type {HTMLElement} */ (document.getElementById('squadron-modal-title'));
    const addBtn = document.getElementById('add-squadron-btn');

    if (addBtn) {
        addBtn.addEventListener('click', () => {
            assignFormAction(
                form,
                safeSameOriginUrl(addBtn.getAttribute('data-action'), form.action),
            );
            title.innerText = SQUADRON_MSG.createTitle;
            dialogField('sq-name').value = '';
            dialogField('sq-shorthand').value = '';
            dialogField('sq-desc').value = '';
            dialogField('edit-squadron-version').value = '0';
            krtModal.open(modal);
        });
    }

    document.addEventListener('click', (e) => {
        const btn = /** @type {Element} */ (e.target).closest('.edit-squadron-btn');
        if (!btn) return;
        assignFormAction(form, safeSameOriginUrl(btn.getAttribute('data-action'), form.action));
        title.innerText = SQUADRON_MSG.editTitle;
        dialogField('sq-name').value = btn.getAttribute('data-name') ?? '';
        dialogField('sq-shorthand').value = btn.getAttribute('data-shorthand') ?? '';
        dialogField('sq-desc').value = btn.getAttribute('data-desc') ?? '';
        dialogField('edit-squadron-version').value = btn.getAttribute('data-version') ?? '';
        krtModal.open(modal);
    });

    document.querySelectorAll('.close-squadron-modal').forEach((btn) => {
        btn.addEventListener('click', () => krtModal.close(modal));
    });

    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-confirm-form')
    );
    if (deleteModal && deleteForm) {
        document.addEventListener('click', (e) => {
            const btn = /** @type {Element} */ (e.target).closest('.delete-btn');
            if (!btn) return;
            assignFormAction(
                deleteForm,
                safeSameOriginUrl(btn.getAttribute('data-action'), deleteForm.action),
            );
            krtModal.open(deleteModal);
        });
        document.querySelectorAll('.close-delete-modal').forEach((btn) => {
            btn.addEventListener('click', () => krtModal.close(deleteModal));
        });
        deleteForm.addEventListener('submit', (e) => {
            e.preventDefault();
            squadronWrite(deleteForm, SQUADRON_MSG.deleted, () => krtModal.close(deleteModal));
        });
    }

    form.addEventListener('submit', (e) => {
        e.preventDefault();
        squadronWrite(form, SQUADRON_MSG.saved, () => krtModal.close(modal));
    });

    document.addEventListener('submit', (e) => {
        const actForm = /** @type {HTMLFormElement | null} */ (
            /** @type {Element} */ (e.target).closest('form[action*="/activate"]')
        );
        if (!actForm) return;
        e.preventDefault();
        squadronWrite(actForm, SQUADRON_MSG.saved, null);
    });

    const inactiveToggle = /** @type {HTMLInputElement | null} */ (
        document.getElementById('includeInactiveSquadrons')
    );
    if (inactiveToggle) {
        const persist = function () {
            try {
                localStorage.setItem(
                    SQUADRON_FILTER_PREF_KEY,
                    JSON.stringify({ includeInactive: inactiveToggle.checked }),
                );
            } catch (_e) {}
        };
        inactiveToggle.addEventListener('change', () => {
            persist();
            krtFetch.swap({
                url: `/admin/org-structure/squadrons?${filterParams()}`,
                container: '#squadrons-results',
                fragmentValue: 'squadrons-results',
                history: true,
            });
        });
        if (/[?&]includeInactiveSquadrons=/.test(window.location.search)) {
            persist();
        } else {
            let saved = null;
            try {
                saved = JSON.parse(
                    /** @type {string} */ (localStorage.getItem(SQUADRON_FILTER_PREF_KEY)),
                );
            } catch (_e) {}
            if (saved && saved.includeInactive === true && !inactiveToggle.checked) {
                inactiveToggle.checked = true;
                inactiveToggle.dispatchEvent(new Event('change'));
            }
        }
    }

    wireInstantToggle({
        selector: '.squadron-promotion-toggle',
        idAttribute: 'data-squadron-id',
        urlFor: (id) => `/api/proxy/squadrons/${encodeURIComponent(id)}/promotion-enabled`,
        bodyFor: (checked) => ({ enabled: checked }),
        labels: {
            on: SQUADRON_MSG.promotionOn,
            off: SQUADRON_MSG.promotionOff,
            saved: SQUADRON_MSG.promotionSaved,
            error: SQUADRON_MSG.promotionError,
        },
        conflict: SQUADRON_CONFLICT,
    });
    wireInstantToggle({
        selector: '.squadron-profit-toggle',
        idAttribute: 'data-squadron-id',
        urlFor: (id) => `/api/proxy/squadrons/${encodeURIComponent(id)}/profit-eligible`,
        bodyFor: (checked) => ({ eligible: checked }),
        labels: {
            on: SQUADRON_MSG.profitOn,
            off: SQUADRON_MSG.profitOff,
            saved: SQUADRON_MSG.profitSaved,
            error: SQUADRON_MSG.profitError,
        },
        conflict: SQUADRON_CONFLICT,
    });
});
