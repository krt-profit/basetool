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

(function () {
    'use strict';

    const inboxEl = document.getElementById('notification-inbox');
    const toolbarEl = document.getElementById('notifications-toolbar');
    if (!inboxEl || !toolbarEl) return;
    const inbox = inboxEl;
    const toolbar = toolbarEl;

    const FILTER_PREF_KEY = 'notifications_filter';
    const VIEWS = ['UNREAD', 'ALL'];

    /**
     * The radios of the unread / all segment.
     *
     * @returns {HTMLInputElement[]} the radios, in document order
     */
    function viewInputs() {
        return Array.from(
            /** @type {NodeListOf<HTMLInputElement>} */ (
                toolbar.querySelectorAll('input[name="filter"]')
            ),
        );
    }

    /**
     * The selected view.
     *
     * @returns {string} `UNREAD` or `ALL`
     */
    function currentView() {
        const checked = viewInputs().find((el) => el.checked);
        return checked ? checked.value : 'UNREAD';
    }

    /**
     * Checks the radio of one view.
     *
     * @param {string} value `UNREAD` or `ALL`
     */
    function selectView(value) {
        viewInputs().forEach((el) => {
            el.checked = el.value === value;
        });
    }

    /** Lets the stylesheet hide read rows while the unread view is selected. */
    function applyView() {
        inbox.setAttribute('data-notif-filter', currentView().toLowerCase());
    }

    /**
     * The stored view, or `null` when none is stored or storage is unavailable.
     *
     * @returns {string | null} a known view
     */
    function readStoredView() {
        try {
            const raw = localStorage.getItem(FILTER_PREF_KEY);
            const saved = raw === null ? null : JSON.parse(raw);
            return saved && typeof saved.view === 'string' && VIEWS.indexOf(saved.view) >= 0
                ? saved.view
                : null;
        } catch (_e) {
            return null;
        }
    }

    /** Stores the selected view (REQ-UI-017). */
    function persistView() {
        try {
            localStorage.setItem(FILTER_PREF_KEY, JSON.stringify({ view: currentView() }));
        } catch (_e) {}
    }

    viewInputs().forEach((el) => {
        el.addEventListener('change', () => {
            applyView();
            persistView();
        });
    });

    const stored = readStoredView();
    if (stored && stored !== currentView()) selectView(stored);
    applyView();
})();
