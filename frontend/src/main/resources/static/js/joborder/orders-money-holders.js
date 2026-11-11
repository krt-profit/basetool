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
/** @typedef {Pick<ApiDto<'PreviewJobOrderMoneyProvisionDto'>, 'holderId' | 'holderUserId' | 'holderName' | 'holderVersion'>} HolderSelection */

const accountlessValue = 'accountless';

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
    const freeTextLabel = newParty.dataset.freeTextLabel || '';
    krtSearchableSelect(newParty, {
        explicitChoice: true,
        remoteSource: async (query) => {
            const name = query.trim();
            /** @type {HolderCandidate[]} */
            const accounts = await krtFetch.getJson(
                `${moneyUrl}/holder-candidates?q=${encodeURIComponent(name)}`,
            );
            const choices = accounts.flatMap(({ id, displayName }) =>
                id && displayName ? [{ value: id, label: displayName }] : [],
            );
            return name
                ? [
                      {
                          value: accountlessValue,
                          label: freeTextLabel.replace('{0}', () => name),
                          data: { holderName: name },
                      },
                      ...choices,
                  ]
                : choices;
        },
    });

    const choice = form.elements.namedItem('holderChoice');
    const search = form.querySelector('[data-money-new-field] input[role="combobox"]');
    if (!(choice instanceof HTMLInputElement) || !(search instanceof HTMLInputElement)) {
        throw new Error('The order holder search was not initialized');
    }
    search.maxLength = Number(
        search.closest('[data-money-new-field]')?.getAttribute('data-name-limit'),
    );

    /** @type {Element[]} */
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
        search.disabled = !canOnBehalf || !searching;
        choice.disabled = search.disabled;
        search.closest('[data-money-new-field]')?.toggleAttribute('hidden', !searching);
    };

    /** Refreshes the available parties without dropping an applicable selected holder. */
    const refresh = () => {
        const projection = document.querySelector('[data-can-provide-self]');
        holders = Array.from(projection?.querySelectorAll('[data-money-holder]') || []);
        canSelf = projection?.getAttribute('data-can-provide-self') === 'true';
        canOnBehalf = projection?.getAttribute('data-can-provide-on-behalf') === 'true';

        const selected = existing.value;
        existing.replaceChildren(
            existing.options[0],
            ...holders.map(
                (holder) =>
                    new Option(
                        holder.getAttribute('data-holder-label') || '',
                        holder.getAttribute('data-money-holder') || '',
                    ),
            ),
        );
        if (Array.from(existing.options).some((option) => option.value === selected)) {
            existing.value = selected;
        }
        sync();
    };

    /**
     * Uses zero for a new holder and the rendered revision of any established holder.
     *
     * @param {Element | undefined} holder the selected order-local row
     * @returns {number} the holder revision
     */
    function versionOf(holder) {
        return Number(holder?.getAttribute('data-version') || 0);
    }

    /** @returns {HolderSelection | null} the authorized, explicit holder terms */
    const selection = () => {
        if (mode.value === 'self') {
            const self = holders.find(
                (holder) => holder.getAttribute('data-user-id') === root.dataset.accountId,
            );
            return canSelf ? { holderVersion: versionOf(self) } : null;
        }
        if (!canOnBehalf) return null;
        if (mode.value === 'existing') {
            const holder = holders.find(
                (row) => row.getAttribute('data-money-holder') === existing.value,
            );
            return holder ? { holderId: existing.value, holderVersion: versionOf(holder) } : null;
        }
        if (mode.value !== 'new' || !choice.value) return null;
        if (choice.value === accountlessValue) {
            return choice.dataset.holderName
                ? { holderName: choice.dataset.holderName, holderVersion: 0 }
                : null;
        }
        const holder = holders.find((row) => row.getAttribute('data-user-id') === choice.value);
        return { holderUserId: choice.value, holderVersion: versionOf(holder) };
    };

    mode.addEventListener('change', sync);
    return {
        refresh,
        selection,
        reset() {
            choice.krtCombobox?.setValue('');
            refresh();
        },
    };
}
