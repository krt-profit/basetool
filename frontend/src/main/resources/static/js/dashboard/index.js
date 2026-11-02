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

import { krtEvents } from '/js/kernel/event-delegation.js';
import { initDashboardEdit } from '/js/dashboard/dashboard-edit.js';
import { initWidgetActions, loadAll, wireLiveUpdates } from '/js/dashboard/dashboard-widgets.js';

/** The browser-side flag that keeps the "Anpassen" hint dismissed. */
const HINT_DISMISSED_KEY = 'krt.dashboard.hint';

/**
 * Whether the member dismissed the hint in this browser.
 *
 * @returns {boolean} true once dismissed; false when nothing is stored or storage is unavailable
 */
function hintDismissed() {
    try {
        return window.localStorage.getItem(HINT_DISMISSED_KEY) === '1';
    } catch (_error) {
        return false;
    }
}

/** Shows the one-time hint unless it was dismissed, and wires its dismiss control. */
function initHint() {
    const hint = document.querySelector('[data-dashboard-hint]');
    if (!(hint instanceof HTMLElement)) return;
    hint.hidden = hintDismissed();
    krtEvents.on('click', 'dashboard-hint-dismiss', () => {
        hint.remove();
        try {
            window.localStorage.setItem(HINT_DISMISSED_KEY, '1');
        } catch (_error) {}
    });
}

document.addEventListener('DOMContentLoaded', () => {
    initHint();
    initWidgetActions();
    initDashboardEdit();
    wireLiveUpdates();
    loadAll();
});
