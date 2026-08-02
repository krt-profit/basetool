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

/*
 * Shared server-side catalog-search sources for the searchable combobox (REQ-FE-016). Registers
 * the `remoteSource` functions the global combobox auto-initialiser (krt-searchable-select.js)
 * wires up by marker value, so the material and location pickers fetch only the matching entries
 * on demand from the backend instead of shipping the whole catalog as <option>s — every entry
 * stays reachable by typing regardless of catalog size, and no silent cap can ever hide the tail
 * of the list (the same scaling switch the item and user pickers already use, ADR-0089/#1193).
 *
 *   - `data-krt-combobox="remote-materials"`          -> full visible material catalog
 *   - `data-krt-combobox="remote-materials-joborder"` -> job-order subset (orders material lines)
 *   - `data-krt-combobox="remote-materials-raw"`      -> refinery inputs (RAW or manually raw)
 *   - `data-krt-combobox="remote-locations"`          -> non-hidden locations
 *   - `data-krt-combobox="remote-game-items"`         -> bookable game items (Lager item mode,
 *                                                        outputs of at least one active blueprint,
 *                                                        REQ-INV-029)
 *
 * Material rows carry the picker metadata as the option `data` map: `quantityType` drives the
 * amount-unit mirror (PIECE vs SCU) and `refinedId`/`refinedName` the refinery output display —
 * krt-searchable-select.js mirrors the selected option's map onto the hidden input. A failure
 * degrades to an empty list so the picker shows "no matches" rather than throwing. Loaded from
 * head.html BEFORE krt-searchable-select.js so the registry exists when the enhancer runs.
 */
(function () {
    'use strict';

    // Maps a backend MaterialDto to the combobox option shape, folding the picker metadata into
    // the option `data` map (mirrored onto the hidden input while the option is selected).
    // The DTO type is generated from the OpenAPI spec, so a backend field rename breaks this
    // mapping at build time instead of silently emptying the picker (ADR-0125).
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

    // Maps a backend LocationReferenceDto to the combobox option shape (no metadata).
    /** @param {ApiDto<'LocationReferenceDto'>} row */
    function toLocationOption(row) {
        return { value: row.id, label: row.name };
    }

    // Maps a backend game-item reference row to the combobox option shape (no metadata).
    /** @param {ApiDto<'GameItemReferenceDto'>} row */
    function toGameItemOption(row) {
        return { value: row.id, label: row.name || row.id };
    }

    // Fetches the matching catalog rows for `query` from the given frontend proxy path and maps
    // them with `toOption`. Returns [] on any non-OK response or network error.
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

    // Registry keyed by the `data-krt-combobox` marker value; read by krt-searchable-select.js.
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
