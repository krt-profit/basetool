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

/* global supplyI18n */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import {
    bindKindToggle,
    bindResults,
    field,
    numberOrNull,
    select,
    sendWrite,
    textOrNull,
} from '/js/supply/supply-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#supply-incoming-results',
        listUrl: '/supply/donations/incoming',
        section: 'donations',
    });

    const results = document.getElementById('supply-incoming-results');
    const unitId = results ? results.getAttribute('data-unit-id') || '' : '';

    const receiveModal = document.getElementById('supply-receive-modal');
    const receiveForm = document.getElementById('supply-receive-form');
    if (receiveModal && receiveForm) {
        krtEvents.on('click', 'supply-donation-receive', (button) => {
            /** @type {HTMLFormElement} */ (receiveForm).reset();
            field('supply-receive-id').value = button.getAttribute('data-donation-id') || '';
            field('supply-receive-version').value = button.getAttribute('data-version') || '';
            field('supply-receive-amount').value = button.getAttribute('data-amount') || '';
            const entry = document.getElementById('supply-receive-entry');
            if (entry) entry.textContent = button.getAttribute('data-entry') || '';
            krtModal.open(receiveModal);
        });
        receiveForm.addEventListener('submit', (event) => {
            event.preventDefault();
            sendWrite({
                method: 'POST',
                url: `/api/proxy/supply/donations/${field('supply-receive-id').value}/receive`,
                payload: {
                    receivedAmount: numberOrNull(field('supply-receive-amount').value),
                    locationId: textOrNull(select('supply-receive-location').value),
                    version: numberOrNull(field('supply-receive-version').value),
                },
                successMessage: supplyI18n.received,
                errorMessage: supplyI18n.donationError,
                submitter: /** @type {HTMLButtonElement | null} */ (
                    receiveForm.querySelector('button[type="submit"]')
                ),
                afterWrite() {
                    krtModal.close(receiveModal);
                    afterWrite();
                },
            });
        });
    }

    const declineModal = document.getElementById('supply-decline-modal');
    const declineForm = document.getElementById('supply-decline-form');
    if (declineModal && declineForm) {
        krtEvents.on('click', 'supply-donation-decline', (button) => {
            field('supply-decline-id').value = button.getAttribute('data-donation-id') || '';
            field('supply-decline-version').value = button.getAttribute('data-version') || '';
            krtModal.open(declineModal);
        });
        declineForm.addEventListener('submit', (event) => {
            event.preventDefault();
            sendWrite({
                method: 'POST',
                url: `/api/proxy/supply/donations/${field('supply-decline-id').value}/decline`,
                payload: {
                    reason: select('supply-decline-reason').value,
                    version: numberOrNull(field('supply-decline-version').value),
                },
                successMessage: supplyI18n.declined,
                errorMessage: supplyI18n.donationError,
                submitter: /** @type {HTMLButtonElement | null} */ (
                    declineForm.querySelector('button[type="submit"]')
                ),
                afterWrite() {
                    krtModal.close(declineModal);
                    afterWrite();
                },
            });
        });
    }

    const recordModal = document.getElementById('supply-record-modal');
    const recordForm = document.getElementById('supply-record-form');
    if (recordModal && recordForm) {
        bindKindToggle(
            'supply-record-kind',
            'supply-record-material-row',
            'supply-record-item-row',
            ['supply-record-quality-row'],
        );
        const needSelect = select('supply-record-need');
        const catalogue = document.getElementById('supply-record-catalogue');
        /** Hides the catalogue picker while a need fixes the material or item. */
        function syncNeed() {
            if (catalogue) catalogue.hidden = needSelect.value !== '';
        }
        needSelect.addEventListener('change', syncNeed);
        krtEvents.on('click', 'supply-open-record', () => {
            /** @type {HTMLFormElement} */ (recordForm).reset();
            syncNeed();
            krtModal.open(recordModal);
        });
        recordForm.addEventListener('submit', (event) => {
            event.preventDefault();
            const needId = textOrNull(needSelect.value);
            const isItem = select('supply-record-kind').value === 'item';
            sendWrite({
                method: 'POST',
                url: '/api/proxy/supply/donations/recorded',
                payload: {
                    receivingOrgUnitId: unitId,
                    needId,
                    materialId:
                        needId || isItem
                            ? null
                            : textOrNull(select('supply-record-material').value),
                    gameItemId:
                        needId || !isItem ? null : textOrNull(select('supply-record-item').value),
                    quality:
                        needId || isItem
                            ? null
                            : numberOrNull(field('supply-record-quality').value),
                    amount: numberOrNull(field('supply-record-amount').value),
                    donorUserId: textOrNull(select('supply-record-donor').value),
                    locationId: textOrNull(select('supply-record-location').value),
                },
                successMessage: supplyI18n.recorded,
                errorMessage: supplyI18n.donationError,
                submitter: /** @type {HTMLButtonElement | null} */ (
                    recordForm.querySelector('button[type="submit"]')
                ),
                afterWrite() {
                    krtModal.close(recordModal);
                    afterWrite();
                },
            });
        });
    }
});
