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

/* global purchasingI18n */

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { bindFilterForm, field } from '/js/purchasing/purchasing-common.js';

/**
 * Builds the query of the chosen period from the two month fields.
 *
 * @returns {URLSearchParams} the query, without an empty month
 */
function periodQuery() {
    const query = new URLSearchParams();
    const from = field('purchasing-report-from').value;
    const to = field('purchasing-report-to').value;
    if (from) query.set('from', from);
    if (to) query.set('to', to);
    return query;
}

/** Points the CSV export at the period currently chosen in the filter. */
function syncExportLink() {
    const link = document.getElementById('purchasing-report-csv');
    if (!link) return;
    const query = periodQuery().toString();
    link.setAttribute('href', `/api/proxy/purchasing/report/export${query ? `?${query}` : ''}`);
}

document.addEventListener('DOMContentLoaded', () => {
    bindFilterForm({
        formSelector: '#purchasing-report-filter',
        containerSelector: '#purchasing-report-results',
        listUrl: '/purchasing/report',
        onApply: syncExportLink,
    });

    const modal = document.getElementById('purchasing-forum-modal');
    const area = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('purchasing-forum-text')
    );
    if (!modal || !area) return;

    krtEvents.on('click', 'purchasing-forum-text', async () => {
        const response = await krtFetch.get(
            `/api/proxy/purchasing/report/forum-text?${periodQuery().toString()}`,
            { accept: 'text/plain' },
        );
        if (!response || !response.ok) {
            showFrontendErrorToast(String(purchasingI18n.forumError));
            return;
        }
        area.value = await response.text();
        krtModal.open(modal);
    });

    krtEvents.on('click', 'purchasing-forum-copy', async () => {
        area.select();
        try {
            await navigator.clipboard.writeText(area.value);
        } catch (_err) {
            showFrontendErrorToast(String(purchasingI18n.clipboardError));
            return;
        }
        showFrontendSuccessToast(String(purchasingI18n.forumCopied));
    });
});
