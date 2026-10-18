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

/* global MSG_ENABLED, MSG_DISABLED, MSG_ERROR, MSG_SAVED, MSG_PROFIT_ENABLED, MSG_PROFIT_DISABLED, MSG_PROFIT_ERROR, MSG_PROFIT_SAVED, SAVE_SUCCESS, SAVE_ERROR, SAVE_CONFLICT */

import { showFrontendErrorToast } from '/js/kernel/toast.js';
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

/**
 * Wires one family of optimistic boolean toggles: each change flips the label at once and PATCHes
 * through krtFetch.write (REQ-FE-002). On failure the checkbox and label are reverted and an error
 * toast is shown; a 409 is left to krtFetch's conflict handling.
 *
 * @param {string} selector the checkbox selector (e.g. '.squadron-promotion-toggle')
 * @param {string} idAttribute the attribute carrying the target id
 * @param {(id: string) => string} urlFor builds the PATCH URL for an id
 * @param {(checked: boolean) => object} bodyFor builds the JSON payload for the new state
 * @param {{ on: string, off: string, saved: string, error: string }} labels localized strings
 */
function wireToggle(selector, idAttribute, urlFor, bodyFor, labels) {
    /** @type {NodeListOf<HTMLInputElement>} */ (document.querySelectorAll(selector)).forEach(
        (checkbox) => {
            checkbox.addEventListener('change', () => {
                const id = checkbox.getAttribute(idAttribute) || '';
                const checked = checkbox.checked;
                const labelSpan = checkbox.parentElement
                    ? checkbox.parentElement.querySelector('.toggle-state-text')
                    : null;
                if (labelSpan) {
                    labelSpan.textContent = checked ? labels.on : labels.off;
                }
                function revert() {
                    checkbox.checked = !checked;
                    if (labelSpan) {
                        labelSpan.textContent = !checked ? labels.on : labels.off;
                    }
                }
                settings.write({
                    method: 'PATCH',
                    url: urlFor(id),
                    payload: bodyFor(checked),
                    submitter: checkbox,
                    successMessage: labels.saved,
                    errorMessage: labels.error,
                    onConflict() {
                        revert();
                        return false;
                    },
                    onError() {
                        revert();
                        showFrontendErrorToast(labels.error);
                        return true;
                    },
                    onNetworkError() {
                        revert();
                        return false;
                    },
                });
            });
        },
    );
}

document.addEventListener('DOMContentLoaded', () => {
    wireToggle(
        '.squadron-promotion-toggle',
        'data-squadron-id',
        (id) => {
            return `/api/proxy/squadrons/${encodeURIComponent(id)}/promotion-enabled`;
        },
        (checked) => {
            return { enabled: checked };
        },
        { on: MSG_ENABLED, off: MSG_DISABLED, saved: MSG_SAVED, error: MSG_ERROR },
    );
    const profitLabels = {
        on: MSG_PROFIT_ENABLED,
        off: MSG_PROFIT_DISABLED,
        saved: MSG_PROFIT_SAVED,
        error: MSG_PROFIT_ERROR,
    };
    wireToggle(
        '.squadron-profit-toggle',
        'data-squadron-id',
        (id) => {
            return `/api/proxy/squadrons/${encodeURIComponent(id)}/profit-eligible`;
        },
        (checked) => {
            return { eligible: checked };
        },
        profitLabels,
    );
    wireToggle(
        '.sk-profit-toggle',
        'data-sk-id',
        (id) => {
            return `/api/proxy/special-commands/${encodeURIComponent(id)}/profit-eligible`;
        },
        (checked) => {
            return { eligible: checked };
        },
        profitLabels,
    );
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
