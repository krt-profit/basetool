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
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { bindFilterForm } from '/js/supply/supply-common.js';

document.addEventListener('DOMContentLoaded', () => {
    bindFilterForm({
        formId: 'supply-stats-filter',
        containerSelector: '#supply-stats-results',
    });

    if (!document.querySelector('#supply-stats-results')) return;
    krtLiveSync.createReceiver({
        topic: 'supply-needs',
        sections: { stats: { container: '#supply-stats-results' } },
        coalesceMs: 1500,
        refresh() {
            krtFetch.swap({
                url: `${window.location.pathname}${window.location.search}`,
                container: '#supply-stats-results',
                history: false,
            });
        },
    });
});
