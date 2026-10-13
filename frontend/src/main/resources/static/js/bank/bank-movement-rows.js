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

import { setMovementRowActive } from '/js/bank/bank-controls.js';
import { toggleCounterpartyExternal } from '/js/bank/bank-counterparty.js';
import { toggleSplitRow, updateFeePreview } from '/js/bank/bank-previews.js';
import { krtBankAccountMeta } from '/js/kernel/krt-bank-account-search.js';

/**
 * Maps a movement type on the unified "Kontobewegung" modal onto its backend endpoint and the
 * JSON field the source-account id is submitted under.
 *
 * @type {Record<string, {endpoint: string, accountField: string}>}
 */
const MOVEMENT_ENDPOINTS = {
    DEPOSIT: { endpoint: '/api/proxy/bank/deposits', accountField: 'accountId' },
    WITHDRAWAL: { endpoint: '/api/proxy/bank/withdrawals', accountField: 'accountId' },
    TRANSFER: { endpoint: '/api/proxy/bank/transfers', accountField: 'sourceAccountId' },
};

/**
 * Recomputes the Begründung field's `required` flag on the unified movement modal (REQ-BANK-045):
 * required only for a withdrawal or transfer whose source account mandates a reason, read from
 * the source option, `window.krtBankAccountMeta` or the form's `data-justification-required`.
 *
 * @param {HTMLFormElement} form the movement form
 */
function updateMovementJustification(form) {
    const input = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="justification"]')
    );
    const typeEl = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('[data-role="bank-movement-type"]')
    );
    if (!input || !typeEl) {
        return;
    }
    const type = typeEl.value;
    if (type !== 'WITHDRAWAL' && type !== 'TRANSFER') {
        input.required = false;
        return;
    }
    const source = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        form.querySelector('[data-role="bank-movement-source"]')
    );
    if (source) {
        const sourceSelect = /** @type {HTMLSelectElement} */ (source);
        if (sourceSelect.options) {
            const option = sourceSelect.options[sourceSelect.selectedIndex];
            input.required =
                !!option && option.getAttribute('data-requires-justification') === 'true';
        } else {
            const meta = krtBankAccountMeta;
            input.required = !!source.value && meta[source.value] === true;
        }
    } else {
        input.required = form.getAttribute('data-justification-required') === 'true';
    }
}

/**
 * Adapts the unified "Kontobewegung" modal to the chosen movement type (REQ-BANK-017): sets the
 * endpoint and source-account field, activates only that type's `[data-movement-types]` rows,
 * swaps the labels and refreshes the split, fee and justification helpers.
 *
 * @param {HTMLSelectElement} typeSelect the movement-type select
 */
export function syncMovementRows(typeSelect) {
    const form = typeSelect.closest('form');
    if (!form) {
        return;
    }
    const type = typeSelect.value;
    const spec = MOVEMENT_ENDPOINTS[type];
    if (spec) {
        form.setAttribute('data-endpoint', spec.endpoint);
        form.setAttribute('data-account-id-field', spec.accountField);
    }
    /** @type {NodeListOf<HTMLElement>} */ (form.querySelectorAll('[data-movement-types]')).forEach(
        (row) => {
            const types = /** @type {string} */ (row.getAttribute('data-movement-types')).split(
                /\s+/,
            );
            setMovementRowActive(row, types.indexOf(type) !== -1);
        },
    );
    const splitToggle = /** @type {HTMLInputElement | null} */ (
        form.querySelector('[data-role="bank-split-toggle"]')
    );
    if (splitToggle) {
        toggleSplitRow(splitToggle);
    }
    /** @type {NodeListOf<HTMLInputElement>} */ (
        form.querySelectorAll('[data-role="bank-cp-external-toggle"]')
    ).forEach((t) => {
        if (!t.disabled) {
            toggleCounterpartyExternal(t);
        }
    });
    const submit = form.querySelector('button[type="submit"][data-label-deposit]');
    if (submit) {
        const label = submit.getAttribute(`data-label-${type.toLowerCase()}`);
        if (label) {
            submit.textContent = label;
        }
    }
    const accountLabel = form.querySelector('[data-role="bank-movement-account-label"]');
    if (accountLabel) {
        const accountLabelText = accountLabel.getAttribute(`data-label-${type.toLowerCase()}`);
        if (accountLabelText) {
            accountLabel.textContent = accountLabelText;
        }
    }
    updateFeePreview(form);
    updateMovementJustification(form);
}

/**
 * Registers the document listeners that adapt the movement modal to the chosen movement type and
 * source account.
 */
export function initMovementRows() {
    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const select = /** @type {HTMLSelectElement | null} */ (
            target.closest('select[data-role="bank-movement-type"]')
        );
        if (select) {
            syncMovementRows(select);
        }
    });

    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const source = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
            target.closest('[data-role="bank-movement-source"]')
        );
        if (!source) {
            return;
        }
        const form = source.closest('form');
        if (!form) {
            return;
        }
        form.setAttribute('data-account-id', source.value || '');
        updateMovementJustification(form);
        updateFeePreview(form);
    });
}
