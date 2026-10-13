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

import { showFrontendErrorToast } from '/js/kernel/toast.js';

/**
 * Hides and empties every inline error slot of a form.
 *
 * @param {HTMLFormElement} form the bank AJAX form
 */
export function clearErrors(form) {
    form.querySelectorAll('.bank-field-error').forEach((el) => {
        el.textContent = '';
        el.classList.remove('visible');
    });
}

/**
 * Shows a localized message in the error slot of the named field, falling back
 * to the form's `_global` slot and finally to an error toast.
 *
 * @param {HTMLFormElement} form the bank AJAX form
 * @param {string} field the field name the message belongs to
 * @param {string} message the localized message
 */
export function showError(form, field, message) {
    let slot = form.querySelector(`.bank-field-error[data-error-for="${field}"]`);
    if (!slot) {
        slot = form.querySelector('.bank-field-error[data-error-for="_global"]');
    }
    if (slot) {
        slot.textContent = message;
        slot.classList.add('visible');
    } else {
        showFrontendErrorToast(message);
    }
}
