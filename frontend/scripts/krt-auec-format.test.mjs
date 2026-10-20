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
 * Regression tests for the live thousands separators of the aUEC fields (REQ-UI-037): what is shown,
 * what forms send, and where the caret lands while the user types.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('kernel/krt-auec-format.js'), context);
const fn = (/** @type {string} */ name) => vm.runInContext(name, context);
const readDisplay = fn('readDisplay');
const readCanonical = fn('readCanonical');
const formatParts = fn('formatParts');
const canonicalOf = fn('canonicalOf');
const caretAfter = fn('caretAfter');
const dropDigitBesideSeparator = fn('dropDigitBesideSeparator');
const typedDecimalDot = fn('typedDecimalDot');

/**
 * Simulates one keystroke result: reads the raw text and caret, formats it and places the caret.
 *
 * @param {string} text the field text after the browser applied the keystroke
 * @param {number} caret the caret after the keystroke
 * @param {number} digits the allowed decimals
 * @param {number} [dot] a `.` index to read as the decimal point
 * @returns {{ shown: string, caret: number, canonical: string }} the formatted outcome
 */
function type(text, caret, digits, dot = -1) {
    const reading = readDisplay(text, caret, digits, dot);
    const shown = formatParts(reading.parts);
    return {
        shown,
        caret: caretAfter(shown, reading.significantBeforeCaret),
        canonical: canonicalOf(reading.parts),
    };
}

const grouping = [
    ['', ''],
    ['0', '0'],
    ['999', '999'],
    ['1000', '1.000'],
    ['1234567', '1.234.567'],
    ['007', '7'],
    ['1.2.3.4', '1.234'],
    ['3,000,000 aUEC', '3.000.000'],
];
for (const [text, shown] of grouping) {
    assert.equal(type(text, text.length, 0).shown, shown, `whole: ${text}`);
}

assert.deepEqual(type('1.2345', 6, 0), { shown: '12.345', caret: 6, canonical: '12345' });
assert.deepEqual(type('19.234.567', 2, 0), {
    shown: '19.234.567',
    caret: 2,
    canonical: '19234567',
});
assert.deepEqual(type('1234567890123456789', 19, 0).canonical, '123456789012345');

assert.deepEqual(type('12.000,5', 8, 2), { shown: '12.000,5', caret: 8, canonical: '12000.5' });
assert.deepEqual(type('12.000,579', 10, 2).canonical, '12000.57');
assert.deepEqual(type('12,', 3, 2), { shown: '12,', caret: 3, canonical: '12' });
assert.deepEqual(type(',5', 2, 2), { shown: '0,5', caret: 3, canonical: '0.5' });
assert.deepEqual(type('12,5', 4, 0), { shown: '125', caret: 3, canonical: '125' });

assert.equal(typedDecimalDot('12.000.', 2), 6);
assert.equal(typedDecimalDot('1234.56', 2), 4);
assert.equal(typedDecimalDot('1.234', 2), -1);
assert.equal(typedDecimalDot('12.5', 0), -1);
assert.equal(typedDecimalDot('1.234,5', 2), -1);
assert.deepEqual(type('12.000.', 7, 2, 6), { shown: '12.000,', caret: 7, canonical: '12000' });

assert.deepEqual(
    { ...dropDigitBesideSeparator('1.234', '1234', 1, false) },
    { text: '234', caret: 0 },
);
assert.deepEqual(
    { ...dropDigitBesideSeparator('23.567', '23567', 2, true) },
    { text: '2367', caret: 2 },
);
assert.deepEqual(
    { ...dropDigitBesideSeparator('1.234', '1.24', 3, false) },
    { text: '1.24', caret: 3 },
);

const canonical = [
    ['1500', { int: '1500', frac: null }],
    ['1500.00', { int: '1500', frac: null }],
    ['1500.50', { int: '1500', frac: '5' }],
    ['.5', { int: '0', frac: '5' }],
    ['0042', { int: '42', frac: null }],
    ['', { int: '', frac: null }],
];
for (const [raw, parts] of canonical) {
    assert.deepEqual({ ...readCanonical(raw, 2) }, parts, `canonical: ${raw}`);
}
assert.deepEqual({ ...readCanonical(null, 0) }, { int: '', frac: null });
assert.deepEqual({ ...readCanonical('1.500.000', 0) }, { int: '1500000', frac: null });

assert.equal(caretAfter('1.234.567', 0), 0);
assert.equal(caretAfter('1.234.567', 1), 1);
assert.equal(caretAfter('1.234.567', 2), 3);
assert.equal(caretAfter('1.234.567', 99), 9);

console.log('krt-auec-format: all cases passed');
