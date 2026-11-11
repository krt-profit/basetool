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
import { krtSearchableSelect } from '/js/kernel/krt-searchable-select.js';

/** @typedef {ApiDto<'JobOrderMoneyHolderCandidate'>} HolderCandidate */
/** @typedef {Omit<ApiDto<'ProvideJobOrderMoneyDto'>, 'operationId'>} ProvisionTerms */
/** @typedef {Pick<ApiDto<'PreviewJobOrderMoneyProvisionDto'>, 'holderId' | 'holderUserId' | 'holderName' | 'holderVersion'>} HolderSelection */

const accountlessValue = 'accountless';

/**
 * Offers explicit account and accountless choices through a scoped search.
 *
 * @param {HTMLSelectElement} select the combobox source
 * @param {(query: string) => Promise<HolderCandidate[]>} findAccounts the permitted search
 * @param {() => boolean} allowName whether this action permits an accountless name
 */
export function bindMoneyPartyChoice(select, findAccounts, allowName = () => true) {
    const group = select.closest('.form-group');
    const label = select.dataset.freeTextLabel || '';
    krtSearchableSelect(select, {
        explicitChoice: true,
        remoteSource: async (query) => {
            const name = query.trim();
            const accounts = await findAccounts(name);
            const choices = accounts.flatMap(({ id, displayName }) =>
                id && displayName ? [{ value: id, label: displayName }] : [],
            );
            return name && allowName()
                ? [
                      {
                          value: accountlessValue,
                          label: label.replace('{0}', () => name),
                          data: { holderName: name },
                      },
                      ...choices,
                  ]
                : choices;
        },
    });
    const value = group?.querySelector('input[type="hidden"]');
    const search = group?.querySelector('input[role="combobox"]');
    if (!(value instanceof HTMLInputElement) || !(search instanceof HTMLInputElement)) {
        throw new Error('The order party search was not initialized');
    }
    search.maxLength = Number(group?.getAttribute('data-name-limit'));
    return {
        /** @param {boolean} enabled whether the current action permits this choice */
        setEnabled(enabled) {
            search.disabled = !enabled;
            value.disabled = !enabled;
            group?.toggleAttribute('hidden', !enabled);
        },
        clear() {
            value.krtCombobox?.setValue('');
        },
        /** @param {string} name a deliberately entered name without an account */
        restoreName(name) {
            value.krtCombobox?.setValue(
                accountlessValue,
                label.replace('{0}', () => name),
                {
                    holderName: name,
                },
            );
        },
        /** @param {string} id the selected linked account */
        restoreAccount(id) {
            value.krtCombobox?.setValue(id, orderHolders().find((row) => row.userId === id)?.label);
        },
        /** @returns {{userId: string} | {name: string} | null} the explicit selection */
        selection() {
            if (value.value === accountlessValue) {
                return value.dataset.holderName ? { name: value.dataset.holderName } : null;
            }
            return value.value ? { userId: value.value } : null;
        },
    };
}

/** @returns {HTMLElement | null} the current live money projection */
export function orderMoneyProjection() {
    return document.querySelector('#order-money-results [data-can-provide-self]');
}

/** @returns {{id: string, userId: string, label: string, version: number}[]} order-local holders */
export function orderHolders() {
    const rows = orderMoneyProjection()?.querySelectorAll('[data-money-holder]') || [];
    return Array.from(rows, (row) => ({
        id: row.getAttribute('data-money-holder') || '',
        userId: row.getAttribute('data-user-id') || '',
        label: row.getAttribute('data-holder-label') || '',
        version: Number(row.getAttribute('data-version') || 0),
    }));
}

/**
 * Retains an existing selection while refreshing its available rows.
 *
 * @param {HTMLSelectElement} select the selector with its initial blank option
 * @param {{id: string, label: string}[]} rows the authorized rows
 */
export function setMoneyOptions(select, rows) {
    const selected = select.value;
    select.replaceChildren(
        select.options[0],
        ...rows.map(({ id, label }) => new Option(label, id)),
    );
    select.value = rows.some(({ id }) => id === selected) ? selected : '';
}

/**
 * Keeps self, existing holders and explicitly chosen new parties in one provision dialog.
 *
 * @param {HTMLFormElement} form the provision form
 * @param {HTMLElement} root the authenticated account and order scope
 */
export function bindHolderChoices(form, root) {
    const mode = form.elements.namedItem('holderMode');
    const existing = form.elements.namedItem('holderId');
    const newParty = form.elements.namedItem('holderChoice');
    if (
        !(mode instanceof HTMLSelectElement) ||
        !(existing instanceof HTMLSelectElement) ||
        !(newParty instanceof HTMLSelectElement)
    ) {
        throw new Error('The order holder choice controls are missing');
    }

    const moneyUrl = `/orders/${root.dataset.orderId}/money`;
    const party = bindMoneyPartyChoice(newParty, (query) =>
        krtFetch.getJson(`${moneyUrl}/holder-candidates?q=${encodeURIComponent(query)}`),
    );

    /** @type {ReturnType<typeof orderHolders>} */
    let holders = [];
    let canSelf = false;
    let canOnBehalf = false;

    const sync = () => {
        const onlySelf = !canOnBehalf && mode.value === 'self';
        mode.disabled = onlySelf;
        mode.closest('[data-money-mode-field]')?.toggleAttribute('hidden', onlySelf);
        for (const option of mode.options) {
            if (option.value === 'existing') option.disabled = !canOnBehalf || holders.length === 0;
            else if (option.value === 'new') option.disabled = !canOnBehalf;
        }
        existing.disabled = !canOnBehalf || mode.value !== 'existing';
        existing
            .closest('[data-money-existing-field]')
            ?.toggleAttribute('hidden', mode.value !== 'existing');
        const searching = mode.value === 'new';
        party.setEnabled(canOnBehalf && searching);
    };

    /** Refreshes the available parties without dropping an applicable selected holder. */
    const refresh = () => {
        const projection = orderMoneyProjection();
        holders = orderHolders();
        canSelf = projection?.dataset.canProvideSelf === 'true';
        canOnBehalf = projection?.dataset.canProvideOnBehalf === 'true';
        setMoneyOptions(existing, holders);
        sync();
    };

    /** @returns {HolderSelection | null} the authorized, explicit holder terms */
    const selection = () => {
        if (mode.value === 'self') {
            const self = holders.find(({ userId }) => userId === root.dataset.accountId);
            return canSelf ? { holderVersion: self?.version ?? 0 } : null;
        }
        if (!canOnBehalf) return null;
        if (mode.value === 'existing') {
            const holder = holders.find(({ id }) => id === existing.value);
            return holder ? { holderId: holder.id, holderVersion: holder.version } : null;
        }
        const selection = mode.value === 'new' ? party.selection() : null;
        if (!selection) return null;
        if ('name' in selection) return { holderName: selection.name, holderVersion: 0 };
        const holder = holders.find(({ userId }) => userId === selection.userId);
        return { holderUserId: selection.userId, holderVersion: holder?.version ?? 0 };
    };

    mode.addEventListener('change', sync);
    return {
        refresh,
        selection,
        canProvide() {
            return canSelf || canOnBehalf;
        },
        reset() {
            party.clear();
            refresh();
        },
        /**
         * Reopens the selected party using its current authorized label and revision.
         *
         * @param {ProvisionTerms} terms the permanently refused submission
         */
        restore(terms) {
            if (terms.holderId) {
                mode.value = 'existing';
                existing.value = terms.holderId;
            } else if (terms.holderUserId) {
                mode.value = 'new';
                party.restoreAccount(terms.holderUserId);
            } else if (terms.holderName) {
                mode.value = 'new';
                party.restoreName(terms.holderName);
            }
            sync();
        },
    };
}
