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

import { krtFetch } from '/js/kernel/krt-fetch.js';

/** How long the marked set must hold still before its value is asked for, in milliseconds. */
const SETTLE_MS = 200;

/** Counts refreshes, so an answer that a later refresh overtook is dropped. */
let refreshGeneration = 0;

/** @type {ReturnType<typeof setTimeout> | null} */
let pendingRequest = null;

/** The marked ids, comma-joined, whose row the slot shows; '' while it shows none. */
let shownSelection = '';

/**
 * The slot the selection's sum row is swapped into; absent while the tree shows no sum row.
 *
 * @returns {HTMLElement | null} the slot
 */
function selectionSlot() {
    return /** @type {HTMLElement | null} */ (
        document.querySelector('[data-lager-sale-value-slot]')
    );
}

/**
 * The sum row of the shown groups.
 *
 * @returns {HTMLElement | null} the row, or null while the tree has none
 */
function viewTotalRow() {
    return /** @type {HTMLElement | null} */ (
        document.querySelector('[data-lager-sale-value="total"]')
    );
}

/**
 * Shows the shown groups' total again and empties the slot.
 *
 * @param {HTMLElement} slot the selection slot
 */
function showViewTotal(slot) {
    shownSelection = '';
    slot.hidden = true;
    slot.replaceChildren();
    const row = viewTotalRow();
    if (row) row.hidden = false;
}

/**
 * Shows the marked entries' row in place of the shown groups' total, or no row when there is none
 * to show, so the view's total never stands while entries are marked.
 *
 * @param {HTMLElement} slot the selection slot
 * @param {string} selection the marked ids, comma-joined
 * @param {string | null} html the server-rendered row, or null when it could not be loaded
 */
function showSelectionTotal(slot, selection, html) {
    shownSelection = html == null ? '' : selection;
    krtFetch.setTrustedHtml(slot, html);
    slot.hidden = html == null;
    const row = viewTotalRow();
    if (row) row.hidden = true;
}

/**
 * Asks the server for the marked entries' sum row and shows it, unless a later refresh overtook
 * the request.
 *
 * @param {HTMLElement} slot the selection slot
 * @param {string[]} ids the marked entry ids
 * @param {number} generation the refresh this request belongs to
 * @returns {Promise<void>} settles once the row is shown or dropped
 */
async function loadSelectionTotal(slot, ids, generation) {
    const url = slot.getAttribute('data-url');
    if (!url) return;
    const result = await krtFetch.write({
        method: 'POST',
        url,
        payload: { itemIds: ids },
        accept: 'text/html',
        toast: false,
        submitter: false,
        errorMessage: slot.getAttribute('data-error') || undefined,
    });
    if (generation !== refreshGeneration || !slot.isConnected) return;
    showSelectionTotal(
        slot,
        ids.join(','),
        result.ok && typeof result.body === 'string' ? result.body : null,
    );
}

/**
 * Points the "Mein Lager" sum row at the marked entries (REQ-INV-072): while entries are marked it
 * shows their estimated sale value, asked for once the marked set holds still; with none marked it
 * shows the shown groups' total again. A no-op where the tree has no sum row, and no new request
 * while the slot already shows the same marked set.
 *
 * @param {string[]} ids the marked entry ids
 */
export function refreshSelectionSaleValue(ids) {
    refreshGeneration++;
    if (pendingRequest) {
        clearTimeout(pendingRequest);
        pendingRequest = null;
    }
    const slot = selectionSlot();
    if (!slot) return;
    if (ids.length === 0) {
        showViewTotal(slot);
        return;
    }
    if (!slot.hidden && ids.join(',') === shownSelection) return;
    const generation = refreshGeneration;
    pendingRequest = setTimeout(() => {
        pendingRequest = null;
        void loadSelectionTotal(slot, ids, generation);
    }, SETTLE_MS);
}
