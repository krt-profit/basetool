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

import { readPlanningRows } from '/js/mission/mission-draft-rows.js';

/**
 * The part of a mission create draft the page keeps itself (REQ-FE-041): the applied template with
 * its revision, which a save refuses as stale when the template changed meanwhile, and the planning
 * rows of each section.
 *
 * @typedef {object} MissionCreateDraft
 * @property {string} templateId the applied template; empty without one
 * @property {string} templateVersion the applied template revision; empty without one
 * @property {ReturnType<typeof readPlanningRows>} rows the planning rows per section
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
    return {
        templateId: textOf(stored, 'templateId'),
        templateVersion: textOf(stored, 'templateVersion'),
        rows: readPlanningRows(stored.rows, sections),
    };
}
