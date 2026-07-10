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
 * Shared server-side user-search sources for the searchable combobox (REQ-FE-011, ADR-0053 +
 * ADR-0087, #1193). Registers the `remoteSource` functions the global combobox auto-initialiser
 * (krt-searchable-select.js) wires up by marker value, so an all-users picker fetches only the
 * matching users on demand from the backend instead of shipping the whole roster as <option>s —
 * the scaling switch for 5000 accounts (ADR-0085).
 *
 *   - `data-krt-combobox="remote-users"`      -> squadron/admin-scoped /users/search
 *   - `data-krt-combobox="remote-bank-users"` -> bank-audience /users/search-bank (register holder,
 *                                                grant Bank-Employee role, approval limits)
 *
 * Both proxies forward to the backend paged search (username OR display name, case-insensitive,
 * squadron-scope-aware) and return a flat list of user rows; a failure degrades to an empty list so
 * the picker shows "no matches" rather than throwing. Loaded from head.html BEFORE
 * krt-searchable-select.js so the registry exists when the enhancer runs.
 */
(function () {
    'use strict';

    // Maps a backend user row to the combobox option shape. The label prefers the display name
    // (effectiveName folds display name over username server-side); the id is the submitted value.
    function toOption(row) {
        return {
            value: row.id,
            label: row.effectiveName || row.displayName || row.username || row.id,
        };
    }

    // Fetches the matching users for `query` from the given frontend proxy path and maps them to
    // combobox options. Returns [] on any non-OK response or network error.
    function fetchUsers(path, query) {
        return fetch(path + '?query=' + encodeURIComponent(query || ''), {
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
    window.krtComboboxRemoteSources['remote-users'] = function (query) {
        return fetchUsers('/users/search', query);
    };
    window.krtComboboxRemoteSources['remote-bank-users'] = function (query) {
        return fetchUsers('/users/search-bank', query);
    };
})();
