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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';

document.addEventListener('DOMContentLoaded', () => {
    krtFetch.bindSwap({ container: '#inventory-results', history: true });
    krtPageSections.create({
        topicClass: 'INVENTORY_ALL',
        sections: {
            stock: { container: '#inventory-results', fragmentValue: 'results' },
        },
        pageUrl() {
            return window.location.pathname + window.location.search;
        },
        topic: 'inventory',
        receiver: document.getElementById('inventory-results') ? { coalesceMs: 1500 } : undefined,
    });
});
