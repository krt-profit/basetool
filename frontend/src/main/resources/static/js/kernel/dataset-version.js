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

/**
 * Reads the optimistic-lock version a server-rendered element carries in a `data-` attribute.
 *
 * A missing or non-numeric attribute yields `null`, which a write echoes as an absent version so
 * the server rejects it, instead of a silent `0` that would overwrite a newer row.
 *
 * @param {DOMStringMap} dataset the element's `dataset`
 * @param {string} key the camel-cased dataset key
 * @returns {number | null} the version, or `null` when the attribute is absent or not an integer
 */
export function versionOf(dataset, key) {
    const raw = dataset[key];
    if (raw === undefined || raw.trim() === '') {
        return null;
    }
    const version = Number(raw);
    return Number.isInteger(version) && version >= 0 ? version : null;
}
