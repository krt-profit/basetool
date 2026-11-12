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

import { createMissionPlanningRows } from '/js/mission/mission-planning-rows.js';

/** @typedef {string | number | boolean | null | undefined} FieldValue */
/** @typedef {Record<string, FieldValue>} PlanningRow */

/**
 * Edits one ordered section of the mission create draft.
 *
 * @param {HTMLElement} list the row container
 * @param {HTMLTemplateElement} template the row markup
 * @param {HTMLInputElement} carrier the serialized planning rows
 * @param {Parameters<typeof createMissionPlanningRows>[2]} options the row controls and labels
 */
export function createMissionDraftRows(list, template, carrier, options) {
    const planning = createMissionPlanningRows(list, template, options);

    /** Serializes the current draft for the mission create endpoint. */
    function serialize() {
        const rows = Array.from(list.children, (row) => {
            /** @type {PlanningRow} */
            const values = {};
            row.querySelectorAll('[data-plan-field]').forEach((element) => {
                const field = /** @type {HTMLInputElement | HTMLSelectElement} */ (element);
                const name = field.dataset.planField || '';
                if (field instanceof HTMLInputElement && field.type === 'checkbox') {
                    values[name] = field.checked;
                } else {
                    const text = field.value.trim();
                    values[name] = name === 'frequency' && text ? Number(text) : text || null;
                }
            });
            return values;
        }).filter((row) => row.title || Object.hasOwn(row, 'name'));
        carrier.value = rows.length ? JSON.stringify(rows) : '';
    }

    if (carrier.value) {
        try {
            const parsed = JSON.parse(carrier.value);
            if (Array.isArray(parsed)) planning.fill(parsed);
        } catch (error) {
            if (!(error instanceof SyntaxError)) throw error;
        }
    }

    return { ...planning, serialize };
}
