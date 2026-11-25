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
 * The part of a mission create draft the page keeps itself (REQ-FE-041): the applied template with
 * its revision, which a save refuses as stale when the template changed meanwhile, and the planning
 * rows of each section.
 *
 * @typedef {object} MissionCreateDraft
 * @property {string} templateId the applied template; empty without one
 * @property {string} templateVersion the applied template revision; empty without one
 * @property {Record<string, MissionDraftRow[]>} rows the planning rows per section
 */

/**
 * Reads a text property of a stored object.
 *
 * @param {Record<string, unknown>} stored the stored object
 * @param {string} name the property
 * @returns {string} its text; empty for anything else
 */
function textOf(stored, name) {
    const value = stored[name];
    return typeof value === 'string' ? value : '';
}

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
 * Reads the part a stored mission create draft holds; anything else yields no template and empty
 * sections.
 *
 * @param {unknown} extra the draft part the page stored
 * @param {readonly string[]} sections the planning sections of the page
 * @returns {MissionCreateDraft} the template link and the rows of every section
 */
export function readMissionCreateDraft(extra, sections) {
    const stored = /** @type {Record<string, unknown>} */ (
        extra && typeof extra === 'object' ? extra : {}
    );
    const storedRows = /** @type {Record<string, unknown>} */ (
        stored.rows && typeof stored.rows === 'object' ? stored.rows : {}
    );
    /** @type {Record<string, MissionDraftRow[]>} */
    const rows = {};
    sections.forEach((section) => {
        const list = storedRows[section];
        rows[section] = Array.isArray(list) ? list.map(rowOf) : [];
    });
    return {
        templateId: textOf(stored, 'templateId'),
        templateVersion: textOf(stored, 'templateVersion'),
        rows,
    };
}
