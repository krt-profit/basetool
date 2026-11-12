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
import { formatReorderAnnouncement, krtReorder } from '/js/kernel/krt-reorder.js';

/** @typedef {Record<string, string | number | boolean | null | undefined>} PlanningRow */

/**
 * Edits the ordered rows of one planning section.
 *
 * @param {HTMLElement} list the row container
 * @param {HTMLTemplateElement} template the row markup
 * @param {{ liveRegion: HTMLElement | null, hiddenShipType: string, onMove: () => void }} options
 *     the announcement region, hidden ship label and draft change callback
 */
export function createMissionPlanningRows(list, template, { liveRegion, hiddenShipType, onMove }) {
    const nameField = '[data-reorder-name-field]';
    const reorder = krtReorder.attach({
        container: list,
        itemSelector: '[data-planning-row]:has([data-reorder-move])',
        liveRegion,
        async persist() {
            syncOrder();
            onMove();
            return true;
        },
    });

    /** Synchronizes position indicators and move controls with the row order. */
    function syncOrder() {
        reorder.syncBounds();
        Array.from(list.children).forEach((row, index) => {
            const badge = row.querySelector('.ae-num');
            if (badge) badge.textContent = String(index + 1);
        });
    }

    /** @param {HTMLInputElement} field the editable name used in labels and announcements */
    function syncName(field) {
        const row = field.closest('[data-planning-row]');
        if (!(row instanceof HTMLElement)) return;
        const name = field.value.trim() || field.dataset.reorderFallback || '';
        row.dataset.reorderName = name;
        row.querySelectorAll('[data-reorder-move]').forEach((button) => {
            if (!(button instanceof HTMLButtonElement)) return;
            const pattern = (button.dataset.reorderLabel ??= button.title);
            const label = formatReorderAnnouncement(pattern, { name });
            button.setAttribute('aria-label', label);
            button.title = label;
        });
    }

    /**
     * @param {PlanningRow} values initial field values
     * @returns {HTMLElement} the inserted row
     */
    function append(values) {
        const row = /** @type {HTMLElement} */ (
            template.content.firstElementChild?.cloneNode(true)
        );
        krtNumberInput.enhanceWithin(row);
        row.querySelectorAll('[data-plan-field], [name]').forEach((field) => {
            if (!(field instanceof HTMLInputElement || field instanceof HTMLSelectElement)) return;
            const key = field.dataset.planField ?? field.name;
            if (!Object.hasOwn(values, key)) return;
            const value = values[key];
            if (field instanceof HTMLInputElement && field.type === 'checkbox') {
                field.checked = value === true;
                return;
            }
            const text = value == null ? '' : String(value);
            if (
                key === 'shipTypeId' &&
                text &&
                field instanceof HTMLSelectElement &&
                !Array.from(field.options).some((option) => option.value === text)
            ) {
                field.add(new Option(hiddenShipType, text));
            }
            field.value = text;
        });
        list.append(row);
        const field = row.querySelector(nameField);
        if (field instanceof HTMLInputElement) syncName(field);
        return row;
    }

    /**
     * @param {PlanningRow} [values] initial field values
     * @returns {HTMLElement} the inserted row
     */
    function add(values = {}) {
        const row = append(values);
        syncOrder();
        return row;
    }

    /** @param {PlanningRow[]} rows the replacement draft rows */
    function fill(rows) {
        list.replaceChildren();
        rows.forEach(append);
        syncOrder();
    }

    /** @param {Element} row the planning row to remove */
    function remove(row) {
        const adjacent = row.nextElementSibling || row.previousElementSibling;
        row.remove();
        syncOrder();
        (adjacent?.querySelector('input') || list.parentElement?.querySelector('button'))?.focus();
    }

    list.addEventListener('input', (event) => {
        const field = event.target;
        if (field instanceof HTMLInputElement && field.matches(nameField)) syncName(field);
    });

    return { list, add, fill, remove };
}
