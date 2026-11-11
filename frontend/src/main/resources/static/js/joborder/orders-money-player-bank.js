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

import { bindBankAccounts } from '/js/joborder/orders-money-bank.js';
import {
    bankRecipients,
    orderHolders,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';
import { showMovementField } from '/js/joborder/orders-money-movement-parties.js';

/** @import { MovementDraft } from '/js/joborder/orders-money-recovery.js' */
/** @typedef {ApiDto<'JobOrderBankSelection'>} BankSelection */

/**
 * Binds Bank effects that involve personal Order holdings and actual custody.
 *
 * @param {HTMLFormElement} form the movement editor
 * @param {HTMLSelectElement} kind the selected movement purpose
 * @param {string} url the order's money route
 * @param {() => void} onChange the draft refresh
 */
export function bindPlayerBankMovement(form, kind, url, onChange) {
    const effect = form.elements.namedItem('bankEffect');
    const recipient = form.elements.namedItem('recipientId');
    const account = form.elements.namedItem('bankAccountId');
    const custodian = form.elements.namedItem('bankCustodian');
    const target = form.elements.namedItem('bankTargetCustodian');
    const payment = form.elements.namedItem('bankPayment');
    const justification = form.elements.namedItem('bankJustification');
    if (
        !(effect instanceof HTMLSelectElement) ||
        !(recipient instanceof HTMLSelectElement) ||
        !(account instanceof HTMLSelectElement) ||
        !(custodian instanceof HTMLSelectElement) ||
        !(target instanceof HTMLSelectElement) ||
        !(payment instanceof HTMLInputElement) ||
        !(justification instanceof HTMLInputElement)
    ) {
        throw new Error('The player Bank movement controls are missing');
    }
    const collecting = () => kind.value === 'COLLECTION';
    const samePlayer = () => kind.value === 'PURPOSE_HANDOVER';
    const deliversToAccount = () =>
        kind.value === 'HANDOVER' && bankRecipients().some(({ id }) => id === recipient.value);
    const type = () =>
        collecting()
            ? effect.value === 'HOLDER_TRANSFER'
                ? 'HOLDER_TRANSFER'
                : 'DEPOSIT'
            : ['REQUESTER_RETURN', 'SETTLEMENT'].includes(kind.value)
              ? 'DEPOSIT'
              : deliversToAccount()
                ? 'TRANSFER'
                : 'WITHDRAWAL';
    const holderTransfer = () => type() === 'HOLDER_TRANSFER';
    const withdrawal = () => type() === 'WITHDRAWAL';
    const debit = () => withdrawal() || deliversToAccount();
    const needsReceivingCustodian = () => holderTransfer() || deliversToAccount();
    const purpose = () =>
        deliversToAccount()
            ? 'transfer'
            : withdrawal()
              ? 'withdrawal'
              : collecting()
                ? 'collection'
                : 'settlementDeposit';
    const accounts = bindBankAccounts(url, account, purpose, onChange);
    let previousPurpose = purpose();

    /**
     * Selects only custodians matching the already chosen Order participants.
     *
     * @param {MovementDraft | null} draft the proposed player movement
     * @param {boolean} enabled whether the personal Bank fields apply
     */
    const sync = (draft, enabled) => {
        showMovementField(effect, enabled && collecting());
        showMovementField(account, enabled && !holderTransfer());
        showMovementField(custodian, enabled);
        const nextPurpose = purpose();
        if (nextPurpose !== previousPurpose) {
            previousPurpose = nextPurpose;
            accounts.sync();
        }
        showMovementField(target, enabled && needsReceivingCustodian());
        showMovementField(payment, enabled && !samePlayer());
        showMovementField(justification, enabled && debit());
        if (!enabled || !draft || draft.command === 'movements/reassignment') return;
        const terms = draft.terms;
        const holders = orderHolders();
        const sourceUser = holders.find(({ id }) => id === terms.sourceHolderId)?.userId;
        const destinationUser =
            terms.destinationUserId ||
            holders.find(({ id }) => id === terms.destinationHolderId)?.userId;
        const choices = accounts.custodians();
        /** @param {string | null | undefined} userId the already selected participant */
        const matching = (userId) =>
            choices.flatMap(({ id, name, userId: owner }) =>
                userId && owner === userId && id && name ? [{ id, label: name }] : [],
            );
        setMoneyOptions(custodian, matching(type() === 'DEPOSIT' ? destinationUser : sourceUser));
        const receivers = deliversToAccount()
            ? choices.flatMap(({ id, name }) => (id && name ? [{ id, label: name }] : []))
            : matching(destinationUser);
        setMoneyOptions(target, receivers);
    };

    /** @returns {BankSelection | null} complete Bank terms for this purpose */
    const selection = () => {
        const accountId = accounts.selection()?.id;
        if (
            !custodian.value ||
            (!samePlayer() && !payment.checked) ||
            (needsReceivingCustodian() && !target.value)
        )
            return null;
        /** @type {BankSelection} */
        const common = {
            type: type(),
            custodianId: custodian.value,
            paymentReported: !samePlayer(),
        };
        if (holderTransfer()) {
            return { ...common, targetCustodianId: target.value };
        }
        if (!accountId) return null;
        return {
            ...common,
            accountId,
            ...(deliversToAccount() ? { targetCustodianId: target.value } : {}),
            ...(justification.value.trim() ? { justification: justification.value.trim() } : {}),
        };
    };

    return { refresh: accounts.load, sync, selection };
}
