// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

(function () {
    'use strict';

    const filterForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('admin-terms-filter-form')
    );
    const results = document.getElementById('admin-terms-results');

    if (!results || !window.krtFetch) {
        return;
    }

    /**
     * Swaps the results fragment for the given URL.
     *
     * @param {string} url the overview URL carrying the wanted filter and page
     */
    function swapResults(url) {
        window.krtFetch.swap({ url, container: results, history: true });
    }

    if (filterForm) {
        filterForm.addEventListener('submit', function (event) {
            event.preventDefault();
            const params = new URLSearchParams();
            for (const [key, value] of new FormData(filterForm).entries()) {
                if (typeof value === 'string' && value !== '') {
                    params.append(key, value);
                }
            }
            const query = params.toString();
            swapResults('/admin/terms' + (query ? '?' + query : ''));
        });

        const filterSelect = filterForm.querySelector('#admin-terms-filter');
        if (filterSelect) {
            filterSelect.addEventListener('change', function () {
                filterForm.requestSubmit();
            });
        }
    }

    results.addEventListener('click', function (event) {
        const target = /** @type {HTMLElement} */ (event.target);
        const link = target.closest('a.admin-terms-page');
        if (!link) {
            return;
        }
        event.preventDefault();
        swapResults(/** @type {HTMLAnchorElement} */ (link).getAttribute('href') || '/admin/terms');
    });
})();
