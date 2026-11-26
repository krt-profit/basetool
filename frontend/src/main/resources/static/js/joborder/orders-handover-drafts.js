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

import { addHandoverItemRow, updateHandoverIndexes } from '/js/joborder/orders-detail.js';
import { rowCountAdapter } from '/js/kernel/krt-draft-rows.js';
import { registerDraftAdapter } from '/js/kernel/krt-drafts.js';

/** A line of the item handover: one outstanding item with its handed amount. */
const ITEM_LINE = '.item-handover-line';

/** The hidden input naming a line's job-order item. */
const ITEM_ID_INPUT = 'input[name$=".jobOrderItemId"]';

/** The input of a line's handed amount. */
const ITEM_AMOUNT_INPUT = 'input[name$=".amount"]';

/**
 * Lists the item handover lines of a form with their item id and amount input.
 *
 * @param {HTMLFormElement} form the item handover form
 * @returns {Array<{id: string, amount: HTMLInputElement}>} the lines that name an item
 */
function itemLines(form) {
    /** @type {Array<{id: string, amount: HTMLInputElement}>} */
    const lines = [];
    form.querySelectorAll(ITEM_LINE).forEach((line) => {
        const id = line.querySelector(ITEM_ID_INPUT);
        const amount = line.querySelector(ITEM_AMOUNT_INPUT);
        if (id instanceof HTMLInputElement && id.value && amount instanceof HTMLInputElement) {
            lines.push({ id: id.value, amount });
        }
    });
    return lines;
}

/**
 * Reads the amounts an item handover draft recorded.
 *
 * @param {unknown} extra the draft part the adapter stored
 * @returns {Record<string, string>} the amount by job-order item id; empty when none
 */
function storedAmounts(extra) {
    const amounts =
        extra && typeof extra === 'object'
            ? /** @type {{amounts?: unknown}} */ (extra).amounts
            : null;
    return amounts && typeof amounts === 'object'
        ? /** @type {Record<string, string>} */ (amounts)
        : {};
}

/**
 * The item handover's adapter: its lines are named by their position in the order's items, so it
 * keeps each amount by the line's job-order item and writes it back into the line of that item.
 *
 * @type {KrtDraftAdapter}
 */
const itemHandoverAdapter = {
    serialize(form) {
        /** @type {Record<string, string>} */
        const amounts = {};
        itemLines(form).forEach((line) => {
            if (line.amount.value) {
                amounts[line.id] = line.amount.value;
            }
        });
        return { amounts };
    },
    restore(form, extra) {
        const amounts = storedAmounts(extra);
        itemLines(form).forEach((line) => {
            const value = amounts[line.id];
            if (typeof value === 'string') {
                line.amount.value = value;
                line.amount.dispatchEvent(new Event('input', { bubbles: true }));
                line.amount.dispatchEvent(new Event('change', { bubbles: true }));
            }
        });
    },
};

/**
 * Lists the rows of the material handover.
 *
 * @returns {Element[]} the rows in order
 */
function materialRows() {
    const container = document.getElementById('handover-items-container');
    return container ? Array.from(container.querySelectorAll('.handover-item-row')) : [];
}

/**
 * Registers the drafts of the job order's handover dialogs (REQ-FE-041): the material handover
 * rebuilds its rows before their inventory entries and amounts are written, the item handover
 * keeps its amounts by item.
 */
export function initHandoverDrafts() {
    registerDraftAdapter(
        'job-order-handover',
        rowCountAdapter({
            rows: materialRows,
            add: addHandoverItemRow,
            remove(row) {
                row.remove();
                updateHandoverIndexes();
            },
            minimum: 0,
        }),
    );
    registerDraftAdapter('job-order-item-handover', itemHandoverAdapter);
}
