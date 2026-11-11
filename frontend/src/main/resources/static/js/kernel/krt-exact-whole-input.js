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

import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { caretAfter, dropDigitBesideSeparator, formatParts } from '/js/kernel/krt-number-format.js';

/** @typedef {{group: string, decimal: string}} Separators */
/** @type {WeakMap<HTMLInputElement, () => void>} */
const fields = new WeakMap();

/**
 * Reads positive whole digits without changing a non-whole or malformed value.
 *
 * @param {string} text the displayed number
 * @param {Separators} seps the page separators
 * @returns {string} canonical digits, or an empty string for invalid input
 */
function canonicalWhole(text, seps) {
    const [integer, fraction, ...extra] = text.trim().split(seps.decimal);
    if (extra.length || (fraction !== undefined && !/^0+$/.test(fraction))) return '';
    const [first, ...rest] = integer.split(seps.group);
    if (!/^\d+$/.test(first)) return '';
    if (rest.length && (first.length > 3 || rest.some((part) => !/^\d{3}$/.test(part)))) {
        return '';
    }
    return [first, ...rest].join('').replace(/^0+/, '');
}

/**
 * Keeps the caret with the same digits while the input is edited.
 *
 * @param {string} text the current visible text
 * @param {number} caret the caret in that text
 * @param {Separators} seps the page separators
 * @returns {{text: string, caret: number}} the display and resulting caret
 */
function editWhole(text, caret, seps) {
    const [integer, fraction, ...extra] = text.split(seps.decimal);
    const int = integer.split(seps.group).join('');
    if (extra.length || !/^\d+$/.test(int) || (fraction !== undefined && !/^\d*$/.test(fraction))) {
        return { text, caret };
    }
    const shown = formatParts({ neg: false, int, frac: fraction ?? null }, seps);
    const significant = text.slice(0, caret).split(seps.group).join('').length;
    return { text: shown, caret: caretAfter(shown, significant, seps) };
}

/**
 * Installs a localized text field whose valid value is an exact positive whole number.
 *
 * @param {HTMLInputElement} input the exact whole field
 * @param {Separators} seps the page separators
 * @param {(field: HTMLInputElement) => string} readText reads its visible value
 * @param {(field: HTMLInputElement, value: string) => void} writeText writes its visible value
 * @returns {() => void} refreshes a reset or updated value
 */
export function enhanceExactWhole(input, seps, readText, writeText) {
    const existing = fields.get(input);
    if (existing) return existing;
    let previous = readText(input);

    /** @param {string} text the visible field value */
    const show = (text) => {
        if (text !== readText(input)) writeText(input, text);
        previous = text;
        const invalid = text.trim() !== '' && !canonicalWhole(text, seps);
        const message = invalid
            ? input.dataset.invalid ||
              krtI18nText(window.krtNumberI18n?.whole, 'krtNumberI18n.whole')
            : '';
        input.setCustomValidity(message);
    };

    /** @param {string} text the value to format when committed */
    const commit = (text) => {
        const amount = canonicalWhole(text, seps);
        show(amount ? formatParts({ neg: false, int: amount, frac: null }, seps) : text);
    };

    Object.defineProperty(input, 'value', {
        configurable: true,
        get: () => canonicalWhole(readText(input), seps),
        set(value) {
            commit(String(value ?? ''));
        },
    });
    input.setAttribute('autocomplete', 'off');
    /** @param {Event} event the user input */
    const edited = (event) => {
        if (event instanceof InputEvent && event.isComposing) return;
        let text = readText(input);
        let caret = input.selectionStart ?? text.length;
        const kind = event instanceof InputEvent ? event.inputType : '';
        if (kind === 'deleteContentBackward' || kind === 'deleteContentForward') {
            ({ text, caret } = dropDigitBesideSeparator(
                previous,
                text,
                caret,
                kind === 'deleteContentForward',
                seps,
            ));
        }
        const multi = event instanceof InputEvent && (event.data?.length ?? 0) > 1;
        const inserted =
            multi ||
            kind === 'insertFromPaste' ||
            kind === 'insertFromDrop' ||
            kind === 'insertReplacementText';
        if (inserted && !canonicalWhole(text, seps)) {
            show(text);
            return;
        }
        const next = editWhole(text, caret, seps);
        show(next.text);
        if (input === document.activeElement) input.setSelectionRange(next.caret, next.caret);
    };
    input.addEventListener('input', edited);
    input.addEventListener('compositionend', edited);
    const sync = () => commit(readText(input));
    input.addEventListener('blur', sync);
    fields.set(input, sync);
    sync();
    return sync;
}
