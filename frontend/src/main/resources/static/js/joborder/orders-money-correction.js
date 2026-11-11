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
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtNumberInput } from '/js/kernel/krt-number-input.js';
import {
    bindMoneyPeer,
    bindMoneyPreview,
    showPreviewAmounts,
} from '/js/joborder/orders-money-preview.js';

/** @typedef {Omit<ApiDto<'CorrectJobOrderMoneyDto'>, 'operationId'>} CorrectionTerms */
/** @typedef {NonNullable<CorrectionTerms['entries']>[number]} CorrectionEntry */
/** @typedef {{element: HTMLElement, select: HTMLSelectElement, amount: HTMLInputElement, provision: HTMLInputElement, attribution: HTMLInputElement}} Line */

const modalId = 'order-money-correction-modal';
const originalSelector = '[data-original-operation]';
const wholeAmount = /^[0-9]+$/;

/**
 * Opens one connected correction on an existing history entry.
 *
 * @param {HTMLElement} root the account and order recovery scope
 * @param {ReturnType<typeof import('/js/joborder/orders-money-recovery.js').bindMoneyRecovery>} recovery the shared operation recovery
 */
export function bindMoneyCorrection(root, recovery) {
    const form = document.getElementById('order-money-correction-form');
    if (!(form instanceof HTMLFormElement)) {
        throw new Error('The order money correction form is missing');
    }
    const linesHost = form.querySelector('[data-money-correction-lines]');
    const template = form.querySelector('[data-money-correction-line-template]');
    const fields = form.querySelector('fieldset');
    const add = form.querySelector('[data-money-correction-add]');
    const basisChanged = form.querySelector('[data-money-correction-basis]');
    if (
        !(linesHost instanceof HTMLElement) ||
        !(template instanceof HTMLTemplateElement) ||
        !(fields instanceof HTMLFieldSetElement) ||
        !(add instanceof HTMLButtonElement) ||
        !(basisChanged instanceof HTMLElement)
    ) {
        throw new Error('The order money correction controls are missing');
    }

    const url = `/orders/${root.dataset.orderId}/money/corrections`;
    /** @type {Line[]} */
    const lines = [];
    let previewId = '';
    let fingerprint = '';
    let saving = false;
    const subscribePeer = bindMoneyPeer();

    const choices = () =>
        new Map(
            Array.from(document.querySelectorAll(originalSelector)).flatMap((element) =>
                element instanceof HTMLElement && element.dataset.originalOperation
                    ? [[element.dataset.originalOperation, element]]
                    : [],
            ),
        );

    /** @returns {CorrectionTerms | null} the revised facts and their exact current scope */
    const readTerms = () => {
        if (lines.length === 0) return null;
        const originals = choices();
        /** @type {CorrectionEntry[]} */
        const entries = [];
        /** @type {Record<string, number>} */
        const holderVersions = {};
        const projection = document.querySelector('[data-order-money-projection]');
        let targetVersion;
        if (
            !(projection instanceof HTMLElement) ||
            projection.dataset.responsibilityValid !== 'true'
        ) {
            return null;
        }
        for (const line of lines) {
            const original = originals.get(line.select.value);
            if (!original || !wholeAmount.test(line.amount.value)) return null;
            const provision = line.provision.disabled ? undefined : line.provision.value;
            if (provision !== undefined && !wholeAmount.test(provision)) return null;
            if (entries.some((entry) => entry.originalOperationId === line.select.value))
                return null;
            entries.push({
                originalOperationId: line.select.value,
                amount: line.amount.value,
                provision,
                removeAttribution: !line.attribution.disabled && line.attribution.checked,
            });
            for (const holder of original.dataset.originalHolders?.split(',') || []) {
                const node = document.querySelector(`[data-money-holder="${CSS.escape(holder)}"]`);
                if (!(node instanceof HTMLElement) || !node.dataset.version) return null;
                holderVersions[holder] = Number(node.dataset.version);
            }
            if (original.dataset.originalAffectsTarget === 'true') {
                targetVersion = Number(projection.dataset.targetVersion);
            }
        }
        return { entries, holderVersions, targetVersion };
    };

    const preview = bindMoneyPreview(
        form,
        (terms) => ({ url: `${url}/preview`, payload: { ...terms, operationId: previewId } }),
        readTerms,
        (output, body, terms) => {
            const result = /** @type {ApiDto<'JobOrderMoneyCorrectionPreview'> | null} */ (body);
            if (!result || typeof result.holdings !== 'string') return undefined;
            showPreviewAmounts(output, {
                holdings: result.holdings,
                provided: result.provided,
                handedOver: result.netHandedOver,
                fees: result.fees,
                linkedHoldings: result.linkedHoldings || undefined,
            });
            output
                .querySelector('[data-money-correction-peer]')
                ?.toggleAttribute('hidden', typeof result.linkedHoldings !== 'string');
            return {
                ...terms,
                holderVersions: { ...terms.holderVersions, ...result.linkedHolderVersions },
            };
        },
    );

    /** @param {boolean} [live] whether the current order data changed */
    const update = (live = false) => {
        const next = JSON.stringify(readTerms());
        if (live && next !== fingerprint) basisChanged.hidden = false;
        fingerprint = next;
        const current = choices();
        add.disabled = lines.length >= Math.min(50, current.size);
        const peers = new Set(
            lines.flatMap(({ select }) => {
                const peer = current.get(select.value)?.dataset.originalPeer;
                return peer ? [peer] : [];
            }),
        );
        const peer = peers.size === 1 ? peers.values().next().value || '' : '';
        subscribePeer(peer, (changed) => {
            if (!krtModal.isOpen(modalId)) return;
            if (changed) basisChanged.hidden = false;
            preview.reset();
            if (!saving) preview.refresh();
        });
        if (!saving) preview.refresh();
    };

    /** @param {Line} line the entry being edited */
    const loadOriginal = (line) => {
        const original = choices().get(line.select.value);
        if (!original) return;
        line.amount.value = original.dataset.originalAmount || '';
        line.provision.value = original.dataset.originalProvision || '0';
        line.provision.disabled = ![
            'COLLECTION',
            'HANDOVER',
            'REQUESTER_RETURN',
            'SETTLEMENT',
        ].includes(original.dataset.originalKind || '');
        line.provision.closest('label')?.toggleAttribute('hidden', line.provision.disabled);
        line.attribution.checked = false;
        line.attribution.disabled = original.dataset.originalAttributed !== 'true';
        line.attribution.closest('label')?.toggleAttribute('hidden', line.attribution.disabled);
    };

    /** @param {string} originalId the history fact to preselect */
    const addLine = (originalId) => {
        const element = template.content.firstElementChild?.cloneNode(true);
        if (!(element instanceof HTMLElement)) return null;
        const select = element.querySelector('select');
        const amount = element.querySelector('[name="amount"]');
        const provision = element.querySelector('[name="provision"]');
        const attribution = element.querySelector('[name="removeAttribution"]');
        if (
            !(select instanceof HTMLSelectElement) ||
            !(amount instanceof HTMLInputElement) ||
            !(provision instanceof HTMLInputElement) ||
            !(attribution instanceof HTMLInputElement)
        ) {
            return null;
        }
        choices().forEach((original, id) => {
            const text = original.closest('li')?.querySelector('strong')?.textContent || '';
            select.add(new Option(text, id));
        });
        select.value = originalId;
        krtNumberInput.enhance(amount);
        krtNumberInput.enhance(provision);
        const line = { element, select, amount, provision, attribution };
        lines.push(line);
        linesHost.append(element);
        loadOriginal(line);
        select.addEventListener('change', () => loadOriginal(line));
        element.querySelector('[data-money-correction-remove]')?.addEventListener('click', () => {
            if (lines.length <= 1) return;
            lines.splice(lines.indexOf(line), 1);
            element.remove();
            update();
        });
        return line;
    };

    /**
     * Restores the chosen original and the user's amounts without overwriting the draft.
     *
     * @param {string} originalId the selected history fact
     * @param {CorrectionTerms | null} [refused] the prior refused terms
     */
    const open = (originalId, refused = null) => {
        previewId = crypto.randomUUID();
        preview.reset();
        lines.length = 0;
        linesHost.replaceChildren();
        basisChanged.hidden = refused === null;
        if (refused?.entries) {
            refused.entries.forEach((entry) => {
                const line = addLine(entry.originalOperationId);
                if (!line) return;
                line.amount.value = entry.amount;
                line.provision.value = entry.provision || '0';
                line.attribution.checked = entry.removeAttribution === true;
            });
        } else {
            addLine(originalId);
        }
        update();
        krtModal.open(modalId);
    };

    krtEvents.on('click', 'od-money-correct', (button) => {
        const original = button.dataset.originalOperation;
        if (!original) return;
        void recovery.openWhenRecovered(() => open(original));
    });
    add.addEventListener('click', () => {
        const next = [...choices().keys()].find((id) =>
            lines.every((line) => line.select.value !== id),
        );
        if (!next) return;
        addLine(next)?.select.focus();
        update();
    });
    form.closest('dialog')?.addEventListener('close', () => {
        if (!krtModal.isOpen(modalId)) subscribePeer('', () => {});
    });
    form.addEventListener('input', () => update());
    form.addEventListener('change', () => update());
    document.addEventListener('krt:swapped', (event) => {
        if (event.detail.container?.id === 'order-money-results' && krtModal.isOpen(modalId)) {
            update(true);
        }
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const terms = preview.ready();
        if (!terms || saving) return;
        saving = true;
        fields.disabled = true;
        try {
            if (await recovery.submit({ command: 'corrections', terms }, event.submitter)) {
                krtModal.close(modalId);
                preview.reset();
            }
        } finally {
            saving = false;
            fields.disabled = false;
            if (krtModal.isOpen(modalId)) update();
        }
    });

    return {
        /** @param {CorrectionTerms} terms the definitively refused correction */
        restore(terms) {
            const original = terms.entries?.[0]?.originalOperationId;
            if (original) open(original, terms);
        },
    };
}
