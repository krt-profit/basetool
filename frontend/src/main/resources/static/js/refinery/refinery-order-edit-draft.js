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

import { readDraftFlags } from '/js/kernel/krt-draft-flags.js';

/** The form key of the refinery-order detail form while the member may edit it (REQ-FE-041). */
export const REFINERY_EDIT_DRAFT_KEY = 'refinery-order-edit';

/**
 * The part of a refinery-order edit draft the page keeps itself (REQ-FE-041): the good id of each
 * material row in order, empty for a row the member added, and the order-form parts the member
 * changed, which are the only ones a save writes (REQ-REFINERY-029).
 *
 * @typedef {object} RefineryEditDraft
 * @property {string[]} goods the good id per material row; empty for an added row
 * @property {Record<string, boolean>} changed whether each part was changed, by part name
 */

/**
 * Reads the part a stored refinery-order edit draft holds; anything else yields no rows and no
 * changed part.
 *
 * @param {unknown} extra the draft part the page stored
 * @returns {RefineryEditDraft} the material rows and the changed parts
 */
export function readRefineryEditDraft(extra) {
    const stored = /** @type {Record<string, unknown>} */ (
        extra && typeof extra === 'object' ? extra : {}
    );
    const goods = Array.isArray(stored.goods)
        ? stored.goods.map((id) => (typeof id === 'string' ? id : ''))
        : [];
    return { goods, changed: readDraftFlags(stored.changed) };
}

/**
 * Matches the material rows a draft recorded to the rendered rows by good id, each rendered row at
 * most once.
 *
 * @param {readonly string[]} rendered the good id per rendered row; empty for a row without one
 * @param {readonly string[]} stored the good id per recorded row, in order; empty for an added row
 * @returns {(number | null)[]} per recorded row, the index of the rendered row carrying its good,
 *     or null where an empty row is added
 */
export function matchGoodRows(rendered, stored) {
    /** @type {Set<number>} */
    const used = new Set();
    return stored.map((id) => {
        if (!id) {
            return null;
        }
        const index = rendered.findIndex((candidate, at) => candidate === id && !used.has(at));
        if (index < 0) {
            return null;
        }
        used.add(index);
        return index;
    });
}
