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
 * Sync-reports admin page module (/admin/sync-reports), extracted verbatim from the former
 * inline script of admin/sync-reports.html (ADR-0069, follow-up to #924).
 *
 * In-place pager swap of the #sync-results table and the purge-old-reports confirm modal: the
 * confirm posts source/days as query params through window.krtFetch, toasts the localized count
 * and re-swaps the results in place; without krtFetch the native form submit runs (no-JS
 * fallback).
 *
 * The interpolated success-template + error strings stay inline in the page bootstrap as the
 * SYNC_MSG global this module reads.
 */

/* global SYNC_MSG */

document.addEventListener('DOMContentLoaded', function () {
    // Intercept the prev/next pager anchors inside #sync-results so paging swaps in place
    // (REQ-FE-002). The table rows carry no per-element handlers, so nothing needs re-init on
    // krt:swapped. Independent of the purge-modal wiring below (which may early-return).
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
        modal.style.display = 'none';
    }

    trigger.addEventListener('click', function () {
        if (!daysInput.checkValidity()) {
            daysInput.reportValidity();
            return;
        }
        if (messageEl && template) {
            messageEl.textContent = template.replace('{0}', daysInput.value);
        }
        modal.style.display = 'flex';
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
        // @RequestParam binds from the query string regardless of the JSON content-type that
        // krtFetch sends, so pass source/days as query params and omit the body.
        const sourceValue = sourceInput ? sourceInput.value : '';
        const url =
            '/admin/sync-reports/delete-old?days=' +
            encodeURIComponent(daysInput.value) +
            '&source=' +
            encodeURIComponent(sourceValue);
        window.krtFetch.write({
            method: 'POST',
            url: url,
            toast: false,
            errorMessage: SYNC_MSG.deleteError,
            onSuccess: function (body) {
                const count = body && body.deleted != null ? body.deleted : 0;
                if (window.showFrontendSuccessToast) {
                    window.showFrontendSuccessToast(SYNC_MSG.successTemplate.replace('{0}', count));
                }
                // Refresh the results table in place for the active tab. The "Gesamt"
                // total sits outside the swap fragment and refreshes on the next
                // navigation — acceptable for a rare maintenance purge.
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
