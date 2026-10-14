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

import {
    field,
    numberOrNull,
    select,
    sendWrite,
    submitterOf,
    textOrNull,
} from '/js/purchasing/purchasing-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('purchasing-settings-form')
    );
    if (form) {
        form.addEventListener('submit', (event) => {
            event.preventDefault();
            const tiers = Array.from(form.querySelectorAll('input.purchasing-tier'))
                .map((input) => /** @type {HTMLInputElement} */ (input))
                .filter((input) => input.checked)
                .map((input) => input.value);
            const areas = Array.from(form.querySelectorAll('input.purchasing-area'))
                .map((input) => /** @type {HTMLInputElement} */ (input))
                .filter((input) => input.checked)
                .map((input) => input.value);
            sendWrite({
                method: 'PUT',
                url: '/api/proxy/purchasing/admin/settings',
                payload: {
                    enabled: field('purchasing-settings-enabled').checked,
                    governingAreaIds: areas,
                    fundingAccountId: textOrNull(select('purchasing-settings-account').value),
                    defaultMonthlyLimit: numberOrNull(field('purchasing-settings-limit').value),
                    budgetCap: numberOrNull(field('purchasing-settings-cap').value),
                    budgetPeriod: select('purchasing-settings-period').value,
                    settlementDueDays: numberOrNull(field('purchasing-settings-due').value),
                    allowedQualityTierIds: tiers,
                    version: numberOrNull(form.getAttribute('data-version') || ''),
                },
                successMessage: purchasingI18n.settingsSaved,
                errorMessage: purchasingI18n.settingsError,
                submitter: submitterOf(form),
                afterWrite(body) {
                    if (body && typeof body.version === 'number') {
                        form.setAttribute('data-version', String(body.version));
                    }
                },
            });
        });
    }

    const classes = /** @type {HTMLFormElement | null} */ (
        document.getElementById('purchasing-classes-form')
    );
    if (classes) {
        classes.addEventListener('submit', (event) => {
            event.preventDefault();
            const rows = Array.from(classes.querySelectorAll('tr[data-code]')).map(
                (row) => /** @type {HTMLElement} */ (row),
            );
            /**
             * Reads one input of a risk-class row.
             *
             * @param {HTMLElement} row the row
             * @param {string} selector the input selector
             * @returns {HTMLInputElement} the input
             */
            const input = (row, selector) =>
                /** @type {HTMLInputElement} */ (row.querySelector(selector));
            sendWrite({
                method: 'PUT',
                url: '/api/proxy/purchasing/admin/risk-classes',
                payload: {
                    classes: rows.map((row) => ({
                        code: row.getAttribute('data-code'),
                        maxTotal: numberOrNull(input(row, '.purchasing-class-total').value),
                        maxAdvancePercent: numberOrNull(
                            input(row, '.purchasing-class-advance').value,
                        ),
                        requiresKnownPartner: input(row, '.purchasing-class-known').checked,
                        escortSuggested: input(row, '.purchasing-class-escort').checked,
                        version: numberOrNull(row.getAttribute('data-version') || ''),
                    })),
                },
                successMessage: purchasingI18n.settingsSaved,
                errorMessage: purchasingI18n.settingsError,
                submitter: submitterOf(classes),
                afterWrite(body) {
                    if (!Array.isArray(body)) return;
                    body.forEach((entry) => {
                        const row = classes.querySelector(`tr[data-code="${entry.code}"]`);
                        if (row) row.setAttribute('data-version', String(entry.version));
                    });
                },
            });
        });
    }
});
