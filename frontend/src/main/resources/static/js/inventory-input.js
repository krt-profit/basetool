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
 * Inventory-input page module (/inventory/input), extracted verbatim from the former two inline
 * scripts of inventory-input.html (ADR-0069, follow-up to #924).
 *
 * First: filters the job-order dropdown to the orders that need the chosen material and mirrors the
 * amount field to the material's quantity type (PIECE integer step vs. SCU 0.001 step + hint).
 * Second (an IIFE): the #577 in-place book-into-inventory submit through krtFetch.submitForm —
 * navigate-after-AJAX on success, a code-specific inline error toast on failure; the classic
 * POST->redirect stays the no-JS fallback. Both former blocks always render, so they are combined
 * into this single module in document order.
 *
 * MSG_UNIT_PIECE / MSG_UNIT_SCU and the INV_ADD_MSG error strings are defined by the inline Thymeleaf
 * bootstrap block of inventory-input.html, which executes immediately before this classic script.
 */

/* global MSG_UNIT_PIECE, MSG_UNIT_SCU, INV_ADD_MSG */

document.addEventListener('DOMContentLoaded', function () {
    const matSelect = document.getElementById('materialId');
    if (matSelect) {
        matSelect.addEventListener('change', function () {
            filterJobOrdersByMaterial(this.value);
            updateAmountFieldForMaterial(this);
        });
        // Initial filter if a material is already selected
        if (matSelect.value) {
            filterJobOrdersByMaterial(matSelect.value);
            updateAmountFieldForMaterial(matSelect);
        } else {
            // If no material is selected, show all job orders initially
            filterJobOrdersByMaterial('');
        }
    }
});

function updateAmountFieldForMaterial(selectElement) {
    const amountInput = document.getElementById('amount');
    const unitSpan = document.getElementById('amount-unit');
    const scuHint = document.getElementById('amount-scu-hint');
    if (
        !amountInput ||
        !selectElement.selectedOptions ||
        selectElement.selectedOptions.length === 0
    )
        return;

    const qtType = selectElement.selectedOptions[0].getAttribute('data-quantity-type');

    if (qtType === 'PIECE') {
        amountInput.setAttribute('step', '1');
        unitSpan.textContent = '(' + MSG_UNIT_PIECE + ')';
        if (scuHint) scuHint.classList.add('krtm-hidden');
    } else if (qtType === 'SCU') {
        amountInput.setAttribute('step', '0.001');
        unitSpan.textContent = '(' + MSG_UNIT_SCU + ')';
        if (scuHint) scuHint.classList.remove('krtm-hidden');
    } else {
        amountInput.setAttribute('step', '0.001');
        unitSpan.textContent = '';
        if (scuHint) scuHint.classList.add('krtm-hidden');
    }
}

function filterJobOrdersByMaterial(matId) {
    const jobSelect = document.getElementById('jobOrderId');
    if (!jobSelect) return;

    let hasSelectedValidOption = false;

    for (let i = 1; i < jobSelect.options.length; i++) {
        const option = jobSelect.options[i];

        if (!matId) {
            // If no material is selected, show all active orders
            option.style.display = '';
            option.disabled = false;
            if (option.selected) hasSelectedValidOption = true;
        } else {
            const materialsStr = option.getAttribute('data-materials');
            if (materialsStr) {
                const materials = materialsStr.split(',');
                if (materials.includes(matId)) {
                    option.style.display = '';
                    option.disabled = false;
                    if (option.selected) hasSelectedValidOption = true;
                } else {
                    option.style.display = 'none';
                    option.disabled = true;
                }
            } else {
                option.style.display = 'none';
                option.disabled = true;
            }
        }
    }

    if (jobSelect.selectedIndex > 0 && !hasSelectedValidOption) {
        jobSelect.value = '';
    }
}

// #577: book an item into the inventory in place. The classic POST->redirect stays the no-JS
// fallback; here we submit via the X-Requested-With twin and navigate to the source listing on
// success (navigate-after-AJAX), keeping the form + a toast on a validation/backend error
// instead of a flash-redirect. FormData submit lets the browser serialize the SCU-decimal amount
// + owner-picker exactly like the classic POST.
(function () {
    if (!window.krtFetch) return; // no-JS / no-foundation: the classic POST->redirect runs.
    const form = document.getElementById('inventory-input-form');
    if (!form) return;
    // Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF
    // header (no Content-Type so the browser sets the multipart boundary), the bare-403
    // refresh-and-retry, X-Reauthenticate and the double-submit guard. This handler keeps only
    // its page behaviour: navigate-after-AJAX on success (toast:false — the destination renders
    // its own toast) and the code-specific inline error toast (onError). The classic
    // POST->redirect stays the no-JS fallback (the guard above returns before this listener).
    form.addEventListener('submit', function (event) {
        event.preventDefault();
        window.krtFetch.submitForm({
            form: form,
            submitter: form.querySelector('button[type="submit"]'),
            toast: false,
            errorMessage: INV_ADD_MSG.failed,
            onError: function (status, problem) {
                let msg = INV_ADD_MSG.failed;
                if (problem && problem.code === 'INVENTORY_PERSONAL_ASSIGNMENT')
                    msg = INV_ADD_MSG.personalAssignment;
                else if (problem && problem.code === 'VALIDATION') msg = INV_ADD_MSG.validation;
                else if (problem && problem.detail) msg = problem.detail;
                if (window.showFrontendErrorToast) window.showFrontendErrorToast(msg);
                return true;
            },
            onSuccess: function (body) {
                // Navigate to the source listing carrying a success signal the destination's
                // toast fragment renders — a pre-navigation toast would be torn down by the load.
                const target = body && body.targetUrl ? body.targetUrl : '/inventory';
                window.location.assign(
                    target +
                        (target.indexOf('?') >= 0 ? '&' : '?') +
                        'success=success.inventory.add',
                );
            },
        });
    });
})();
