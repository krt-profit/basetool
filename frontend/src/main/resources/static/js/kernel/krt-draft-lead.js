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
 * Reads the value a leading control keeps.
 *
 * @param {Element | null} control the control
 * @returns {string | boolean | null} a checkbox's checked state, else the value; null for no
 *     control
 */
function leadingValue(control) {
    if (control instanceof HTMLInputElement && control.type === 'checkbox') {
        return control.checked;
    }
    return control instanceof HTMLInputElement || control instanceof HTMLSelectElement
        ? control.value
        : null;
}

/**
 * Writes a stored value into an enabled leading control.
 *
 * @param {Element | null} control the control
 * @param {unknown} value the stored value
 * @returns {boolean} whether the value was written
 */
function applyLeading(control, value) {
    if (!(control instanceof HTMLInputElement || control instanceof HTMLSelectElement)) {
        return false;
    }
    if (control.disabled) {
        return false;
    }
    if (control instanceof HTMLInputElement && control.type === 'checkbox') {
        if (typeof value !== 'boolean') {
            return false;
        }
        control.checked = value;
        return true;
    }
    if (typeof value !== 'string') {
        return false;
    }
    control.value = value;
    return true;
}

/**
 * Builds the draft adapter of a form whose leading controls switch other fields on and off, such
 * as a type select that enables the rows of its type (REQ-FE-041): it keeps their values and sets
 * them first, in order, each announced with `change`, so the fields they enable are enabled when
 * the draft's values are written.
 *
 * @param {readonly string[]} selectors the leading controls, in the order they are set
 * @returns {KrtDraftAdapter} the adapter to register
 */
export function leadingControlsAdapter(selectors) {
    return {
        serialize(form) {
            return selectors.map((selector) => leadingValue(form.querySelector(selector)));
        },
        restore(form, extra) {
            const values = Array.isArray(extra) ? extra : [];
            selectors.forEach((selector, index) => {
                const control = form.querySelector(selector);
                if (control && applyLeading(control, values[index])) {
                    control.dispatchEvent(new Event('change', { bubbles: true }));
                }
            });
        },
    };
}
