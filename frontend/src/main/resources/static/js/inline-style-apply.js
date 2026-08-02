// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

/*
 * Applies dynamic, data-driven inline styles that cannot be a static CSS class — currently the
 * progress-bar widths that used to be a Thymeleaf `th:style="width: <expr>%"`. The computed numeric
 * value is rendered into a `data-krtm-width` attribute and applied here via `element.style.width`
 * (the CSSOM), which the CSP `style-src-attr` directive does NOT govern — so no inline `style`
 * attribute remains in the HTML and `style-src-attr 'unsafe-inline'` can be dropped.
 *
 * Runs on initial load and again after every krtFetch fragment swap (`krt:swapped`) so bars
 * re-rendered in place also get their width.
 */
(function () {
    'use strict';

    /**
     * Applies `data-krtm-width` to `style.width` for every matching element under `root`.
     *
     * @param {ParentNode} root subtree to scan (defaults to the whole document)
     */
    function applyWidths(root) {
        // Capability probe, not a type check: callers hand in whatever a swap
        // handed them, which is not always a ParentNode.
        const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
        scope.querySelectorAll('[data-krtm-width]').forEach(function (el) {
            const n = parseFloat(el.getAttribute('data-krtm-width') || '');
            /** @type {HTMLElement} */ (el).style.width = (isFinite(n) ? n : 0) + '%';
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            applyWidths(document);
        });
    } else {
        applyWidths(document);
    }

    // Re-apply after in-place fragment swaps (progress bars inside a swapped section).
    document.addEventListener('krt:swapped', function (event) {
        const container = event && event.detail && event.detail.container;
        applyWidths(container || document);
    });
})();
