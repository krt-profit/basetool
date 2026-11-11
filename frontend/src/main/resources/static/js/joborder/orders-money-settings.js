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
import { joborderHooks } from '/js/joborder/joborder-hooks.js';
import {
    bindMoneyPartyChoice,
    orderHolders,
    orderMoneyProjection,
    setMoneyOptions,
} from '/js/joborder/orders-money-holders.js';

/** @typedef {ApiDto<'DesignateJobOrderMoneyRecipientDto'>} Designation */

const recipientModal = 'order-money-recipient-modal';
const editorModal = 'order-money-edit-modal';

const refresh = () =>
    joborderHooks.refreshOrderSection?.(['money', 'header', 'actions'], { broadcast: false });

/**
 * Connects recipient designation and independent contextual order edits.
 *
 * @param {HTMLElement} root the current order scope
 */
export function bindMoneySettings(root) {
    const url = `/orders/${root.dataset.orderId}/money`;
    bindRecipients(url);
    bindOrderEditor(url);
}

/** @param {string} url the order's money URL */
function bindRecipients(url) {
    const form = document.getElementById('order-money-recipient-form');
    if (!(form instanceof HTMLFormElement)) throw new Error('The recipient form is missing');
    const mode = form.elements.namedItem('mode');
    const holder = form.elements.namedItem('holderId');
    const select = form.elements.namedItem('party');
    const save = form.querySelector('button[type="submit"]');
    if (
        !(mode instanceof HTMLSelectElement) ||
        !(holder instanceof HTMLSelectElement) ||
        !(select instanceof HTMLSelectElement) ||
        !(save instanceof HTMLButtonElement)
    ) {
        throw new Error('The recipient form controls are missing');
    }
    const party = bindMoneyPartyChoice(select, (query) =>
        krtFetch.getJson(`${url}/recipient-candidates?q=${encodeURIComponent(query)}`),
    );

    /** @returns {Designation | null} the explicit designation permitted by current authority */
    const selection = () => {
        if (orderMoneyProjection()?.dataset.canDesignateRecipient !== 'true') return null;
        if (mode.value === 'existing') return holder.value ? { holderId: holder.value } : null;
        return party.selection();
    };

    const sync = () => {
        const existing = mode.value === 'existing';
        holder.disabled = !existing;
        holder.closest('.form-group')?.toggleAttribute('hidden', !existing);
        party.setEnabled(!existing);
        save.disabled = !selection();
    };

    const update = () => {
        const rows = orderMoneyProjection()?.querySelectorAll('[data-money-recipient]') || [];
        const designated = Array.from(rows);
        setMoneyOptions(
            holder,
            orderHolders().filter(
                (row) =>
                    !designated.some(
                        (recipient) =>
                            recipient.getAttribute('data-holder-id') === row.id ||
                            (row.userId && recipient.getAttribute('data-user-id') === row.userId),
                    ),
            ),
        );
        sync();
    };

    krtEvents.on('click', 'od-money-designate-recipient', () => {
        form.reset();
        party.clear();
        update();
        if (holder.options.length < 2) mode.value = 'new';
        sync();
        krtModal.open(recipientModal);
    });
    form.addEventListener('change', sync);
    form.addEventListener('input', sync);
    document.addEventListener('krt:swapped', (event) => {
        if (
            event.detail.container?.id === 'order-money-results' &&
            krtModal.isOpen(recipientModal)
        ) {
            update();
        }
    });
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const payload = selection();
        if (!payload) return;
        const result = await krtFetch.write({
            method: 'POST',
            url: `${url}/recipients`,
            payload,
            submitter: event.submitter,
        });
        if (result.ok && !result.redirected) {
            krtModal.close(recipientModal);
            await refresh();
        }
    });
    krtEvents.on('click', 'od-money-remove-recipient', (button) => {
        const recipientId = button.dataset.recipientId;
        if (!recipientId) return;
        void (async () => {
            const result = await krtFetch.write({
                method: 'DELETE',
                url: `${url}/recipients/${recipientId}`,
                submitter: button,
            });
            if (result.ok && !result.redirected) await refresh();
        })();
    });
}

/**
 * Opens the contextual editor and maintains separate current bases for its forms.
 *
 * @param {string} url the order's money URL
 */
function bindOrderEditor(url) {
    const target = document.getElementById('order-money-edit-target-form');
    const information = document.getElementById('order-money-edit-information-form');
    if (!(target instanceof HTMLFormElement) || !(information instanceof HTMLFormElement)) {
        throw new Error('The order money edit forms are missing');
    }
    const amount = target.elements.namedItem('target');
    const handle = information.elements.namedItem('handle');
    const comment = information.elements.namedItem('comment');
    if (
        !(amount instanceof HTMLInputElement) ||
        !(handle instanceof HTMLInputElement) ||
        !(comment instanceof HTMLTextAreaElement)
    ) {
        throw new Error('The order money edit fields are missing');
    }
    krtNumberInput.enhance(amount);
    const completes = target.querySelector('[data-target-completes]');
    const reopens = target.querySelector('[data-target-reopens]');
    const updateTargetConsequence = () => {
        const view = orderMoneyProjection()?.dataset;
        const changed = amount.value && amount.value !== view?.target;
        const enough = !!changed && BigInt(amount.value) <= BigInt(view?.netHandedOver || '0');
        const active = view?.status === 'OPEN' || view?.status === 'IN_PROGRESS';
        completes?.toggleAttribute('hidden', !(active && enough));
        reopens?.toggleAttribute('hidden', !(view?.status === 'COMPLETED' && changed && !enough));
    };
    target.addEventListener('input', updateTargetConsequence);
    const targetDraft = bindEditScope(target, `${url}/target`, {
        read: () => ({ target: amount.value }),
        write: (value) => {
            amount.value = value.target;
        },
        current: () => {
            const data = orderMoneyProjection()?.dataset;
            return {
                value: { target: data?.target || '' },
                version: Number(data?.targetVersion || 0),
                allowed: data?.canEditTarget === 'true',
            };
        },
    });
    const informationDraft = bindEditScope(information, `${url}/information`, {
        read: () => ({ handle: handle.value, comment: comment.value }),
        write: (value) => {
            handle.value = value.handle;
            comment.value = value.comment;
        },
        current: () => {
            const data = orderMoneyProjection()?.dataset;
            return {
                value: { handle: data?.handle || '', comment: data?.comment || '' },
                version: Number(data?.informationVersion || 0),
                allowed: data?.canEditInformation === 'true',
            };
        },
    });
    krtEvents.on('click', 'od-money-edit', () => {
        targetDraft.reset();
        informationDraft.reset();
        updateTargetConsequence();
        krtModal.open(editorModal);
    });
    document.addEventListener('krt:swapped', (event) => {
        if (event.detail.container?.id === 'order-money-results' && krtModal.isOpen(editorModal)) {
            targetDraft.sync();
            informationDraft.sync();
            updateTargetConsequence();
        }
    });
}

/**
 * Keeps one edit's revision independent and preserves its dirty fields.
 *
 * @template {object} Value
 * @param {HTMLFormElement} form the contextual form
 * @param {string} url its mutation endpoint
 * @param {{
 *   read: () => Value,
 *   write: (value: Value) => void,
 *   current: () => {value: Value, version: number, allowed: boolean}
 * }} scope the form's fields and live basis
 */
function bindEditScope(form, url, scope) {
    const notice = form.querySelector('[data-money-basis-changed]');
    if (!(notice instanceof HTMLElement)) throw new Error('The order edit basis notice is missing');
    let basis = scope.current();
    const reset = () => {
        basis = scope.current();
        scope.write(basis.value);
        form.hidden = !basis.allowed;
        notice.hidden = true;
    };
    const sync = () => {
        const latest = scope.current();
        form.hidden = !latest.allowed;
        if (JSON.stringify(scope.read()) === JSON.stringify(basis.value)) reset();
        else if (latest.version !== basis.version) notice.hidden = false;
    };
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (form.hidden) return;
        const submitted = scope.read();
        const result = await krtFetch.write({
            method: 'PATCH',
            url,
            payload: { ...submitted, version: basis.version },
            submitter: event.submitter,
            onError: (status) => status === 409,
        });
        if (result.ok && !result.redirected) {
            await refresh();
            if (JSON.stringify(scope.read()) === JSON.stringify(submitted)) reset();
            else basis = scope.current();
        } else if (result.status === 409) {
            await refresh();
            basis = scope.current();
            notice.hidden = false;
        }
    });
    return { reset, sync };
}
