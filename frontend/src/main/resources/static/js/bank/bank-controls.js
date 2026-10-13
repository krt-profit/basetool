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

/**
 * Toggles a transfer-only row and its select: shown, enabled and required only for a transfer,
 * otherwise hidden, disabled and cleared.
 *
 * @param {HTMLElement|null} row the transfer-only row wrapper
 * @param {boolean} isTransfer whether the current request type is TRANSFER
 */
export function toggleTransferOnlyControl(row, isTransfer) {
    if (!row) {
        return;
    }
    row.hidden = !isTransfer;
    const control = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        row.querySelector('select, .krt-combobox input[type="hidden"]')
    );
    if (control) {
        control.disabled = !isTransfer;
        control.required = isTransfer;
        if (!isTransfer) {
            if (control.krtCombobox) {
                control.krtCombobox.setValue('');
            } else {
                control.value = '';
            }
        }
        const box = control.closest ? control.closest('.krt-combobox') : null;
        const textbox = /** @type {HTMLInputElement | null} */ (
            box ? box.querySelector('.krt-combobox__input') : null
        );
        if (textbox) {
            textbox.disabled = !isTransfer;
        }
    }
}

/**
 * Enables or disables one movement-modal control, clearing its value (or combobox selection) when
 * disabled; a combobox's visible textbox mirrors the disabled state.
 *
 * @param {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} control the form control to
 *     toggle
 * @param {boolean} on whether the owning row is active for the current movement type
 */
export function setMovementControlActive(control, on) {
    control.disabled = !on;
    if (control.type === 'checkbox') {
        if (!on) {
            /** @type {HTMLInputElement} */ (control).checked = false;
        }
    } else if (!on) {
        if (control.krtCombobox) {
            control.krtCombobox.setValue('');
        } else {
            control.value = '';
        }
    }
    const box = control.closest ? control.closest('.krt-combobox') : null;
    const textbox = /** @type {HTMLInputElement | null} */ (
        box ? box.querySelector('.krt-combobox__input') : null
    );
    if (textbox) {
        textbox.disabled = !on;
    }
}

/**
 * Shows or hides a movement-modal row and enables or disables every submittable control in it.
 *
 * @param {HTMLElement} row the [data-movement-types] wrapper
 * @param {boolean} on whether the row is active for the current movement type
 */
export function setMovementRowActive(row, on) {
    row.hidden = !on;
    /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>} */ (
        row.querySelectorAll(
            'input:not(.krt-combobox__input), select, textarea, .krt-combobox input[type="hidden"]',
        )
    ).forEach((control) => {
        setMovementControlActive(control, on);
    });
}
