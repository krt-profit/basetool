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

function poToggleAll(openState) {
    const nodes = document.querySelectorAll('details.rank-group');
    nodes.forEach(function (d) {
        /** @type {HTMLDetailsElement} */ (d).open = openState;
    });
}
document.addEventListener('DOMContentLoaded', function () {
    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'po-expand-all', function () {
            poToggleAll(true);
        });
        window.krtEvents.on('click', 'po-collapse-all', function () {
            poToggleAll(false);
        });
    }
});
