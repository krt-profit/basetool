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
    orderHolders,
    orderMoneyProjection,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';

/** @typedef {ReturnType<typeof orderHolders>[number]} Holder */
/** @typedef {{id: string, holderId: string, userId: string, label: string, active: boolean, returnable: boolean}} Recipient */
/** @typedef {Pick<ApiDto<'SettleJobOrderMoneyDto'>, 'destinationHolderId' | 'destinationVersion' | 'destinationUserId' | 'destinationName'>} Destination */

/** @returns {Recipient[]} the visible requester designations */
function recipients() {
    const rows = orderMoneyProjection()?.querySelectorAll('[data-money-recipient]') || [];
    return Array.from(rows, (row) => ({
        id: row.getAttribute('data-money-recipient') || '',
        holderId: row.getAttribute('data-holder-id') || '',
        userId: row.getAttribute('data-user-id') || '',
        label: row.getAttribute('data-recipient-label') || '',
        active: row.getAttribute('data-money-recipient-active') === 'true',
        returnable: row.getAttribute('data-money-recipient-returnable') === 'true',
    }));
}

/**
 * Enables only the fields relevant to the selected purpose.
 *
 * @param {HTMLSelectElement | HTMLInputElement} input the contextual field
 * @param {boolean} enabled whether it applies to the draft
 */
export function showMovementField(input, enabled) {
    input.disabled = !enabled;
    input.closest('.form-group')?.toggleAttribute('hidden', !enabled);
}

/**
 * Checks a deliberate holder reference or a stable account link.
 *
 * @param {Holder} holder the order holder
 * @param {Recipient | undefined} recipient a requester designation
 */
function samePlayer(holder, recipient) {
    return (
        recipient?.holderId === holder.id ||
        (!!holder.userId && recipient?.userId === holder.userId)
    );
}

/**
 * Keeps the movement's party fields aligned with its purpose and live grants.
 *
 * @param {HTMLFormElement} form the movement form
 * @param {HTMLSelectElement} purpose the movement purpose
 * @param {string} url the scoped order money route
 * @param {string} self the signed-in account
 */
export function bindMovementParties(form, purpose, url, self) {
    const source = form.elements.namedItem('sourceHolderId');
    const mode = form.elements.namedItem('destinationMode');
    const destination = form.elements.namedItem('destinationHolderId');
    const choice = form.elements.namedItem('destinationChoice');
    const recipient = form.elements.namedItem('recipientId');
    if (
        !(source instanceof HTMLSelectElement) ||
        !(mode instanceof HTMLSelectElement) ||
        !(destination instanceof HTMLSelectElement) ||
        !(choice instanceof HTMLSelectElement) ||
        !(recipient instanceof HTMLSelectElement)
    ) {
        throw new Error('The movement party controls are missing');
    }
    const sourceLabel = source.labels?.item(0);
    const recipientLabel = recipient.labels?.item(0);
    const returning = () => purpose.value === 'REQUESTER_RETURN';
    const collecting = () => purpose.value === 'COLLECTION';
    const settling = () => purpose.value === 'SETTLEMENT';
    const handover = () => ['HANDOVER', 'PURPOSE_HANDOVER'].includes(purpose.value);
    const moveForOthers = () => {
        const data = orderMoneyProjection()?.dataset;
        return data?.canMaintainHolders === 'true' || data?.canMoveForOthers === 'true';
    };
    const requesterHandover = () =>
        orderMoneyProjection()?.dataset.canRecordRequesterHandover === 'true';
    const requesterReturn = () =>
        orderMoneyProjection()?.dataset.canRecordRequesterReturn === 'true';
    const selectedSource = () =>
        source.disabled ? undefined : orderHolders().find(({ id }) => id === source.value);
    const canSpend = () => moveForOthers() || selectedSource()?.userId === self;
    const ownsHolding = () => orderHolders().some(({ userId }) => userId === self);
    const canReturn = () =>
        recipients().some(
            (row) =>
                row.returnable && (moveForOthers() || requesterReturn() || row.userId === self),
        );

    const chosenParty = bindMoneyPartyChoice(choice, (query) => {
        if (settling() || returning()) {
            const params = new URLSearchParams({ kind: purpose.value, q: query });
            if (!source.disabled && source.value) params.set('sourceHolderId', source.value);
            if (returning() && recipient.value) params.set('recipientId', recipient.value);
            if (settling() ? !source.value : !recipient.value) return Promise.resolve([]);
            return krtFetch.getJson(`${url}/movements/settlement/candidates?${params}`);
        }
        if (!source.value) return Promise.resolve([]);
        const params = new URLSearchParams({ sourceHolderId: source.value, q: query });
        return krtFetch.getJson(`${url}/movement-candidates?${params}`);
    });

    const sync = () => {
        const holders = orderHolders();
        const designations = recipients();
        if (returning()) {
            setMoneyOptions(
                recipient,
                designations.filter(
                    (row) =>
                        row.returnable &&
                        (moveForOthers() || requesterReturn() || row.userId === self),
                ),
            );
        }

        const currentRecipient = designations.find(({ id }) => id === recipient.value);
        const foreignSource =
            moveForOthers() ||
            collecting() ||
            (handover() &&
                (requesterHandover() ||
                    designations.some(({ active, userId }) => active && userId === self)));
        const sourceRows = returning()
            ? holders.filter((holder) => samePlayer(holder, currentRecipient))
            : holders.filter((row) => foreignSource || row.userId === self);
        setMoneyOptions(source, sourceRows);
        source.required = !returning();
        showMovementField(source, !returning() || sourceRows.length > 0);
        if (sourceLabel) {
            sourceLabel.textContent = returning()
                ? sourceLabel.dataset.feePayerLabel || ''
                : sourceLabel.dataset.sourceLabel || '';
        }

        if (handover()) {
            const sender = selectedSource();
            setMoneyOptions(
                recipient,
                designations.filter(
                    (row) =>
                        row.active &&
                        (canSpend() || requesterHandover() || row.userId === self) &&
                        (purpose.value === 'PURPOSE_HANDOVER'
                            ? !!sender && samePlayer(sender, row)
                            : !sender || !samePlayer(sender, row)),
                ),
            );
        }
        showMovementField(recipient, returning() || handover());
        if (recipientLabel) {
            recipientLabel.textContent = returning()
                ? recipientLabel.dataset.returnerLabel || ''
                : recipientLabel.dataset.recipientLabel || '';
        }

        const destinationRequired = collecting() || returning();
        const canChooseNew = returning()
            ? !!currentRecipient &&
              (moveForOthers() || requesterReturn() || currentRecipient.userId === self)
            : canSpend();
        const available = holders.filter(
            (row) =>
                (!collecting() || row.id !== source.value) && (canChooseNew || row.userId === self),
        );
        setMoneyOptions(destination, available);
        const selfOption = mode.querySelector('option[value="self"]');
        if (selfOption instanceof HTMLOptionElement) {
            selfOption.disabled = !self || (collecting() && (canChooseNew || ownsHolding()));
            selfOption.hidden = selfOption.disabled;
        }
        const newOption = mode.querySelector('option[value="new"]');
        if (newOption instanceof HTMLOptionElement) newOption.disabled = !canChooseNew;
        showMovementField(mode, destinationRequired);
        showMovementField(destination, destinationRequired && mode.value === 'existing');
        chosenParty.setEnabled(
            settling() || (destinationRequired && canChooseNew && mode.value === 'new'),
        );
    };

    /** @returns {Destination | null} the exact selected receiving party */
    const selection = () => {
        if (!settling() && mode.selectedOptions.item(0)?.disabled) return null;
        const holders = orderHolders();
        if (!settling() && mode.value === 'existing') {
            const holder = holders.find(({ id }) => id === destination.value);
            return holder
                ? { destinationHolderId: holder.id, destinationVersion: holder.version }
                : null;
        }
        if (!settling() && mode.value === 'self' && self) {
            const own = holders.find(({ userId }) => userId === self);
            return { destinationUserId: self, destinationVersion: own?.version || 0 };
        }
        const party = chosenParty.selection();
        if (!party) return null;
        if (settling()) {
            return 'name' in party
                ? { destinationName: party.name }
                : { destinationUserId: party.userId };
        }
        if ('name' in party) return { destinationName: party.name, destinationVersion: 0 };
        const holder = holders.find(({ userId }) => userId === party.userId);
        return { destinationUserId: party.userId, destinationVersion: holder?.version || 0 };
    };

    /** @param {Destination & {recipientId?: string | null}} terms the refused movement parties */
    const restore = (terms) => {
        if (terms.destinationName || terms.destinationUserId) mode.value = 'new';
        if (terms.destinationUserId === self && collecting() && !canSpend()) mode.value = 'self';
        if (terms.destinationName) chosenParty.restoreName(terms.destinationName);
        if (terms.destinationUserId) chosenParty.restoreAccount(terms.destinationUserId);
        sync();
        recipient.value = terms.recipientId || '';
        sync();
        destination.value = terms.destinationHolderId || '';
    };

    return {
        sync,
        source: selectedSource,
        canSpend,
        canReturn,
        ownsHolding,
        moveForOthers,
        recipientId: () => recipient.value,
        returnFeeAuthority: () =>
            moveForOthers() ||
            requesterReturn() ||
            recipients().some((row) => row.id === recipient.value && row.userId === self),
        setSource(/** @type {string} */ id) {
            source.value = id;
        },
        selection,
        restore,
        clear: () => chosenParty.clear(),
    };
}
