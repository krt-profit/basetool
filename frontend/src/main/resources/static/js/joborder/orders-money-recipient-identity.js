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
import { bindMoneyPartyChoice, orderMoneyProjection } from '/js/joborder/orders-money-holders.js';

const modal = 'order-money-recipient-identity-modal';
const trigger = 'od-money-recipient-identity';

/**
 * Binds correction of a direct requester designation while retaining the draft on peer changes.
 *
 * @param {HTMLElement} root the order scope
 */
export function bindRecipientIdentity(root) {
    const form = document.getElementById('order-money-recipient-identity-form');
    if (!(form instanceof HTMLFormElement))
        throw new Error('The recipient identity form is missing');
    const select = form.elements.namedItem('party');
    const label = form.querySelector('[data-recipient-identity-label]');
    const stale = form.querySelector('[data-recipient-identity-stale]');
    const save = form.querySelector('button[type="submit"]');
    if (
        !(select instanceof HTMLSelectElement) ||
        !(save instanceof HTMLButtonElement) ||
        !label ||
        !stale
    ) {
        throw new Error('The recipient identity controls are missing');
    }

    let recipientId = '';
    let saving = false;
    const endpoint = () => `/orders/${root.dataset.orderId}/money/recipients/${recipientId}`;
    const refresh = () =>
        joborderHooks.refreshOrderSection?.(['money', 'actions'], { broadcast: false });
    const party = bindMoneyPartyChoice(select, (query) =>
        krtFetch.getJson(`${endpoint()}/identity-candidates?q=${encodeURIComponent(query)}`),
    );
    const available = () => {
        const projection = orderMoneyProjection();
        return (
            projection?.dataset.canCorrectRecipient === 'true' &&
            !!projection.querySelector(
                `[data-trigger="${trigger}"][data-recipient-id="${recipientId}"]`,
            )
        );
    };
    const sync = () => {
        const canCorrect = available();
        stale.toggleAttribute('hidden', canCorrect);
        party.setEnabled(canCorrect);
        save.disabled = !canCorrect || saving || !party.selection();
    };

    krtEvents.on('click', trigger, (button) => {
        recipientId = button.dataset.recipientId || '';
        if (!recipientId || !available()) return;
        form.reset();
        party.clear();
        label.textContent = button.dataset.recipientLabel || '';
        sync();
        krtModal.open(modal);
    });
    form.addEventListener('input', sync);
    form.addEventListener('change', sync);
    document.addEventListener('krt:swapped', (event) => {
        if (event.detail.container?.id === 'order-money-results' && krtModal.isOpen(modal)) sync();
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const selection = party.selection();
        if (!selection || save.disabled || !joborderHooks.writeOrder) return;
        saving = true;
        sync();
        try {
            const result = await joborderHooks.writeOrder({
                method: 'PATCH',
                url: `${endpoint()}/identity`,
                sectionKey: 'money',
                payload: selection,
                submitter: event.submitter,
                onConflict() {
                    void refresh();
                    return false;
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
