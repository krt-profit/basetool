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

// @ts-check

/*
 * Admin Personensuche (REQ-SEC-060, ADR-0184). One IIFE, classic non-module script — ADR-0069.
 *
 * The search submit swaps the results block in place (REQ-FE-002) instead of reloading; the GET
 * form is the no-JS fallback and needs no JavaScript to work at all, which matters because this
 * page is used while serving a legal request.
 *
 * `history: true` keeps the address bar in step, so a search can be re-run by refreshing and the
 * URL can be pasted into a case note. That does put the searched name in the browser history —
 * which is the same exposure as any search box, and the alternative (a POST) would make the page
 * un-refreshable in the middle of handling a request.
 */

(function () {
    'use strict';

    const RESULTS_ID = 'person-search-results';

    // Delegated on document so the binding survives the results block being swapped, and so the
    // form keeps working if it is ever moved inside the fragment.
    document.addEventListener('submit', function (event) {
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || form.id !== 'person-search-form') {
            return;
        }
        event.preventDefault();
        /** @type {HTMLInputElement | null} */
        const input = form.querySelector('input[name="q"]');
        const term = input ? input.value.trim() : '';
        const action = form.getAttribute('action') || '/admin/person-search';
        const url = action + (term ? '?q=' + encodeURIComponent(term) : '');
        if (window.krtFetch) {
            window.krtFetch.swap({ url, container: '#' + RESULTS_ID, history: true });
        } else {
            window.location.assign(url);
        }
    });

    if (window.krtFetch) {
        window.krtFetch.bindSwap({ container: '#' + RESULTS_ID, history: true });
    }
})();
