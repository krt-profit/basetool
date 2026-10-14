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
import { showKrtConfirm } from '/js/kernel/toast.js';
import {
    bindResults,
    field,
    numberOrNull,
    select,
    sendWrite,
    submitterOf,
    textOrNull,
} from '/js/purchasing/purchasing-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#purchasing-delegates-results',
        listUrl: '/purchasing/delegates',
        section: 'deals',
    });

    const appointModal = document.getElementById('purchasing-appoint-modal');
    const appointForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('purchasing-appoint-form')
    );
    if (appointModal && appointForm) {
        krtEvents.on('click', 'purchasing-open-appoint', () => {
            appointForm.reset();
            krtModal.open(appointModal);
        });
        appointForm.addEventListener('submit', (event) => {
            event.preventDefault();
            const until = textOrNull(field('purchasing-appoint-until').value);
            sendWrite({
                method: 'POST',
                url: '/api/proxy/purchasing/delegates',
                payload: {
                    userId: textOrNull(select('purchasing-appoint-user').value),
                    validUntil: until ? `${until}T23:59:59Z` : null,
                    monthlyLimit: numberOrNull(field('purchasing-appoint-limit').value),
                },
                successMessage: purchasingI18n.delegateSaved,
                errorMessage: purchasingI18n.delegateError,
                submitter: submitterOf(appointForm),
                afterWrite() {
                    krtModal.close(appointModal);
                    afterWrite();
                },
            });
        });
    }

    const limitModal = document.getElementById('purchasing-limit-modal');
    const limitForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('purchasing-limit-form')
    );
    if (limitModal && limitForm) {
        krtEvents.on('click', 'purchasing-delegate-limit', (button) => {
            limitForm.reset();
            field('purchasing-limit-id').value = button.getAttribute('data-delegate-id') || '';
            field('purchasing-limit-version').value = button.getAttribute('data-version') || '';
            field('purchasing-limit-value').value = button.getAttribute('data-limit') || '';
            krtModal.open(limitModal);
        });
        limitForm.addEventListener('submit', (event) => {
            event.preventDefault();
            sendWrite({
                method: 'PUT',
                url: `/api/proxy/purchasing/delegates/${field('purchasing-limit-id').value}/limit`,
                payload: {
                    monthlyLimit: numberOrNull(field('purchasing-limit-value').value),
                    version: numberOrNull(field('purchasing-limit-version').value),
                },
                successMessage: purchasingI18n.delegateSaved,
                errorMessage: purchasingI18n.delegateError,
                submitter: submitterOf(limitForm),
                afterWrite() {
                    krtModal.close(limitModal);
                    afterWrite();
                },
            });
        });
    }

    krtEvents.on('click', 'purchasing-delegate-revoke', async (button) => {
        const confirmed = await showKrtConfirm(
            String(purchasingI18n.revokeTitle),
            String(purchasingI18n.revokeConfirm),
            String(purchasingI18n.revokeAction),
        );
        if (!confirmed) return;
        sendWrite({
            method: 'POST',
            url: `/api/proxy/purchasing/delegates/${button.getAttribute('data-delegate-id')}/revoke`,
            payload: { version: numberOrNull(button.getAttribute('data-version') || '') },
            successMessage: purchasingI18n.delegateSaved,
            errorMessage: purchasingI18n.delegateError,
            submitter: button,
            afterWrite,
        });
    });
});
