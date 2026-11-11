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

import {
    canonicalOf,
    EXACT_WHOLE_INPUT_MODE,
    formatParts,
    separatorsFor,
} from '/js/kernel/krt-number-format.js';

/**
 * Reads a positive whole aUEC value in the page language without passing through a JS number.
 *
 * @param {string} text the visible input value
 * @returns {string | null} the exact canonical value, or null for invalid input
 */
export function readWholeAuec(text) {
    const { group, decimal } = separatorsFor(document.documentElement.lang);
    const [integer, fraction, ...extra] = text.trim().split(decimal);
    if (extra.length || (fraction !== undefined && !/^0+$/.test(fraction))) return null;
    const [first, ...rest] = integer.split(group);
    if (!/^\d+$/.test(first)) return null;
    if (rest.length && (first.length > 3 || rest.some((part) => !/^\d{3}$/.test(part)))) {
        return null;
    }
    const int = [first, ...rest].join('').replace(/^0+/, '');
    return int ? canonicalOf({ neg: false, int, frac: null }) : null;
}

/**
 * Formats canonical whole aUEC for the page language.
 *
 * @param {string} amount canonical decimal digits
 * @returns {string} the grouped amount
 */
export function formatWholeAuec(amount) {
    const parts = { neg: false, int: amount, frac: null };
    return formatParts(parts, separatorsFor(document.documentElement.lang));
}

/**
 * Adds exact validation and locale grouping to the visible money control.
 *
 * @param {HTMLInputElement} input the monetary input
 */
export function bindWholeAuec(input) {
    if (input.dataset.numberInput !== EXACT_WHOLE_INPUT_MODE) {
        throw new Error('An aUEC amount requires an exact whole number input');
    }
    input.addEventListener('input', () => input.setCustomValidity(''));
    input.addEventListener('blur', () => {
        const amount = readWholeAuec(input.value);
        if (amount) input.value = formatWholeAuec(amount);
    });
}

/**
 * Reads a required whole-aUEC input and reports invalid terms on the ordinary form.
 *
 * @param {HTMLInputElement} input the monetary input
 * @returns {string | null} the valid canonical value
 */
export function requireWholeAuec(input) {
    const amount = readWholeAuec(input.value);
    if (amount) return amount;
    input.setCustomValidity(input.dataset.invalid || '');
    input.reportValidity();
    return null;
}
