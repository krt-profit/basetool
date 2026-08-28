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

/*
 * Admin-settings page module (/admin/settings), extracted verbatim from the former inline script of
 * admin-settings.html (ADR-0069, follow-up to #924).
 *
 * Per-squadron promotion + profit-eligibility toggles and the per-SK profit toggle each PATCH the
 * proxy endpoint on change with optimistic UI (flip the label, revert on error). The multi-setting
 * form saves in place (#582) through window.krtFetch and writes the five bumped optimistic-lock
 * versions back into the hidden inputs; without krtFetch the native POST->redirect runs (no-JS
 * fallback). All CSRF comes from the shared window.krtCsrf reader.
 *
 * The Thymeleaf-interpolated toast/label/conflict strings stay inline in the page bootstrap as the
 * MSG_* / SAVE_* / SAVE_CONFLICT globals this module reads.
 */

/* global MSG_ENABLED, MSG_DISABLED, MSG_ERROR, MSG_SAVED, MSG_PROFIT_ENABLED, MSG_PROFIT_DISABLED, MSG_PROFIT_ERROR, MSG_PROFIT_SAVED, SAVE_SUCCESS, SAVE_ERROR, SAVE_CONFLICT */

/*
 * Per-squadron promotion-feature toggle: PATCH /api/proxy/squadrons/{id}/promotion-enabled
 * on every checkbox change. Optimistic UI — flips the label text immediately, reverts on
 * error. CSP-safe (no inline onclick attributes) so the strict script-src-attr policy
 * stays intact.
 */

// Sourced from the shared window.krtCsrf reader (the single source of truth over the meta
// tags, epic #571) rather than re-reading the meta elements locally.
function csrfToken() {
    return (window.krtCsrf && window.krtCsrf.token()) || '';
}
function csrfHeader() {
    return (window.krtCsrf && window.krtCsrf.headerName()) || 'X-CSRF-TOKEN';
}

function patchPromotionEnabled(squadronId, enabled) {
    const headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader()] = csrfToken();
    return fetch('/api/proxy/squadrons/' + encodeURIComponent(squadronId) + '/promotion-enabled', {
        method: 'PATCH',
        headers: headers,
        body: JSON.stringify({ enabled: enabled }),
    });
}

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.squadron-promotion-toggle').forEach(function (checkbox) {
        checkbox.addEventListener('change', function () {
            const squadronId = checkbox.getAttribute('data-squadron-id');
            const enabled = checkbox.checked;
            const labelSpan = checkbox.parentElement
                ? checkbox.parentElement.querySelector('.toggle-state-text')
                : null;
            if (labelSpan) {
                labelSpan.textContent = enabled ? MSG_ENABLED : MSG_DISABLED;
            }
            patchPromotionEnabled(squadronId, enabled)
                .then(function (response) {
                    if (!response.ok) {
                        // Revert UI on error.
                        checkbox.checked = !enabled;
                        if (labelSpan) {
                            labelSpan.textContent = !enabled ? MSG_ENABLED : MSG_DISABLED;
                        }
                        if (window.showFrontendErrorToast) window.showFrontendErrorToast(MSG_ERROR);
                    } else if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(MSG_SAVED);
                    }
                })
                .catch(function () {
                    checkbox.checked = !enabled;
                    if (labelSpan) {
                        labelSpan.textContent = !enabled ? MSG_ENABLED : MSG_DISABLED;
                    }
                    if (window.showFrontendErrorToast) window.showFrontendErrorToast(MSG_ERROR);
                });
        });
    });
});

/*
 * Per-squadron profit-eligibility toggle: PATCH /api/proxy/squadrons/{id}/profit-eligible on
 * every checkbox change. Same optimistic-UI + CSP-safe pattern as the promotion toggle above.
 */

function patchProfitEligible(squadronId, eligible) {
    const headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader()] = csrfToken();
    return fetch('/api/proxy/squadrons/' + encodeURIComponent(squadronId) + '/profit-eligible', {
        method: 'PATCH',
        headers: headers,
        body: JSON.stringify({ eligible: eligible }),
    });
}

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.squadron-profit-toggle').forEach(function (checkbox) {
        checkbox.addEventListener('change', function () {
            const squadronId = checkbox.getAttribute('data-squadron-id');
            const eligible = checkbox.checked;
            const labelSpan = checkbox.parentElement
                ? checkbox.parentElement.querySelector('.toggle-state-text')
                : null;
            if (labelSpan) {
                labelSpan.textContent = eligible ? MSG_PROFIT_ENABLED : MSG_PROFIT_DISABLED;
            }
            patchProfitEligible(squadronId, eligible)
                .then(function (response) {
                    if (!response.ok) {
                        checkbox.checked = !eligible;
                        if (labelSpan) {
                            labelSpan.textContent = !eligible
                                ? MSG_PROFIT_ENABLED
                                : MSG_PROFIT_DISABLED;
                        }
                        if (window.showFrontendErrorToast)
                            window.showFrontendErrorToast(MSG_PROFIT_ERROR);
                    } else if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(MSG_PROFIT_SAVED);
                    }
                })
                .catch(function () {
                    checkbox.checked = !eligible;
                    if (labelSpan) {
                        labelSpan.textContent = !eligible
                            ? MSG_PROFIT_ENABLED
                            : MSG_PROFIT_DISABLED;
                    }
                    if (window.showFrontendErrorToast)
                        window.showFrontendErrorToast(MSG_PROFIT_ERROR);
                });
        });
    });
});

/*
 * Per-SK profit-eligibility toggle: PATCH /api/proxy/special-commands/{id}/profit-eligible.
 * Reuses the MSG_PROFIT_* labels (generic "berechtigt"/"nicht berechtigt").
 */
function patchSkProfitEligible(skId, eligible) {
    const headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader()] = csrfToken();
    return fetch('/api/proxy/special-commands/' + encodeURIComponent(skId) + '/profit-eligible', {
        method: 'PATCH',
        headers: headers,
        body: JSON.stringify({ eligible: eligible }),
    });
}

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.sk-profit-toggle').forEach(function (checkbox) {
        checkbox.addEventListener('change', function () {
            const skId = checkbox.getAttribute('data-sk-id');
            const eligible = checkbox.checked;
            const labelSpan = checkbox.parentElement
                ? checkbox.parentElement.querySelector('.toggle-state-text')
                : null;
            if (labelSpan) {
                labelSpan.textContent = eligible ? MSG_PROFIT_ENABLED : MSG_PROFIT_DISABLED;
            }
            patchSkProfitEligible(skId, eligible)
                .then(function (response) {
                    if (!response.ok) {
                        checkbox.checked = !eligible;
                        if (labelSpan) {
                            labelSpan.textContent = !eligible
                                ? MSG_PROFIT_ENABLED
                                : MSG_PROFIT_DISABLED;
                        }
                        if (window.showFrontendErrorToast)
                            window.showFrontendErrorToast(MSG_PROFIT_ERROR);
                    } else if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(MSG_PROFIT_SAVED);
                    }
                })
                .catch(function () {
                    checkbox.checked = !eligible;
                    if (labelSpan) {
                        labelSpan.textContent = !eligible
                            ? MSG_PROFIT_ENABLED
                            : MSG_PROFIT_DISABLED;
                    }
                    if (window.showFrontendErrorToast)
                        window.showFrontendErrorToast(MSG_PROFIT_ERROR);
                });
        });
    });
});

/*
 * In-place settings save (#582): the multi-setting form saves via the AJAX twin and writes the
 * five bumped optimistic-lock versions back into the hidden inputs so a second save does not
 * 409. A 422 validation problem+json carries a localized detail that krtFetch toasts directly;
 * an OPTIMISTIC_LOCK conflict triggers the reload-confirm. Without krtFetch the native
 * POST->redirect runs (no-JS fallback).
 */

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
                payload: payload,
                successMessage: SAVE_SUCCESS,
                errorMessage: SAVE_ERROR,
                conflict: SAVE_CONFLICT,
                onSuccess: function (body) {
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
