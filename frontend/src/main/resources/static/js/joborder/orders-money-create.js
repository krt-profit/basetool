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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtNumberInput } from '/js/kernel/krt-number-input.js';

const form = document.querySelector('#mode-auec form');
const input = document.getElementById('auec-target');

if (form instanceof HTMLFormElement && input instanceof HTMLInputElement) {
    krtNumberInput.enhance(input);
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        void createOrder(form, input, event.submitter);
    });
}

/**
 * Opens the shared order detail on successful creation.
 *
 * @param {HTMLFormElement} form the creation form
 * @param {HTMLInputElement} input the target input
 * @param {HTMLElement | null} submitter the activated control
 */
async function createOrder(form, input, submitter) {
    if (!input.reportValidity()) return;
    const fields = new FormData(form);
    /** @type {ApiDto<'CreateAuecJobOrderDto'>} */
    const payload = {
        responsibleOrgUnitId: String(fields.get('responsibleOrgUnitId')),
        requestingOrgUnitId: String(fields.get('requestingOrgUnitId')),
        handle: String(fields.get('handle')),
        comment: String(fields.get('comment')).trim() || undefined,
        target: String(fields.get('target')),
    };
    const result = await krtFetch.write({
        url: form.action,
        method: 'POST',
        payload,
        submitter,
        toast: false,
        errorMessage: form.dataset.error || '',
    });
    if (!result.ok || result.redirected) return;
    /** @type {ApiDto<'JobOrderDto'> | null} */
    const order = result.body;
    if (order?.id) {
        window.location.assign(`/orders/${order.id}`);
    } else {
        showFrontendErrorToast(form.dataset.error || '');
    }
}
