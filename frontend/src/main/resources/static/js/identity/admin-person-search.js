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

import { krtFetch } from '/js/kernel/krt-fetch.js';

const RESULTS_ID = 'person-search-results';

document.addEventListener('submit', (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || form.id !== 'person-search-form') {
        return;
    }
    event.preventDefault();
    /** @type {HTMLInputElement | null} */
    const input = form.querySelector('input[name="q"]');
    const term = input ? input.value.trim() : '';
    const action = form.getAttribute('action') || '/admin/person-search';
    const url = action + (term ? `?q=${encodeURIComponent(term)}` : '');
    krtFetch.swap({ url, container: `#${RESULTS_ID}`, history: true });
});

krtFetch.bindSwap({ container: `#${RESULTS_ID}`, history: true });
