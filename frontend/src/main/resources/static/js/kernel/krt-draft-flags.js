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
 * Reads the per-part flags a draft stored, such as the sections an edit form's member changed
 * (REQ-FE-041); a flag that is not `true` reads as false.
 *
 * @param {unknown} stored the stored flags
 * @returns {Record<string, boolean>} whether each part is flagged, by part name; empty for
 *     anything that is not an object
 */
export function readDraftFlags(stored) {
    /** @type {Record<string, boolean>} */
    const flags = {};
    if (stored && typeof stored === 'object') {
        Object.entries(stored).forEach(([part, value]) => {
            flags[part] = value === true;
        });
    }
    return flags;
}
