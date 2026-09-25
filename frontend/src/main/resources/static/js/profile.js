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

(function () {
    'use strict';
    if (!window.krtFetch) {
        return;
    }
    const i18n = window.krtProfileI18n;

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
                onSuccess(body) {
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
            version,
        };
    });
    bindInPlaceSave('profile-payout-form', function (form, version) {
        const select = form.querySelector('select[name="defaultPayoutPreference"]');
        return {
            defaultPayoutPreference: select ? select.value : null,
            version,
        };
    });
    bindInPlaceSave('profile-blueprint-sharing-form', function (form, version) {
        const checkbox = form.querySelector('input[name="shareBlueprintsGlobally"]');
        return {
            shareBlueprintsGlobally: checkbox ? checkbox.checked : false,
            version,
        };
    });

    const DELETION_URL = '/profile/deletion-request';

    function refreshDeletionCard() {
        if (!window.krtFetch.swap) {
            return undefined;
        }
        return window.krtFetch.swap({
            url: DELETION_URL,
            container: '#profile-deletion-host',
            fragmentValue: 'card',
            errorMessage: i18n.deletionError,
        });
    }

    const deletionSubmit = document.getElementById('profile-deletion-submit');
    if (deletionSubmit) {
        deletionSubmit.addEventListener('click', function () {
            /** @type {HTMLInputElement | null} */
            const eraseHistory = document.querySelector('#profile-deletion-erase-history');
            window.krtFetch.write({
                method: 'POST',
                url: DELETION_URL,
                payload: { eraseHistory: eraseHistory ? eraseHistory.checked : false },
                successMessage: i18n.deletionRequested,
                errorMessage: i18n.deletionError,
                onSuccess() {
                    window.krtModal.close('profile-deletion-modal');
                    if (eraseHistory) {
                        eraseHistory.checked = false;
                    }
                    return refreshDeletionCard();
                },
            });
        });
    }

    const deletionHost = document.getElementById('profile-deletion-host');
    if (deletionHost) {
        deletionHost.addEventListener('click', function (event) {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }
            if (!target.closest('#profile-deletion-withdraw')) {
                return;
            }
            window.krtFetch.write({
                method: 'DELETE',
                url: DELETION_URL,
                successMessage: i18n.deletionWithdrawn,
                errorMessage: i18n.deletionError,
                onSuccess: refreshDeletionCard,
            });
        });
    }
})();
