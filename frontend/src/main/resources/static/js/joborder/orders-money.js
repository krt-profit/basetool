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

import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { bindWholeAuec, formatWholeAuec, readWholeAuec } from '/js/joborder/orders-money-amount.js';
import { bindHolderChoices } from '/js/joborder/orders-money-holders.js';
import { bindProvisionPreview } from '/js/joborder/orders-money-preview.js';
import { bindProvisionRecovery } from '/js/joborder/orders-money-recovery.js';

const modalId = 'order-money-provision-modal';
const root = document.getElementById('order-money-operation');
const form = document.getElementById('order-money-provision-form');

if (root && form instanceof HTMLFormElement) bindProvisionForm(root, form);

/**
 * Preserves the draft outside the money section while updating its holder revision.
 *
 * @param {HTMLElement} root the account scope and recovery controls
 * @param {HTMLFormElement} form the provision form
 */
function bindProvisionForm(root, form) {
    const fields = form.querySelector('fieldset');
    const input = form.elements.namedItem('amount');
    const bankFree = form.elements.namedItem('bankFree');
    const changed = form.querySelector('[data-money-basis-changed]');
    const amountError = form.querySelector('[data-money-amount-error]');
    if (
        !(fields instanceof HTMLFieldSetElement) ||
        !(input instanceof HTMLInputElement) ||
        !(bankFree instanceof HTMLInputElement) ||
        !(changed instanceof HTMLElement) ||
        !(amountError instanceof HTMLElement)
    ) {
        throw new Error('The order provision form controls are missing');
    }

    const holders = bindHolderChoices(form, root);
    /** @type {ApiDto<'ProvideJobOrderMoneyDto'> | null} */
    let refusedDraft = null;
    let basis = '';
    let saving = false;
    bindWholeAuec(input);

    const preview = bindProvisionPreview(form, `/orders/${root.dataset.orderId}/money`, () => {
        const amount = readWholeAuec(input.value);
        const selection = holders.selection();
        return amount && bankFree.checked && selection
            ? { ...selection, amount, bankHandling: false }
            : null;
    });

    const updateDraft = () => {
        basis = JSON.stringify(holders.selection());
        if (!saving) preview.refresh();
    };

    /**
     * Opens a new draft or restores the refused terms for another explicit choice.
     *
     * @param {ApiDto<'ProvideJobOrderMoneyDto'> | null} [refused] the resolved stale submission
     */
    const open = (refused = null) => {
        form.reset();
        holders.reset();
        preview.reset();
        changed.hidden = refused === null;
        amountError.hidden = true;
        input.removeAttribute('aria-invalid');
        if (refused?.amount) {
            holders.restore(refused);
            input.value = formatWholeAuec(refused.amount);
        }
        updateDraft();
        krtModal.open(modalId);
    };

    const openPending = () => {
        open(refusedDraft);
        refusedDraft = null;
    };

    const recovery = bindProvisionRecovery(root, (refused) => {
        refusedDraft = refused;
        holders.refresh();
        if (holders.canProvide()) {
            openPending();
        } else {
            krtModal.close(modalId);
            form.reset();
            preview.reset();
        }
    });
    krtEvents.on('click', 'od-money-open', () => {
        if (recovery.pending()) {
            void recovery.recover();
            return;
        }
        openPending();
    });
    form.addEventListener('input', updateDraft);
    form.addEventListener('change', updateDraft);
    input.addEventListener('blur', () => {
        const invalid = input.value.trim() !== '' && readWholeAuec(input.value) === null;
        amountError.hidden = !invalid;
        if (invalid) input.setAttribute('aria-invalid', 'true');
        else input.removeAttribute('aria-invalid');
    });
    input.addEventListener('input', () => {
        if (readWholeAuec(input.value) !== null) {
            amountError.hidden = true;
            input.removeAttribute('aria-invalid');
        }
    });

    document.addEventListener('krt:swapped', (event) => {
        if (event.detail.container?.id !== 'order-money-results' || !krtModal.isOpen(modalId))
            return;
        holders.refresh();
        const next = JSON.stringify(holders.selection());
        if (next !== basis && (input.value || bankFree.checked)) changed.hidden = false;
        updateDraft();
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const terms = preview.ready();
        if (!terms || saving) return;
        saving = true;
        fields.disabled = true;
        try {
            const submitted = await recovery.submit(terms, event.submitter);
            if (submitted) {
                krtModal.close(modalId);
                form.reset();
                preview.reset();
            }
        } finally {
            saving = false;
            fields.disabled = false;
            updateDraft();
        }
    });
}
