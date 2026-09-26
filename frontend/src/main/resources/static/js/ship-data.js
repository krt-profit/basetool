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

function openModal(id) {
    document.getElementById(id).style.display = 'flex';
}
function closeModal(id) {
    if (typeof window.resetUnsavedChanges === 'function') {
        window.resetUnsavedChanges();
    }
    document.getElementById(id).style.display = 'none';
}

window.filterTable = filterTable;
window.openModal = openModal;

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.krt-modal-overlay').forEach((m) => {
        m.addEventListener('click', function (e) {
            if (e.target === this) {
                this.style.display = 'none';
            }
        });
    });
});

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'ship-close-modal', function (el) {
        closeModal(el.getAttribute('data-modal-id'));
    });
}

(function () {
    if (!window.krtFetch) {
        return;
    }
    const i18n = shipDataI18n;
    const conflict = shipDataConflict;

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
                conflict,
                onSuccess() {
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

    window.krtEvents.on('click', 'shipdata-reset', function (el) {
        el.disabled = true;
        window.krtFetch
            .write({
                method: 'POST',
                url: shipDataResetUrl,
                toast: false,
                errorMessage: i18n.resetError,
                conflict,
                onSuccess() {
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
