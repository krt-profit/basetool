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

/** The header naming the version that answered (`kernel/web/AppVersionHeaderFilter`). */
const VERSION_HEADER = 'X-Basetool-Version';

/** The meta tag naming the version the page was rendered with (`kernel/fragments/head.html`). */
const PAGE_VERSION_SELECTOR = 'meta[name="krt-app-version"]';

/**
 * Reads the version the page was rendered with.
 *
 * @returns {string} the version; empty when the page names none
 */
function pageVersion() {
    const meta = document.querySelector(PAGE_VERSION_SELECTOR);
    return meta ? meta.getAttribute('content') || '' : '';
}

/**
 * Tells whether an answer comes from another version than the one the page was rendered with: a
 * release replaced the frontend while the page stayed open (REQ-FE-042).
 *
 * @param {Response} response the answer
 * @returns {boolean} true when both versions are known and differ
 */
export function servesAnotherVersion(response) {
    const served = response.headers.get(VERSION_HEADER);
    if (!served) {
        return false;
    }
    const own = pageVersion();
    return own !== '' && served !== own;
}
