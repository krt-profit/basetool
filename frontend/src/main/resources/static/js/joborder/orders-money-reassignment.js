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
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtNumberInput } from '/js/kernel/krt-number-input.js';
import { setMoneyOptions } from '/js/joborder/orders-money-holders.js';
import { showMovementField } from '/js/joborder/orders-money-movement-parties.js';

/** @typedef {ReturnType<typeof import('/js/joborder/orders-money-holders.js').orderHolders>[number]} Holder */
/** @typedef {ApiDto<'JobOrderMoneyReassignmentDestination'>} Destination */
/** @typedef {Pick<ApiDto<'ReassignJobOrderMoneyDto'>, 'destinationOrderId' | 'destinationHolderId' | 'destinationVersion' | 'confirmSamePerson'>} DestinationTerms */

const newHolderChoice = 'new';

/**
 * Resolves one destination order number and its permitted same-person holder choices.
 *
 * @param {HTMLFormElement} form the existing movement form
 * @param {string} url the source order money route
 * @param {(live: boolean) => void} changed recomputes the draft when holder choices arrive
 */
export function bindReassignmentDestination(form, url, changed) {
    const number = form.elements.namedItem('destinationNumber');
    const holder = form.elements.namedItem('crossOrderHolder');
    const confirmation = form.elements.namedItem('confirmSamePerson');
    const unavailable = form.querySelector('[data-money-destination-unavailable]');
    if (
        !(number instanceof HTMLInputElement) ||
        !(holder instanceof HTMLSelectElement) ||
        !(confirmation instanceof HTMLInputElement) ||
        !(unavailable instanceof HTMLElement)
    ) {
        throw new Error('The purpose destination controls are missing');
    }
    krtNumberInput.enhance(number);
    let key = '';
    let timer = 0;
    let sequence = 0;
    let selectedOnRestore = '';
    let subscribedOrder = '';
    /** @type {{unsubscribe(): void} | null} */
    let subscription = null;
    /** @type {Destination | null} */
    let target = null;

    /**
     * Keeps the open form's destination basis current through the existing order room.
     *
     * @param {string} order the authorised destination, or empty when no destination is open
     * @param {() => void} refreshBasis rereads the destination's current versions
     */
    const subscribe = (order, refreshBasis) => {
        if (order === subscribedOrder) return;
        subscription?.unsubscribe();
        subscribedOrder = order;
        subscription = order
            ? krtLiveSync.subscribe(`order:${order}`, {
                  onSubscribed: refreshBasis,
                  onChanged(sections) {
                      if (sections.includes('money') || sections.includes('header')) refreshBasis();
                  },
                  onDenied: refreshBasis,
              })
            : null;
    };

    /** @param {boolean} accountless whether the sender requires a named counterpart */
    const showHolderChoices = (accountless) => {
        const hasExisting = !!target?.holders?.length;
        showMovementField(holder, accountless && hasExisting);
        showMovementField(confirmation, accountless && !!target && !!holder.value);
    };

    holder.addEventListener('change', () => {
        confirmation.checked = false;
    });

    /**
     * Updates the purpose fields while retaining a still-valid selection.
     *
     * @param {boolean} active whether reassignment is the selected purpose
     * @param {Holder | undefined} source the source holder
     * @param {boolean} [live] whether to refresh the current peer basis
     */
    const refresh = (active, source, live = false) => {
        const accountless = active && !!source && !source.userId;
        showMovementField(number, active);
        showHolderChoices(accountless);
        const displayNumber = Number(number.value);
        const next =
            active && source && /^[0-9]{1,9}$/.test(number.value) && displayNumber > 0
                ? `${source.id}:${displayNumber}`
                : '';
        if (next === key && (!live || !next)) return;
        const selection = next === key ? holder.value : selectedOnRestore;
        if (next !== key) {
            confirmation.checked = false;
            selectedOnRestore = '';
            subscribe('', () => {});
        }
        key = next;
        window.clearTimeout(timer);
        const request = ++sequence;
        unavailable.hidden = true;
        if (!next || !source || !live) {
            target = null;
            setMoneyOptions(holder, []);
            showMovementField(holder, false);
            showMovementField(confirmation, false);
        }
        if (!next || !source) return;
        const params = new URLSearchParams({
            sourceHolderId: source.id,
            number: String(displayNumber),
        });
        timer = window.setTimeout(async () => {
            try {
                /** @type {Destination | null} */
                const result = await krtFetch.getJson(
                    `${url}/movements/reassignment/destination?${params}`,
                );
                if (request !== sequence) return;
                target = result?.id ? result : null;
            } catch {
                if (request !== sequence) return;
                target = null;
            }
            const options = (target?.holders || []).flatMap(({ id, displayName }) =>
                id && displayName ? [{ id, label: displayName }] : [],
            );
            if (accountless && target) {
                options.push({ id: newHolderChoice, label: holder.dataset.newHolderLabel || '' });
            }
            setMoneyOptions(holder, options);
            if (options.some(({ id }) => id === selection)) holder.value = selection;
            if (accountless && target && !target.holders?.length) holder.value = newHolderChoice;
            showHolderChoices(accountless);
            unavailable.hidden = target !== null;
            subscribe(target?.id || '', () => {
                if (next === key) refresh(active, source, true);
            });
            changed(live);
        }, 220);
    };

    return {
        refresh,
        /**
         * Captures the explicitly resolved destination at its exact revision.
         *
         * @param {Holder} source the debited holder
         * @returns {DestinationTerms | null} the selected paired destination
         */
        selection(source) {
            if (!target?.id) return null;
            if (source.userId) {
                const existing = target.holders?.[0];
                return {
                    destinationOrderId: target.id,
                    destinationHolderId: existing?.id,
                    destinationVersion: existing?.version || 0,
                };
            }
            if (!confirmation.checked) return null;
            if (holder.value === newHolderChoice) {
                return {
                    destinationOrderId: target.id,
                    destinationVersion: 0,
                    confirmSamePerson: true,
                };
            }
            const selected = target.holders?.find(({ id }) => id === holder.value);
            return selected?.id
                ? {
                      destinationOrderId: target.id,
                      destinationHolderId: selected.id,
                      destinationVersion: selected.version,
                      confirmSamePerson: true,
                  }
                : null;
        },
        displayNumber: () => number.value,
        reset() {
            window.clearTimeout(timer);
            sequence += 1;
            key = '';
            target = null;
            subscribe('', () => {});
            selectedOnRestore = '';
            setMoneyOptions(holder, []);
        },
        /** @param {string} displayNumber the previously entered destination number
         * @param {string} destinationHolderId the previously selected holder
         * @param {boolean} createNamedHolder whether the submitted choice added the named holder
         */
        restore(displayNumber, destinationHolderId, createNamedHolder) {
            number.value = displayNumber;
            selectedOnRestore = createNamedHolder ? newHolderChoice : destinationHolderId;
        },
    };
}
