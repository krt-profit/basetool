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

const ROW_CONTROLS = 'input:not([type="hidden"]), select, textarea';

/**
 * Tells whether a control can take the user's input: enabled, not read-only and rendered.
 *
 * @param {Element} control a form control inside the row
 * @returns {control is HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} true when the
 *     control is editable and visible
 */
function isEditable(control) {
    if (control instanceof HTMLSelectElement) {
        return !control.disabled && control.getClientRects().length > 0;
    }
    if (control instanceof HTMLInputElement || control instanceof HTMLTextAreaElement) {
        return !control.disabled && !control.readOnly && control.getClientRects().length > 0;
    }
    return false;
}

/**
 * Brings a form row the user just added into view and focuses its first editable control, so the
 * new row is seen and can be typed into at once (REQ-UI-036).
 *
 * @param {Element | null | undefined} row the added row; an absent row changes nothing
 */
function krtFocusNewRow(row) {
    if (!(row instanceof HTMLElement)) {
        return;
    }
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    row.scrollIntoView({ block: 'center', behavior: reduceMotion ? 'auto' : 'smooth' });
    const control = Array.from(row.querySelectorAll(ROW_CONTROLS)).find(isEditable);
    if (control) {
        control.focus({ preventScroll: true });
    }
}

export { krtFocusNewRow };
