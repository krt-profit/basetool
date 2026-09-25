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

    /** @param {ApiDto<'MaterialDto'>} row */
    function toMaterialOption(row) {
        return {
            value: row.id,
            label: row.name,
            data: {
                quantityType: row.quantityType || '',
                refinedId: (row.refinedMaterial && row.refinedMaterial.id) || '',
                refinedName: (row.refinedMaterial && row.refinedMaterial.name) || '',
            },
        };
    }

    /** @param {ApiDto<'LocationReferenceDto'>} row */
    function toLocationOption(row) {
        return { value: row.id, label: row.name };
    }

    /** @param {ApiDto<'GameItemReferenceDto'>} row */
    function toGameItemOption(row) {
        return { value: row.id, label: row.name || row.id };
    }

    function fetchCatalog(path, query, toOption) {
        return fetch(
            path + (path.indexOf('?') >= 0 ? '&' : '?') + 'q=' + encodeURIComponent(query || ''),
            {
                headers: { Accept: 'application/json' },
            },
        )
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
    window.krtComboboxRemoteSources['remote-materials'] = function (query) {
        return fetchCatalog('/catalog/material-search', query, toMaterialOption);
    };
    window.krtComboboxRemoteSources['remote-materials-joborder'] = function (query) {
        return fetchCatalog('/catalog/material-search?jobOrder=true', query, toMaterialOption);
    };
    window.krtComboboxRemoteSources['remote-materials-raw'] = function (query) {
        return fetchCatalog('/catalog/material-search?raw=true', query, toMaterialOption);
    };
    window.krtComboboxRemoteSources['remote-locations'] = function (query) {
        return fetchCatalog('/catalog/location-search', query, toLocationOption);
    };
    window.krtComboboxRemoteSources['remote-game-items'] = function (query) {
        return fetchCatalog('/inventory/item-search', query, toGameItemOption);
    };
})();
