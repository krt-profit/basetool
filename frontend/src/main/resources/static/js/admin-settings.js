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
    document.querySelectorAll(selector).forEach(function (checkbox) {
        checkbox.addEventListener('change', function () {
            if (!window.krtFetch) {
                return;
            }
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
            window.krtFetch.write({
                method: 'PATCH',
                url: urlFor(id),
                payload: bodyFor(checked),
                submitter: checkbox,
                successMessage: labels.saved,
                errorMessage: labels.error,
                conflict: SAVE_CONFLICT,
                onError(status) {
                    revert();
                    if (status === 409) {
                        return false;
                    }
                    if (window.showFrontendErrorToast) window.showFrontendErrorToast(labels.error);
                    return true;
                },
                onNetworkError() {
                    revert();
                    return false;
                },
            });
        });
    });
}

document.addEventListener('DOMContentLoaded', function () {
    wireToggle(
        '.squadron-promotion-toggle',
        'data-squadron-id',
        function (id) {
            return '/api/proxy/squadrons/' + encodeURIComponent(id) + '/promotion-enabled';
        },
        function (checked) {
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
        function (id) {
            return '/api/proxy/squadrons/' + encodeURIComponent(id) + '/profit-eligible';
        },
        function (checked) {
            return { eligible: checked };
        },
        profitLabels,
    );
    wireToggle(
        '.sk-profit-toggle',
        'data-sk-id',
        function (id) {
            return '/api/proxy/special-commands/' + encodeURIComponent(id) + '/profit-eligible';
        },
        function (checked) {
            return { eligible: checked };
        },
        profitLabels,
    );
});

document.addEventListener('DOMContentLoaded', function () {
    const settingsForm = document.getElementById('admin-settings-form');
    if (!settingsForm) {
        return;
    }
    settingsForm.addEventListener('submit', function (event) {
        event.preventDefault();
        if (!window.krtFetch) {
            settingsForm.submit();
            return;
        }
        const el = settingsForm.elements;
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
        const submitBtn = settingsForm.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: settingsForm.getAttribute('action'),
                payload,
                successMessage: SAVE_SUCCESS,
                errorMessage: SAVE_ERROR,
                conflict: SAVE_CONFLICT,
                onSuccess(body) {
                    if (!body) {
                        return;
                    }
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
            .finally(function () {
                if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    });
});
