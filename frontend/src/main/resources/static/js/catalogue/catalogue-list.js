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

/**
 * Wires a paginated catalogue list (REQ-ADMIN-001): the page links and page-size links inside the
 * results container swap it in place, and the search form swaps it after a pause in typing, on a
 * picked suggestion or on submit, keeping the address bar in step.
 *
 * @param {object} cfg the wiring
 * @param {string} cfg.formSelector the selector of the search form
 * @param {string} cfg.containerSelector the selector of the results container
 * @param {string} cfg.listUrl the path of the list page
 */
export function bindCatalogueList(cfg) {
    const container = document.querySelector(cfg.containerSelector);
    if (!container) return;
    krtFetch.bindSwap({
        container: cfg.containerSelector,
        fragmentValue: 'results',
        history: true,
    });

    const form = /** @type {HTMLFormElement | null} */ (document.querySelector(cfg.formSelector));
    if (!form) return;
    /** @type {number | undefined} */
    let timer;
    let applied = currentQuery(form);

    /** Swaps the results for the current search, unless it is the one already shown. */
    const apply = () => {
        clearTimeout(timer);
        const query = currentQuery(/** @type {HTMLFormElement} */ (form));
        if (query === applied) return;
        applied = query;
        krtFetch.swap({
            url: `${cfg.listUrl}${query ? `?${query}` : ''}`,
            container: cfg.containerSelector,
            fragmentValue: 'results',
            history: true,
        });
    };

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        apply();
    });
    form.addEventListener('change', apply);
    form.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(apply, 300);
    });
    document.addEventListener('krt:swapped', (event) => {
        const detail = /** @type {CustomEvent<{ container?: Element }>} */ (event).detail;
        if (!detail || detail.container !== container) return;
        queueMicrotask(() => {
            const size = new URLSearchParams(window.location.search).get('size');
            const sizeInput = /** @type {HTMLInputElement | null} */ (
                form.querySelector('input[name="size"]')
            );
            if (size && sizeInput) sizeInput.value = size;
            applied = currentQuery(/** @type {HTMLFormElement} */ (form));
        });
    });
}

/**
 * Serialises the non-empty fields of the search form, trimmed, as a query string.
 *
 * @param {HTMLFormElement} form the search form
 * @returns {string} the query string without a leading question mark
 */
function currentQuery(form) {
    const params = new URLSearchParams();
    for (const [key, value] of new FormData(form).entries()) {
        const text = String(value).trim();
        if (text !== '') params.append(key, text);
    }
    return params.toString();
}
