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

import { showFrontendSuccessToast } from '/js/kernel/toast.js';

/**
 * Fills the numbered placeholders {0}, {1}, … of a localized message.
 *
 * @param {string | undefined} template the message, empty when missing
 * @param {Array<string | number>} args the values in placeholder order
 * @returns {string} the filled message
 */
export function formatMessage(template, args) {
    let text = String(template || '');
    args.forEach((arg, index) => {
        text = text.replace(`{${index}}`, String(arg));
    });
    return text;
}

/**
 * Presets the unit picker to a current unit, offering a unit the owner has since left as its own
 * option instead of silently clearing it.
 *
 * @param {HTMLSelectElement} select the unit picker
 * @param {string} current the current unit id, empty for none
 * @param {string} currentName the current unit's name, empty when unknown
 */
export function presetUnitSelect(select, current, currentName) {
    select.querySelectorAll('option[data-former-unit]').forEach((o) => o.remove());
    const known = Array.from(select.options).some((o) => o.value === current);
    if (!known && current) {
        const former = document.createElement('option');
        former.value = current;
        former.textContent = currentName || current;
        former.setAttribute('data-former-unit', '');
        select.appendChild(former);
    }
    select.value = current;
}

/**
 * The localized texts of a bulk unit change's outcome toast.
 *
 * @typedef {object} BulkUnitTexts
 * @property {string | undefined} none nothing moved
 * @property {string | undefined} success {0} ships moved
 * @property {string | undefined} partial {0} ships moved, {1} already were there
 */

/**
 * Reports how many selected ships moved and how many already were in the target unit.
 *
 * @param {{ changed?: number, skipped?: number } | null} body the result counts
 * @param {BulkUnitTexts} texts the localized toast texts
 */
export function reportBulkUnitOutcome(body, texts) {
    const changed = body && typeof body.changed === 'number' ? body.changed : 0;
    const skipped = body && typeof body.skipped === 'number' ? body.skipped : 0;
    if (changed === 0) {
        showFrontendSuccessToast(formatMessage(texts.none, []));
        return;
    }
    showFrontendSuccessToast(
        skipped > 0
            ? formatMessage(texts.partial, [changed, skipped])
            : formatMessage(texts.success, [changed]),
    );
}

/**
 * The ship selection of a hangar list.
 *
 * @typedef {object} ShipSelection
 * @property {Set<string>} selected the selected ship ids
 * @property {() => HTMLInputElement[]} checks the selection checkboxes of the rendered list
 * @property {() => void} render syncs the checkboxes, the select-all box and the bar
 */

/**
 * Wires the row checkboxes and the select-all box of a ship list to a selection that holds only
 * the ships the list shows, and the selection bar that shows its count while anything is selected
 * (REQ-HANGAR-010, REQ-HANGAR-012).
 *
 * @param {object} options the list's parts
 * @param {string} options.results selector of the list container the checkboxes live in
 * @param {string} options.selectAllId id of the select-all box in the list head
 * @param {HTMLElement | null} options.bar the selection bar
 * @param {HTMLElement | null} options.count the bar's count
 * @returns {ShipSelection} the selection
 */
export function createShipSelection({ results, selectAllId, bar, count }) {
    /** @type {Set<string>} */
    const selected = new Set();

    /**
     * Lists the selection checkboxes of the rendered ship list.
     *
     * @returns {HTMLInputElement[]} one checkbox per visible ship
     */
    function checks() {
        return Array.from(
            /** @type {NodeListOf<HTMLInputElement>} */ (
                document.querySelectorAll(`${results} input.hangar-ship-check`)
            ),
        );
    }

    /**
     * Keeps the selection to the ships the list shows, ticks their checkboxes and the select-all
     * box, and shows the bar with its count while anything is selected.
     */
    function render() {
        const boxes = checks();
        const visible = new Set(boxes.map((c) => c.value));
        Array.from(selected).forEach((id) => {
            if (!visible.has(id)) selected.delete(id);
        });
        boxes.forEach((c) => {
            c.checked = selected.has(c.value);
        });
        const selectAll = /** @type {HTMLInputElement | null} */ (
            document.getElementById(selectAllId)
        );
        if (selectAll) selectAll.checked = boxes.length > 0 && boxes.every((c) => c.checked);
        if (count) count.textContent = String(selected.size);
        if (bar) bar.hidden = selected.size === 0;
    }

    /**
     * Adds a ship to the selection or removes it.
     *
     * @param {string} shipId the ship
     * @param {boolean} on whether it is selected
     */
    function toggle(shipId, on) {
        if (on) {
            selected.add(shipId);
        } else {
            selected.delete(shipId);
        }
    }

    const container = document.querySelector(results);
    if (container) {
        container.addEventListener('change', (e) => {
            const target = e.target;
            if (!(target instanceof HTMLInputElement)) return;
            if (target.classList.contains('hangar-select-all')) {
                checks().forEach((c) => toggle(c.value, target.checked));
            } else if (target.classList.contains('hangar-ship-check')) {
                toggle(target.value, target.checked);
            } else {
                return;
            }
            render();
        });
    }

    return { selected, checks, render };
}
