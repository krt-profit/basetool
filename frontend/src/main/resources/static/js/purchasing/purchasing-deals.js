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

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#purchasing-deals-results',
        listUrl: '/purchasing/deals',
        section: 'deals',
    });

    const dealModal = document.getElementById('purchasing-deal-modal');
    const dealForm = document.getElementById('purchasing-deal-form');
    if (dealModal && dealForm) {
        const needSelect = select('purchasing-deal-need');
        const hint = document.getElementById('purchasing-deal-need-hint');

        /** Shows the open remainder and the price ceiling of the chosen need. */
        function showNeedHint() {
            const option = needSelect.selectedOptions[0];
            if (!hint || !option) return;
            hint.textContent = String(purchasingI18n.needHint)
                .replace('{0}', option.getAttribute('data-entry') || '')
                .replace('{1}', option.getAttribute('data-open') || '')
                .replace('{2}', option.getAttribute('data-max-price') || '–');
        }
        needSelect.addEventListener('change', showNeedHint);

        krtEvents.on('click', 'purchasing-open-deal', () => {
            /** @type {HTMLFormElement} */ (dealForm).reset();
            showNeedHint();
            krtModal.open(dealModal);
        });

        dealForm.addEventListener('submit', (event) => {
            event.preventDefault();
            sendWrite({
                method: 'POST',
                url: '/api/proxy/purchasing/deals',
                payload: {
                    needId: textOrNull(needSelect.value),
                    partnerId: textOrNull(select('purchasing-deal-partner').value),
                    amount: numberOrNull(field('purchasing-deal-amount').value),
                    unitPrice: numberOrNull(field('purchasing-deal-price').value),
                    paymentModel: select('purchasing-deal-model').value,
                    riskClass: textOrNull(select('purchasing-deal-class').value),
                    escortWish: field('purchasing-deal-escort').checked,
                    missionId: null,
                },
                successMessage: purchasingI18n.dealSaved,
                errorMessage: purchasingI18n.dealError,
                submitter: submitterOf(dealForm),
                afterWrite() {
                    krtModal.close(dealModal);
                    afterWrite();
                },
            });
        });
    }

    const pauseModal = document.getElementById('purchasing-pause-modal');
    const pauseForm = document.getElementById('purchasing-pause-form');
    if (pauseModal && pauseForm) {
        krtEvents.on('click', 'purchasing-open-pause', () => {
            /** @type {HTMLFormElement} */ (pauseForm).reset();
            krtModal.open(pauseModal);
        });
        pauseForm.addEventListener('submit', (event) => {
            event.preventDefault();
            sendWrite({
                method: 'POST',
                url: '/api/proxy/purchasing/pause',
                payload: {
                    orgUnitId: textOrNull(select('purchasing-pause-scope').value),
                    reason: select('purchasing-pause-reason').value,
                },
                successMessage: purchasingI18n.paused,
                errorMessage: purchasingI18n.pauseError,
                submitter: submitterOf(pauseForm),
                afterWrite() {
                    krtModal.close(pauseModal);
                    afterWrite();
                },
            });
        });
    }

    krtEvents.on('click', 'purchasing-resume', (button) => {
        sendWrite({
            method: 'POST',
            url: '/api/proxy/purchasing/pause/resume',
            payload: { orgUnitId: textOrNull(button.getAttribute('data-unit-id') || '') },
            successMessage: purchasingI18n.resumed,
            errorMessage: purchasingI18n.pauseError,
            submitter: button,
            afterWrite,
        });
    });
});
