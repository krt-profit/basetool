// @ts-check
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

(function () {
    'use strict';

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
     * A row's price on one side.
     *
     * @param {HTMLTableRowElement} row the terminal row
     * @param {'sell' | 'buy'} side the side
     * @returns {number | null} the price, or `null` when the terminal does not trade that side
     */
    function priceOf(row, side) {
        const raw = row.getAttribute(`data-${side}`);
        if (raw === null || raw === '') {
            return null;
        }
        const n = Number(raw);
        return isFinite(n) ? n : null;
    }

    /**
     * Sorts the rows: by sale price, highest first, or by purchase price, lowest first; rows
     * without that side go last, by terminal name.
     *
     * @param {'sell' | 'buy'} side the sort side
     */
    function sortBy(side) {
        const rows = /** @type {HTMLTableRowElement[]} */ (
            Array.prototype.slice.call(rowsBody.rows)
        );
        rows.sort((a, b) => {
            const pa = priceOf(a, side);
            const pb = priceOf(b, side);
            if (pa !== null && pb !== null && pa !== pb) {
                return side === 'sell' ? pb - pa : pa - pb;
            }
            if (pa === null && pb !== null) return 1;
            if (pa !== null && pb === null) return -1;
            return String(a.getAttribute('data-terminal') || '').localeCompare(
                String(b.getAttribute('data-terminal') || ''),
                undefined,
                { sensitivity: 'base' },
            );
        });
        rows.forEach((row) => {
            rowsBody.appendChild(row);
        });
        priceTable.setAttribute('data-sort', side);
        priceTable.querySelectorAll('th[data-sort-col]').forEach((th) => {
            const active = th.getAttribute('data-sort-col') === side;
            th.setAttribute(
                'aria-sort',
                active ? (side === 'sell' ? 'descending' : 'ascending') : 'none',
            );
        });
    }

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

    document.querySelectorAll('input[name="terminalSort"]').forEach((el) => {
        const radio = /** @type {HTMLInputElement} */ (el);
        radio.addEventListener('change', () => {
            if (radio.checked) {
                sortBy(radio.value === 'buy' ? 'buy' : 'sell');
            }
        });
    });
})();
