// @ts-check
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

    /**
     * Copies a swapped list's total into every page-head count chip bound to that list: the chip's
     * `data-list-count-for` names the results container, whose `[data-list-total]` carries the
     * total; a list without one (an empty result) counts zero.
     */
    document.addEventListener('krt:swapped', (event) => {
        const detail = /** @type {CustomEvent} */ (event).detail;
        const container = detail && detail.container instanceof Element ? detail.container : null;
        if (!container || !container.id) return;
        const chips = document.querySelectorAll('[data-list-count-for]');
        for (let i = 0; i < chips.length; i++) {
            const chip = chips[i];
            if (chip.getAttribute('data-list-count-for') !== container.id) continue;
            const source = container.querySelector('[data-list-total]');
            chip.textContent = source ? source.getAttribute('data-list-total') || '0' : '0';
        }
    });
})();
