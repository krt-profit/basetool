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

const COLLAPSED_KEY = 'sc_links_collapsed_categories';
const TOGGLE_SELECTOR = '[data-trigger="sc-links-toggle-category"]';

/**
 * Reads the categories the member collapsed.
 *
 * @returns {string[]} the stored category keys, empty when nothing is stored or storage fails
 */
function readCollapsed() {
    try {
        const raw = localStorage.getItem(COLLAPSED_KEY);
        const parsed = raw === null ? [] : JSON.parse(raw);
        return Array.isArray(parsed) ? parsed.filter((key) => typeof key === 'string') : [];
    } catch (_e) {
        return [];
    }
}

/**
 * Stores the categories the member collapsed; silent when storage is unavailable.
 *
 * @param {string[]} keys the keys of the collapsed categories
 */
function writeCollapsed(keys) {
    try {
        localStorage.setItem(COLLAPSED_KEY, JSON.stringify(keys));
    } catch (_e) {}
}

/**
 * Opens or closes one category: the toggle's aria-expanded and the hidden state of its card grid.
 *
 * @param {Element} toggle the category's toggle button
 * @param {boolean} expanded whether the category is open afterwards
 */
function setExpanded(toggle, expanded) {
    const grid = document.getElementById(toggle.getAttribute('aria-controls') || '');
    toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    if (grid) grid.hidden = !expanded;
}

/** Stores the keys of all categories that are closed right now. */
function persist() {
    const collapsed = Array.from(document.querySelectorAll(TOGGLE_SELECTOR))
        .filter((toggle) => toggle.getAttribute('aria-expanded') === 'false')
        .map((toggle) => toggle.getAttribute('data-category') || '')
        .filter((key) => key !== '');
    writeCollapsed(collapsed);
}

/**
 * Flips one category open or closed and remembers the result.
 *
 * @param {Element} toggle the category's toggle button
 */
function toggleCategory(toggle) {
    setExpanded(toggle, toggle.getAttribute('aria-expanded') !== 'true');
    persist();
}

/** Closes the categories stored as collapsed; every other category stays open. */
function restore() {
    const collapsed = readCollapsed();
    document.querySelectorAll(TOGGLE_SELECTOR).forEach((toggle) => {
        if (collapsed.includes(toggle.getAttribute('data-category') || '')) {
            setExpanded(toggle, false);
        }
    });
}

restore();
krtEvents.on('click', 'sc-links-toggle-category', (toggle) => {
    toggleCategory(toggle);
});
