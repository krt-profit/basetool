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

// @ts-check

/* global shipDataI18n, shipDataConflict, shipDataResetUrl */

import { krtAutocomplete } from '/js/kernel/autocomplete.js';
import { showFrontendSuccessToast } from '/js/kernel/toast.js';
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtHooks } from '/js/kernel/krt-hooks.js';

document.addEventListener('DOMContentLoaded', () => {
    const shipTypesData = /** @type {HTMLDataListElement | null} */ (
        document.getElementById('shipTypeNames-data')
    );
    const shipTypeNames = shipTypesData
        ? Array.from(shipTypesData.options).map((o) => {
              return o.value;
          })
        : [];
    const mfgData = /** @type {HTMLDataListElement | null} */ (
        document.getElementById('mfgNames-data')
    );
    const mfgNames = mfgData
        ? Array.from(mfgData.options).map((o) => {
              return o.value;
          })
        : [];

    const inpShipTypes = /** @type {HTMLInputElement | null} */ (
        document.getElementById('filterShipTypes')
    );
    if (inpShipTypes) krtAutocomplete(inpShipTypes, shipTypeNames);

    const inpMfgs = /** @type {HTMLInputElement | null} */ (
        document.getElementById('filterManufacturers')
    );
    if (inpMfgs) krtAutocomplete(inpMfgs, mfgNames);
});

/**
 * Hides every table row whose first cell does not contain the query, case-insensitively.
 *
 * @param {string} tableId id of the table to filter
 * @param {string} query the text to look for
 */
function filterTable(tableId, query) {
    const filter = query.toUpperCase();
    const table = document.getElementById(tableId);
    if (!table) {
        return;
    }
    const tr = table.getElementsByTagName('tr');
    for (let i = 1; i < tr.length; i++) {
        const td = tr[i].getElementsByTagName('td')[0];
        if (td) {
            const txtValue = td.textContent || td.innerText;
            if (txtValue.toUpperCase().indexOf(filter) > -1) {
                tr[i].style.display = '';
            } else {
                tr[i].style.display = 'none';
            }
        }
    }
}

/**
 * Closes a modal overlay after discarding the unsaved-changes guard.
 *
 * @param {string | null} id the overlay id
 */
function closeModal(id) {
    resetUnsavedChanges();
    krtModal.close(id);
}

krtHooks.filterTable = filterTable;

document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.krt-modal-overlay').forEach((m) => {
        m.addEventListener('click', (e) => {
            if (e.target === m) {
                krtModal.close(m);
            }
        });
    });
});

krtEvents.on('click', 'ship-close-modal', (el) => {
    closeModal(el.getAttribute('data-modal-id'));
});

function initShipData() {
    const i18n = shipDataI18n;
    const conflict = shipDataConflict;

    document.addEventListener('submit', (e) => {
        const form = /** @type {HTMLFormElement} */ (e.target);
        if (!form.classList || !form.classList.contains('js-visibility-toggle')) {
            return;
        }
        e.preventDefault();
        const hiddenInput = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="hidden"]')
        );
        const desired = hiddenInput ? hiddenInput.value === 'true' : true;
        const btn = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        if (btn) {
            btn.disabled = true;
        }
        krtFetch
            .write({
                method: 'POST',
                url: `${form.getAttribute('action')}?hidden=${desired}`,
                toast: false,
                errorMessage: i18n.toggleError,
                conflict,
                onSuccess() {
                    const row = form.closest('tr');
                    if (row) {
                        row.classList.toggle('sd-row--hidden', desired);
                    }
                    if (hiddenInput) {
                        hiddenInput.value = (!desired).toString();
                    }
                    if (btn) {
                        btn.textContent = desired
                            ? form.getAttribute('data-label-show')
                            : form.getAttribute('data-label-hide');
                    }
                    showFrontendSuccessToast(i18n.saved);
                },
            })
            .then(() => {
                if (btn) {
                    btn.disabled = false;
                }
            });
    });

    krtEvents.on('click', 'shipdata-reset', (target) => {
        const el = /** @type {HTMLButtonElement} */ (target);
        el.disabled = true;
        krtFetch
            .write({
                method: 'POST',
                url: shipDataResetUrl,
                toast: false,
                errorMessage: i18n.resetError,
                conflict,
                onSuccess() {
                    closeModal('reset-fitted-confirm-modal');
                    showFrontendSuccessToast(i18n.resetSuccess);
                },
            })
            .then(() => {
                el.disabled = false;
            });
    });
}

initShipData();
