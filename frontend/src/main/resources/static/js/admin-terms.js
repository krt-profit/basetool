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

    if (!filterForm) {
        return;
    }
    const form = filterForm;

    /**
     * Tells whether an event target is one of the filter segment's radios.
     *
     * @param {EventTarget | null} target the changed element
     * @returns {boolean} true for an `input[name="filter"]` inside the filter form
     */
    function isFilterRadio(target) {
        return (
            target instanceof HTMLInputElement && target.name === 'filter' && form.contains(target)
        );
    }

    if (!results || !window.krtFetch) {
        form.addEventListener('change', (event) => {
            if (isFilterRadio(event.target)) {
                form.submit();
            }
        });
        return;
    }

    const krtFetch = window.krtFetch;
    krtFetch.bindSwap({ container: results, history: true });

    form.addEventListener('change', (event) => {
        if (!isFilterRadio(event.target)) {
            return;
        }
        const params = new URLSearchParams();
        for (const [key, value] of new FormData(form).entries()) {
            if (typeof value === 'string' && value !== '') {
                params.append(key, value);
            }
        }
        const query = params.toString();
        krtFetch.swap({
            url: `/admin/terms${query ? `?${query}` : ''}`,
            container: results,
            history: true,
        });
    });
})();
