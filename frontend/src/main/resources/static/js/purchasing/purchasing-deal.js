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

/* global purchasingI18n */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import {
    bindResults,
    field,
    numberOrNull,
    select,
    sendWrite,
    submitterOf,
    textOrNull,
} from '/js/purchasing/purchasing-common.js';

/** @type {Record<string, string[]>} */
const KINDS_BY_MODEL = {
    ADVANCE: ['ADVANCE', 'FINAL'],
    ON_DELIVERY: ['ON_DELIVERY', 'FINAL'],
    PARTIAL: ['ADVANCE', 'ON_DELIVERY', 'FINAL'],
};

/**
 * Looks up a modal and its form; a page without the trade dialogs returns null.
 *
 * @param {string} modalId the dialog id
 * @param {string} formId the form id
 * @returns {{ modal: HTMLElement, form: HTMLFormElement } | null} the pair, or null
 */
function dialog(modalId, formId) {
    const modal = document.getElementById(modalId);
    const form = /** @type {HTMLFormElement | null} */ (document.getElementById(formId));
    return modal && form ? { modal, form } : null;
}

/**
 * Sends a write against the deal and refreshes the detail afterwards.
 *
 * @param {object} opts the request
 * @param {string} opts.method the HTTP method
 * @param {string} opts.path the path below the deal's proxy URL
 * @param {string} opts.dealId the deal
 * @param {object | undefined} opts.payload the JSON body
 * @param {string} opts.success the localized success toast
 * @param {string} opts.error the localized error text
 * @param {HTMLElement | null} opts.submitter the control to disable while the request runs
 * @param {() => void} opts.afterWrite called after a successful write
 * @param {HTMLElement | null} [opts.modal] the dialog to close on success
 */
function writeDeal(opts) {
    sendWrite({
        method: opts.method,
        url: `/api/proxy/purchasing/deals/${opts.dealId}${opts.path}`,
        payload: opts.payload,
        successMessage: opts.success,
        errorMessage: opts.error,
        submitter: opts.submitter,
        afterWrite() {
            if (opts.modal) krtModal.close(opts.modal);
            opts.afterWrite();
        },
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#purchasing-deal-detail',
        listUrl: window.location.pathname,
        section: 'deals',
        fragment: 'detail',
    });

    const edit = dialog('purchasing-edit-modal', 'purchasing-edit-form');
    if (edit) {
        krtEvents.on('click', 'purchasing-edit-deal', (button) => {
            edit.form.reset();
            field('purchasing-edit-id').value = button.getAttribute('data-deal-id') || '';
            field('purchasing-edit-version').value = button.getAttribute('data-version') || '';
            select('purchasing-edit-partner').value = button.getAttribute('data-partner-id') || '';
            field('purchasing-edit-amount').value = button.getAttribute('data-amount') || '';
            field('purchasing-edit-price').value = button.getAttribute('data-unit-price') || '';
            select('purchasing-edit-model').value = button.getAttribute('data-model') || 'ADVANCE';
            field('purchasing-edit-escort').checked = button.getAttribute('data-escort') === 'true';
            krtModal.open(edit.modal);
        });
        edit.form.addEventListener('submit', (event) => {
            event.preventDefault();
            writeDeal({
                method: 'PUT',
                path: '',
                dealId: field('purchasing-edit-id').value,
                payload: {
                    partnerId: textOrNull(select('purchasing-edit-partner').value),
                    amount: numberOrNull(field('purchasing-edit-amount').value),
                    unitPrice: numberOrNull(field('purchasing-edit-price').value),
                    paymentModel: select('purchasing-edit-model').value,
                    riskClass: textOrNull(select('purchasing-edit-class').value),
                    escortWish: field('purchasing-edit-escort').checked,
                    missionId: null,
                    version: numberOrNull(field('purchasing-edit-version').value),
                },
                success: purchasingI18n.dealSaved,
                error: purchasingI18n.dealError,
                submitter: submitterOf(edit.form),
                afterWrite,
                modal: edit.modal,
            });
        });
    }

    const tranche = dialog('purchasing-tranche-modal', 'purchasing-tranche-form');
    if (tranche) {
        krtEvents.on('click', 'purchasing-request-tranche', (button) => {
            tranche.form.reset();
            field('purchasing-tranche-deal').value = button.getAttribute('data-deal-id') || '';
            const allowed = KINDS_BY_MODEL[button.getAttribute('data-model') || ''] || [];
            const kind = select('purchasing-tranche-kind');
            Array.from(kind.options).forEach((option) => {
                option.hidden = !allowed.includes(option.value);
                option.disabled = option.hidden;
            });
            if (allowed.length > 0) kind.value = allowed[0];
            field('purchasing-tranche-amount').value = button.getAttribute('data-open') || '';
            krtModal.open(tranche.modal);
        });
        tranche.form.addEventListener('submit', (event) => {
            event.preventDefault();
            writeDeal({
                method: 'POST',
                path: '/tranches',
                dealId: field('purchasing-tranche-deal').value,
                payload: {
                    kind: select('purchasing-tranche-kind').value,
                    amount: numberOrNull(field('purchasing-tranche-amount').value),
                },
                success: purchasingI18n.trancheRequested,
                error: purchasingI18n.trancheError,
                submitter: submitterOf(tranche.form),
                afterWrite,
                modal: tranche.modal,
            });
        });
    }

    const delivery = dialog('purchasing-delivery-modal', 'purchasing-delivery-form');
    if (delivery) {
        krtEvents.on('click', 'purchasing-record-delivery', (button) => {
            delivery.form.reset();
            field('purchasing-delivery-deal').value = button.getAttribute('data-deal-id') || '';
            field('purchasing-delivery-amount').value = button.getAttribute('data-open') || '';
            krtModal.open(delivery.modal);
        });
        delivery.form.addEventListener('submit', (event) => {
            event.preventDefault();
            writeDeal({
                method: 'POST',
                path: '/deliveries',
                dealId: field('purchasing-delivery-deal').value,
                payload: {
                    amount: numberOrNull(field('purchasing-delivery-amount').value),
                    locationId: textOrNull(select('purchasing-delivery-location').value),
                },
                success: purchasingI18n.deliveryRecorded,
                error: purchasingI18n.deliveryError,
                submitter: submitterOf(delivery.form),
                afterWrite,
                modal: delivery.modal,
            });
        });
    }

    krtEvents.on('click', 'purchasing-close-delivery', (button) => {
        writeDeal({
            method: 'POST',
            path: '/close-delivery',
            dealId: button.getAttribute('data-deal-id') || '',
            payload: undefined,
            success: purchasingI18n.deliveryClosed,
            error: purchasingI18n.deliveryError,
            submitter: button,
            afterWrite,
        });
    });

    const accept = dialog('purchasing-accept-modal', 'purchasing-accept-form');
    if (accept) {
        const rejected = field('purchasing-accept-rejected');
        const accepted = field('purchasing-accept-accepted');
        const reasonRow = document.getElementById('purchasing-accept-reason-row');
        const acceptForm = accept.form;
        const total = document.getElementById('purchasing-accept-total');

        /** Shows the reason only while a part is rejected and keeps the two parts balanced. */
        function syncRejected() {
            const delivered = Number(acceptForm.getAttribute('data-amount') || '0');
            const rejectedValue = numberOrNull(rejected.value) || 0;
            if (reasonRow) reasonRow.hidden = rejectedValue <= 0;
            accepted.value = String(
                Math.max(0, Math.round((delivered - rejectedValue) * 1000) / 1000),
            );
        }
        rejected.addEventListener('input', syncRejected);

        krtEvents.on('click', 'purchasing-accept-delivery', (button) => {
            accept.form.reset();
            accept.form.setAttribute('data-amount', button.getAttribute('data-amount') || '0');
            field('purchasing-accept-deal').value = button.getAttribute('data-deal-id') || '';
            field('purchasing-accept-delivery').value =
                button.getAttribute('data-delivery-id') || '';
            field('purchasing-accept-version').value = button.getAttribute('data-version') || '';
            accepted.value = button.getAttribute('data-amount') || '';
            if (total) {
                total.textContent = String(purchasingI18n.totalDelivered)
                    .replace('{0}', button.getAttribute('data-amount') || '0')
                    .replace('{1}', button.getAttribute('data-unit') || '');
            }
            const isMaterial = button.getAttribute('data-material') === 'true';
            const qualityRow = document.getElementById('purchasing-accept-quality-row');
            if (qualityRow) qualityRow.hidden = !isMaterial;
            const qualityHint = document.getElementById('purchasing-accept-quality-hint');
            if (qualityHint) {
                const minQuality = button.getAttribute('data-min-quality') || '0';
                qualityHint.textContent = isMaterial
                    ? String(purchasingI18n.qualityMin).replace('{0}', minQuality)
                    : '';
            }
            const location = button.getAttribute('data-location-id') || '';
            if (location) select('purchasing-accept-location').value = location;
            if (reasonRow) reasonRow.hidden = true;
            krtModal.open(accept.modal);
        });
        accept.form.addEventListener('submit', (event) => {
            event.preventDefault();
            const dealId = field('purchasing-accept-deal').value;
            const deliveryId = field('purchasing-accept-delivery').value;
            const rejectedValue = numberOrNull(rejected.value) || 0;
            writeDeal({
                method: 'POST',
                path: `/deliveries/${deliveryId}/accept`,
                dealId,
                payload: {
                    acceptedAmount: numberOrNull(accepted.value),
                    rejectedAmount: rejectedValue,
                    rejectReason:
                        rejectedValue > 0 ? select('purchasing-accept-reason').value : null,
                    measuredQuality: numberOrNull(field('purchasing-accept-quality').value),
                    locationId: textOrNull(select('purchasing-accept-location').value),
                    version: numberOrNull(field('purchasing-accept-version').value),
                },
                success: purchasingI18n.deliveryDecided,
                error: purchasingI18n.deliveryError,
                submitter: submitterOf(accept.form),
                afterWrite,
                modal: accept.modal,
            });
        });
    }

    const settle = dialog('purchasing-settle-modal', 'purchasing-settle-form');
    if (settle) {
        const loss = field('purchasing-settle-loss');
        const paid = field('purchasing-settle-paid');
        const reasonRow = document.getElementById('purchasing-settle-reason-row');
        const received = document.getElementById('purchasing-settle-received');

        /** Shows the loss reason only while a loss is entered. */
        function syncLoss() {
            if (reasonRow) reasonRow.hidden = (numberOrNull(loss.value) || 0) <= 0;
        }
        loss.addEventListener('input', syncLoss);

        krtEvents.on('click', 'purchasing-settle', (button) => {
            settle.form.reset();
            field('purchasing-settle-deal').value = button.getAttribute('data-deal-id') || '';
            field('purchasing-settle-version').value = button.getAttribute('data-version') || '';
            paid.value = button.getAttribute('data-paid') || '';
            loss.value = button.getAttribute('data-loss') || '0';
            if (received) {
                const format = new Intl.NumberFormat(document.documentElement.lang || undefined);
                const amount = format.format(Number(button.getAttribute('data-received') || '0'));
                received.textContent = String(purchasingI18n.receivedOf).replace('{0}', amount);
            }
            syncLoss();
            krtModal.open(settle.modal);
        });
        settle.form.addEventListener('submit', (event) => {
            event.preventDefault();
            const lossValue = numberOrNull(loss.value) || 0;
            writeDeal({
                method: 'PUT',
                path: '/settlement',
                dealId: field('purchasing-settle-deal').value,
                payload: {
                    moneyPaid: numberOrNull(paid.value),
                    lossAmount: lossValue,
                    lossReason: lossValue > 0 ? select('purchasing-settle-reason').value : null,
                    version: numberOrNull(field('purchasing-settle-version').value),
                },
                success: purchasingI18n.settled,
                error: purchasingI18n.settleError,
                submitter: submitterOf(settle.form),
                afterWrite,
                modal: settle.modal,
            });
        });
    }

    const cancel = dialog('purchasing-cancel-modal', 'purchasing-cancel-form');
    if (cancel) {
        krtEvents.on('click', 'purchasing-cancel-deal', (button) => {
            field('purchasing-cancel-deal').value = button.getAttribute('data-deal-id') || '';
            field('purchasing-cancel-version').value = button.getAttribute('data-version') || '';
            krtModal.open(cancel.modal);
        });
        cancel.form.addEventListener('submit', (event) => {
            event.preventDefault();
            writeDeal({
                method: 'POST',
                path: '/cancel',
                dealId: field('purchasing-cancel-deal').value,
                payload: { version: numberOrNull(field('purchasing-cancel-version').value) },
                success: purchasingI18n.dealCancelled,
                error: purchasingI18n.dealError,
                submitter: submitterOf(cancel.form),
                afterWrite,
                modal: cancel.modal,
            });
        });
    }
});
