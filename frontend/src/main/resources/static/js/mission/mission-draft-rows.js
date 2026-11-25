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

/** @typedef {Record<string, string | number | boolean | null>} MissionDraftRow */

/**
 * Keeps the plain field values of a stored planning row.
 *
 * @param {unknown} stored the stored row
 * @returns {MissionDraftRow} its string, number, boolean and null fields
 */
function rowOf(stored) {
    /** @type {MissionDraftRow} */
    const row = {};
    if (stored && typeof stored === 'object') {
        Object.entries(stored).forEach(([field, value]) => {
            if (
                value === null ||
                typeof value === 'string' ||
                typeof value === 'number' ||
                typeof value === 'boolean'
            ) {
                row[field] = value;
            }
        });
    }
    return row;
}

/**
 * Reads the planning rows a stored mission draft holds per section (REQ-FE-041); a section that is
 * missing or not a list yields no rows.
 *
 * @param {unknown} stored the stored rows, keyed by section
 * @param {readonly string[]} sections the planning sections of the page
 * @returns {Record<string, MissionDraftRow[]>} the rows of every section
 */
export function readPlanningRows(stored, sections) {
    const storedRows = /** @type {Record<string, unknown>} */ (
        stored && typeof stored === 'object' ? stored : {}
    );
    /** @type {Record<string, MissionDraftRow[]>} */
    const rows = {};
    sections.forEach((section) => {
        const list = storedRows[section];
        rows[section] = Array.isArray(list) ? list.map(rowOf) : [];
    });
    return rows;
}
