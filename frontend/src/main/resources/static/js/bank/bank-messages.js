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
 * Reads the localized fallback error text from `<main data-bank-generic-error>`.
 *
 * @returns {string} the generic error message
 */
export function genericError() {
    const main = document.querySelector('main[data-bank-generic-error]');
    return main ? /** @type {string} */ (main.getAttribute('data-bank-generic-error')) : 'Error';
}

/**
 * Reads the localized "booking was filed as an approval request" notice from `<main>`, falling
 * back to the generic error text (REQ-BANK-047).
 *
 * @returns {string} the approval-request-filed notice
 */
export function approvalRequestFiledMessage() {
    const main = document.querySelector('main[data-bank-approval-request-filed]');
    return main
        ? /** @type {string} */ (main.getAttribute('data-bank-approval-request-filed'))
        : genericError();
}

/**
 * Reads the localized "saved, but reload" refresh-error message from `<main>`, falling back to
 * the generic error text.
 *
 * @returns {string} the refresh-error message
 */
export function bankRefreshError() {
    const main = document.querySelector('main[data-bank-refresh-error]');
    return main
        ? /** @type {string} */ (main.getAttribute('data-bank-refresh-error'))
        : genericError();
}
