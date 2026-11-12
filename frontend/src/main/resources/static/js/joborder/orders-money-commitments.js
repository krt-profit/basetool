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
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

const modalId = 'order-money-commitment-modal';

/** Binds the editor on a money order detail page. */
export function initMoneyCommitments() {
    const form = document.getElementById('order-money-commitment-form');
    if (form instanceof HTMLFormElement) bindCommitmentForm(form);
}

/**
 * Refreshes the available choices from the latest participation projection.
 *
 * @param {HTMLSelectElement} select the choice field
 * @param {string} templateId the source template in the participation section
 * @param {string} value the option to keep selected
 */
function updateCommitmentOptions(select, templateId, value) {
    const source = document.getElementById(templateId);
    select.replaceChildren();
    if (source instanceof HTMLTemplateElement) {
        select.append(source.content.cloneNode(true));
    }
    select.value = value;
}

/**
 * Restores the explicitly selected occurrence against the current authorised choices.
 *
 * @param {HTMLSelectElement} select the provision or top-up attribution field
 * @param {string} desired the occurrence to keep selected
 */
export function refreshAttributionChoices(select, desired) {
    updateCommitmentOptions(select, 'order-money-provision-commitments', desired);
    if (select.options.length === 0) {
        select.add(new Option(select.dataset.unattributed, ''));
    }
    if (desired && !select.value) {
        const unavailable = new Option(select.dataset.unavailable, desired, true, true);
        unavailable.disabled = true;
        select.add(unavailable);
        select.value = desired;
    }
}

/**
 * Reads the chosen attribution without silently losing an unavailable selection.
 *
 * @param {HTMLSelectElement} select the provision or top-up attribution field
 * @returns {{commitmentId?: string} | null} normalized choice or a stale selection
 */
export function readAttributionChoice(select) {
    if (select.selectedOptions.item(0)?.disabled) return null;
    return select.value ? { commitmentId: select.value } : {};
}

/** @param {HTMLFormElement} form the stable editor outside the replaceable section */
function bindCommitmentForm(form) {
    const unit = form.elements.namedItem('unitId');
    const amount = form.elements.namedItem('amount');
    const warning = form.querySelector('[data-commitment-changed]');
    const current = form.querySelector('[data-commitment-current]');
    const review = form.querySelector('[data-commitment-review]');
    const end = form.querySelector('[data-commitment-end]');
    const save = form.querySelector('[data-testid="order-money-commitment-save"]');
    if (
        !(unit instanceof HTMLSelectElement) ||
        !(amount instanceof HTMLInputElement) ||
        !(warning instanceof HTMLElement) ||
        !(current instanceof HTMLElement) ||
        !(review instanceof HTMLButtonElement) ||
        !(end instanceof HTMLButtonElement) ||
        !(save instanceof HTMLButtonElement)
    )
        return;

    let saving = false;
    let needsReview = false;
    krtNumberInput.enhance(amount);

    const selected = () => unit.selectedOptions.item(0);

    const selectedBasis = () => {
        const option = selected();
        return JSON.stringify([
            option?.value,
            option?.dataset.commitmentId,
            option?.dataset.version,
        ]);
    };

    const render = () => {
        warning.hidden = !needsReview;
        current.textContent = selected()?.dataset.current || '';
        current.hidden = !current.textContent;
        review.hidden = !unit.value;
        end.hidden = !selected()?.dataset.commitmentId;
        end.disabled = save.disabled = saving || needsReview || !unit.value;
    };

    /** @param {string} value the requested unit */
    const loadUnits = (value) =>
        updateCommitmentOptions(unit, 'order-money-commitment-units', value);

    krtEvents.on('click', 'od-money-commitment-open', (element) => {
        if (!(element instanceof HTMLElement) || saving) return;
        form.reset();
        loadUnits(element.dataset.unitId || '');
        amount.value = selected()?.dataset.outstanding || '';
        needsReview = false;
        render();
        krtModal.open(modalId);
    });

    unit.addEventListener('change', () => {
        amount.value = selected()?.dataset.outstanding || '';
        needsReview = false;
        render();
    });

    review.addEventListener('click', () => {
        needsReview = false;
        render();
        (unit.value ? amount : unit).focus();
    });

    document.addEventListener('krt:swapped', (event) => {
        if (
            event.detail.container?.id !== 'order-participations-results' ||
            !krtModal.isOpen(modalId)
        )
            return;
        const before = selectedBasis();
        loadUnits(unit.value);
        needsReview ||= !!amount.value && before !== selectedBasis();
        render();
    });

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const { orderId, error } = form.dataset;
        const option = selected();
        const unitId = option?.value;
        const isEnd =
            event.submitter instanceof HTMLButtonElement && event.submitter.value === 'end';
        const value = isEnd ? '0' : amount.value;
        if (
            saving ||
            needsReview ||
            !orderId ||
            !option ||
            !unitId ||
            !value ||
            !joborderHooks.writeOrder
        )
            return;
        const { commitmentId, version } = option.dataset;
        saving = true;
        render();
        try {
            await joborderHooks.writeOrder({
                method: 'PUT',
                url: `/orders/${encodeURIComponent(orderId)}/participations/${encodeURIComponent(unitId)}/money-commitment`,
                payload: {
                    amount: value,
                    commitmentId,
                    version: version === undefined ? undefined : Number(version),
                },
                sectionKey: 'participations',
                refresh: ['participations', 'money'],
                errorMessage: error,
                onConflict() {
                    needsReview = true;
                    render();
                    void joborderHooks.refreshOrderSection?.('participations', {
                        broadcast: false,
                    });
                    return true;
                },
                onSuccess() {
                    krtModal.close(modalId);
                    krtPageSections.publish('orders', ['queue']);
                },
            });
        } finally {
            saving = false;
            render();
        }
    });
}
