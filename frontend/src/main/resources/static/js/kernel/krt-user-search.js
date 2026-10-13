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
import { krtComboboxRemoteSources } from '/js/kernel/krt-combobox-sources.js';

/**
 * Maps a user search hit to a combobox option.
 *
 * @param {{ id: string, effectiveName?: string, displayName?: string, username?: string }} row
 *     the search hit
 * @returns {{ value: string, label: string }} the option
 */
function toOption(row) {
    return {
        value: row.id,
        label: row.effectiveName || row.displayName || row.username || row.id,
    };
}

/**
 * Searches users through the given endpoint.
 *
 * @param {string} path the search endpoint
 * @param {string} [query] the search text
 * @returns {Promise<{ value: string, label: string }[]>} the matching options; empty on failure
 */
function fetchUsers(path, query) {
    return krtFetch
        .getJson(`${path}?query=${encodeURIComponent(query || '')}`)
        .then((list) => {
            return (list || []).map(toOption);
        })
        .catch(() => {
            return [];
        });
}

krtComboboxRemoteSources['remote-users'] = function (query) {
    return fetchUsers('/users/search', query);
};
krtComboboxRemoteSources['remote-bank-users'] = function (query) {
    return fetchUsers('/users/search-bank', query);
};
