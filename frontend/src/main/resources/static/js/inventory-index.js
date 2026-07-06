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
 * Inventory-index page module, extracted verbatim from the former inline script of
 * inventory-index.html (ADR-0069, follow-up to #924).
 *
 * Binds the in-place pager for the pagination-only inventory list so paging swaps the
 * #inventory-results fragment in place with history (epic #571 / #573) instead of reloading.
 */

document.addEventListener('DOMContentLoaded', function () {
    if (window.krtFetch) {
        // Pagination-only list (no filter): bind the in-place pager so paging stays in place
        // (epic #571 / #573). The clickable rows use the delegated navigate-href trigger, so
        // they keep working after a swap.
        window.krtFetch.bindSwap({ container: '#inventory-results', history: true });
    }
});
