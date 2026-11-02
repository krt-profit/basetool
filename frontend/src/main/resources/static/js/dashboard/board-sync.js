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
 * The saved state of a dashboard board that another window can change (REQ-UI-034): the layout
 * version and every instance with its place and options.
 *
 * @typedef {object} BoardState
 * @property {string} version the layout version
 * @property {boolean} loaded whether the stored board could be read
 * @property {Array<Record<string, string>>} items the instances in document order
 */

/** The data attributes of an instance that make up its saved state. */
const ITEM_FIELDS = [
    'widgetId',
    'widgetType',
    'optionsVersion',
    'unit',
    'days',
    'rows',
    'status',
    'available',
];

/**
 * Reads the saved state a board element shows.
 *
 * @param {ParentNode & { dataset: DOMStringMap }} root the board element
 * @returns {BoardState} its version and instances
 */
export function readBoard(root) {
    const items = Array.from(root.querySelectorAll('[data-dashboard-item]')).map((element) => {
        const item = /** @type {HTMLElement} */ (element);
        /** @type {Record<string, string>} */
        const state = {
            column:
                item.closest('[data-dashboard-column]')?.getAttribute('data-dashboard-column') ??
                '',
        };
        ITEM_FIELDS.forEach((field) => {
            state[field] = item.dataset[field] ?? '';
        });
        return state;
    });
    return {
        version: root.dataset.version ?? '',
        loaded: root.dataset.loaded === 'true',
        items,
    };
}

/**
 * A comparable text of a board's saved state: equal texts mean nothing another window changed.
 *
 * @param {BoardState} state the board state
 * @returns {string} the signature
 */
export function boardSignature(state) {
    return JSON.stringify([
        state.version,
        state.items.map((item) => [item.column, ...ITEM_FIELDS.map((field) => item[field] ?? '')]),
    ]);
}

/**
 * Whether the board on the page has to be replaced by the freshly read one: the saved state
 * differs, the fresh read is a real one, and neither a local edit nor a local write is under way.
 *
 * @param {object} situation what the poll found
 * @param {BoardState} situation.current the board on the page
 * @param {BoardState} situation.fresh the board the server answered
 * @param {boolean} situation.editing whether the member is customising the board
 * @param {boolean} situation.writing whether a local write is in flight
 * @param {boolean} situation.visible whether the tab is visible
 * @returns {boolean} true when the page swaps the board in place
 */
export function shouldSwapBoard({ current, fresh, editing, writing, visible }) {
    if (editing || writing || !visible || !fresh.loaded) return false;
    return boardSignature(current) !== boardSignature(fresh);
}
