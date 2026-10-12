// @ts-check
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

import { toggleTransferOnlyControl } from '/js/bank/bank-controls.js';
import {
    toggleSplitRow,
    updateJustificationRequired,
    updateLimitWarning,
} from '/js/bank/bank-previews.js';

/**
 * Adapts the org-unit request modal to the chosen request type (REQ-BANK-039, REQ-BANK-042):
 * filters the source accounts (any for a deposit, `data-can-debit` otherwise), and toggles the
 * hints, transfer, split, justification and counterparty rows before recomputing the warnings.
 *
 * @param {HTMLSelectElement} select the request-type select
 */
export function syncRequestTypeRows(select) {
    const form = select.closest('form');
    if (!form) {
        return;
    }
    const type = select.value;
    const isTransfer = type === 'TRANSFER';
    const isDeposit = type === 'DEPOSIT';
    const account = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[data-role="org-unit-request-account"]')
    );
    if (account) {
        let currentVisible = false;
        Array.prototype.forEach.call(account.options, (option) => {
            const debitable = option.getAttribute('data-can-debit') === 'true';
            const visible = isDeposit || debitable;
            option.hidden = !visible;
            option.disabled = !visible;
            if (visible && option.value === account.value) {
                currentVisible = true;
            }
        });
        if (!currentVisible) {
            const first = Array.prototype.find.call(account.options, (o) => {
                return !o.disabled;
            });
            account.value = first ? first.value : '';
        }
    }
    const depositHint = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-deposit-hint]')
    );
    const debitHint = /** @type {HTMLElement | null} */ (form.querySelector('[data-debit-hint]'));
    if (depositHint) {
        depositHint.hidden = !isDeposit;
    }
    if (debitHint) {
        debitHint.hidden = isDeposit;
    }
    const row = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-request-transfer-only]')
    );
    toggleTransferOnlyControl(row, isTransfer);
    const splitBlock = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-split-deposit-only]')
    );
    if (splitBlock) {
        splitBlock.hidden = !isDeposit;
        const splitToggle = /** @type {HTMLInputElement | null} */ (
            splitBlock.querySelector('[data-role="bank-split-toggle"]')
        );
        if (splitToggle) {
            splitToggle.disabled = !isDeposit;
            if (!isDeposit) {
                splitToggle.checked = false;
            }
            toggleSplitRow(splitToggle);
        }
    }
    const justificationRow = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-request-justification-only]')
    );
    if (justificationRow) {
        const isDebit = type === 'WITHDRAWAL' || type === 'TRANSFER';
        justificationRow.hidden = !isDebit;
        const input = /** @type {HTMLInputElement | null} */ (
            justificationRow.querySelector('input[name="justification"]')
        );
        if (input) {
            input.disabled = !isDebit;
            if (!isDebit) {
                input.value = '';
                input.required = false;
            }
        }
    }
    syncRequestCounterparty(form, type === 'WITHDRAWAL');
    updateJustificationRequired(form);
    updateLimitWarning(form);
}

/**
 * Enables the request's Empfänger block only for a withdrawal (REQ-BANK-055); otherwise its
 * controls are disabled but keep their values. On first activation it dispatches one `change` on
 * a pre-filled user picker so its org units load.
 *
 * @param {HTMLFormElement} form the request form
 * @param {boolean} on whether the block is active (the request is a withdrawal)
 */
function syncRequestCounterparty(form, on) {
    const block = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-request-withdrawal-only]')
    );
    if (!block) {
        return;
    }
    block.hidden = !on;
    /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement>} */ (
        block.querySelectorAll(
            'select, .krt-combobox input[type="hidden"], input:not(.krt-combobox__input)',
        )
    ).forEach((control) => {
        control.disabled = !on;
        const box = control.closest ? control.closest('.krt-combobox') : null;
        const textbox = /** @type {HTMLInputElement | null} */ (
            box ? box.querySelector('.krt-combobox__input') : null
        );
        if (textbox) {
            textbox.disabled = !on;
        }
    });
    if (!on) {
        return;
    }
    const picker = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        block.querySelector('[data-counterparty-user]')
    );
    if (picker && picker.value && !picker.dataset.cpUnitsPrimed) {
        picker.dataset.cpUnitsPrimed = 'true';
        picker.dispatchEvent(new Event('change', { bubbles: true }));
    }
}

/**
 * Registers the document listener that adapts the org-unit request modal to the chosen request type.
 */
export function initRequestRows() {
    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const select = /** @type {HTMLSelectElement | null} */ (
            target.closest('select[data-role="org-unit-request-type"]')
        );
        if (select) {
            syncRequestTypeRows(select);
        }
    });
}
