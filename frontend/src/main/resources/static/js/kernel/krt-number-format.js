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
 * A number split into its sign and digit strings.
 *
 * @typedef {{ neg: boolean, int: string, frac: string | null }} KrtNumberParts
 */

/**
 * The outcome of reading typed text: the number, how many significant characters (sign, digits
 * and the decimal separator) stood before the caret, and the fraction digits beyond the field's
 * decimals that were left out.
 *
 * @typedef {{ parts: KrtNumberParts, significantBeforeCaret: number, extra: string }} KrtNumberReading
 */

/**
 * The separators of the page language: `.` and `,` in German, `,` and `.` in English.
 *
 * @typedef {{ group: string, decimal: string }} KrtNumberSeparators
 */

/**
 * How a field reads numbers: the decimals it allows and whether it takes a minus sign.
 *
 * @typedef {{ fractionDigits: number, allowNegative: boolean }} KrtNumberRules
 */

/** The separators of German, the fallback when a language yields none. */
export const NUMBER_GERMAN = Object.freeze({ group: '.', decimal: ',' });

/** The most integer digits a field keeps; more cannot be represented exactly as a number. */
export const NUMBER_MAX_INT_DIGITS = 15;

/**
 * Finds the thousands and decimal separators of a language.
 *
 * @param {string} lang a BCP 47 language tag such as `de` or `en`
 * @returns {KrtNumberSeparators} the separators, German when the language yields none
 */
export function separatorsFor(lang) {
    try {
        const parts = new Intl.NumberFormat(lang || 'de').formatToParts(1234567.5);
        const group = parts.find((p) => p.type === 'group');
        const decimal = parts.find((p) => p.type === 'decimal');
        if (group && decimal && group.value !== decimal.value) {
            return { group: group.value, decimal: decimal.value };
        }
    } catch (_e) {}
    return NUMBER_GERMAN;
}

/**
 * Strips leading zeros from an integer digit string, keeping one digit.
 *
 * @param {string} int the integer digits
 * @returns {string} the digits without leading zeros
 */
function stripLeadingZeros(int) {
    return int.replace(/^0+(?=\d)/, '');
}

/**
 * Reads the text of a field as the user sees it: the group separator and every other character
 * are dropped, the decimal separator starts the fraction and a `-` before the first digit makes the
 * number negative where the field allows it. The group separator at `decimalAtIndex` starts the
 * fraction instead, for a pasted number written with the other convention.
 *
 * @param {string} text the field text
 * @param {number} caret the caret position in `text`
 * @param {KrtNumberRules} rules the decimals and sign the field allows
 * @param {KrtNumberSeparators} seps the separators of the page language
 * @param {number} [decimalAtIndex] the index of a group separator to read as the decimal one
 * @returns {KrtNumberReading} the number and the significant characters before the caret
 */
export function readDisplay(text, caret, rules, seps, decimalAtIndex = -1) {
    let neg = false;
    let int = '';
    /** @type {string | null} */
    let frac = null;
    let extra = '';
    let significantBeforeCaret = 0;
    for (let i = 0; i < text.length; i++) {
        const ch = text.charAt(i);
        let kept = false;
        if (ch >= '0' && ch <= '9') {
            if (frac === null) {
                if (int.length < NUMBER_MAX_INT_DIGITS) {
                    int += ch;
                    kept = true;
                }
            } else if (frac.length < rules.fractionDigits) {
                frac += ch;
                kept = true;
            } else {
                extra += ch;
            }
        } else if (ch === '-' && rules.allowNegative && !neg && int === '' && frac === null) {
            neg = true;
            kept = true;
        } else if (
            rules.fractionDigits > 0 &&
            frac === null &&
            (ch === seps.decimal || (ch === seps.group && i === decimalAtIndex))
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
        significantBeforeCaret -= Math.min(
            strip,
            Math.max(0, significantBeforeCaret - (neg ? 1 : 0)),
        );
    }
    if (int === '' && frac !== null) {
        int = '0';
        if (significantBeforeCaret > (neg ? 1 : 0)) {
            significantBeforeCaret++;
        }
    }
    return { parts: { neg, int, frac }, significantBeforeCaret, extra };
}

/**
 * Adds 1 to a string of decimal digits, propagating the carry (`999` becomes `1000`).
 *
 * @param {string} digits the digits
 * @returns {string} the incremented digits
 */
function incrementDigits(digits) {
    const a = digits.split('');
    let i = a.length - 1;
    while (i >= 0 && a[i] === '9') {
        a[i] = '0';
        i--;
    }
    if (i < 0) {
        a.unshift('1');
    } else {
        a[i] = String.fromCharCode(a[i].charCodeAt(0) + 1);
    }
    return a.join('');
}

/**
 * Rounds a number half up by the fraction digits it lost (commercial rounding, REQ-INV-042):
 * `1.234` with `5` left out becomes `1.235`, `12.999` with `5` becomes `13`.
 *
 * @param {KrtNumberParts} parts the number cut to the field's decimals
 * @param {string} extra the fraction digits that were cut off
 * @returns {KrtNumberParts} the rounded number
 */
export function roundHalfUp(parts, extra) {
    if (extra === '' || extra.charAt(0) < '5' || parts.frac === null) {
        return parts;
    }
    const fracLength = parts.frac.length;
    const raised = incrementDigits((parts.int || '0') + parts.frac);
    const int = stripLeadingZeros(raised.slice(0, raised.length - fracLength)) || '0';
    const frac = raised.slice(raised.length - fracLength).replace(/0+$/, '');
    return { neg: parts.neg, int, frac: frac === '' ? null : frac };
}

/**
 * Reads a canonical number as code and the server write it (`1500`, `-3`, `1500.25`), rounded
 * half up to the field's decimals; text that is not canonical is read as display text.
 *
 * @param {unknown} raw the value handed to the field
 * @param {KrtNumberRules} rules the decimals and sign the field allows
 * @param {KrtNumberSeparators} seps the separators of the page language
 * @returns {KrtNumberParts} the number
 */
export function readCanonical(raw, rules, seps) {
    const text = raw === null || raw === undefined ? '' : String(raw).trim();
    const match = /^(-?)(\d*)(?:\.(\d*))?$/.exec(text);
    if (!match) {
        const reading = readDisplay(text, 0, rules, seps);
        return roundHalfUp(reading.parts, reading.extra);
    }
    let int = stripLeadingZeros(match[2]).slice(0, NUMBER_MAX_INT_DIGITS);
    const allFrac = match[3] === undefined ? '' : match[3];
    const extra = allFrac.slice(rules.fractionDigits);
    /** @type {string | null} */
    let frac = allFrac.slice(0, rules.fractionDigits);
    if (int === '' && (frac !== '' || extra !== '')) {
        int = '0';
    }
    const neg = match[1] === '-' && rules.allowNegative && int !== '';
    const rounded = roundHalfUp(
        { neg, int, frac: frac === '' && extra === '' ? null : frac },
        extra,
    );
    frac = rounded.frac === null ? null : rounded.frac.replace(/0+$/, '');
    return { neg: rounded.neg, int: rounded.int, frac: frac === '' ? null : frac };
}

/**
 * Formats a number for display with the separators of the page language.
 *
 * @param {KrtNumberParts} parts the number
 * @param {KrtNumberSeparators} seps the separators of the page language
 * @returns {string} the display text, `""` for no number
 */
export function formatParts(parts, seps) {
    const sign = parts.neg ? '-' : '';
    const grouped = parts.int.replace(/\B(?=(\d{3})+(?!\d))/g, seps.group);
    if (parts.frac === null) {
        return sign + grouped;
    }
    return `${sign}${grouped || '0'}${seps.decimal}${parts.frac}`;
}

/**
 * The canonical value of a number as forms send it: an optional `-`, digits, a `.` before a
 * non-empty fraction.
 *
 * @param {KrtNumberParts} parts the number
 * @returns {string} the canonical value, `""` for no number
 */
export function canonicalOf(parts) {
    if (parts.int === '' && (parts.frac === null || parts.frac === '')) {
        return '';
    }
    const int = parts.int === '' ? '0' : parts.int;
    const body = parts.frac ? `${int}.${parts.frac}` : int;
    return parts.neg && /[1-9]/.test(body) ? `-${body}` : body;
}

/**
 * Finds the caret position in display text after the given number of significant characters.
 *
 * @param {string} display the formatted text
 * @param {number} significant the significant characters that must stand before the caret
 * @param {KrtNumberSeparators} seps the separators of the page language
 * @returns {number} the caret index
 */
export function caretAfter(display, significant, seps) {
    if (significant <= 0) {
        return 0;
    }
    let seen = 0;
    for (let i = 0; i < display.length; i++) {
        if (display.charAt(i) !== seps.group) {
            seen++;
            if (seen === significant) {
                return i + 1;
            }
        }
    }
    return display.length;
}

/**
 * Removes the digit beside a deleted thousands separator, so Backspace or Delete on a separator
 * removes the digit the user aimed at instead of a separator that would come straight back.
 *
 * @param {string} before the field text before the edit
 * @param {string} after the field text after the edit
 * @param {number} caret the caret position in `after`
 * @param {boolean} forward true for Delete, false for Backspace
 * @param {KrtNumberSeparators} seps the separators of the page language
 * @returns {{ text: string, caret: number }} the corrected text and caret
 */
export function dropDigitBesideSeparator(before, after, caret, forward, seps) {
    const significant = (/** @type {string} */ s) =>
        Array.from(s)
            .filter((c) => (c >= '0' && c <= '9') || c === seps.decimal || c === '-')
            .join('');
    if (after.length !== before.length - 1 || significant(after) !== significant(before)) {
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
 * Finds a group separator a pasted number used as its decimal separator, such as `1234.56` pasted
 * into a German field: in a decimal field without the language's decimal separator, the only group
 * separator when one or two digits end the text after it.
 *
 * @param {string} text the field text after the paste
 * @param {number} fractionDigits the decimals the field allows
 * @param {KrtNumberSeparators} seps the separators of the page language
 * @returns {number} the index of that separator, or -1
 */
export function pastedDecimalIndex(text, fractionDigits, seps) {
    if (fractionDigits <= 0 || text.includes(seps.decimal)) {
        return -1;
    }
    const at = text.lastIndexOf(seps.group);
    if (at < 0 || text.indexOf(seps.group) !== at) {
        return -1;
    }
    return /^\d{1,2}$/.test(text.slice(at + 1).trim()) ? at : -1;
}
