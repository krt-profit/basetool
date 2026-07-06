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
 * Promotion-overview page module (/promotion), extracted verbatim from the former inline script
 * of promotion-overview.html (ADR-0069, follow-up to #924).
 *
 * Wires the expand-all / collapse-all toolbar buttons for the rank-group <details> blocks through
 * the delegated window.krtEvents bus. Native <details> already handles the per-group toggle, so the
 * only added behaviour is the bulk action; there is no persistence.
 *
 * The block carried no Thymeleaf interpolation, so there is no inline bootstrap: the whole script
 * moved here unchanged (its `th:unless="${isAllSquadronsMode}"` gate now rides on the th:src tag).
 */

/*
 * Expand-all / collapse-all helpers for the rank-group <details> blocks.
 * Native <details> already handles the per-group toggle; we only need to
 * automate the bulk action. No persistence — the "you are here" group is
 * always re-opened on load, which is the only state we want to remember.
 */
function poToggleAll(openState) {
    const nodes = document.querySelectorAll('details.rank-group');
    nodes.forEach(function (d) {
        d.open = openState;
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
