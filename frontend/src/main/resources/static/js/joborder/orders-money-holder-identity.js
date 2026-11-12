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
import { joborderHooks } from '/js/joborder/joborder-hooks.js';
import {
    bindMoneyPartyChoice,
    orderHolders,
    orderMoneyProjection,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';

/** @typedef {ReturnType<typeof orderHolders>[number]} Holder */
/** @typedef {ApiDto<'JobOrderMoneyHolderIdentityDto'>} IdentityChange */

const modal = 'order-money-holder-identity-modal';

/**
 * Maintains a holder from the money workspace and keeps an open draft on peer changes.
 *
 * @param {HTMLElement} root the current order scope
 */
export function bindHolderIdentity(root) {
    const form = document.getElementById('order-money-holder-identity-form');
    if (!(form instanceof HTMLFormElement)) throw new Error('The holder identity form is missing');
    const kind = form.elements.namedItem('kind');
    const name = form.elements.namedItem('name');
    const partySelect = form.elements.namedItem('party');
    const target = form.elements.namedItem('target');
    const label = form.querySelector('[data-holder-identity-label]');
    const basisChanged = form.querySelector('[data-holder-identity-basis]');
    const review = form.querySelector('[data-holder-identity-review]');
    const save = form.querySelector('button[type="submit"]');
    if (
        !(kind instanceof HTMLSelectElement) ||
        !(name instanceof HTMLInputElement) ||
        !(partySelect instanceof HTMLSelectElement) ||
        !(target instanceof HTMLSelectElement) ||
        !(review instanceof HTMLButtonElement) ||
        !(save instanceof HTMLButtonElement) ||
        !basisChanged ||
        !label
    ) {
        throw new Error('The holder identity form controls are missing');
    }

    const url = `/orders/${root.dataset.orderId}/money/holders`;
    const party = bindMoneyPartyChoice(
        partySelect,
        (query) => krtFetch.getJson(`${url}/identity-candidates?q=${encodeURIComponent(query)}`),
        () => kind.value === 'CORRECT_LINK',
    );
    /** @type {Holder | undefined} */
    let source;
    /** @type {Holder[]} */
    let targets = [];
    let conflicted = false;
    let saving = false;

    const refresh = () =>
        joborderHooks.refreshOrderSection?.(['money', 'actions'], { broadcast: false });
    const canEdit = () => orderMoneyProjection()?.dataset.canMaintainHolders === 'true';
    const selectedTarget = () => targets.find((row) => row.id === target.value);

    /** @returns {IdentityChange | null} the explicit change and the versions the user reviewed */
    const payload = () => {
        if (!source) return null;
        const version = source.version;
        if (kind.value === 'NAME') {
            const corrected = name.value.trim();
            return corrected ? { kind: 'NAME', version, name: corrected } : null;
        }
        if (kind.value === 'MERGE') {
            const survivor = selectedTarget();
            return survivor
                ? {
                      kind: 'MERGE',
                      version,
                      targetHolderId: survivor.id,
                      targetVersion: survivor.version,
                  }
                : null;
        }
        const selected = party.selection();
        if (!selected) return null;
        if (kind.value === 'LINK' && 'userId' in selected) {
            return { kind: 'LINK', version, ...selected };
        }
        if (kind.value === 'CORRECT_LINK') return { kind: 'CORRECT_LINK', version, ...selected };
        return null;
    };

    const sync = () => {
        const rows = orderHolders();
        const actualSource = rows.find((row) => row.id === source?.id);
        const survivor = kind.value === 'MERGE' ? selectedTarget() : undefined;
        const actualTarget = rows.find((row) => row.id === survivor?.id);
        const canReview = !!actualSource && canEdit();
        const changed =
            conflicted ||
            !canReview ||
            actualSource?.version !== source?.version ||
            actualTarget?.version !== survivor?.version;
        const showName = kind.value === 'NAME';
        const showTarget = kind.value === 'MERGE';
        name.closest('.form-group')?.toggleAttribute('hidden', !showName);
        target.closest('.form-group')?.toggleAttribute('hidden', !showTarget);
        name.disabled = !showName;
        target.disabled = !showTarget;
        party.setEnabled(kind.value === 'LINK' || kind.value === 'CORRECT_LINK');
        basisChanged?.toggleAttribute('hidden', !changed);
        review.disabled = !canReview || saving;
        save.disabled = changed || saving || !payload();
    };

    /** @param {Holder} reviewed the explicitly reviewed current holder */
    const reviewBasis = (reviewed) => {
        source = reviewed;
        conflicted = false;
        label.textContent = reviewed.label;
        targets = orderHolders().filter((row) => row.id !== reviewed.id);
        setMoneyOptions(target, targets);
        const linked = !!reviewed.userId;
        for (const option of kind.options) {
            const available = linked
                ? option.value === 'CORRECT_LINK'
                : option.value !== 'CORRECT_LINK' &&
                  (option.value !== 'MERGE' || targets.length > 0);
            option.hidden = !available;
            option.disabled = !available;
        }
        if (!kind.selectedOptions[0] || kind.selectedOptions[0].disabled) {
            kind.value = linked ? 'CORRECT_LINK' : 'NAME';
        }
        sync();
    };

    krtEvents.on('click', 'od-money-holder-identity', (button) => {
        const current = orderHolders().find((row) => row.id === button.dataset.holderId);
        if (!current || !canEdit()) return;
        form.reset();
        party.clear();
        reviewBasis(current);
        krtModal.open(modal);
    });
    review.addEventListener('click', () => {
        const current = orderHolders().find((row) => row.id === source?.id);
        if (current && canEdit()) reviewBasis(current);
    });
    form.addEventListener('input', sync);
    form.addEventListener('change', sync);
    document.addEventListener('krt:swapped', (event) => {
        if (event.detail.container?.id === 'order-money-results' && krtModal.isOpen(modal)) sync();
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const request = payload();
        if (!source || !request || save.disabled || !joborderHooks.writeOrder) return;
        saving = true;
        sync();
        try {
            const result = await joborderHooks.writeOrder({
                method: 'PATCH',
                url: `${url}/${source.id}/identity`,
                sectionKey: 'money',
                payload: request,
                submitter: event.submitter,
                onConflict(problem) {
                    if (
                        problem?.code !== 'OPTIMISTIC_LOCK' &&
                        problem?.code !== 'PESSIMISTIC_LOCK'
                    ) {
                        return false;
                    }
                    conflicted = true;
                    sync();
                    void refresh();
                    return true;
                },
            });
            if (result.ok && !result.redirected) {
                krtModal.close(modal);
                await refresh();
            }
        } finally {
            saving = false;
            sync();
        }
    });
}
