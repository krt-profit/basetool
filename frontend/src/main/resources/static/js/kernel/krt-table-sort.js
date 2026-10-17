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

/** @typedef {'asc' | 'desc'} KrtSortDir */
/** @typedef {{ key: string, dir: KrtSortDir }} KrtSortState */
/** @typedef {number | string | null} KrtSortValue */

/**
 * @typedef {object} KrtTableSortOptions
 * @property {KrtSortState} initial the order the table starts in
 * @property {(row: HTMLTableRowElement, key: string) => KrtSortValue} valueOf a row's raw value
 *     for a column; `null` when the row has none
 * @property {(a: HTMLTableRowElement, b: HTMLTableRowElement) => number} [tiebreak] the order of
 *     rows with equal values
 * @property {(state: KrtSortState) => void} [onChange] called after a header click changed the order
 */

/**
 * @typedef {object} KrtTableSorter
 * @property {() => KrtSortState} state the current order
 * @property {(state: unknown) => boolean} set adopts an order if it names a sortable column
 * @property {() => void} apply reorders the body rows and marks the headers
 */

/**
 * Compares two sort values in one direction; a `null` or non-finite number sorts last either way.
 *
 * @param {KrtSortValue} left the first value
 * @param {KrtSortValue} right the second value
 * @param {KrtSortDir} dir the direction
 * @returns {number} negative, zero or positive
 */
export function compareSortValues(left, right, dir) {
    const a = typeof left === 'number' && !isFinite(left) ? null : left;
    const b = typeof right === 'number' && !isFinite(right) ? null : right;
    if (a === null || b === null) {
        if (a === b) return 0;
        return a === null ? 1 : -1;
    }
    const cmp =
        typeof a === 'string' && typeof b === 'string'
            ? a.localeCompare(b, undefined, { sensitivity: 'base' })
            : Number(a) - Number(b);
    return dir === 'desc' ? -cmp : cmp;
}

/**
 * The order after a click on a column header: the active column reverses, another one starts in
 * its first direction.
 *
 * @param {KrtSortState} state the current order
 * @param {string} key the clicked column
 * @param {KrtSortDir} firstDir the clicked column's first direction
 * @returns {KrtSortState} the new order
 */
export function nextSortState(state, key, firstDir) {
    if (state.key === key) {
        return { key, dir: state.dir === 'asc' ? 'desc' : 'asc' };
    }
    return { key, dir: firstDir };
}

/**
 * Makes a table's column headers sort its body rows (REQ-UI-029). A sortable header is a
 * `th[data-sort-col]` holding a `button.sort-header[data-sort-key]`; `data-sort-first="desc"` on
 * the button starts that column descending.
 *
 * @param {HTMLTableElement} table the table
 * @param {KrtTableSortOptions} options the values, the tie order and the change listener
 * @returns {KrtTableSorter} the sorter
 */
function attach(table, options) {
    let current = options.initial;

    /**
     * Whether a column of this table is sortable.
     *
     * @param {string} key the column key
     * @returns {boolean} true when a header button names it
     */
    function sortable(key) {
        return Array.prototype.some.call(
            table.querySelectorAll('button[data-sort-key]'),
            (/** @type {Element} */ button) => button.getAttribute('data-sort-key') === key,
        );
    }

    /** Reorders the body rows by the current order and marks every sortable header. */
    function apply() {
        const body = table.tBodies[0];
        if (body) {
            const rows = /** @type {HTMLTableRowElement[]} */ (Array.from(body.rows));
            rows.sort((a, b) => {
                const cmp = compareSortValues(
                    options.valueOf(a, current.key),
                    options.valueOf(b, current.key),
                    current.dir,
                );
                return cmp !== 0 || !options.tiebreak ? cmp : options.tiebreak(a, b);
            });
            rows.forEach((row) => {
                body.appendChild(row);
            });
        }
        table.querySelectorAll('th[data-sort-col]').forEach((th) => {
            const active = th.getAttribute('data-sort-col') === current.key;
            th.setAttribute(
                'aria-sort',
                active ? (current.dir === 'desc' ? 'descending' : 'ascending') : 'none',
            );
        });
    }

    table.addEventListener('click', (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const button = target ? target.closest('button[data-sort-key]') : null;
        if (!button || !table.contains(button)) return;
        const key = button.getAttribute('data-sort-key') || '';
        const first = button.getAttribute('data-sort-first') === 'desc' ? 'desc' : 'asc';
        current = nextSortState(current, key, first);
        apply();
        if (options.onChange) options.onChange(current);
    });

    apply();

    return {
        state: () => current,
        set(state) {
            const candidate = /** @type {{ key?: unknown, dir?: unknown } | null} */ (
                state && typeof state === 'object' ? state : null
            );
            if (
                !candidate ||
                typeof candidate.key !== 'string' ||
                (candidate.dir !== 'asc' && candidate.dir !== 'desc') ||
                !sortable(candidate.key)
            ) {
                return false;
            }
            current = { key: candidate.key, dir: candidate.dir };
            apply();
            return true;
        },
        apply,
    };
}

/** Sortable table headers, one implementation for every page (REQ-UI-029). */
export const krtTableSort = { attach };
