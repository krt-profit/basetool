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
import {
    bindMoneyPartyChoice,
    orderHolders,
    orderMoneyProjection,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';
import { bindMoneyPreview, showPreviewAmounts } from '/js/joborder/orders-money-preview.js';

/** @typedef {Omit<ApiDto<'MoveJobOrderMoneyDto'>, 'operationId'>} MovementDraft */
/** @typedef {ApiDto<'JobOrderMoneyMovementPreview'>} MovementPreview */
/** @import { bindMoneyRecovery } from '/js/joborder/orders-money-recovery.js' */
/** @typedef {ReturnType<typeof bindMoneyRecovery>} Recovery */

const modalId = 'order-money-movement-modal';

/**
 * Reads the currently selectable requester recipients.
 *
 * @returns {{id: string, holderId: string, userId: string, label: string}[]} the active designations
 */
function recipients() {
    const rows = orderMoneyProjection()?.querySelectorAll('[data-money-recipient]') || [];
    return Array.from(rows, (row) => ({
        id: row.getAttribute('data-money-recipient') || '',
        holderId: row.getAttribute('data-holder-id') || '',
        userId: row.getAttribute('data-user-id') || '',
        label: row.getAttribute('data-recipient-label') || '',
    }));
}

/**
 * Enables only the fields relevant to the selected purpose.
 *
 * @param {HTMLSelectElement | HTMLInputElement} input the contextual field
 * @param {boolean} enabled whether it applies to the draft
 */
function showField(input, enabled) {
    input.disabled = !enabled;
    input.closest('.form-group')?.toggleAttribute('hidden', !enabled);
}

/**
 * Records one actual collection, delivery or same-player purpose change.
 *
 * @param {HTMLElement} root the authenticated account and order scope
 * @param {Recovery} recovery the shared money operation recovery
 */
export function bindMoneyMovement(root, recovery) {
    const form = document.getElementById('order-money-movement-form');
    if (!(form instanceof HTMLFormElement)) throw new Error('The order movement form is missing');
    const kind = form.elements.namedItem('kind');
    const source = form.elements.namedItem('sourceHolderId');
    const destinationMode = form.elements.namedItem('destinationMode');
    const destination = form.elements.namedItem('destinationHolderId');
    const destinationChoice = form.elements.namedItem('destinationChoice');
    const recipient = form.elements.namedItem('recipientId');
    const amount = form.elements.namedItem('amount');
    const bankFree = form.elements.namedItem('bankFree');
    const topUp = form.elements.namedItem('topUp');
    const fields = form.querySelector('fieldset');
    const changed = form.querySelector('[data-money-basis-changed]');
    if (
        !(kind instanceof HTMLSelectElement) ||
        !(source instanceof HTMLSelectElement) ||
        !(destinationMode instanceof HTMLSelectElement) ||
        !(destination instanceof HTMLSelectElement) ||
        !(destinationChoice instanceof HTMLSelectElement) ||
        !(recipient instanceof HTMLSelectElement) ||
        !(amount instanceof HTMLInputElement) ||
        !(bankFree instanceof HTMLInputElement) ||
        !(topUp instanceof HTMLInputElement) ||
        !(fields instanceof HTMLFieldSetElement) ||
        !(changed instanceof HTMLElement)
    ) {
        throw new Error('The order movement controls are missing');
    }

    const url = `/orders/${root.dataset.orderId}/money`;
    const self = root.dataset.accountId || '';
    let offered = '';
    let basis = '';
    let saving = false;
    krtNumberInput.enhance(amount);

    const chosenParty = bindMoneyPartyChoice(destinationChoice, (query) => {
        if (!source.value) return Promise.resolve([]);
        const params = new URLSearchParams({ sourceHolderId: source.value, q: query });
        return krtFetch.getJson(`${url}/movement-candidates?${params}`);
    });

    const moveForOthers = () => orderMoneyProjection()?.dataset.canMoveForOthers === 'true';
    const recordForRequester = () =>
        orderMoneyProjection()?.dataset.canRecordRequesterHandover === 'true';
    const selectedSource = () => orderHolders().find(({ id }) => id === source.value);
    const canSpendSource = () => moveForOthers() || selectedSource()?.userId === self;
    const isCollection = () => kind.value === 'COLLECTION';

    /** Refreshes the authorized parties while retaining explicit choices. */
    const updateOptions = () => {
        const holders = orderHolders();
        const designations = recipients();
        const hasOwnRecipient = designations.some(({ userId }) => userId === self);
        const canChooseForeignSource =
            isCollection() || recordForRequester() || hasOwnRecipient || moveForOthers();
        setMoneyOptions(
            source,
            holders.filter((row) => canChooseForeignSource || row.userId === self),
        );

        const canSpend = canSpendSource();
        const availableHolders = holders.filter(
            (row) => row.id !== source.value && (canSpend || row.userId === self),
        );
        setMoneyOptions(destination, availableHolders);
        const selfOption = destinationMode.querySelector('option[value="self"]');
        if (selfOption instanceof HTMLOptionElement) {
            selfOption.hidden = canSpend || holders.some((row) => row.userId === self);
            selfOption.disabled = selfOption.hidden;
        }
        const newOption = destinationMode.querySelector('option[value="new"]');
        if (newOption instanceof HTMLOptionElement) newOption.disabled = !canSpend;

        const sourceUser = selectedSource()?.userId;
        const sameHolder = (/** @type {(typeof designations)[number]} */ designation) =>
            designation.holderId === source.value ||
            (!!sourceUser && designation.userId === sourceUser);
        setMoneyOptions(
            recipient,
            designations.filter(
                (row) =>
                    (canSpend || recordForRequester() || row.userId === self) &&
                    (kind.value === 'PURPOSE_HANDOVER' ? sameHolder(row) : !sameHolder(row)),
            ),
        );

        const collecting = isCollection();
        showField(destinationMode, collecting);
        showField(destination, collecting && destinationMode.value === 'existing');
        chosenParty.setEnabled(collecting && canSpend && destinationMode.value === 'new');
        showField(recipient, !collecting);
    };

    /** @returns {MovementDraft | null} the complete draft before accepting a top-up */
    const readBase = () => {
        const projection = orderMoneyProjection();
        const holder = selectedSource();
        if (
            projection?.dataset.canRecordMovement !== 'true' ||
            !holder ||
            !amount.value ||
            !bankFree.checked
        ) {
            return null;
        }
        const common = {
            amount: amount.value,
            sourceHolderId: holder.id,
            sourceVersion: holder.version,
            bankHandling: false,
        };
        if (kind.value === 'HANDOVER' || kind.value === 'PURPOSE_HANDOVER') {
            if (!recipient.value) return null;
            return {
                ...common,
                kind: kind.value,
                targetVersion: Number(projection.dataset.targetVersion || 0),
                recipientId: recipient.value,
            };
        }
        const collection = { ...common, kind: /** @type {const} */ ('COLLECTION') };
        if (destinationMode.value === 'existing') {
            const target = orderHolders().find(({ id }) => id === destination.value);
            return target
                ? {
                      ...collection,
                      destinationHolderId: target.id,
                      destinationVersion: target.version,
                  }
                : null;
        }
        if (destinationMode.value === 'self' && self && !canSpendSource()) {
            return { ...collection, destinationUserId: self, destinationVersion: 0 };
        }
        const party = chosenParty.selection();
        if (destinationMode.value !== 'new' || !canSpendSource() || !party) return null;
        if ('name' in party)
            return { ...collection, destinationName: party.name, destinationVersion: 0 };
        const target = orderHolders().find(({ userId }) => userId === party.userId);
        return {
            ...collection,
            destinationUserId: party.userId,
            destinationVersion: target?.version || 0,
        };
    };

    const preview = bindMoneyPreview(
        form,
        `${url}/movements/preview`,
        () => {
            const base = readBase();
            return base && topUp.checked && offered ? { ...base, provision: offered } : base;
        },
        (output, body, terms) => {
            const result = /** @type {MovementPreview | null} */ (body);
            if (
                !result ||
                typeof result.amount !== 'string' ||
                typeof result.fee !== 'string' ||
                typeof result.shortfall !== 'string' ||
                typeof result.rate !== 'string'
            ) {
                return undefined;
            }
            const { amount, fee, provision, sourceAfter, overpayment, shortfall } = result;
            showPreviewAmounts(output, {
                amount,
                fee,
                charge: (BigInt(amount) + BigInt(fee)).toString(),
                provision,
                sourceAfter,
                overpayment,
                shortfall,
            });
            for (const [name, value] of Object.entries({
                source: result.sourceName,
                destination: result.destinationName,
            })) {
                const label = output.querySelector(`[data-preview-label="${name}"]`);
                if (label) label.textContent = value ?? root.dataset.nameUnavailable ?? '';
            }
            const unfunded = shortfall !== '0';
            output.querySelector('[data-preview-shortfall]')?.toggleAttribute('hidden', !unfunded);
            output
                .querySelector('[data-preview-overpayment]')
                ?.toggleAttribute('hidden', !overpayment || overpayment === '0');
            const remaining = BigInt(orderMoneyProjection()?.dataset.remaining || '0');
            const completes =
                terms.kind !== 'COLLECTION' && remaining > 0n && BigInt(amount) >= remaining;
            output.querySelector('[data-preview-completes]')?.toggleAttribute('hidden', !completes);
            const provisionOffer = unfunded ? shortfall : fee;
            if (!topUp.checked) {
                offered = canSpendSource() && provisionOffer !== '0' ? provisionOffer : '';
            }
            showPreviewAmounts(form, { offer: offered });
            topUp.closest('label')?.toggleAttribute('hidden', !offered);
            return unfunded ? null : { ...terms, rate: result.rate };
        },
    );

    /** @param {boolean} [live] whether the basis changed through a section update */
    const sync = (live = false) => {
        updateOptions();
        const next = JSON.stringify(readBase());
        if (next !== basis) {
            if (live && amount.value) changed.hidden = false;
            offered = '';
            topUp.checked = false;
            topUp.closest('label')?.setAttribute('hidden', '');
        }
        basis = next;
        if (!saving) preview.refresh();
    };

    /** @param {MovementDraft | null} [refused] the rejected terms to review */
    const open = (refused = null) => {
        form.reset();
        preview.reset();
        chosenParty.clear();
        changed.hidden = !refused;
        offered = '';
        topUp.closest('label')?.setAttribute('hidden', '');
        if (refused) kind.value = refused.kind;
        updateOptions();
        const own = orderHolders().find(({ userId }) => userId === self);
        source.value = refused?.sourceHolderId || own?.id || '';
        if (refused) {
            amount.value = refused.amount;
            if (refused.destinationName || refused.destinationUserId) destinationMode.value = 'new';
            if (refused.destinationName) chosenParty.restoreName(refused.destinationName);
            if (refused.destinationUserId) chosenParty.restoreAccount(refused.destinationUserId);
            updateOptions();
            recipient.value = refused.recipientId || '';
            destination.value = refused.destinationHolderId || '';
        }
        sync();
        krtModal.open(modalId);
    };

    krtEvents.on('click', 'od-money-move', () => {
        if (recovery.pending()) void recovery.recover();
        else open();
    });
    form.addEventListener('input', () => sync());
    form.addEventListener('change', () => sync());
    document.addEventListener('krt:swapped', (event) => {
        if (event.detail.container?.id === 'order-money-results' && krtModal.isOpen(modalId))
            sync(true);
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const terms = preview.ready();
        if (!terms || saving) return;
        saving = true;
        fields.disabled = true;
        try {
            if (await recovery.submit({ command: 'movements', terms }, event.submitter)) {
                krtModal.close(modalId);
                form.reset();
                preview.reset();
            }
        } finally {
            saving = false;
            fields.disabled = false;
            sync();
        }
    });
    return { restore: open };
}
