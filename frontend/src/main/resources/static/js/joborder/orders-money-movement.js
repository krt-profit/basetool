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
import { bindAccountMovement } from '/js/joborder/orders-money-account-movement.js';
import { bindBankMode } from '/js/joborder/orders-money-bank.js';
import { bindPlayerBankMovement } from '/js/joborder/orders-money-player-bank.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtNumberInput } from '/js/kernel/krt-number-input.js';
import { orderHolders, orderMoneyProjection } from '/js/joborder/orders-money-holders.js';
import {
    bindMoneyPreview,
    showBankPreview,
    showPreviewAmounts,
} from '/js/joborder/orders-money-preview.js';
import {
    bindMovementParties,
    showMovementField,
} from '/js/joborder/orders-money-movement-parties.js';
import { bindReassignmentDestination } from '/js/joborder/orders-money-reassignment.js';
import {
    readAttributionChoice,
    refreshAttributionChoices,
} from '/js/joborder/orders-money-commitments.js';

/** @import { bindMoneyRecovery, MovementDraft } from '/js/joborder/orders-money-recovery.js' */
/** @typedef {ReturnType<typeof bindMoneyRecovery>} Recovery */
/** @typedef {ApiDto<'JobOrderMoneyMovementPreview'>} MovementPreview */
/** @typedef {ApiDto<'JobOrderMoneyReassignmentPreview'>} ReassignmentPreview */

const modalId = 'order-money-movement-modal';
const handovers = new Set(['HANDOVER', 'PURPOSE_HANDOVER']);
const activePurposes = new Set(['COLLECTION', ...handovers]);

/**
 * Shows exact server-calculated amounts, parties and the applicable lifecycle effect.
 *
 * @param {HTMLElement} output the shared preview
 * @param {MovementPreview | ReassignmentPreview} result the authorised effects
 * @param {boolean} handover whether net handover rises
 * @param {boolean} returning whether a requester return lowers net handover
 * @param {boolean} hasDestination whether the action involves a receiving party
 * @param {string} nameUnavailable the localized missing party label
 */
function renderMovement(output, result, handover, returning, hasDestination, nameUnavailable) {
    const { amount, fee, provision, sourceAfter } = result;
    const bank = 'bank' in result ? result.bank : undefined;
    const shortfall = 'shortfall' in result ? result.shortfall : undefined;
    const overpayment = 'overpayment' in result ? result.overpayment : undefined;
    const destinationAfter =
        'destinationAfter' in result && typeof result.destinationAfter === 'string'
            ? result.destinationAfter
            : undefined;
    showPreviewAmounts(output, {
        amount,
        fee: bank?.actualFee ?? fee,
        charge:
            bank?.orderSourceCharge ??
            (returning ? fee : (BigInt(amount || '0') + BigInt(fee || '0')).toString()),
        provision,
        sourceAfter,
        destinationAfter,
        overpayment,
        shortfall,
    });
    for (const [name, value] of Object.entries({
        source: result.sourceName,
        destination: result.destinationName,
    })) {
        const label = output.querySelector(`[data-preview-label="${name}"]`);
        if (label) label.textContent = value ?? nameUnavailable;
    }
    output.querySelector('[data-preview-destination]')?.toggleAttribute('hidden', !hasDestination);
    output
        .querySelector('[data-preview-shortfall]')
        ?.toggleAttribute('hidden', !shortfall || shortfall === '0');
    output
        .querySelector('[data-preview-overpayment]')
        ?.toggleAttribute('hidden', !overpayment || overpayment === '0');
    output
        .querySelector('[data-preview-destination-balance]')
        ?.toggleAttribute('hidden', destinationAfter === undefined);
    const completes = handover && 'completes' in result && result.completes;
    const reopens = returning && 'reopens' in result && result.reopens;
    showBankPreview(output, bank);
    output.querySelector('[data-preview-completes]')?.toggleAttribute('hidden', !completes);
    output.querySelector('[data-preview-reopens]')?.toggleAttribute('hidden', !reopens);
}

/**
 * Binds one movement editor and persistent outcome recovery to the aUEC order.
 *
 * @param {HTMLElement} root the authenticated account and order scope
 * @param {Recovery} recovery the shared money operation recovery
 */
export function bindMoneyMovement(root, recovery) {
    const form = document.getElementById('order-money-movement-form');
    if (!(form instanceof HTMLFormElement)) throw new Error('The order movement form is missing');
    const kind = form.elements.namedItem('kind');
    const amount = form.elements.namedItem('amount');
    const topUp = form.elements.namedItem('topUp');
    const commitment = form.elements.namedItem('commitmentId');
    const fields = form.querySelector('fieldset');
    const changed = form.querySelector('[data-money-basis-changed]');
    if (
        !(kind instanceof HTMLSelectElement) ||
        !(amount instanceof HTMLInputElement) ||
        !(topUp instanceof HTMLInputElement) ||
        !(commitment instanceof HTMLSelectElement) ||
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

    const bank = bindBankMode(form);
    const playerBank = bindPlayerBankMovement(form, kind, url, () => sync(true));
    const parties = bindMovementParties(
        form,
        kind,
        url,
        self,
        bank.enabled,
        playerBank.canReturnFromBank,
    );
    const account = bindAccountMovement(form, kind, bank.enabled, parties.canReturn);
    const reassignment = bindReassignmentDestination(form, url, (live) => sync(live));
    const isReassignment = () => kind.value === 'REASSIGNMENT';
    const isReturning = () => kind.value === 'REQUESTER_RETURN';

    const updatePurposes = () => {
        const state = orderMoneyProjection()?.dataset;
        const valid =
            !!state && state.responsibilityValid !== 'false' && state.status !== 'REJECTED';
        const active = state?.status === 'OPEN' || state?.status === 'IN_PROGRESS';
        const hasOwnScope = parties.moveForOthers() || parties.ownsHolding();
        for (const option of kind.options) {
            const permitted = activePurposes.has(option.value)
                ? active &&
                  state?.canRecordMovement === 'true' &&
                  (option.value === 'PURPOSE_HANDOVER' && bank.enabled()
                      ? account.canHandOver() || orderHolders().length > 0
                      : orderHolders().length > 0)
                : option.value === 'REQUESTER_RETURN'
                  ? parties.canReturn() || (bank.enabled() && account.canReturn())
                  : option.value === 'SHORTAGE' || option.value === 'RELEASE'
                    ? hasOwnScope || account.canAdjust()
                    : hasOwnScope && orderHolders().length > 0;
            option.disabled = !valid || !permitted;
            option.hidden = option.disabled && option.value !== kind.value;
        }
    };

    const playerBankEnabled = () =>
        bank.enabled() && !account.active() && !isReassignment() && !account.adjusting();

    /** @returns {MovementDraft | null} the complete Order-side terms before selecting additional funds */
    const readOrderTerms = () => {
        const state = orderMoneyProjection()?.dataset;
        if (!state || kind.selectedOptions.item(0)?.disabled || !amount.value) return null;
        if (account.active()) return account.selection(amount.value);

        const common = { amount: amount.value, bankHandling: false };
        const holder = parties.source();
        const targetVersion = Number(state.targetVersion || 0);
        if (kind.value === 'REQUESTER_RETURN') {
            const destination = parties.selection();
            const recipientId = parties.recipientId();
            return destination && recipientId
                ? {
                      command: 'movements/settlement',
                      terms: {
                          ...common,
                          ...destination,
                          kind: 'REQUESTER_RETURN',
                          recipientId,
                          sourceHolderId: holder?.id,
                          sourceVersion: holder?.version,
                          targetVersion,
                      },
                  }
                : null;
        }
        if (!holder) return null;
        const source = { ...common, sourceHolderId: holder.id, sourceVersion: holder.version };
        switch (kind.value) {
            case 'REASSIGNMENT': {
                const destination = reassignment.selection(holder);
                return destination
                    ? {
                          command: 'movements/reassignment',
                          terms: { ...source, ...destination },
                          destinationNumber: reassignment.displayNumber(),
                      }
                    : null;
            }
            case 'HANDOVER':
            case 'PURPOSE_HANDOVER': {
                const recipientId = parties.recipientId();
                return recipientId
                    ? {
                          command: 'movements',
                          terms: { ...source, kind: kind.value, targetVersion, recipientId },
                      }
                    : null;
            }
            case 'COLLECTION': {
                const destination = parties.selection();
                return destination
                    ? {
                          command: 'movements',
                          terms: { ...source, ...destination, kind: 'COLLECTION' },
                      }
                    : null;
            }
            case 'SETTLEMENT': {
                const destination = parties.selection();
                return destination
                    ? {
                          command: 'movements/settlement',
                          terms: { ...source, ...destination, kind: 'SETTLEMENT' },
                      }
                    : null;
            }
            case 'RELEASE':
            case 'SHORTAGE':
                return { command: 'movements/settlement', terms: { ...source, kind: kind.value } };
            default:
                return null;
        }
    };

    /** @returns {MovementDraft | null} the complete terms before selecting additional funds */
    const readBase = () => {
        const draft = readOrderTerms();
        if (!draft || !playerBankEnabled() || draft.command === 'movements/reassignment')
            return draft;
        const selection = playerBank.selection();
        if (!selection) return null;
        draft.terms.bankHandling = true;
        draft.terms.bank = selection;
        return draft;
    };

    const showAttribution = () =>
        showMovementField(
            commitment,
            !!(
                !account.active() &&
                activePurposes.has(kind.value) &&
                topUp.checked &&
                offered &&
                commitment.options.length > 1
            ),
        );

    /** @returns {MovementDraft | null} the draft including any consciously selected fee funding */
    const readTerms = () => {
        const draft = readBase();
        if (
            !draft ||
            account.active() ||
            !topUp.checked ||
            !offered ||
            draft.command === 'movements/reassignment'
        ) {
            return draft;
        }
        if (draft.command === 'movements/settlement') {
            return { ...draft, terms: { ...draft.terms, provision: offered } };
        }
        const attribution = readAttributionChoice(commitment);
        return attribution
            ? { ...draft, terms: { ...draft.terms, ...attribution, provision: offered } }
            : null;
    };

    const preview = bindMoneyPreview(
        form,
        (draft) => ({ url: `${url}/${draft.command}/preview`, payload: draft.terms }),
        readTerms,
        (output, body, draft) => {
            const result = /** @type {MovementPreview | ReassignmentPreview | null} */ (body);
            const bankRequested = 'bankHandling' in draft.terms && draft.terms.bankHandling;
            if (
                !result ||
                typeof result.amount !== 'string' ||
                typeof result.fee !== 'string' ||
                (bankRequested && (!('bank' in result) || !result.bank))
            ) {
                return undefined;
            }
            const handover = draft.command === 'movements' && handovers.has(draft.terms.kind);
            const returning =
                draft.command === 'movements/settlement' && draft.terms.kind === 'REQUESTER_RETURN';
            const hasDestination =
                draft.command !== 'movements/settlement' ||
                (draft.terms.kind !== 'RELEASE' && draft.terms.kind !== 'SHORTAGE');
            renderMovement(
                output,
                account.active() ? { ...result, ...account.labels() } : result,
                handover,
                returning,
                hasDestination,
                root.dataset.nameUnavailable || '',
            );
            if (draft.command === 'movements/reassignment') return draft;
            if (!('rate' in result) || typeof result.rate !== 'string') return undefined;

            const { rate, shortfall, fee } = result;
            const unfunded = typeof shortfall === 'string' && shortfall !== '0';
            const offer = unfunded ? shortfall : fee;
            if (!topUp.checked) {
                const mayFund =
                    !account.active() &&
                    (returning ? parties.returnFeeAuthority() : parties.canSpend());
                const feeOnly = draft.command === 'movements/settlement';
                offered =
                    mayFund && offer && offer !== '0' && (!feeOnly || BigInt(offer) <= BigInt(fee))
                        ? offer
                        : '';
            }
            showPreviewAmounts(form, { offer: offered });
            topUp.closest('label')?.toggleAttribute('hidden', !offered);
            showAttribution();
            if (unfunded) return null;
            return draft.command === 'movements'
                ? { command: draft.command, terms: { ...draft.terms, rate } }
                : { command: draft.command, terms: { ...draft.terms, rate } };
        },
    );

    /**
     * Refreshes current choices and invalidates any preview whose scope changed.
     *
     * @param {boolean} [live] whether this refresh came from a server change
     * @param {boolean} [refreshPeer] whether to reread the destination holder versions
     */
    const sync = (live = false, refreshPeer = false) => {
        updatePurposes();
        account.refreshPurpose();
        bank.show(!isReassignment() && !account.adjusting());
        const accountAction = account.active();
        if (accountAction) parties.hide();
        else parties.sync();
        playerBank.sync(accountAction ? null : readOrderTerms(), playerBankEnabled());
        account.sync();
        reassignment.refresh(isReassignment(), parties.source(), refreshPeer);
        const next = JSON.stringify(readBase());
        if (next !== basis) {
            if (live && amount.value) changed.hidden = false;
            if (!live) {
                offered = '';
                topUp.checked = false;
                topUp.closest('label')?.setAttribute('hidden', '');
            }
        }
        basis = next;
        showAttribution();
        if (!saving) preview.refresh();
    };

    /** @param {MovementDraft | null} [refused] the rejected movement terms to review */
    const open = (refused = null) => {
        form.reset();
        reassignment.reset();
        refreshAttributionChoices(
            commitment,
            refused?.command === 'movements' ? refused.terms.commitmentId || '' : '',
        );
        preview.reset();
        parties.clear();
        changed.hidden = refused === null;
        offered = '';
        topUp.closest('label')?.setAttribute('hidden', '');
        if (refused) {
            bank.restore(
                refused.command === 'movements/reassignment' || refused.terms.bankHandling,
            );
            kind.value =
                refused.command === 'movements/reassignment' ? 'REASSIGNMENT' : refused.terms.kind;
        }
        updatePurposes();
        if (!refused && account.canHandOver()) kind.value = 'PURPOSE_HANDOVER';
        if (!refused && !account.canHandOver() && account.canReturn())
            kind.value = 'REQUESTER_RETURN';
        if (!refused && kind.selectedOptions.item(0)?.disabled) {
            kind.value = Array.from(kind.options).find((option) => !option.disabled)?.value || '';
        }
        account.refreshPurpose(true);
        if (account.active()) {
            parties.hide();
            account.sync(true);
        } else {
            parties.sync();
        }
        const own = orderHolders().find(({ userId }) => userId === self);
        parties.setSource(refused?.terms.sourceHolderId || (isReturning() ? '' : own?.id) || '');
        if (refused) {
            amount.value = refused.terms.amount;
            if (refused.command === 'movements/reassignment') {
                reassignment.restore(
                    refused.destinationNumber,
                    refused.terms.destinationHolderId || '',
                    !refused.terms.destinationHolderId && refused.terms.confirmSamePerson === true,
                );
            } else if (!account.restore(refused)) {
                parties.restore(refused.terms);
            }
        }
        sync();
        krtModal.open(modalId);
        if (bank.enabled()) void playerBank.refresh('');
    };

    krtEvents.on('click', 'od-money-move', () => {
        void recovery.openWhenRecovered(() => {
            if (
                document.querySelector(
                    '#order-money-results [data-trigger="od-money-move"]:enabled',
                )
            ) {
                open();
            }
        });
    });
    form.closest('dialog')?.addEventListener('close', () => {
        if (!krtModal.isOpen(modalId)) reassignment.reset();
    });
    form.addEventListener('input', () => sync());
    form.addEventListener('change', (event) => {
        sync();
        if (
            bank.enabled() &&
            (event.target === kind ||
                (event.target instanceof HTMLInputElement && event.target.name === 'bankHandling'))
        ) {
            void playerBank.refresh();
        }
    });
    document.addEventListener('krt:swapped', (event) => {
        if (!krtModal.isOpen(modalId)) return;
        const section = event.detail.container?.id;
        if (section === 'order-participations-results') {
            const observed = commitment.selectedOptions.item(0)?.dataset.version;
            refreshAttributionChoices(commitment, commitment.value);
            if (
                topUp.checked &&
                commitment.value &&
                observed !== commitment.selectedOptions.item(0)?.dataset.version
            ) {
                changed.hidden = false;
                preview.reset();
            }
            sync(true);
        } else if (section === 'order-money-results') {
            sync(true, true);
            if (bank.enabled()) void playerBank.refresh();
        }
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const draft = preview.ready();
        if (!draft || saving) return;
        saving = true;
        fields.disabled = true;
        try {
            if (await recovery.submit(draft, event.submitter)) {
                krtModal.close(modalId);
                form.reset();
                reassignment.reset();
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
