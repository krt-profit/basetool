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

/**
 * @typedef {object} CatalogOption
 * @property {string | undefined} value the option value
 * @property {string | undefined} label the option label
 * @property {Record<string, string>} [data] extra `data-*` values of the option
 */

/**
 * Fetches a catalogue search endpoint and maps every row to a combobox option; resolves an empty
 * list when the request fails.
 *
 * @param {string} path the search endpoint, with or without a query string
 * @param {string | null | undefined} query the typed search text
 * @param {(row: any) => CatalogOption} toOption maps one result row to an option
 * @returns {Promise<CatalogOption[]>} the options
 */
function fetchCatalog(path, query, toOption) {
    return krtFetch
        .getJson(
            `${path + (path.indexOf('?') >= 0 ? '&' : '?')}q=${encodeURIComponent(query || '')}`,
        )
        .then((list) => {
            return (list || []).map(toOption);
        })
        .catch(() => {
            return [];
        });
}

krtComboboxRemoteSources['remote-materials'] = function (query) {
    return fetchCatalog('/catalog/material-search', query, toMaterialOption);
};
krtComboboxRemoteSources['remote-materials-joborder'] = function (query) {
    return fetchCatalog('/catalog/material-search?jobOrder=true', query, toMaterialOption);
};
krtComboboxRemoteSources['remote-materials-raw'] = function (query) {
    return fetchCatalog('/catalog/material-search?raw=true', query, toMaterialOption);
};
krtComboboxRemoteSources['remote-locations'] = function (query) {
    return fetchCatalog('/catalog/location-search', query, toLocationOption);
};
krtComboboxRemoteSources['remote-game-items'] = function (query) {
    return fetchCatalog('/inventory/item-search', query, toGameItemOption);
};
