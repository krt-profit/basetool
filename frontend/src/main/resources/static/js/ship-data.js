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

/*
 * Ship-data page module (/ship-data), extracted verbatim from the two former inline scripts of
 * ship-data.html (ADR-0069, follow-up to #924).
 *
 * Wires the manufacturer/ship-type autocomplete filters (via the shared krtAutocomplete from
 * autocomplete.js), the client-side name filterTable, the modal open/close helpers (closeModal also
 * clears the unsaved-changes guard), and the in-place AJAX visibility toggle + reset-all-fitted flow
 * (REQ-FE-001): both go through window.krtFetch and flip the row / close the modal without a full-page
 * reload, with the classic POST->redirect forms staying the no-JS fallback.
 *
 * The AJAX block's localized toast + conflict strings and the reset endpoint URL are the only Thymeleaf
 * interpolation, so they stay inline in the page bootstrap as the shipDataI18n / shipDataConflict /
 * shipDataResetUrl globals this module reads.
 */

/* global krtAutocomplete, shipDataI18n, shipDataConflict, shipDataResetUrl */

document.addEventListener('DOMContentLoaded', function () {
    const shipTypesData = document.getElementById('shipTypeNames-data');
    const shipTypeNames = shipTypesData
        ? Array.from(shipTypesData.options).map(function (o) {
              return o.value;
          })
        : [];
    const mfgData = document.getElementById('mfgNames-data');
    const mfgNames = mfgData
        ? Array.from(mfgData.options).map(function (o) {
              return o.value;
          })
        : [];

    const inpShipTypes = document.getElementById('filterShipTypes');
    if (inpShipTypes) krtAutocomplete(inpShipTypes, shipTypeNames);

    const inpMfgs = document.getElementById('filterManufacturers');
    if (inpMfgs) krtAutocomplete(inpMfgs, mfgNames);
});

function filterTable(tableId, query) {
    const filter = query.toUpperCase();
    const table = document.getElementById(tableId);
    const tr = table.getElementsByTagName('tr');
    for (let i = 1; i < tr.length; i++) {
        const td = tr[i].getElementsByTagName('td')[0]; // Name is in the first column
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

function openModal(id) {
    document.getElementById(id).style.display = 'flex';
}
function closeModal(id) {
    if (typeof window.resetUnsavedChanges === 'function') {
        window.resetUnsavedChanges();
    }
    document.getElementById(id).style.display = 'none';
}

// Publish the page globals the original inline classic script exposed on window. The shared
// filter-table common-handler (common-handlers.js) calls window.filterTable(tableId, value) on each
// keystroke of the per-table filter inputs; openModal is retained as a page global for parity (the
// open flow itself now runs through the global open-modal-display handler). A classic-script
// top-level function is already a window property, but the explicit assignment documents the contract
// and satisfies no-unused-vars.
window.filterTable = filterTable;
window.openModal = openModal;

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.modal').forEach((m) => {
        m.addEventListener('click', function (e) {
            if (e.target === this) {
                this.style.display = 'none';
            }
        });
    });
});

// CSP-safe delegated binding for the page-local close-modal flow (which additionally
// calls window.resetUnsavedChanges before hiding — see closeModal above). Open and the
// form-submit are covered by the global common-handlers (open-modal-display /
// submit-form-by-id).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'ship-close-modal', function (el) {
        closeModal(el.getAttribute('data-modal-id'));
    });
}

// In-place AJAX (epic #571 / REQ-FE-001): the visibility toggles flip the row and the reset
// clears the fitted flag without a full-page reload. The classic POST→redirect forms stay the
// no-JS fallback (the AJAX twins are gated on the X-Requested-With header).
(function () {
    if (!window.krtFetch) {
        return;
    }
    const i18n = shipDataI18n;
    const conflict = shipDataConflict;

    // Visibility toggle: intercept the per-row form submit and flip the affected row in place
    // (button label + secondary style + dimmed opacity), updating the hidden input so the next
    // toggle sends the opposite value. Delegated on document so it needs no per-row binding.
    document.addEventListener('submit', function (e) {
        const form = e.target;
        if (!form.classList || !form.classList.contains('js-visibility-toggle')) {
            return;
        }
        e.preventDefault();
        const hiddenInput = form.querySelector('input[name="hidden"]');
        const desired = hiddenInput ? hiddenInput.value === 'true' : true;
        const btn = form.querySelector('button[type="submit"]');
        if (btn) {
            btn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: form.getAttribute('action') + '?hidden=' + desired,
                toast: false,
                errorMessage: i18n.toggleError,
                conflict: conflict,
                onSuccess: function () {
                    const row = form.closest('tr');
                    if (row) {
                        row.style.opacity = desired ? '0.5' : '';
                    }
                    if (hiddenInput) {
                        hiddenInput.value = (!desired).toString();
                    }
                    if (btn) {
                        btn.textContent = desired
                            ? form.getAttribute('data-label-show')
                            : form.getAttribute('data-label-hide');
                        btn.classList.toggle('btn-secondary', !desired);
                    }
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(i18n.saved);
                    }
                },
            })
            .then(function () {
                if (btn) {
                    btn.disabled = false;
                }
            });
    });

    // Reset-all-fitted: drive the confirm button through krtFetch, then close the modal + toast.
    window.krtEvents.on('click', 'shipdata-reset', function (el) {
        el.disabled = true;
        window.krtFetch
            .write({
                method: 'POST',
                url: shipDataResetUrl,
                toast: false,
                errorMessage: i18n.resetError,
                conflict: conflict,
                onSuccess: function () {
                    closeModal('reset-fitted-confirm-modal');
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(i18n.resetSuccess);
                    }
                },
            })
            .then(function () {
                el.disabled = false;
            });
    });
})();
