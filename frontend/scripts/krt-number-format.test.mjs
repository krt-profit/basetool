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
 * Regression tests for the live number field formatting (REQ-UI-037): the separators of German
 * and English, the sign, the decimals a field allows, what forms send and where the caret lands.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({ Intl });
vm.runInContext(flattenModule('kernel/krt-number-format.js'), context);
const fn = (/** @type {string} */ name) => vm.runInContext(name, context);
const separatorsFor = fn('separatorsFor');
const readDisplay = fn('readDisplay');
const readCanonical = fn('readCanonical');
const formatParts = fn('formatParts');
const canonicalOf = fn('canonicalOf');
const caretAfter = fn('caretAfter');
const dropDigitBesideSeparator = fn('dropDigitBesideSeparator');
const pastedDecimalIndex = fn('pastedDecimalIndex');

const DE = { ...separatorsFor('de') };
const EN = { ...separatorsFor('en') };
assert.deepEqual(DE, { group: '.', decimal: ',' });
assert.deepEqual(EN, { group: ',', decimal: '.' });

const WHOLE = { fractionDigits: 0, allowNegative: false };
const MONEY = { fractionDigits: 2, allowNegative: false };
const SCU = { fractionDigits: 3, allowNegative: false };
const SIGNED = { fractionDigits: 0, allowNegative: true };

/**
 * Simulates one keystroke result: reads the raw text and caret, formats it and places the caret.
 *
 * @param {string} text the field text after the browser applied the keystroke
 * @param {number} caret the caret after the keystroke
 * @param {{ fractionDigits: number, allowNegative: boolean }} rules the field's rules
 * @param {{ group: string, decimal: string }} seps the page separators
 * @param {number} [at] a group separator index to read as the decimal one
 * @returns {{ shown: string, caret: number, canonical: string }} the formatted outcome
 */
function type(text, caret, rules, seps, at = -1) {
    const reading = readDisplay(text, caret, rules, seps, at);
    const shown = formatParts(reading.parts, seps);
    return {
        shown,
        caret: caretAfter(shown, reading.significantBeforeCaret, seps),
        canonical: canonicalOf(reading.parts),
    };
}

assert.deepEqual(type('19999990', 8, MONEY, DE).shown, '19.999.990');
assert.deepEqual(type('1999999,0', 9, MONEY, DE), {
    shown: '1.999.999,0',
    caret: 11,
    canonical: '1999999.0',
});
assert.deepEqual(type('1999999.0', 9, MONEY, EN), {
    shown: '1,999,999.0',
    caret: 11,
    canonical: '1999999.0',
});
assert.deepEqual(type('1999999.05', 10, MONEY, EN).canonical, '1999999.05');

const grouping = [
    ['', '', ''],
    ['0', '0', '0'],
    ['999', '999', '999'],
    ['1000', '1.000', '1,000'],
    ['1234567', '1.234.567', '1,234,567'],
    ['007', '7', '7'],
    ['3,000,000 aUEC', '3.000.000', '3,000,000'],
    ['3.000.000 aUEC', '3.000.000', '3,000,000'],
];
for (const [text, de, en] of grouping) {
    assert.equal(type(text, text.length, WHOLE, DE).shown, de, `de whole: ${text}`);
    assert.equal(type(text, text.length, WHOLE, EN).shown, en, `en whole: ${text}`);
}

assert.deepEqual(type('1.2345', 6, WHOLE, DE), { shown: '12.345', caret: 6, canonical: '12345' });
assert.deepEqual(type('1,2345', 6, WHOLE, EN), { shown: '12,345', caret: 6, canonical: '12345' });
assert.deepEqual(type('19.234.567', 2, WHOLE, DE), {
    shown: '19.234.567',
    caret: 2,
    canonical: '19234567',
});
assert.equal(type('1234567890123456789', 19, WHOLE, DE).canonical, '123456789012345');

assert.deepEqual(type('12,345', 6, SCU, DE), { shown: '12,345', caret: 6, canonical: '12.345' });
assert.deepEqual(type('12.345', 6, SCU, EN), { shown: '12.345', caret: 6, canonical: '12.345' });
assert.equal(type('1234,5678', 9, SCU, DE).canonical, '1234.567');
assert.equal(type('1.000', 5, SCU, DE).canonical, '1000');
assert.equal(type('1,000', 5, SCU, EN).canonical, '1000');
assert.deepEqual(type('12,', 3, MONEY, DE), { shown: '12,', caret: 3, canonical: '12' });
assert.deepEqual(type(',5', 2, MONEY, DE), { shown: '0,5', caret: 3, canonical: '0.5' });
assert.deepEqual(type('.5', 2, MONEY, EN), { shown: '0.5', caret: 3, canonical: '0.5' });
assert.deepEqual(type('12,5', 4, WHOLE, DE), { shown: '125', caret: 3, canonical: '125' });

assert.deepEqual(type('-5', 2, SIGNED, DE), { shown: '-5', caret: 2, canonical: '-5' });
assert.deepEqual(type('-1234', 5, SIGNED, DE), { shown: '-1.234', caret: 6, canonical: '-1234' });
assert.deepEqual(type('-', 1, SIGNED, DE), { shown: '-', caret: 1, canonical: '' });
assert.deepEqual(type('-5', 2, WHOLE, DE), { shown: '5', caret: 1, canonical: '5' });
assert.deepEqual(type('5-', 2, SIGNED, DE), { shown: '5', caret: 1, canonical: '5' });
assert.equal(type('-0', 2, SIGNED, DE).canonical, '0');

assert.equal(pastedDecimalIndex('1234.56', 2, DE), 4);
assert.equal(pastedDecimalIndex('1234,56', 2, EN), 4);
assert.equal(pastedDecimalIndex('1.234', 3, DE), -1);
assert.equal(pastedDecimalIndex('1.234.56', 2, DE), -1);
assert.equal(pastedDecimalIndex('12.5', 0, DE), -1);
assert.equal(pastedDecimalIndex('1.234,5', 2, DE), -1);
assert.equal(type('1234.56', 7, MONEY, DE, 4).shown, '1.234,56');

assert.deepEqual(
    { ...dropDigitBesideSeparator('1.234', '1234', 1, false, DE) },
    { text: '234', caret: 0 },
);
assert.deepEqual(
    { ...dropDigitBesideSeparator('1,234', '1234', 1, false, EN) },
    { text: '234', caret: 0 },
);
assert.deepEqual(
    { ...dropDigitBesideSeparator('23.567', '23567', 2, true, DE) },
    { text: '2367', caret: 2 },
);
assert.deepEqual(
    { ...dropDigitBesideSeparator('1.234', '1.24', 3, false, DE) },
    { text: '1.24', caret: 3 },
);

const canonical = [
    ['1500', { neg: false, int: '1500', frac: null }],
    ['1500.00', { neg: false, int: '1500', frac: null }],
    ['1500.50', { neg: false, int: '1500', frac: '5' }],
    ['.5', { neg: false, int: '0', frac: '5' }],
    ['0042', { neg: false, int: '42', frac: null }],
    ['', { neg: false, int: '', frac: null }],
];
for (const [raw, parts] of canonical) {
    assert.deepEqual({ ...readCanonical(raw, SCU, DE) }, parts, `canonical: ${raw}`);
}
assert.deepEqual({ ...readCanonical('-3', SIGNED, DE) }, { neg: true, int: '3', frac: null });
assert.deepEqual({ ...readCanonical('-3', WHOLE, DE) }, { neg: false, int: '3', frac: null });
assert.deepEqual({ ...readCanonical(null, WHOLE, DE) }, { neg: false, int: '', frac: null });
assert.equal(formatParts(readCanonical('1999999.5', MONEY, DE), DE), '1.999.999,5');
assert.equal(formatParts(readCanonical('1999999.5', MONEY, EN), EN), '1,999,999.5');

assert.equal(caretAfter('1.234.567', 0, DE), 0);
assert.equal(caretAfter('1.234.567', 1, DE), 1);
assert.equal(caretAfter('1.234.567', 2, DE), 3);
assert.equal(caretAfter('1,234,567', 2, EN), 3);
assert.equal(caretAfter('1.234.567', 99, DE), 9);

const roundHalfUp = fn('roundHalfUp');
const rounding = [
    ['0.0015', '0.002'],
    ['1.2345', '1.235'],
    ['12.9995', '13'],
    ['1.2344', '1.234'],
    ['0.0004', ''],
    ['999.9999', '1000'],
];
for (const [raw, expected] of rounding) {
    const got = canonicalOf(readCanonical(raw, SCU, DE));
    assert.equal(got === '0' ? '' : got, expected, `round: ${raw}`);
}
assert.equal(canonicalOf(readCanonical('12.7', WHOLE, DE)), '13');
assert.equal(canonicalOf(readCanonical('12.4', WHOLE, DE)), '12');
const cut = readDisplay('1,23456', 7, SCU, DE);
assert.equal(canonicalOf(cut.parts), '1.234');
assert.equal(cut.extra, '56');
assert.equal(canonicalOf(roundHalfUp(cut.parts, cut.extra)), '1.235');
assert.equal(formatParts(roundHalfUp(cut.parts, cut.extra), EN), '1.235');

console.log('krt-number-format: all cases passed');
