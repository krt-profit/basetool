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

/* global SAVE_SUCCESS, SAVE_ERROR, SAVE_CONFLICT */

import { krtPageSections } from '/js/kernel/krt-page-sections.js';

const settings = krtPageSections.create({
    dict() {
        return {
            'conflict.title': SAVE_CONFLICT.title,
            'conflict.detail': SAVE_CONFLICT.reloadDetailFallback,
            'conflict.reload': SAVE_CONFLICT.reloadLabel,
            'conflict.dismiss': SAVE_CONFLICT.dismissLabel,
            'conflict.question': SAVE_CONFLICT.reloadQuestion,
        };
    },
    keys: {
        conflictTitleKey: 'conflict.title',
        reloadDetailKey: 'conflict.detail',
        reloadLabelKey: 'conflict.reload',
        dismissLabelKey: 'conflict.dismiss',
        reloadQuestionKey: 'conflict.question',
    },
});

document.addEventListener('DOMContentLoaded', () => {
    const settingsForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('admin-settings-form')
    );
    if (!settingsForm) {
        return;
    }
    settingsForm.addEventListener('submit', (event) => {
        event.preventDefault();

        const el = /** @type {Record<string, HTMLInputElement>} */ (
            /** @type {unknown} */ (settingsForm.elements)
        );
        const payload = {
            ageYellowDays: el['ageYellowDays'].value,
            ageYellowVersion: Number(el['ageYellowVersion'].value),
            ageRedDays: el['ageRedDays'].value,
            ageRedVersion: Number(el['ageRedVersion'].value),
            refineryRoundingMode: el['refineryRoundingMode'].value,
            refineryRoundingVersion: Number(el['refineryRoundingVersion'].value),
            transferFeePercent: el['transferFeePercent'].value,
            transferFeeVersion: Number(el['transferFeeVersion'].value),
        };
        const submitBtn = /** @type {HTMLButtonElement | null} */ (
            settingsForm.querySelector('button[type="submit"]')
        );
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        settings
            .write({
                method: 'POST',
                url: settingsForm.getAttribute('action') || '',
                payload,
                successMessage: SAVE_SUCCESS,
                errorMessage: SAVE_ERROR,
                onSuccess(body) {
                    if (!body) {
                        return;
                    }
                    /**
                     * @param {string} name the form control name
                     * @param {any} value the value from the response body
                     */
                    function setVer(name, value) {
                        if (el[name] && value != null) {
                            el[name].value = value;
                        }
                    }
                    setVer('ageYellowVersion', body.ageYellowVersion);
                    setVer('ageRedVersion', body.ageRedVersion);
                    setVer('refineryRoundingVersion', body.refineryRoundingVersion);
                    setVer('transferFeeVersion', body.transferFeeVersion);
                    if (body.transferFeePercent != null && el['transferFeePercent']) {
                        el['transferFeePercent'].value = body.transferFeePercent;
                    }
                },
            })
            .finally(() => {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    });
});
