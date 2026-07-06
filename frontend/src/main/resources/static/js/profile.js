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
 * Profile page module, extracted verbatim from the former inline script of profile.html
 * (ADR-0069, follow-up to #924). One IIFE.
 *
 * Binds the three in-place profile forms (description/display-name, payout preference, global
 * blueprint sharing) to save through krtFetch without reloading and, on success, syncs every
 * hidden version input across the forms (they share one user-row @Version) so the next save
 * from either form does not 409.
 *
 * The Thymeleaf-interpolated toast/conflict labels (window.krtProfileI18n) stay inline in the
 * page bootstrap this module reads.
 */

(function () {
    'use strict';
    if (!window.krtFetch) {
        return;
    }
    const i18n = window.krtProfileI18n;

    // The description form and the payout-preference form share ONE user-row version; sync
    // every hidden version input on success so the next save (from either form) does not 409.
    function syncAllVersions(version) {
        if (version == null) {
            return;
        }
        document.querySelectorAll('input[name="version"]').forEach(function (input) {
            input.value = String(version);
        });
    }

    function bindInPlaceSave(formId, buildPayload) {
        const form = document.getElementById(formId);
        if (!form) {
            return;
        }
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            const versionInput = form.querySelector('input[name="version"]');
            window.krtFetch.write({
                method: 'POST',
                url: form.getAttribute('action'),
                payload: buildPayload(form, versionInput ? Number(versionInput.value) : 0),
                successMessage: i18n.saved,
                errorMessage: i18n.error,
                conflict: {
                    title: i18n.conflictTitle,
                    reloadLabel: i18n.conflictReload,
                    dismissLabel: i18n.conflictDismiss,
                    reloadQuestion: i18n.conflictQuestion,
                    reloadDetailFallback: i18n.conflictDetail,
                },
                onSuccess: function (body) {
                    if (body) {
                        syncAllVersions(body.version);
                    }
                },
            });
        });
    }

    bindInPlaceSave('profile-description-form', function (form, version) {
        return {
            description: form.querySelector('#description').value,
            displayName: form.querySelector('#displayName').value,
            version: version,
        };
    });
    bindInPlaceSave('profile-payout-form', function (form, version) {
        const select = form.querySelector('select[name="defaultPayoutPreference"]');
        return {
            defaultPayoutPreference: select ? select.value : null,
            version: version,
        };
    });
    bindInPlaceSave('profile-blueprint-sharing-form', function (form, version) {
        const checkbox = form.querySelector('input[name="shareBlueprintsGlobally"]');
        return {
            shareBlueprintsGlobally: checkbox ? checkbox.checked : false,
            version: version,
        };
    });
})();
