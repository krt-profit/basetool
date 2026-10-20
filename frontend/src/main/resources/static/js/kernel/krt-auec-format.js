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
 * An aUEC amount split into its digit strings.
 *
 * @typedef {{ int: string, frac: string | null }} KrtAuecParts
 */

/**
 * The outcome of reading typed text: the amount and how many significant characters (digits and
 * the decimal comma) stood before the caret.
 *
 * @typedef {{ parts: KrtAuecParts, significantBeforeCaret: number }} KrtAuecReading
 */

/** The thousands separator shown in an aUEC field, as on the bank pages. */
export const AUEC_GROUP = '.';

/** The decimal separator shown in an aUEC field. */
export const AUEC_DECIMAL = ',';

/** The most integer digits a field keeps; more cannot be represented exactly as a number. */
export const AUEC_MAX_INT_DIGITS = 15;

/**
 * Reads the text of a field as the user sees it: `.` groups thousands, `,` starts the fraction
 * and every other character is dropped. A `.` at `decimalDotIndex` is the decimal separator
 * instead, for a decimal point the user just typed or pasted.
 *
 * @param {string} text the field text
 * @param {number} caret the caret position in `text`
 * @param {number} fractionDigits the decimals the field allows; 0 for whole aUEC
 * @param {number} [decimalDotIndex] the index of a `.` to read as the decimal separator
 * @returns {KrtAuecReading} the amount and the significant characters before the caret
 */
export function readDisplay(text, caret, fractionDigits, decimalDotIndex = -1) {
    let int = '';
    /** @type {string | null} */
    let frac = null;
    let significantBeforeCaret = 0;
    for (let i = 0; i < text.length; i++) {
        const ch = text.charAt(i);
        let kept = false;
        if (ch >= '0' && ch <= '9') {
            if (frac === null) {
                if (int.length < AUEC_MAX_INT_DIGITS) {
                    int += ch;
                    kept = true;
                }
            } else if (frac.length < fractionDigits) {
                frac += ch;
                kept = true;
            }
        } else if (
            fractionDigits > 0 &&
            frac === null &&
            (ch === AUEC_DECIMAL || (ch === AUEC_GROUP && i === decimalDotIndex))
        ) {
            frac = '';
            kept = true;
        }
        if (kept && i < caret) {
            significantBeforeCaret++;
        }
    }
    const zeros = /^0+/.exec(int);
    if (zeros) {
        const strip = Math.min(zeros[0].length, int.length - 1);
        int = int.slice(strip);
        significantBeforeCaret -= Math.min(strip, significantBeforeCaret);
    }
    if (int === '' && frac !== null) {
        int = '0';
        if (significantBeforeCaret > 0) {
            significantBeforeCaret++;
        }
    }
    return { parts: { int, frac }, significantBeforeCaret };
}

/**
 * Reads a canonical amount as code and the server write it, `1500` or `1500.25`; text that is not
 * canonical is read as display text.
 *
 * @param {unknown} raw the value handed to the field
 * @param {number} fractionDigits the decimals the field allows; 0 for whole aUEC
 * @returns {KrtAuecParts} the amount
 */
export function readCanonical(raw, fractionDigits) {
    const text = raw === null || raw === undefined ? '' : String(raw).trim();
    const match = /^(\d*)(?:\.(\d*))?$/.exec(text);
    if (!match) {
        return readDisplay(text, 0, fractionDigits).parts;
    }
    let int = match[1].replace(/^0+(?=\d)/, '').slice(0, AUEC_MAX_INT_DIGITS);
    /** @type {string | null} */
    let frac = match[2] === undefined ? null : match[2].replace(/0+$/, '');
    if (frac !== null && frac.length === 0) {
        frac = null;
    }
    if (int === '' && frac !== null) {
        int = '0';
    }
    return { int, frac };
}

/**
 * Formats an amount for display: `.` between thousands, `,` before the fraction.
 *
 * @param {KrtAuecParts} parts the amount
 * @returns {string} the display text, `""` for no amount
 */
export function formatParts(parts) {
    const grouped = parts.int.replace(/\B(?=(\d{3})+(?!\d))/g, AUEC_GROUP);
    if (parts.frac === null) {
        return grouped;
    }
    return `${grouped || '0'}${AUEC_DECIMAL}${parts.frac}`;
}

/**
 * The canonical value of an amount as forms send it: digits, a `.` before a non-empty fraction.
 *
 * @param {KrtAuecParts} parts the amount
 * @returns {string} the canonical value, `""` for no amount
 */
export function canonicalOf(parts) {
    if (parts.int === '' && (parts.frac === null || parts.frac === '')) {
        return '';
    }
    const int = parts.int === '' ? '0' : parts.int;
    return parts.frac ? `${int}.${parts.frac}` : int;
}

/**
 * Finds the caret position in display text after the given number of significant characters.
 *
 * @param {string} display the formatted text
 * @param {number} significant the significant characters that must stand before the caret
 * @returns {number} the caret index
 */
export function caretAfter(display, significant) {
    if (significant <= 0) {
        return 0;
    }
    let seen = 0;
    for (let i = 0; i < display.length; i++) {
        if (display.charAt(i) !== AUEC_GROUP) {
            seen++;
            if (seen === significant) {
                return i + 1;
            }
        }
    }
    return display.length;
}

/**
 * Removes the digit beside a deleted thousands separator, so Backspace or Delete on a `.` removes
 * the digit the user aimed at instead of a separator that would come straight back.
 *
 * @param {string} before the field text before the edit
 * @param {string} after the field text after the edit
 * @param {number} caret the caret position in `after`
 * @param {boolean} forward true for Delete, false for Backspace
 * @returns {{ text: string, caret: number }} the corrected text and caret
 */
export function dropDigitBesideSeparator(before, after, caret, forward) {
    const digits = (/** @type {string} */ s) => s.replace(/[^0-9,]/g, '');
    if (after.length !== before.length - 1 || digits(after) !== digits(before)) {
        return { text: after, caret };
    }
    if (forward) {
        for (let i = caret; i < after.length; i++) {
            if (/[0-9]/.test(after.charAt(i))) {
                return { text: after.slice(0, i) + after.slice(i + 1), caret };
            }
        }
        return { text: after, caret };
    }
    for (let i = caret - 1; i >= 0; i--) {
        if (/[0-9]/.test(after.charAt(i))) {
            return { text: after.slice(0, i) + after.slice(i + 1), caret: i };
        }
    }
    return { text: after, caret };
}

/**
 * Finds a `.` the user meant as a decimal point: in a decimal field without a `,`, the last `.`
 * when it is followed by fewer digits than a thousands group.
 *
 * @param {string} text the field text after a typed or pasted insertion
 * @param {number} fractionDigits the decimals the field allows
 * @returns {number} the index of that `.`, or -1
 */
export function typedDecimalDot(text, fractionDigits) {
    if (fractionDigits <= 0 || text.includes(AUEC_DECIMAL)) {
        return -1;
    }
    const dot = text.lastIndexOf(AUEC_GROUP);
    if (dot < 0) {
        return -1;
    }
    const tail = /^\d*/.exec(text.slice(dot + 1));
    const digitsAfter = tail ? tail[0].length : 0;
    return digitsAfter < 3 ? dot : -1;
}
