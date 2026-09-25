// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

(function () {
    'use strict';

    /**
     * Applies `data-krtm-width` to `style.width` for every matching element under `root`.
     *
     * @param {ParentNode} root subtree to scan (defaults to the whole document)
     */
    function applyWidths(root) {
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

    document.addEventListener('krt:swapped', function (event) {
        const container = event && event.detail && event.detail.container;
        applyWidths(container || document);
    });
})();
