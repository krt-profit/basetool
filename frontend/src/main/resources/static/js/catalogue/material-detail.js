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

import { krtTableSort } from '/js/kernel/krt-table-sort.js';

/**
 * Wires the price table: column-header sorting and the terminal search.
 */
function initMaterialDetail() {
    const table = /** @type {HTMLTableElement | null} */ (document.getElementById('priceTable'));
    const input = /** @type {HTMLInputElement | null} */ (
        document.getElementById('terminalFilter')
    );
    const noResults = document.getElementById('noResultsRow');
    if (!table) {
        return;
    }
    const priceTable = table;
    const tbody = priceTable.tBodies[0];
    if (!tbody) {
        return;
    }
    const rowsBody = tbody;

    /**
     * A row's value in one sortable column.
     *
     * @param {HTMLTableRowElement} row the terminal row
     * @param {string} key the column key: `terminal`, `location`, `sell` or `buy`
     * @returns {number | string | null} the price or text, or `null` when the row has none
     */
    function valueOf(row, key) {
        const raw = row.getAttribute(`data-${key}`);
        if (raw === null || raw === '') {
            return null;
        }
        if (key === 'terminal' || key === 'location') {
            return raw;
        }
        const n = Number(raw);
        return isFinite(n) ? n : null;
    }

    /**
     * Orders two rows of equal value by terminal name.
     *
     * @param {HTMLTableRowElement} a the first row
     * @param {HTMLTableRowElement} b the second row
     * @returns {number} the comparison result
     */
    function byTerminal(a, b) {
        return String(a.getAttribute('data-terminal') || '').localeCompare(
            String(b.getAttribute('data-terminal') || ''),
            undefined,
            { sensitivity: 'base' },
        );
    }

    krtTableSort.attach(priceTable, {
        initial: { key: 'sell', dir: 'desc' },
        valueOf,
        tiebreak: byTerminal,
    });

    /** Shows the rows whose terminal name contains the search text. */
    function filterTerminals() {
        const needle = input ? input.value.trim().toLocaleLowerCase() : '';
        let visible = 0;
        Array.prototype.forEach.call(rowsBody.rows, (/** @type {HTMLTableRowElement} */ row) => {
            const name = (row.getAttribute('data-terminal') || '').toLocaleLowerCase();
            const match = needle === '' || name.indexOf(needle) >= 0;
            row.hidden = !match;
            if (match) visible++;
        });
        priceTable.hidden = visible === 0;
        if (noResults) {
            noResults.hidden = visible !== 0;
        }
    }

    if (input) {
        input.addEventListener('input', filterTerminals);
    }
}

initMaterialDetail();
