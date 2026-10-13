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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { field, numberOrNull, select, sendWrite, textOrNull } from '/js/supply/supply-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('supply-settings-form');
    if (form) {
        form.addEventListener('submit', (event) => {
            event.preventDefault();
            sendWrite({
                method: 'PUT',
                url: '/api/proxy/supply/admin/settings',
                payload: {
                    enabled: field('supply-settings-enabled').checked,
                    governingAreaId: textOrNull(select('supply-settings-area').value),
                },
                successMessage: supplyI18n.settingsSaved,
                errorMessage: supplyI18n.settingsError,
                submitter: /** @type {HTMLButtonElement | null} */ (
                    form.querySelector('button[type="submit"]')
                ),
                afterWrite() {},
            });
        });
    }

    krtEvents.on('change', 'supply-unit-change', (checkbox) => {
        const row = /** @type {HTMLElement | null} */ (checkbox.closest('tr[data-unit-id]'));
        if (!row) return;
        const raise = /** @type {HTMLInputElement} */ (row.querySelector('.supply-unit-raise'));
        const accept = /** @type {HTMLInputElement} */ (row.querySelector('.supply-unit-accept'));
        krtFetch.write({
            method: 'PUT',
            url: `/api/proxy/supply/admin/units/${row.getAttribute('data-unit-id')}`,
            payload: {
                mayRaiseNeeds: raise.checked,
                acceptsDonations: accept.checked,
                version: numberOrNull(row.getAttribute('data-version') || ''),
            },
            successMessage: supplyI18n.settingsSaved,
            errorMessage: supplyI18n.settingsError,
            onSuccess(body) {
                if (body && typeof body.version === 'number') {
                    row.setAttribute('data-version', String(body.version));
                }
            },
        });
    });
});
