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

/* global SYNC_MSG */

document.addEventListener('DOMContentLoaded', function () {
    if (window.krtFetch) {
        window.krtFetch.bindSwap({ container: '#sync-results', history: true });
    }

    const form = document.getElementById('purge-form');
    const daysInput = document.getElementById('purge-days');
    const sourceInput = form ? form.querySelector('input[name="source"]') : null;
    const modal = document.getElementById('purge-confirm-modal');
    const trigger = document.getElementById('purge-trigger');
    const cancelBtn = document.getElementById('purge-cancel');
    const confirmBtn = document.getElementById('purge-confirm');
    const messageEl = document.getElementById('purge-confirm-message');

    if (!form || !daysInput || !modal || !trigger || !confirmBtn) {
        return;
    }

    const template = messageEl ? messageEl.getAttribute('data-template') || '' : '';

    function closeModal() {
        window.krtModal.close(modal);
    }

    trigger.addEventListener('click', function () {
        if (!daysInput.checkValidity()) {
            daysInput.reportValidity();
            return;
        }
        if (messageEl && template) {
            messageEl.textContent = template.replace('{0}', daysInput.value);
        }
        window.krtModal.open(modal);
    });

    if (cancelBtn) {
        cancelBtn.addEventListener('click', closeModal);
    }
    confirmBtn.addEventListener('click', function () {
        closeModal();
        if (!window.krtFetch) {
            form.requestSubmit();
            return;
        }
        const sourceValue = sourceInput ? sourceInput.value : '';
        const url =
            '/admin/sync-reports/delete-old?days=' +
            encodeURIComponent(daysInput.value) +
            '&source=' +
            encodeURIComponent(sourceValue);
        window.krtFetch.write({
            method: 'POST',
            url,
            toast: false,
            errorMessage: SYNC_MSG.deleteError,
            onSuccess(body) {
                const count = body && body.deleted != null ? body.deleted : 0;
                if (window.showFrontendSuccessToast) {
                    window.showFrontendSuccessToast(SYNC_MSG.successTemplate.replace('{0}', count));
                }
                if (window.krtFetch) {
                    window.krtFetch.swap({
                        url: window.location.pathname + window.location.search,
                        container: '#sync-results',
                        history: false,
                    });
                }
            },
        });
    });
    window.addEventListener('click', function (event) {
        if (event.target === modal) {
            closeModal();
        }
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeModal();
        }
    });
});
