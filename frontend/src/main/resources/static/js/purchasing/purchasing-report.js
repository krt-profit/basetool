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

document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('purchasing-forum-modal');
    const area = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('purchasing-forum-text')
    );
    if (!modal || !area) return;

    krtEvents.on('click', 'purchasing-forum-text', async (button) => {
        const query = new URLSearchParams();
        const from = button.getAttribute('data-from');
        const to = button.getAttribute('data-to');
        if (from) query.set('from', from);
        if (to) query.set('to', to);
        const response = await krtFetch.get(
            `/api/proxy/purchasing/report/forum-text?${query.toString()}`,
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
        await navigator.clipboard.writeText(area.value);
        showFrontendSuccessToast(String(purchasingI18n.forumCopied));
    });
});
