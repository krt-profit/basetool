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
 * First: mirrors the amount field to the material's quantity type (PIECE integer step vs. SCU 0.001
 * step + hint) and drives the Variante-C split-at-check-in rows (REQ-INV-027, R4) — repeatable
 * job-order / mission allocation rows cloned from hidden templates and bound as indexed form params
 * (jobOrderAllocations[i].targetId / .amount), each order row filtered to the chosen material, with
 * an over-allocation hint and a personal-toggle that clears/hides the sections.
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
            filterOrderSelects(this.value);
            updateAmountFieldForMaterial(this);
        });
        if (matSelect.value) {
            updateAmountFieldForMaterial(matSelect);
        }
    }

    const personalToggle = document.getElementById('personal');
    if (personalToggle) {
        personalToggle.addEventListener('change', syncPersonalAllocations);
    }

    // Delegated controls for the Variante-C split-at-check-in rows (REQ-INV-027, R4).
    document.addEventListener('click', function (event) {
        if (event.target.closest('[data-trigger="inv-input-add-order"]')) {
            addAllocRow('jobOrder');
        } else if (event.target.closest('[data-trigger="inv-input-add-mission"]')) {
            addAllocRow('mission');
        } else if (event.target.closest('[data-trigger="inv-input-remove-alloc"]')) {
            removeAllocRow(event.target.closest('[data-trigger="inv-input-remove-alloc"]'));
        }
    });
    document.addEventListener('input', function (event) {
        if (event.target.matches('[data-alloc-amount]') || event.target.id === 'amount') {
            updateAllocOver();
        }
    });
    document.addEventListener('change', function (event) {
        if (event.target.matches('[data-alloc-target]')) {
            updateAllocOver();
        }
    });
});

function updateAmountFieldForMaterial(selectElement) {
    const amountInput = document.getElementById('amount');
    const unitSpan = document.getElementById('amount-unit');
    const scuHint = document.getElementById('amount-scu-hint');
    if (!amountInput) return;

    // The material picker is a searchable combobox (REQ-FE-016): the selected option's
    // data-quantity-type is mirrored onto the hidden input carrying #materialId, replacing the
    // former selectedOptions[0] read (the native <option>s are gone after enhancement).
    const qtType = selectElement.dataset.quantityType || '';

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

    updateMergeOptIn(qtType);
}

// REQ-INV-026: the per-action stock-merge opt-in is offered only for an SCU material — a PIECE
// book-in always merges server-side, so the checkbox is hidden and reset for PIECE / no selection.
function updateMergeOptIn(qtType) {
    const mergeRow = document.getElementById('merge-stock-row');
    const mergeCheckbox = document.getElementById('mergeStock');
    if (!mergeRow) return;
    if (qtType === 'SCU') {
        mergeRow.classList.remove('krtm-hidden');
    } else {
        mergeRow.classList.add('krtm-hidden');
        if (mergeCheckbox) mergeCheckbox.checked = false;
    }
}

// ===== Variante C split-at-check-in (REQ-INV-027, R4) =====
// Repeatable job-order / mission allocation rows, cloned from the hidden <template>s and bound as
// indexed form params (jobOrderAllocations[i].targetId / .amount) the FormData submit carries; the
// single scalar selects were replaced by these lists.

// Per-dimension DOM/param handles for the split-at-check-in rows.
function allocConfig(dimension) {
    return dimension === 'jobOrder'
        ? {
              rows: 'jobOrderAllocRows',
              template: 'jobOrderRowTemplate',
              prefix: 'jobOrderAllocations',
              group: 'jobOrderAllocGroup',
          }
        : {
              rows: 'missionAllocRows',
              template: 'missionRowTemplate',
              prefix: 'missionAllocations',
              group: 'missionAllocGroup',
          };
}

// Renumbers a dimension's rows so the indexed form params stay contiguous (Spring binding needs no
// gaps) after an add or remove.
function reindexAllocRows(dimension) {
    const cfg = allocConfig(dimension);
    const container = document.getElementById(cfg.rows);
    if (!container) return;
    container.querySelectorAll('[data-alloc-row]').forEach(function (row, index) {
        const select = row.querySelector('[data-alloc-target]');
        const amount = row.querySelector('[data-alloc-amount]');
        if (select) select.name = cfg.prefix + '[' + index + '].targetId';
        if (amount) amount.name = cfg.prefix + '[' + index + '].amount';
    });
}

// Appends a fresh allocation row for the dimension, cloned from its <template>.
function addAllocRow(dimension) {
    const cfg = allocConfig(dimension);
    const container = document.getElementById(cfg.rows);
    const template = document.getElementById(cfg.template);
    if (!container || !template) return;
    container.appendChild(template.content.cloneNode(true));
    reindexAllocRows(dimension);
    if (dimension === 'jobOrder') {
        const matSelect = document.getElementById('materialId');
        filterOrderSelects(matSelect ? matSelect.value : '');
    }
    updateAllocOver();
}

// Removes the allocation row owning `button` and renumbers the surviving rows of that dimension.
function removeAllocRow(button) {
    const row = button.closest('[data-alloc-row]');
    if (!row) return;
    const missionContainer = document.getElementById('missionAllocRows');
    const dimension = missionContainer && missionContainer.contains(row) ? 'mission' : 'jobOrder';
    row.remove();
    reindexAllocRows(dimension);
    updateAllocOver();
}

// Filters every job-order row select to the orders that need the chosen material (data-materials
// CSV), clearing an incompatible current selection — the multi-row successor of the former single
// #jobOrderId filter.
function filterOrderSelects(matId) {
    document.querySelectorAll('#jobOrderAllocRows [data-alloc-target]').forEach(function (select) {
        let hasSelectedValid = false;
        for (let i = 1; i < select.options.length; i++) {
            const option = select.options[i];
            const materialsStr = option.getAttribute('data-materials');
            const compatible = !matId || (materialsStr && materialsStr.split(',').includes(matId));
            option.style.display = compatible ? '' : 'none';
            option.disabled = !compatible;
            if (compatible && option.selected) hasSelectedValid = true;
        }
        if (select.selectedIndex > 0 && !hasSelectedValid) select.value = '';
    });
}

// Sums a dimension's entered allocation amounts.
function allocDimensionSum(dimension) {
    let sum = 0;
    document
        .querySelectorAll('#' + allocConfig(dimension).rows + ' [data-alloc-amount]')
        .forEach(function (input) {
            const value = parseFloat(input.value);
            if (!isNaN(value)) sum += value;
        });
    return sum;
}

// Shows the over-allocation warning when either dimension's Σ exceeds the entry amount (the backend
// enforces the same R5 rule with a 422).
function updateAllocOver() {
    const overEl = document.getElementById('inputAllocOver');
    if (!overEl) return;
    const amountEl = document.getElementById('amount');
    const raw = amountEl ? amountEl.value : '';
    const amount = window.krtScuInput && amountEl ? window.krtScuInput.parse(raw) : parseFloat(raw);
    const total = isNaN(amount) ? 0 : amount;
    const over =
        allocDimensionSum('jobOrder') > total + 1e-6 || allocDimensionSum('mission') > total + 1e-6;
    overEl.classList.toggle('krtm-hidden', !over);
}

// Personal stock can never be assigned: hide + clear both allocation sections while personal is
// ticked (the backend rejects a personal entry carrying assignments too).
function syncPersonalAllocations() {
    const personal = document.getElementById('personal');
    const on = !!(personal && personal.checked);
    ['jobOrder', 'mission'].forEach(function (dimension) {
        const cfg = allocConfig(dimension);
        const group = document.getElementById(cfg.group);
        if (group) group.classList.toggle('krtm-hidden', on);
        if (on) {
            const container = document.getElementById(cfg.rows);
            if (container) container.innerHTML = '';
        }
    });
    updateAllocOver();
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
