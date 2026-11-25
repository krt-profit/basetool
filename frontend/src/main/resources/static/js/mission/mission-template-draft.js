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

/** The form key of the mission template editor while it creates a template (REQ-FE-041). */
export const TEMPLATE_DRAFT_KEY = 'mission-template-create';

/**
 * The part of a mission template create draft the editor keeps itself (REQ-FE-041): the parts
 * shown in the editor and the planning rows of each section.
 *
 * @typedef {object} MissionTemplateDraft
 * @property {string[]} parts the shown parts, in the order of the known parts
 * @property {ReturnType<typeof readPlanningRows>} rows the planning rows per section
 */

/**
 * Reads the part a stored mission template draft holds; an unknown part is dropped, and anything
 * else yields no shown part and empty sections.
 *
 * @param {unknown} extra the draft part the editor stored
 * @param {readonly string[]} parts the parts the editor knows
 * @param {readonly string[]} sections the planning sections of the editor
 * @returns {MissionTemplateDraft} the shown parts and the rows of every section
 */
export function readMissionTemplateDraft(extra, parts, sections) {
    const stored = /** @type {Record<string, unknown>} */ (
        extra && typeof extra === 'object' ? extra : {}
    );
    const shown = Array.isArray(stored.parts) ? stored.parts : [];
    return {
        parts: parts.filter((part) => shown.includes(part)),
        rows: readPlanningRows(stored.rows, sections),
    };
}
