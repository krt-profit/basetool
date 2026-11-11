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
import { krtNumberInput } from '/js/kernel/krt-number-input.js';
import { bindHolderChoices } from '/js/joborder/orders-money-holders.js';
import { bindProvisionPreview } from '/js/joborder/orders-money-preview.js';
import { bindMoneyRecovery } from '/js/joborder/orders-money-recovery.js';
import { bindMoneyMovement } from '/js/joborder/orders-money-movement.js';
import { bindMoneySettings } from '/js/joborder/orders-money-settings.js';
import { bindMoneyCorrection } from '/js/joborder/orders-money-correction.js';
import { bindHolderIdentity } from '/js/joborder/orders-money-holder-identity.js';
import { bindRecipientIdentity } from '/js/joborder/orders-money-recipient-identity.js';
import {
    initMoneyCommitments,
    readAttributionChoice,
    refreshAttributionChoices,
} from '/js/joborder/orders-money-commitments.js';

const modalId = 'order-money-provision-modal';
const root = document.getElementById('order-money-operation');
const form = document.getElementById('order-money-provision-form');

initMoneyCommitments();
if (root && form instanceof HTMLFormElement) {
    const recovery = bindMoneyRecovery(root, (submission) => {
        const { command, terms } = submission;
        if (command === 'provisions') provision.restore(terms);
        else if (command === 'corrections') correction.restore(terms);
        else movement.restore(submission);
    });
    const provision = bindProvisionForm(root, form, recovery);
    const movement = bindMoneyMovement(root, recovery);
    const correction = bindMoneyCorrection(root, recovery);
    bindMoneySettings(root);
    bindHolderIdentity(root);
    bindRecipientIdentity(root);
    void recovery.recover();
}

/**
 * Preserves the draft outside the money section while updating its holder revision.
 *
 * @param {HTMLElement} root the account scope and recovery controls
 * @param {HTMLFormElement} form the provision form
 * @param {ReturnType<typeof bindMoneyRecovery>} recovery the shared account and order recovery
 */
function bindProvisionForm(root, form, recovery) {
    const fields = form.querySelector('fieldset');
    const input = form.elements.namedItem('amount');
    const bankFree = form.elements.namedItem('bankFree');
    const commitment = form.elements.namedItem('commitmentId');
    const commitmentField = form.querySelector('[data-money-commitment-field]');
    const changed = form.querySelector('[data-money-basis-changed]');
    const amountError = form.querySelector('[data-money-amount-error]');
    if (
        !(fields instanceof HTMLFieldSetElement) ||
        !(input instanceof HTMLInputElement) ||
        !(bankFree instanceof HTMLInputElement) ||
        !(commitment instanceof HTMLSelectElement) ||
        !(commitmentField instanceof HTMLElement) ||
        !(changed instanceof HTMLElement) ||
        !(amountError instanceof HTMLElement)
    ) {
        throw new Error('The order provision form controls are missing');
    }

    const holders = bindHolderChoices(form, root);
    /** @type {Omit<ApiDto<'ProvideJobOrderMoneyDto'>, 'operationId'> | null} */
    let refusedDraft = null;
    let basis = '';
    let saving = false;
    krtNumberInput.enhance(input);

    /** @param {string} desired the selected occurrence, including a refused draft */
    const refreshCommitments = (desired) => {
        refreshAttributionChoices(commitment, desired);
        commitmentField.hidden = commitment.options.length <= 1;
    };

    const currentBasis = () =>
        JSON.stringify([
            holders.selection(),
            commitment.value,
            commitment.selectedOptions.item(0)?.dataset.version,
        ]);

    const preview = bindProvisionPreview(form, `/orders/${root.dataset.orderId}/money`, () => {
        const amount = input.value;
        const selection = holders.selection();
        const attribution = readAttributionChoice(commitment);
        return amount && bankFree.checked && selection && attribution
            ? { ...selection, ...attribution, amount, bankHandling: false }
            : null;
    });

    const updateDraft = () => {
        basis = currentBasis();
        if (!saving) preview.refresh();
    };

    /**
     * Opens a new draft or restores the refused terms for another explicit choice.
     *
     * @param {Omit<ApiDto<'ProvideJobOrderMoneyDto'>, 'operationId'> | null} [refused] the resolved stale submission
     */
    const open = (refused = null) => {
        form.reset();
        holders.reset();
        refreshCommitments(refused?.commitmentId || '');
        preview.reset();
        changed.hidden = refused === null;
        amountError.hidden = true;
        input.removeAttribute('aria-invalid');
        if (refused?.amount) {
            holders.restore(refused);
            input.value = refused.amount;
        }
        updateDraft();
        krtModal.open(modalId);
    };

    const openPending = () => {
        open(refusedDraft);
        refusedDraft = null;
    };

    /** @param {Omit<ApiDto<'ProvideJobOrderMoneyDto'>, 'operationId'>} refused the closed operation */
    const restore = (refused) => {
        refusedDraft = refused;
        holders.refresh();
        if (holders.canProvide()) {
            openPending();
        } else {
            krtModal.close(modalId);
            form.reset();
            preview.reset();
        }
    };
    krtEvents.on('click', 'od-money-open', () => void recovery.openWhenRecovered(openPending));
    form.addEventListener('input', updateDraft);
    form.addEventListener('change', updateDraft);
    input.addEventListener('blur', () => {
        const invalid = input.validity.customError;
        amountError.hidden = !invalid;
        if (invalid) input.setAttribute('aria-invalid', 'true');
        else input.removeAttribute('aria-invalid');
    });
    input.addEventListener('input', () => {
        if (!input.validity.customError) {
            amountError.hidden = true;
            input.removeAttribute('aria-invalid');
        }
    });

    document.addEventListener('krt:swapped', (event) => {
        const section = event.detail.container?.id;
        if (!krtModal.isOpen(modalId)) return;
        if (section === 'order-money-results') {
            holders.refresh();
        } else if (section === 'order-participations-results') {
            refreshCommitments(commitment.value);
        } else {
            return;
        }
        const next = currentBasis();
        if (next !== basis && (input.value || bankFree.checked)) {
            changed.hidden = false;
            preview.reset();
        }
        updateDraft();
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const terms = preview.ready();
        if (!terms || saving) return;
        saving = true;
        fields.disabled = true;
        try {
            const submitted = await recovery.submit(
                { command: 'provisions', terms },
                event.submitter,
            );
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
    return { restore };
}
