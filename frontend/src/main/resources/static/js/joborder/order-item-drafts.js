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
 * One item line of a job-order create draft (REQ-FE-041): the picked item with its label, the
 * blueprint, the amount, the quality code per derived material and the position of the parent
 * line in the draft, which survives the new client line ids a restore hands out.
 *
 * @typedef {object} OrderItemLineDraft
 * @property {string} gameItemId the picked item; empty when none is picked
 * @property {string} gameItemName the item's picker label
 * @property {string} blueprintId the chosen blueprint; empty before the blueprints are loaded
 * @property {string} amount the ordered amount
 * @property {number | null} parent the position of the parent line in the draft, or null
 * @property {Record<string, string>} qualities the chosen quality code per derived material id
 */

/**
 * The prefill a restored line is built from, in the shape the page's line builder takes.
 *
 * @typedef {object} OrderItemLinePrefill
 * @property {string} [gameItemId] the ordered item
 * @property {string} [gameItemName] the item's picker label
 * @property {string} [amount] the ordered amount
 * @property {string} [blueprintId] the blueprint to preselect
 * @property {Record<string, string>} [qualities] the quality code per material id
 * @property {string} [parentId] the client line id of the parent line
 */

/**
 * Reads the value of a control inside a line.
 *
 * @param {Element} row the `.item-line`
 * @param {string} selector the control's selector
 * @returns {string} its value; empty when the line has no such control
 */
function valueIn(row, selector) {
    const control = row.querySelector(selector);
    return control instanceof HTMLInputElement || control instanceof HTMLSelectElement
        ? control.value
        : '';
}

/**
 * Collects the chosen quality code of each derived material of a line.
 *
 * @param {Element} row the `.item-line`
 * @returns {Record<string, string>} the quality code per material id
 */
function qualitiesOf(row) {
    /** @type {Record<string, string>} */
    const qualities = {};
    row.querySelectorAll('.oc-material-line').forEach((line) => {
        const materialId = valueIn(line, 'input[name$=".materialId"]');
        const quality = valueIn(line, 'select[name$=".quality"]');
        if (materialId && quality) {
            qualities[materialId] = quality;
        }
    });
    return qualities;
}

/**
 * Captures the item lines of the create form in order.
 *
 * @param {Element} container the `#item-lines` container
 * @returns {OrderItemLineDraft[]} one draft per line
 */
export function itemLineDrafts(container) {
    const rows = Array.from(container.querySelectorAll('.item-line'));
    const positions = new Map(
        rows.map((row, position) => [
            row instanceof HTMLElement ? row.dataset.lineIndex || '' : '',
            position,
        ]),
    );
    return rows.map((row) => {
        const item = row.querySelector('[data-role="item-select"]');
        const selection =
            item instanceof HTMLElement && item.krtCombobox ? item.krtCombobox.selection() : null;
        const parentKey = valueIn(row, 'input[name$=".parentClientLineId"]');
        const parent = parentKey === '' ? undefined : positions.get(parentKey);
        return {
            gameItemId: selection ? selection.value : valueIn(row, '[data-role="item-select"]'),
            gameItemName: selection ? selection.label : '',
            blueprintId: valueIn(row, '[data-role="blueprint-select"]'),
            amount: valueIn(row, '[data-role="amount"]'),
            parent: parent === undefined ? null : parent,
            qualities: qualitiesOf(row),
        };
    });
}

/**
 * Reads a text property of a stored line.
 *
 * @param {Record<string, unknown>} line the stored line
 * @param {string} name the property
 * @returns {string} its text; empty for anything else
 */
function textOf(line, name) {
    const value = line[name];
    return typeof value === 'string' ? value : '';
}

/**
 * Reads the item lines a stored draft holds; anything that is no line list yields none.
 *
 * @param {unknown} extra the draft part the page stored
 * @returns {OrderItemLineDraft[]} the stored lines
 */
export function readItemLineDrafts(extra) {
    const lines =
        extra && typeof extra === 'object' ? /** @type {{lines?: unknown}} */ (extra).lines : null;
    if (!Array.isArray(lines)) {
        return [];
    }
    return lines.map((stored) => {
        const line = /** @type {Record<string, unknown>} */ (
            stored && typeof stored === 'object' ? stored : {}
        );
        /** @type {Record<string, string>} */
        const qualities = {};
        const storedQualities = line.qualities;
        if (storedQualities && typeof storedQualities === 'object') {
            Object.entries(storedQualities).forEach(([materialId, code]) => {
                if (typeof code === 'string') {
                    qualities[materialId] = code;
                }
            });
        }
        return {
            gameItemId: textOf(line, 'gameItemId'),
            gameItemName: textOf(line, 'gameItemName'),
            blueprintId: textOf(line, 'blueprintId'),
            amount: textOf(line, 'amount'),
            parent: typeof line.parent === 'number' ? line.parent : null,
            qualities,
        };
    });
}

/**
 * Rebuilds item lines through the page's line builder, in order, linking each line to the client
 * line id its parent got.
 *
 * @param {OrderItemLineDraft[]} drafts the stored lines
 * @param {(prefill: OrderItemLinePrefill) => HTMLElement | null} addLine the page's line builder
 */
export function restoreItemLines(drafts, addLine) {
    /** @type {(string | undefined)[]} */
    const lineIds = [];
    drafts.forEach((draft) => {
        const row = addLine({
            gameItemId: draft.gameItemId || undefined,
            gameItemName: draft.gameItemName || undefined,
            amount: draft.amount || undefined,
            blueprintId: draft.blueprintId || undefined,
            qualities: draft.qualities,
            parentId: draft.parent === null ? undefined : lineIds[draft.parent],
        });
        lineIds.push(row ? row.dataset.lineIndex : undefined);
    });
}
