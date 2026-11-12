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

import { krtNumberInput } from '/js/kernel/krt-number-input.js';

/** @typedef {string | number | boolean | null | undefined} FieldValue */
/** @typedef {Record<string, FieldValue>} PlanningRow */

/**
 * Edits one ordered section of the mission create draft.
 *
 * @param {HTMLElement} list the row container
 * @param {HTMLTemplateElement} template the row markup
 * @param {HTMLInputElement} carrier the serialized planning rows
 * @param {string} hiddenShipType the fallback label for an unavailable ship type
 */
export function createMissionDraftRows(list, template, carrier, hiddenShipType) {
    /** Numbers rows in their current display order. */
    function renumber() {
        Array.from(list.children).forEach((row, index) => {
            const badge = row.querySelector('.ae-num');
            if (badge) badge.textContent = String(index + 1);
        });
    }

    /**
     * @param {PlanningRow} values the initial field values
     * @returns {HTMLElement} the new row
     */
    function add(values = {}) {
        const row = /** @type {HTMLElement} */ (
            template.content.firstElementChild?.cloneNode(true)
        );
        row.querySelectorAll('[data-plan-field]').forEach((element) => {
            const field = /** @type {HTMLInputElement | HTMLSelectElement} */ (element);
            const name = field.dataset.planField || '';
            if (!Object.hasOwn(values, name)) return;
            const value = values[name];
            if (field instanceof HTMLInputElement && field.type === 'checkbox') {
                field.checked = value === true;
                return;
            }
            const text = value == null ? '' : String(value);
            if (
                name === 'shipTypeId' &&
                text &&
                field instanceof HTMLSelectElement &&
                !Array.from(field.options).some((option) => option.value === text)
            ) {
                field.add(new Option(hiddenShipType, text));
            }
            field.value = text;
        });
        list.append(row);
        krtNumberInput.enhanceWithin(row);
        return row;
    }

    /**
     * @param {PlanningRow[]} rows the plan section displayed in the draft
     */
    function fill(rows) {
        list.replaceChildren();
        rows.forEach(add);
        renumber();
    }

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
            if (Array.isArray(parsed)) fill(parsed);
        } catch (error) {
            if (!(error instanceof SyntaxError)) throw error;
        }
    }

    return { list, add, renumber, fill, serialize };
}
