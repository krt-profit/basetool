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

    function toOption(row) {
        return {
            value: row.id,
            label: row.effectiveName || row.displayName || row.username || row.id,
        };
    }

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

    window.krtComboboxRemoteSources = window.krtComboboxRemoteSources || {};
    window.krtComboboxRemoteSources['remote-users'] = function (query) {
        return fetchUsers('/users/search', query);
    };
    window.krtComboboxRemoteSources['remote-bank-users'] = function (query) {
        return fetchUsers('/users/search-bank', query);
    };
})();
