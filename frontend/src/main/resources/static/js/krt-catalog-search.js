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

/*
 * Shared server-side catalog-search sources for the searchable combobox (REQ-FE-016, design §6.6),
 * the catalog sibling of krt-user-search.js. Registers the `remoteSource` function the global
 * combobox auto-initialiser (krt-searchable-select.js) wires up by marker value, so a game-item
 * picker fetches only the matching bookable items on demand instead of preloading the multi-
 * thousand-entry catalog as <option>s:
 *
 *   - `data-krt-combobox="remote-game-items"` -> authenticated /inventory/item-search
 *
 * The proxy forwards to the backend bookable-item catalog (items that are the output of at least
 * one active blueprint, REQ-INV-029) with the session's bearer token attached server-side and
 * returns a flat list of item references; a failure degrades to an empty list so the picker shows
 * "no matches" rather than throwing. Loaded from head.html BEFORE krt-searchable-select.js so the
 * registry exists when the enhancer runs.
 */
(function () {
    'use strict';

    // Maps a backend game-item reference row to the combobox option shape. The label is the item's
    // display name; the id is the submitted value.
    function toOption(row) {
        return {
            value: row.id,
            label: row.name || row.id,
        };
    }

    // Fetches the matching bookable game items for `query` from the frontend proxy and maps them
    // to combobox options. Returns [] on any non-OK response or network error.
    function fetchGameItems(query) {
        return fetch('/inventory/item-search?q=' + encodeURIComponent(query || ''), {
            headers: { Accept: 'application/json' },
        })
            .then(function (response) {
                return response.ok ? response.json() : [];
            })
            .then(function (list) {
                return (list || []).map(toOption);
            })
            .catch(function () {
                return [];
            });
    }

    // Registry keyed by the `data-krt-combobox` marker value; read by krt-searchable-select.js.
    window.krtComboboxRemoteSources = window.krtComboboxRemoteSources || {};
    window.krtComboboxRemoteSources['remote-game-items'] = function (query) {
        return fetchGameItems(query);
    };
})();
