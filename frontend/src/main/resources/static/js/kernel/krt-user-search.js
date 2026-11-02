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
 * A user search hit; a candidate hit also carries the unit that marks a non-member.
 *
 * @typedef {{ id: string, effectiveName?: string, displayName?: string, username?: string,
 *     unitShorthand?: string | null, unitName?: string | null }} UserSearchHit
 */

/**
 * Maps a user search hit to a combobox option.
 *
 * @param {UserSearchHit} row the search hit
 * @returns {{ value: string, label: string }} the option
 */
function toOption(row) {
    return {
        value: row.id,
        label: row.effectiveName || row.displayName || row.username || row.id,
    };
}

/**
 * Maps a candidate hit to a combobox option whose label names the unit of a non-member.
 *
 * @param {UserSearchHit} row the candidate hit
 * @returns {{ value: string, label: string }} the option
 */
function toCandidateOption(row) {
    const option = toOption(row);
    const unit = row.unitShorthand || row.unitName;
    return unit ? { value: option.value, label: `${option.label} (${unit})` } : option;
}

/**
 * Searches users through the given endpoint.
 *
 * @param {string} path the search endpoint
 * @param {string} [query] the search text
 * @param {(row: UserSearchHit) => { value: string, label: string }} [map] the hit-to-option mapping
 * @returns {Promise<{ value: string, label: string }[]>} the matching options; empty on failure
 */
function fetchUsers(path, query, map = toOption) {
    return krtFetch
        .getJson(`${path}?query=${encodeURIComponent(query || '')}`)
        .then((list) => {
            return (list || []).map(map);
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
krtComboboxRemoteSources['remote-user-candidates'] = function (query, select) {
    const path = select?.dataset.comboboxSourceUrl;
    return path ? fetchUsers(path, query, toCandidateOption) : Promise.resolve([]);
};
