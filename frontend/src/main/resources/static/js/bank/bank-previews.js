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
 * Reads the org-wide in-game transfer-fee rate from `<main data-transfer-fee-rate>`
 * (REQ-BANK-033).
 *
 * @returns {number} the fee rate as a fraction, or 0 when absent or outside [0, 1)
 */
function transferFeeRate() {
    const main = document.querySelector('main[data-transfer-fee-rate]');
    const raw = main ? Number(main.getAttribute('data-transfer-fee-rate')) : 0;
    return Number.isFinite(raw) && raw > 0 && raw < 1 ? raw : 0;
}

/**
 * Updates the live transfer-fee preview and the fee-inclusive toggle (REQ-BANK-033). The fee is
 * `round(amount * rate)`, added on top by default or deducted from the amount when `feeInclusive`
 * is checked. Deposits and same-holder transfers are fee-free and hide the toggle and preview.
 *
 * @param {HTMLFormElement} form the booking form carrying a `[data-fee-preview]` block
 */
export function updateFeePreview(form) {
    const preview = /** @type {HTMLElement | null} */ (form.querySelector('[data-fee-preview]'));
    const amountEl = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="amount"]')
    );
    if (!preview || !amountEl) {
        return;
    }
    const rate = transferFeeRate();
    const typeEl = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('[data-role="bank-movement-type"]')
    );
    const type = typeEl ? typeEl.value : null;
    const src = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        form.querySelector('[name="sourceHolderId"]')
    );
    const dst = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        form.querySelector('[name="destinationHolderId"]')
    );
    const sameHolder = !!(src && dst && src.value && src.value === dst.value);
    const feeApplies = rate > 0 && type !== 'DEPOSIT' && !(type === 'TRANSFER' && sameHolder);
    const inclusiveRow = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-fee-inclusive-row]')
    );
    const inclusiveToggle = /** @type {HTMLInputElement | null} */ (
        inclusiveRow ? inclusiveRow.querySelector('input[name="feeInclusive"]') : null
    );
    if (inclusiveRow) {
        inclusiveRow.hidden = !feeApplies;
    }
    if (inclusiveToggle) {
        inclusiveToggle.disabled = !feeApplies;
        if (!feeApplies) {
            inclusiveToggle.checked = false;
        }
    }
    const amount = Number(amountEl.value);
    if (!feeApplies || !Number.isFinite(amount) || amount <= 0) {
        preview.hidden = true;
        return;
    }
    const fee = Math.round(amount * rate);
    const inclusive = !!(inclusiveToggle && inclusiveToggle.checked);
    const debited = inclusive ? amount : amount + fee;
    const arrives = inclusive ? amount - fee : amount;
    const valueEl = preview.querySelector('[data-fee-value]');
    const debitEl = preview.querySelector('[data-fee-debit]');
    const arrivesEl = preview.querySelector('[data-fee-arrives]');
    if (valueEl) {
        valueEl.textContent = fee.toLocaleString('de-DE');
    }
    if (debitEl) {
        debitEl.textContent = debited.toLocaleString('de-DE');
    }
    if (arrivesEl) {
        arrivesEl.textContent = arrives.toLocaleString('de-DE');
    }
    preview.hidden = false;
}

/**
 * Recomputes the fee preview for the form the edited field belongs to.
 *
 * @param {Element | null} target the field that fired the event
 */
function recomputeFeePreviewFrom(target) {
    const form = target && target.closest ? target.closest('form') : null;
    if (form && form.querySelector('[data-fee-preview]')) {
        updateFeePreview(form);
    }
}

/**
 * Shows the advisory owner-approval warning on the org-unit request modal when a withdrawal or
 * transfer exceeds the source option's `data-limit` (REQ-BANK-041). A missing limit always warns;
 * a `data-exempt="true"` account never does.
 *
 * @param {HTMLFormElement} form the org-unit request form carrying a `[data-limit-warning]` block
 */
export function updateLimitWarning(form) {
    const warning = /** @type {HTMLElement | null} */ (form.querySelector('[data-limit-warning]'));
    const amountEl = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="amount"]')
    );
    const accountEl = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[name="sourceAccountId"]')
    );
    if (!warning || !amountEl || !accountEl) {
        return;
    }
    const typeEl = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[data-role="org-unit-request-type"]')
    );
    if (typeEl && typeEl.value === 'DEPOSIT') {
        warning.hidden = true;
        return;
    }
    const amount = Number(amountEl.value);
    if (!Number.isFinite(amount) || amount <= 0) {
        warning.hidden = true;
        return;
    }
    const option = accountEl.options[accountEl.selectedIndex];
    if (option && option.getAttribute('data-exempt') === 'true') {
        warning.hidden = true;
        return;
    }
    const rawLimit = option ? option.getAttribute('data-limit') : null;
    const limit = rawLimit === null || rawLimit === '' ? null : Number(rawLimit);
    const needsApproval = limit === null || !Number.isFinite(limit) || amount > limit;
    warning.hidden = !needsApproval;
}

/**
 * Recomputes the over-limit warning for the form the edited field belongs to.
 *
 * @param {Element | null} target the field that fired the event
 */
function recomputeLimitWarningFrom(target) {
    const form = target && target.closest ? target.closest('form') : null;
    if (form && form.querySelector('[data-limit-warning]')) {
        updateLimitWarning(form);
    }
}

/**
 * Recomputes the Begründung field's `required` flag on the org-unit request modal (REQ-BANK-045):
 * required only for a withdrawal or transfer whose source option carries
 * `data-requires-justification="true"`.
 *
 * @param {HTMLFormElement} form the org-unit request form
 */
export function updateJustificationRequired(form) {
    const input = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="justification"]')
    );
    if (!input) {
        return;
    }
    const typeEl = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[data-role="org-unit-request-type"]')
    );
    const type = typeEl ? typeEl.value : null;
    if (type !== 'WITHDRAWAL' && type !== 'TRANSFER') {
        input.required = false;
        return;
    }
    const accountEl = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[name="sourceAccountId"]')
    );
    const option = accountEl ? accountEl.options[accountEl.selectedIndex] : null;
    input.required = !!option && option.getAttribute('data-requires-justification') === 'true';
}

/**
 * Recomputes the justification-required flag for the edited field's form, only when it has a
 * `[data-request-justification-only]` wrapper.
 *
 * @param {Element | null} target the field that fired the event
 */
function recomputeJustificationRequiredFrom(target) {
    const form = target && target.closest ? target.closest('form') : null;
    if (form && form.querySelector('[data-request-justification-only]')) {
        updateJustificationRequired(form);
    }
}

/**
 * Shows and enables a deposit form's split-percentage row to match its toggle (REQ-BANK-043).
 * When off, the input is disabled and cleared so it is not submitted; the preview is refreshed.
 *
 * @param {HTMLInputElement} toggle the split toggle checkbox
 */
export function toggleSplitRow(toggle) {
    const form = toggle.closest('form');
    if (!form) {
        return;
    }
    const row = /** @type {HTMLElement | null} */ (form.querySelector('[data-split-row]'));
    const input = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="splitPercent"]')
    );
    const on = toggle.checked && !toggle.disabled;
    if (row) {
        row.hidden = !on;
    }
    if (input) {
        input.disabled = !on;
        if (!on) {
            input.value = '';
        }
    }
    updateSplitPreview(form);
}

/**
 * Updates the live split-deposit preview (REQ-BANK-043): the slice distributed to the squadron
 * accounts (`round(gross * percent / 100)`) and the remainder kept on the named account. Hidden
 * when the split is off or the inputs are out of range.
 *
 * @param {HTMLFormElement} form the deposit/request form carrying a `[data-split-preview]` block
 */
function updateSplitPreview(form) {
    const preview = /** @type {HTMLElement | null} */ (form.querySelector('[data-split-preview]'));
    const amountEl = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="amount"]')
    );
    const percentEl = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="splitPercent"]')
    );
    if (!preview || !amountEl || !percentEl) {
        return;
    }
    const gross = Number(amountEl.value);
    const percent = Number(percentEl.value);
    if (
        percentEl.disabled ||
        !Number.isFinite(gross) ||
        gross <= 0 ||
        !Number.isFinite(percent) ||
        percent <= 0 ||
        percent > 100
    ) {
        preview.hidden = true;
        return;
    }
    const slice = Math.round((gross * percent) / 100);
    if (slice <= 0) {
        preview.hidden = true;
        return;
    }
    const sliceEl = preview.querySelector('[data-split-slice]');
    const remainderEl = preview.querySelector('[data-split-remainder]');
    if (sliceEl) {
        sliceEl.textContent = slice.toLocaleString('de-DE');
    }
    if (remainderEl) {
        remainderEl.textContent = (gross - slice).toLocaleString('de-DE');
    }
    preview.hidden = false;
}

/**
 * Recomputes the split preview for the form the edited field belongs to (amount or percentage).
 *
 * @param {Element | null} target the field that fired the event
 */
function recomputeSplitPreviewFrom(target) {
    const form = target && target.closest ? target.closest('form') : null;
    if (form && form.querySelector('[data-split-preview]')) {
        updateSplitPreview(form);
    }
}

/**
 * Updates the client-side balance-split calculator on the holder detail page (REQ-BANK-032): the
 * holder's own money is the entered in-game balance minus the panel's `data-reserved` total.
 *
 * @param {HTMLInputElement} input the balance input that fired
 */
function updateBalanceCalc(input) {
    const panel = input.closest('[data-reserved]');
    if (!panel) {
        return;
    }
    const result = /** @type {HTMLElement | null} */ (panel.querySelector('[data-balance-result]'));
    const ownEl = panel.querySelector('[data-balance-own]');
    if (!result || !ownEl) {
        return;
    }
    const balance = Number(input.value);
    if (input.value === '' || !Number.isFinite(balance)) {
        result.hidden = true;
        return;
    }
    const reserved = Number(panel.getAttribute('data-reserved'));
    const own = balance - (Number.isFinite(reserved) ? reserved : 0);
    ownEl.textContent = `${Math.round(own).toLocaleString('de-DE')} aUEC`;
    ownEl.classList.toggle('bank-amount--neg', own < 0);
    ownEl.classList.toggle('bank-amount--pos', own > 0);
    result.hidden = false;
}

/**
 * Dispatches one field edit to the fee preview, limit warning, justification flag, split preview
 * and balance calculator.
 *
 * @param {Element | null} target the field that fired the event
 */
function onBankFieldEdit(target) {
    recomputeFeePreviewFrom(target);
    recomputeLimitWarningFrom(target);
    recomputeJustificationRequiredFrom(target);
    recomputeSplitPreviewFrom(target);
    if (target && target.matches && target.matches('[data-balance-input]')) {
        updateBalanceCalc(/** @type {HTMLInputElement} */ (target));
    }
}

/**
 * Registers the document listeners that keep the split toggle and the live previews (fee, limit
 * warning, justification flag, split, balance) in step with the edited field.
 */
export function initFieldPreviews() {
    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const toggle = /** @type {HTMLInputElement | null} */ (
            target.closest('[data-role="bank-split-toggle"]')
        );
        if (toggle) {
            toggleSplitRow(toggle);
        }
    });

    document.addEventListener('input', (event) => {
        onBankFieldEdit(/** @type {Element} */ (event.target));
    });

    document.addEventListener('change', (event) => {
        onBankFieldEdit(/** @type {Element} */ (event.target));
    });
}
