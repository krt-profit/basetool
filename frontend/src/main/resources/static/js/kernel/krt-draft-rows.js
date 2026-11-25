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
 * The rows of a form whose script-built rows differ only in number: how to list, add and remove
 * them, and how to show the form when its draft is offered.
 *
 * @typedef {object} KrtDraftRows
 * @property {() => Element[]} rows lists the rows in order
 * @property {() => void} add appends an empty row
 * @property {(row: Element) => void} remove removes a row, or leaves the last one in place
 * @property {(form: HTMLFormElement) => void} [reveal] shows the form on a page of several
 */

/**
 * Reads the row count a draft recorded.
 *
 * @param {unknown} extra the draft part the adapter stored
 * @returns {number} the row count, at least one
 */
function storedRowCount(extra) {
    const rows =
        extra && typeof extra === 'object' ? /** @type {{rows?: unknown}} */ (extra).rows : null;
    return typeof rows === 'number' && rows >= 1 ? Math.floor(rows) : 1;
}

/**
 * Builds the draft adapter of a form with script-built rows (REQ-FE-041): it stores the row count
 * and adds or removes rows until the form has as many, before the values are written.
 *
 * @param {KrtDraftRows} form the form's rows
 * @returns {KrtDraftAdapter} the adapter to register
 */
export function rowCountAdapter(form) {
    /** @type {KrtDraftAdapter} */
    const adapter = {
        serialize() {
            return { rows: form.rows().length };
        },
        restore(_form, extra) {
            const wanted = storedRowCount(extra);
            while (form.rows().length < wanted) {
                form.add();
            }
            let current = form.rows();
            while (current.length > wanted) {
                form.remove(current[current.length - 1]);
                const after = form.rows();
                if (after.length >= current.length) {
                    break;
                }
                current = after;
            }
        },
    };
    if (form.reveal) {
        adapter.reveal = form.reveal;
    }
    return adapter;
}
