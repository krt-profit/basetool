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
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtNumberInput } from '/js/kernel/krt-number-input.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { bindBankMode } from '/js/joborder/orders-money-bank.js';
import { setMoneyOptions } from '/js/joborder/orders-money-holders.js';
import { showMovementField } from '/js/joborder/orders-money-movement-parties.js';
import {
    bindMoneyPreview,
    showBankPreview,
    showPreviewAmounts,
} from '/js/joborder/orders-money-preview.js';

/** @import { bindMoneyRecovery } from '/js/joborder/orders-money-recovery.js' */
/** @typedef {ApiDto<'JobOrderMoneyRepairView'>} Repair */
/** @typedef {ApiDto<'JobOrderMoneyRepairQuote'>} Quote */
/** @typedef {ApiDto<'ReplaceJobOrderBankAttemptDto'>} RepairTerms */

const modalId = 'order-money-repair-modal';

/**
 * Repairs a failed attempt with the same logical identity and frozen fee basis.
 *
 * @param {HTMLElement} root the order workspace
 * @param {ReturnType<typeof bindMoneyRecovery>} recovery the shared outcome recovery
 */
export function bindLinkedMoneyRepair(root, recovery) {
    const form = document.getElementById('order-money-repair-form');
    if (!(form instanceof HTMLFormElement)) return;
    const fields = form.querySelector('[data-repair-fields]');
    const amount = form.elements.namedItem('amount');
    const provision = form.elements.namedItem('provision');
    const account = form.elements.namedItem('bankAccountId');
    const reason = form.elements.namedItem('justification');
    const bankFreeLabel = form.querySelector('[data-money-bank-free]')?.closest('label');
    const stale = form.querySelector('[data-repair-stale]');
    const paid = form.querySelector('[data-repair-paid]');
    if (
        !(fields instanceof HTMLFieldSetElement) ||
        !(amount instanceof HTMLInputElement) ||
        !(provision instanceof HTMLInputElement) ||
        !(account instanceof HTMLSelectElement) ||
        !(reason instanceof HTMLTextAreaElement) ||
        !(bankFreeLabel instanceof HTMLElement) ||
        !(stale instanceof HTMLElement) ||
        !(paid instanceof HTMLElement)
    ) {
        throw new Error('The order Bank repair form is incomplete');
    }

    const bank = bindBankMode(form);
    const base = `/orders/${root.dataset.orderId}/money/operations/`;
    let operation = '';
    let loading = 0;
    let saving = false;
    /** @type {Repair | null} */
    let original = null;

    krtNumberInput.enhance(amount);
    krtNumberInput.enhance(provision);

    /** @returns {RepairTerms | null} the proposed replacement terms */
    const terms = () => {
        if (!original?.amount || fields.disabled || !amount.value || !amount.validity.valid)
            return null;
        const outsideBank = !bank.enabled();
        if (outsideBank && !original.outsideBankAllowed) return null;
        return {
            revision: original.revision,
            amount: outsideBank ? original.amount : amount.value,
            outsideBank,
            ...(outsideBank || account.disabled || !account.value
                ? {}
                : { accountId: account.value }),
            ...(outsideBank || reason.disabled || !reason.value.trim()
                ? {}
                : { justification: reason.value.trim() }),
            ...(provision.disabled || !provision.value ? {} : { provision: provision.value }),
        };
    };

    const preview = bindMoneyPreview(
        form,
        (draft) => ({ url: `${base}${operation}/bank-replacement/preview`, payload: draft }),
        terms,
        (output, body, draft) => {
            const quote = /** @type {Quote | null} */ (body);
            if (!quote || quote.revision !== draft.revision) return undefined;
            showPreviewAmounts(output, {
                amount: quote.amount,
                fee: quote.actualFee,
                sourceCharge: quote.orderSourceCharge,
            });
            const payer = output.querySelector('[data-repair-fee-payer]');
            if (payer instanceof HTMLElement) {
                const key = (quote.feeFunding || '').toLowerCase().replace('_', '-');
                payer.textContent = payer.getAttribute(`data-payer-${key}`) || '';
            }
            const rate = output.querySelector('[data-repair-rate]');
            if (rate) rate.textContent = quote.rate || '';
            showBankPreview(output, quote.bank);
            return draft;
        },
    );

    const refresh = () => {
        const selectedBank = bank.enabled();
        amount.readOnly = !selectedBank;
        showMovementField(
            provision,
            !!(
                selectedBank &&
                original &&
                original.kind !== 'PROVISION' &&
                original.provision !== '0'
            ),
        );
        showMovementField(
            account,
            !!(selectedBank && original?.accountChangeAllowed && original.accounts?.length),
        );
        showMovementField(
            reason,
            !!(
                selectedBank &&
                original &&
                original.type !== 'DEPOSIT' &&
                original.type !== 'HOLDER_TRANSFER'
            ),
        );
        if (!saving) preview.refresh();
    };

    /**
     * Refreshes current repair rights without replacing an open draft.
     *
     * @param {string} id the original logical operation
     * @param {boolean} preserve whether a draft is already open
     */
    const load = async (id, preserve) => {
        const generation = ++loading;
        const previous = original;
        const selection = account.value;
        if (!preserve) {
            preview.reset();
            fields.disabled = true;
        }
        try {
            /** @type {Repair} */
            const current = await krtFetch.getJson(`${base}${id}/bank-replacement`);
            if (generation !== loading) return;
            if (preserve) {
                if (JSON.stringify(previous) === JSON.stringify(current)) return;
                preview.reset();
                fields.disabled = true;
                if (
                    previous?.revision !== current.revision ||
                    (!bank.enabled() && !current.outsideBankAllowed)
                ) {
                    stale.hidden = false;
                    return;
                }
            }
            original = current;
            operation = id;
            if (!preserve) {
                form.reset();
                amount.value = current.amount || '';
                provision.value = current.provision || '';
            }
            setMoneyOptions(
                account,
                (current.accounts || []).flatMap(({ id, name }) =>
                    id && name ? [{ id, label: name }] : [],
                ),
            );
            const selected = preserve ? selection : current.accountId;
            if (
                selected &&
                Array.from(account.options).some((option) => option.value === selected)
            ) {
                account.value = selected;
            } else if (preserve && selection) {
                stale.hidden = false;
                return;
            }
            paid.hidden = !current.actualPayment;
            bankFreeLabel.hidden = !current.outsideBankAllowed;
            if (!current.outsideBankAllowed) bank.restore(true);
            stale.hidden = true;
            fields.disabled = false;
            refresh();
            if (!preserve) krtModal.open(modalId);
        } catch {
            if (generation !== loading) return;
            if (preserve) {
                preview.reset();
                fields.disabled = true;
                stale.hidden = false;
            } else {
                showFrontendErrorToast(root.dataset.provideError || '');
            }
        }
    };

    krtEvents.on('click', 'od-money-bank-repair', (button) => {
        const id = button.dataset.repairOperation;
        if (id) void recovery.openWhenRecovered(() => void load(id, false));
    });
    form.addEventListener('input', refresh);
    form.addEventListener('change', refresh);
    document.addEventListener('krt:swapped', (event) => {
        if (
            !saving &&
            operation &&
            krtModal.isOpen(modalId) &&
            event.detail.container?.id === 'order-money-results'
        ) {
            void load(operation, true);
        }
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const draft = preview.ready();
        if (!draft || !operation || saving) return;
        saving = true;
        fields.disabled = true;
        try {
            const accepted = await recovery.submit(
                { command: 'bank-replacement', existingOperationId: operation, terms: draft },
                event.submitter,
            );
            if (accepted) {
                krtModal.close(modalId);
                preview.reset();
            }
        } finally {
            saving = false;
            fields.disabled = false;
            if (krtModal.isOpen(modalId)) refresh();
        }
    });
}
