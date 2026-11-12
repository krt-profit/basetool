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

import {
    bankRecipients,
    orderHolders,
    orderMoneyProjection,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';
import { showMovementField } from '/js/joborder/orders-money-movement-parties.js';

/** @import { MovementDraft } from '/js/joborder/orders-money-recovery.js' */

const adjust = new Set(['SHORTAGE', 'RELEASE']);

function accountHoldings() {
    const rows = orderMoneyProjection()?.querySelectorAll('[data-money-account-holding]') || [];
    return Array.from(rows, (row) => ({
        id: row.getAttribute('data-money-account-holding') || '',
        label: row.getAttribute('data-holding-label') || '',
        version: Number(row.getAttribute('data-version') || 0),
        available: row.getAttribute('data-available') || '0',
    }));
}

function accountReturns() {
    const rows = orderMoneyProjection()?.querySelectorAll('[data-money-account-return]') || [];
    return Array.from(rows, (row) => ({
        account: row.getAttribute('data-money-account-return') || '',
        recipient: row.getAttribute('data-recipient-id') || '',
        available: row.getAttribute('data-available') || '0',
    }));
}

/**
 * Selects the Order's own positions and designations without exposing Bank account identifiers.
 *
 * @param {HTMLFormElement} form the money movement editor
 * @param {HTMLSelectElement} kind the selected movement purpose
 * @param {() => boolean} usesBank the operation's Bank choice
 * @param {() => boolean} canReturnToPlayer whether personal requester return is possible
 */
export function bindAccountMovement(form, kind, usesBank, canReturnToPlayer) {
    const account = form.elements.namedItem('accountHoldingId');
    const recipient = form.elements.namedItem('recipientId');
    const sourceType = form.elements.namedItem('sourceType');
    const justification = form.elements.namedItem('bankJustification');
    if (
        !(account instanceof HTMLSelectElement) ||
        !(recipient instanceof HTMLSelectElement) ||
        !(sourceType instanceof HTMLSelectElement) ||
        !(justification instanceof HTMLInputElement)
    ) {
        throw new Error('The Order account movement controls are missing');
    }
    const returning = () => kind.value === 'REQUESTER_RETURN';
    const adjusting = () => adjust.has(kind.value);
    const canAdjust = () => {
        const data = orderMoneyProjection()?.dataset;
        return (
            data?.canRecordSettlement === 'true' &&
            data.canMaintainHolders === 'true' &&
            accountHoldings().some(({ available }) => available !== '0')
        );
    };
    const canReturn = () => accountReturns().some(({ available }) => available !== '0');
    const canHandOver = () =>
        accountHoldings().some(({ available }) => available !== '0') &&
        bankRecipients().some(({ active }) => active);
    const handover = () => kind.value === 'PURPOSE_HANDOVER';
    const relevant = () => adjusting() || (usesBank() && (returning() || handover()));
    const active = () => relevant() && sourceType.value === 'account';
    let currentPurpose = '';

    /** @param {boolean} [fresh] whether a newly opened action resets its account choice */
    const refreshPurpose = (fresh = false) => {
        if (!fresh && kind.value === currentPurpose) return;
        currentPurpose = kind.value;
        const preferAccount = adjusting()
            ? canAdjust() && orderHolders().length === 0
            : returning()
              ? canReturn()
              : handover() && canHandOver();
        sourceType.value = preferAccount ? 'account' : 'player';
    };

    /** @param {boolean} [suggest] whether to select the sole eligible option on initial open */
    const sync = (suggest = false) => {
        const accountAvailable = adjusting()
            ? canAdjust()
            : returning()
              ? canReturn()
              : canHandOver();
        const playerAvailable = returning() ? canReturnToPlayer() : orderHolders().length > 0;
        showMovementField(
            sourceType,
            relevant() && playerAvailable && (accountAvailable || active()),
        );
        const enabled = active();
        showMovementField(account, enabled);
        if (!enabled) return;
        showMovementField(justification, !adjusting());
        const returns = accountReturns();
        const eligible = accountHoldings().filter(({ id, available }) =>
            returning()
                ? returns.some(({ account, available }) => account === id && available !== '0')
                : available !== '0',
        );
        setMoneyOptions(account, eligible);
        if (suggest && !account.value && eligible.length === 1) account.value = eligible[0].id;
        showMovementField(recipient, !adjusting());
        if (!adjusting()) {
            const options = bankRecipients().filter(({ id, active }) =>
                returning()
                    ? returns.some(
                          (entry) =>
                              entry.account === account.value &&
                              entry.recipient === id &&
                              entry.available !== '0',
                      )
                    : active,
            );
            setMoneyOptions(recipient, options);
            if (suggest && !recipient.value && options.length === 1)
                recipient.value = options[0].id;
        }
    };

    /**
     * Reads the scoped position and current target versions for a proposed account effect.
     *
     * @param {string} amount the whole-aUEC principal
     * @returns {MovementDraft | null} the proposed account effect
     */
    const selection = (amount) => {
        const holding = accountHoldings().find(({ id }) => id === account.value);
        if (!holding || !amount) return null;
        const source = { sourceAccountHoldingId: holding.id, sourceVersion: holding.version };
        if (adjusting()) {
            if (!canAdjust() || holding.available === '0') return null;
            return {
                command: 'movements/settlement',
                terms: {
                    ...source,
                    amount,
                    kind: kind.value === 'SHORTAGE' ? 'SHORTAGE' : 'RELEASE',
                    bankHandling: false,
                },
            };
        }
        if (!recipient.value) return null;
        /** @type {Pick<ApiDto<'MoveJobOrderMoneyDto'>, 'amount' | 'recipientId' | 'targetVersion' | 'bankHandling' | 'bank'>} */
        const terms = {
            amount,
            recipientId: recipient.value,
            targetVersion: Number(orderMoneyProjection()?.dataset.targetVersion || 0),
            bankHandling: true,
            bank: {
                type: 'TRANSFER',
                accountOnly: true,
                paymentReported: false,
                ...(justification.value.trim()
                    ? { justification: justification.value.trim() }
                    : {}),
            },
        };
        if (returning()) {
            return {
                command: 'movements/settlement',
                terms: {
                    ...terms,
                    kind: 'REQUESTER_RETURN',
                    destinationAccountHoldingId: holding.id,
                    destinationVersion: holding.version,
                },
            };
        }
        return { command: 'movements', terms: { ...terms, ...source, kind: 'PURPOSE_HANDOVER' } };
    };

    /** @param {MovementDraft} draft the refused account effect */
    const restore = (draft) => {
        if (draft.command === 'movements/reassignment') return false;
        const id =
            draft.terms.sourceAccountHoldingId ||
            (draft.command === 'movements/settlement'
                ? draft.terms.destinationAccountHoldingId
                : '');
        if (!id) return false;
        sourceType.value = 'account';
        justification.value = draft.terms.bank?.justification || '';
        sync();
        account.value = id;
        sync();
        recipient.value = draft.terms.recipientId || '';
        return true;
    };

    const labels = () => {
        const position = account.selectedOptions.item(0)?.label;
        const designation = recipient.selectedOptions.item(0)?.label;
        return returning()
            ? { sourceName: designation, destinationName: position }
            : { sourceName: position, destinationName: designation };
    };

    return {
        active,
        adjusting,
        canAdjust,
        canHandOver,
        canReturn,
        refreshPurpose,
        sync,
        selection,
        restore,
        labels,
    };
}
