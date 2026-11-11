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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import {
    bindMoneyPartyChoice,
    orderMoneyProjection,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';
import { showMovementField } from '/js/joborder/orders-money-movement-parties.js';

/** @typedef {ApiDto<'JobOrderMoneyBankAccounts'>} BankAccounts */
/** @typedef {NonNullable<BankAccounts['collection']>[number]} BankAccount */
/** @typedef {Pick<ApiDto<'PreviewJobOrderMoneyProvisionDto'>, 'bankHandling' | 'bank' | 'holderVersion'>} BankProvision */

const emptyAccounts = {
    collection: [],
    designation: [],
    withdrawal: [],
    transfer: [],
    settlementDeposit: [],
    custodians: [],
};

/**
 * Tracks the deliberate Bank choice of one editable operation.
 *
 * @param {HTMLFormElement} form the money form
 */
export function bindBankMode(form) {
    const bank = form.querySelector('[data-money-use-bank]');
    const controls = bank?.closest('[data-money-bank-mode]');
    if (!(bank instanceof HTMLInputElement) || !(controls instanceof HTMLElement)) {
        throw new Error('The Order Bank choice is missing');
    }
    return {
        enabled: () => bank.checked,
        /** @param {boolean} enabled the original Bank choice */
        restore(enabled) {
            bank.checked = enabled;
            const free = form.querySelector('[data-money-bank-free]');
            if (free instanceof HTMLInputElement) free.checked = !enabled;
        },
        /** @param {boolean} applicable whether the action moves actual money */
        show(applicable) {
            controls.hidden = !applicable;
            for (const input of controls.querySelectorAll('input')) input.disabled = !applicable;
        },
    };
}

/**
 * Loads current selectable Bank references without retaining a former grant.
 *
 * @param {string} url the Order money route
 * @param {HTMLSelectElement} select the account control
 * @param {() => Exclude<keyof BankAccounts, 'custodians'>} purpose the current purpose
 * @param {() => void} onChange the editor's draft refresh
 */
export function bindBankAccounts(url, select, purpose, onChange) {
    const group = select.closest('.form-group');
    const problem = group?.querySelector('[data-money-bank-account-error]');
    /** @type {BankAccounts} */
    let accounts = emptyAccounts;
    let generation = 0;
    let desired = '';
    let suggest = false;

    const sync = () => {
        const current = select.value;
        const rows = accounts[purpose()] || [];
        setMoneyOptions(
            select,
            rows.flatMap(({ id, name }) => (id && name ? [{ id, label: name }] : [])),
        );
        if (desired && rows.some(({ id }) => id === desired)) select.value = desired;
        else if (suggest && !current && rows[0]?.id) select.value = rows[0].id;
    };

    /**
     * Refreshes authorized references before allowing a selection.
     *
     * @param {string} [restored] the submitted account selected in the refused draft
     * @param {boolean} [initial] whether to suggest the responsible unit's account
     */
    const load = async (restored = select.value || desired, initial = false) => {
        const request = ++generation;
        desired = restored;
        suggest = initial;
        accounts = emptyAccounts;
        sync();
        problem?.setAttribute('hidden', '');
        onChange();
        try {
            /** @type {BankAccounts} */
            const result = await krtFetch.getJson(`${url}/bank-accounts`);
            if (request !== generation) return;
            accounts = result || emptyAccounts;
        } catch (error) {
            if (request !== generation) return;
            if (problem instanceof HTMLElement) {
                const forbidden =
                    error instanceof Error && 'status' in error && error.status === 403;
                problem.textContent = forbidden
                    ? problem.dataset.denied || ''
                    : problem.dataset.unavailable || '';
                problem.hidden = false;
            }
        }
        sync();
        suggest = false;
        onChange();
    };

    select.addEventListener('change', () => {
        desired = select.value;
    });

    return {
        load,
        sync,
        custodians: () => accounts.custodians || [],
        /** @returns {BankAccount | undefined} the selected eligible account */
        selection() {
            return (accounts[purpose()] || []).find(({ id }) => id === select.value);
        },
    };
}

/**
 * Binds the account provision choices and the separate actual player withdrawal.
 *
 * @param {HTMLFormElement} form the provision editor
 * @param {HTMLElement} root the scoped order and caller
 * @param {() => void} onChange the preview refresh
 */
export function bindBankProvision(form, root, onChange) {
    const purpose = form.elements.namedItem('bankPurpose');
    const account = form.elements.namedItem('bankAccountId');
    const depositor = form.elements.namedItem('bankDepositor');
    const payerMode = form.elements.namedItem('bankPayer');
    const custodian = form.elements.namedItem('bankCustodian');
    const payment = form.elements.namedItem('bankPayment');
    const justification = form.elements.namedItem('bankJustification');
    if (
        !(purpose instanceof HTMLSelectElement) ||
        !(account instanceof HTMLSelectElement) ||
        !(depositor instanceof HTMLSelectElement) ||
        !(payerMode instanceof HTMLSelectElement) ||
        !(custodian instanceof HTMLSelectElement) ||
        !(payment instanceof HTMLInputElement) ||
        !(justification instanceof HTMLTextAreaElement)
    ) {
        throw new Error('The Bank provision fields are missing');
    }
    const url = `/orders/${root.dataset.orderId}/money`;
    const mode = bindBankMode(form);
    const withdrawal = () => purpose.value === 'WITHDRAWAL';
    const deposit = () => purpose.value === 'DEPOSIT';
    const accounts = bindBankAccounts(
        url,
        account,
        () => (deposit() ? 'collection' : 'withdrawal'),
        onChange,
    );
    const payer = bindMoneyPartyChoice(depositor, (query) =>
        krtFetch.getJson(`${url}/holder-candidates?q=${encodeURIComponent(query)}`),
    );

    let desiredCustodian = '';
    const canNamePayer = () => orderMoneyProjection()?.dataset.canProvideOnBehalf === 'true';
    const sync = () => {
        const enabled = mode.enabled();
        setMoneyOptions(
            custodian,
            accounts
                .custodians()
                .flatMap(({ id, name }) => (id && name ? [{ id, label: name }] : [])),
        );
        if (
            desiredCustodian &&
            Array.from(custodian.options).some(({ value }) => value === desiredCustodian)
        ) {
            custodian.value = desiredCustodian;
        }
        showMovementField(custodian, enabled && withdrawal());
        form.querySelector('[data-money-bank-free-note]')?.toggleAttribute('hidden', enabled);
        showMovementField(purpose, enabled);
        showMovementField(account, enabled);
        showMovementField(payment, enabled && deposit());
        justification.disabled = !(enabled && !deposit());
        justification.closest('.form-group')?.toggleAttribute('hidden', justification.disabled);
        showMovementField(payerMode, enabled && deposit() && canNamePayer());
        payer.setEnabled(enabled && deposit() && canNamePayer() && payerMode.value === 'other');
    };
    purpose.addEventListener('change', () => accounts.sync());
    custodian.addEventListener('input', () => {
        desiredCustodian = custodian.value;
    });

    return {
        sync,
        refresh: accounts.load,
        /** @param {ApiDto<'JobOrderBankSelection'> | null | undefined} bank the submitted Bank terms */
        restore(bank) {
            payer.clear();
            desiredCustodian = bank?.custodianId || '';
            mode.restore(!!bank);
            payerMode.value =
                bank?.counterpartyName ||
                (bank?.counterpartyUserId && bank.counterpartyUserId !== root.dataset.accountId)
                    ? 'other'
                    : 'self';
            if (bank?.type) purpose.value = bank.type;
            if (bank?.counterpartyName) payer.restoreName(bank.counterpartyName);
            if (bank?.counterpartyUserId) payer.restoreAccount(bank.counterpartyUserId);
            payment.checked = bank?.paymentReported === true;
            justification.value = bank?.justification || '';
        },
        enabled: mode.enabled,
        needsHolder: () => !mode.enabled() || withdrawal(),
        /** @returns {BankProvision | null} the complete Bank provision selection */
        selection() {
            const selected = accounts.selection();
            if (!selected?.id) return null;
            /** @type {ApiDto<'JobOrderBankSelection'>} */
            const common = {
                accountId: selected.id,
                type: withdrawal() ? 'WITHDRAWAL' : deposit() ? 'DEPOSIT' : 'AUTHORISATION',
            };
            if (withdrawal()) {
                return custodian.value
                    ? {
                          bankHandling: true,
                          bank: {
                              ...common,
                              ...(justification.value.trim()
                                  ? { justification: justification.value.trim() }
                                  : {}),
                              custodianId: custodian.value,
                              paymentReported: true,
                          },
                      }
                    : null;
            }
            if (payerMode.value === 'other' && !canNamePayer()) return null;
            const party =
                payerMode.value === 'self' && root.dataset.accountId
                    ? { userId: root.dataset.accountId }
                    : payer.selection();
            if (deposit() && (!party || !payment.checked)) return null;
            const actualParty =
                party && deposit()
                    ? 'name' in party
                        ? { counterpartyName: party.name }
                        : { counterpartyUserId: party.userId }
                    : {};
            return {
                holderVersion: selected.version,
                bankHandling: true,
                bank: {
                    ...common,
                    ...actualParty,
                    accountOnly: true,
                    ...(deposit() ? { paymentReported: true } : {}),
                    ...(!deposit() && justification.value.trim()
                        ? { justification: justification.value.trim() }
                        : {}),
                },
            };
        },
    };
}
