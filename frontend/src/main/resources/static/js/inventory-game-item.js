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

/*
 * Per-game-item inventory drilldown page module (templates/inventory-game-item.html,
 * REQ-INV-030) — the item sibling of inventory-material.js. Binds the in-place pager for the
 * server-side paginated results table (REQ-INV-033) so a page/size click swaps the results
 * fragment in place with history, and joins the shared "inventory" live-sync room (REQ-FE-015)
 * so a peer's stock change anywhere re-fetches this read-only drilldown's results table in place,
 * without a reload. The page has no mutations of its own, so it only receives.
 */

// Single source of truth for the receiver map; the one key mirrors the
// LiveSyncTopicClass.INVENTORY_ALL whitelist (parity-checked at build time).
const INVENTORY_GAME_ITEM_SECTIONS = {
    stock: { container: '#inventory-game-item-results', fragmentValue: 'results' },
};

document.addEventListener('DOMContentLoaded', function () {
    if (window.krtFetch) {
        // In-place pager + page-size picker (REQ-INV-033): paging swaps the results fragment in
        // place with history, so the URL always names the visible page and the live-sync refresh
        // below re-fetches exactly that page.
        window.krtFetch.bindSwap({ container: '#inventory-game-item-results', history: true });
    }
    if (
        window.krtLiveSync &&
        typeof window.krtLiveSync.createReceiver === 'function' &&
        window.krtFetch &&
        typeof window.krtFetch.swap === 'function' &&
        document.getElementById('inventory-game-item-results')
    ) {
        window.krtLiveSync.createReceiver({
            topic: 'inventory',
            sections: INVENTORY_GAME_ITEM_SECTIONS,
            coalesceMs: 1500,
            refresh: function () {
                window.krtFetch.swap({
                    url: window.location.pathname + window.location.search,
                    container: '#inventory-game-item-results',
                    fragmentValue: INVENTORY_GAME_ITEM_SECTIONS.stock.fragmentValue,
                    history: false,
                });
            },
        });
    }
});
