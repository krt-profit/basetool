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

const INVENTORY_INDEX_SECTIONS = {
    stock: { container: '#inventory-results', fragmentValue: 'results' },
};

document.addEventListener('DOMContentLoaded', function () {
    if (window.krtFetch) {
        window.krtFetch.bindSwap({ container: '#inventory-results', history: true });
    }
    if (
        window.krtLiveSync &&
        typeof window.krtLiveSync.createReceiver === 'function' &&
        window.krtFetch &&
        typeof window.krtFetch.swap === 'function' &&
        document.getElementById('inventory-results')
    ) {
        window.krtLiveSync.createReceiver({
            topic: 'inventory',
            sections: INVENTORY_INDEX_SECTIONS,
            coalesceMs: 1500,
            refresh() {
                window.krtFetch.swap({
                    url: window.location.pathname + window.location.search,
                    container: '#inventory-results',
                    fragmentValue: INVENTORY_INDEX_SECTIONS.stock.fragmentValue,
                    history: false,
                });
            },
        });
    }
});
