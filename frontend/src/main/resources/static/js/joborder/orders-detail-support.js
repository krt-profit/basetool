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

const STORAGE_KEY = 'krt.orders.detail.support.open';
const PANEL_SELECTOR = 'details[data-od-support]';

/**
 * Reads whether the viewer last left the Staffel-support panel open.
 *
 * @returns {boolean} true when the panel was left open; false by default or without storage
 */
function readOpen() {
    try {
        return localStorage.getItem(STORAGE_KEY) === 'true';
    } catch {
        return false;
    }
}

/**
 * Remembers the panel's open state for this browser.
 *
 * @param {boolean} open whether the panel is open
 */
function writeOpen(open) {
    try {
        localStorage.setItem(STORAGE_KEY, String(open));
    } catch {
        return;
    }
}

/** Applies the remembered open state to the rendered panel, if the page shows one. */
function restorePanel() {
    const panel = document.querySelector(PANEL_SELECTOR);
    if (panel instanceof HTMLDetailsElement) {
        panel.open = readOpen();
    }
}

document.addEventListener(
    'toggle',
    (event) => {
        const panel = event.target;
        if (panel instanceof HTMLDetailsElement && panel.matches(PANEL_SELECTOR)) {
            writeOpen(panel.open);
        }
    },
    true,
);

document.addEventListener('krt:swapped', (event) => {
    if (event.detail.container?.id === 'order-participations-results') {
        restorePanel();
    }
});

restorePanel();
