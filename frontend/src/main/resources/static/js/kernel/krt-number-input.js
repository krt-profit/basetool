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
import {
    canonicalOf,
    caretAfter,
    dropDigitBesideSeparator,
    formatParts,
    pastedDecimalIndex,
    readCanonical,
    readDisplay,
    roundHalfUp,
    separatorsFor,
} from '/js/kernel/krt-number-format.js';

/**
 * The state of one enhanced field: the text last written into it and the canonical value of that
 * text.
 *
 * @typedef {{ shown: string, canonical: string }} KrtNumberState
 */

const SELECTOR = 'input[data-number-input]';

/** The thousands and decimal separators of the page language. */
const SEPS = separatorsFor(document.documentElement.lang);

const NATIVE_VALUE = /** @type {PropertyDescriptor} */ (
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
);

/** @type {WeakMap<HTMLInputElement, KrtNumberState>} */
const states = new WeakMap();

/**
 * Reads the text the browser holds for a field, past the canonical `value` an enhanced field
 * reports.
 *
 * @param {HTMLInputElement} el the field
 * @returns {string} the shown text
 */
function shownText(el) {
    return /** @type {() => string} */ (NATIVE_VALUE.get).call(el);
}

/**
 * Writes the text the browser shows for a field.
 *
 * @param {HTMLInputElement} el the field
 * @param {string} text the text to show
 */
function writeShown(el, text) {
    /** @type {(v: string) => void} */ (NATIVE_VALUE.set).call(el, text);
}

/**
 * Reads a numeric attribute of a field.
 *
 * @param {HTMLInputElement} el the field
 * @param {string} name the attribute name
 * @returns {number | null} the number, or null when absent or not a number
 */
function numberAttribute(el, name) {
    const raw = el.getAttribute(name);
    if (raw === null || raw.trim() === '') {
        return null;
    }
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
}

/**
 * How a field reads numbers. The decimals come from its `step`: none for a whole step or none
 * given, three for `any`, otherwise the step's own decimals. A minus sign is allowed when `min` is
 * absent or negative.
 *
 * @param {HTMLInputElement} el the field
 * @returns {{ fractionDigits: number, allowNegative: boolean }} the field's rules
 */
function rulesOf(el) {
    const step = (el.getAttribute('step') || '').trim();
    let fractionDigits = 0;
    if (step === 'any') {
        fractionDigits = 3;
    } else if (step !== '' && !/^\d+$/.test(step)) {
        const dot = step.indexOf('.');
        fractionDigits = dot < 0 ? 0 : step.length - dot - 1;
    }
    const min = numberAttribute(el, 'min');
    return { fractionDigits, allowNegative: min === null || min < 0 };
}

/**
 * Fills a message's `{0}` with a number in display form.
 *
 * @param {string} message the localized message
 * @param {number} bound the number
 * @param {HTMLInputElement} el the field
 * @returns {string} the message with the number
 */
function withBound(message, bound, el) {
    return message.replace(
        '{0}',
        formatParts(readCanonical(String(bound), rulesOf(el), SEPS), SEPS),
    );
}

/**
 * Computes a field's validity message from its canonical value. An SCU or piece amount field
 * (`data-scu-decimal`) must be positive, or zero or more with `data-scu-allow-zero`; any other
 * field is checked against a whole `step`, `min` and `max`. An empty field is left to `required`.
 *
 * @param {HTMLInputElement} el the field
 * @param {string} canonical the field's canonical value
 * @returns {string} the message, or "" when valid
 */
function validityMessage(el, canonical) {
    if (canonical === '') {
        return '';
    }
    const n = Number(canonical);
    const whole = rulesOf(el).fractionDigits === 0;
    if (el.hasAttribute('data-scu-decimal')) {
        const allowZero = el.hasAttribute('data-scu-allow-zero');
        if (allowZero ? n >= 0 : n > 0) {
            return '';
        }
        const scu = window.krtScuI18n || {};
        return whole
            ? krtI18nText(scu.piece, 'krtScuI18n.piece')
            : krtI18nText(scu.scu, 'krtScuI18n.scu');
    }
    const i18n = window.krtNumberI18n || {};
    if (whole && !Number.isInteger(n)) {
        return krtI18nText(i18n.whole, 'krtNumberI18n.whole');
    }
    const min = numberAttribute(el, 'min');
    if (min !== null && n < min) {
        return withBound(krtI18nText(i18n.min, 'krtNumberI18n.min'), min, el);
    }
    const max = numberAttribute(el, 'max');
    if (max !== null && n > max) {
        return withBound(krtI18nText(i18n.max, 'krtNumberI18n.max'), max, el);
    }
    return '';
}

/**
 * Shows a number in a field, keeps its canonical value and refreshes its validity.
 *
 * @param {HTMLInputElement} el the field
 * @param {{ neg: boolean, int: string, frac: string | null }} parts the number
 * @param {number | null} significantBeforeCaret where the caret goes, or null to leave it
 */
function show(el, parts, significantBeforeCaret) {
    const shown = formatParts(parts, SEPS);
    const canonical = canonicalOf(parts);
    if (shownText(el) !== shown) {
        writeShown(el, shown);
    }
    states.set(el, { shown, canonical });
    if (significantBeforeCaret !== null && el === document.activeElement) {
        const caret = caretAfter(shown, significantBeforeCaret, SEPS);
        try {
            el.setSelectionRange(caret, caret);
        } catch (_e) {}
    }
    el.setCustomValidity(validityMessage(el, canonical));
}

/**
 * Reads text this module did not write: the server's canonical value when it equals the `value`
 * attribute (a form reset), else display text (a browser restoring what the user saw).
 *
 * @param {HTMLInputElement} el the field
 * @param {string} text the shown text
 * @returns {{ neg: boolean, int: string, frac: string | null }} the number
 */
function readForeign(el, text) {
    const rules = rulesOf(el);
    return text === el.defaultValue
        ? readCanonical(text, rules, SEPS)
        : readDisplay(text, 0, rules, SEPS).parts;
}

/**
 * The canonical value of a field.
 *
 * @param {HTMLInputElement} el the field
 * @returns {string} the canonical value
 */
function canonicalValue(el) {
    const text = shownText(el);
    const state = states.get(el);
    if (state && state.shown === text) {
        return state.canonical;
    }
    return canonicalOf(readForeign(el, text));
}

/**
 * Turns a field into a live-formatted number field: it shows `1.500.000` in German and
 * `1,500,000` in English, while its `value`, `valueAsNumber` and every form entry it contributes
 * stay canonical (`1500000`).
 *
 * @param {HTMLInputElement} el the field
 */
function enhance(el) {
    if (states.has(el)) {
        return;
    }
    Object.defineProperty(el, 'value', {
        configurable: true,
        get() {
            return canonicalValue(el);
        },
        set(v) {
            show(el, readCanonical(v, rulesOf(el), SEPS), null);
        },
    });
    Object.defineProperty(el, 'valueAsNumber', {
        configurable: true,
        get() {
            const canonical = canonicalValue(el);
            return canonical === '' ? NaN : Number(canonical);
        },
        set(v) {
            show(el, readCanonical(Number.isFinite(v) ? String(v) : '', rulesOf(el), SEPS), null);
        },
    });
    const rules = rulesOf(el);
    if (!el.hasAttribute('inputmode') && !rules.allowNegative) {
        el.setAttribute('inputmode', rules.fractionDigits > 0 ? 'decimal' : 'numeric');
    }
    el.setAttribute('autocomplete', 'off');
    show(el, readForeign(el, shownText(el)), null);
}

/**
 * Enhances every number field in a subtree, the root included.
 *
 * @param {ParentNode | Element} root the subtree
 */
function enhanceWithin(root) {
    if (root instanceof HTMLInputElement && root.matches(SELECTOR)) {
        enhance(root);
    }
    /** @type {NodeListOf<HTMLInputElement>} */ (root.querySelectorAll(SELECTOR)).forEach(enhance);
}

/**
 * Re-formats an enhanced field from its canonical value, after its text or its rules changed
 * without an input event (a reset, a rewritten `value`, `step`, `min` or `max`).
 *
 * @param {HTMLInputElement} el the field
 */
function resync(el) {
    if (!states.has(el)) {
        return;
    }
    show(el, readCanonical(canonicalValue(el), rulesOf(el), SEPS), null);
}

/**
 * Formats a field as the user types, keeping the caret beside the character it was at.
 *
 * @param {Event} e the input event
 */
function onInput(e) {
    const el = /** @type {HTMLInputElement} */ (e.target);
    if (!(el instanceof HTMLInputElement) || !el.matches(SELECTOR)) {
        return;
    }
    enhance(el);
    const input = /** @type {InputEvent} */ (e);
    if (input.isComposing) {
        return;
    }
    const rules = rulesOf(el);
    const before = (states.get(el) || { shown: '' }).shown;
    let text = shownText(el);
    let caret = el.selectionStart === null ? text.length : el.selectionStart;
    const type = input.inputType || '';
    if (type === 'deleteContentBackward' || type === 'deleteContentForward') {
        const fixed = dropDigitBesideSeparator(
            before,
            text,
            caret,
            type === 'deleteContentForward',
            SEPS,
        );
        text = fixed.text;
        caret = fixed.caret;
    }
    const isPaste = type === 'insertFromPaste' || type === 'insertFromDrop';
    const pasted = isPaste ? pastedDecimalIndex(text, rules.fractionDigits, SEPS) : -1;
    const reading = readDisplay(text, caret, rules, SEPS, pasted);
    if (isPaste && reading.extra !== '') {
        show(el, roundHalfUp(reading.parts, reading.extra), Number.MAX_SAFE_INTEGER);
        return;
    }
    show(el, reading.parts, reading.significantBeforeCaret);
}

/**
 * Formats a field once an IME composition ends.
 *
 * @param {Event} e the compositionend event
 */
function onCompositionEnd(e) {
    const el = /** @type {HTMLInputElement} */ (e.target);
    if (!(el instanceof HTMLInputElement) || !el.matches(SELECTOR)) {
        return;
    }
    enhance(el);
    const text = shownText(el);
    const caret = el.selectionStart === null ? text.length : el.selectionStart;
    const reading = readDisplay(text, caret, rulesOf(el), SEPS);
    show(el, reading.parts, reading.significantBeforeCaret);
}

/**
 * Tidies a field when it is committed: a dangling decimal separator, a lone minus sign or leading
 * zeros go.
 *
 * @param {Event} e the change event
 */
function onChange(e) {
    const el = /** @type {HTMLInputElement} */ (e.target);
    if (!(el instanceof HTMLInputElement) || !el.matches(SELECTOR)) {
        return;
    }
    resync(el);
}

/**
 * Replaces the shown text of every number field in a submitted or serialized form with its
 * canonical value, so the server and every `FormData` reader get `1500000`, never `1.500.000`.
 *
 * @param {Event} e the formdata event
 */
function onFormData(e) {
    const event = /** @type {FormDataEvent} */ (e);
    const form = /** @type {HTMLFormElement} */ (event.target);
    if (!(form instanceof HTMLFormElement)) {
        return;
    }
    /** @type {Map<string, HTMLInputElement[]>} */
    const byName = new Map();
    /** @type {Set<string>} */
    const mixed = new Set();
    for (const control of Array.from(form.elements)) {
        const named = /** @type {HTMLInputElement} */ (control);
        if (!named.name || named.disabled) {
            continue;
        }
        if (!(named instanceof HTMLInputElement) || !named.matches(SELECTOR)) {
            mixed.add(named.name);
            continue;
        }
        const list = byName.get(named.name) || [];
        list.push(named);
        byName.set(named.name, list);
    }
    byName.forEach((fields, name) => {
        if (mixed.has(name)) {
            return;
        }
        event.formData.delete(name);
        fields.forEach((field) => event.formData.append(name, canonicalValue(field)));
    });
}

/**
 * Re-formats the fields of a form after the browser reset it.
 *
 * @param {Event} e the reset event
 */
function onReset(e) {
    const form = /** @type {HTMLFormElement} */ (e.target);
    if (!(form instanceof HTMLFormElement)) {
        return;
    }
    window.setTimeout(() => {
        /** @type {NodeListOf<HTMLInputElement>} */ (form.querySelectorAll(SELECTOR)).forEach(
            resync,
        );
    }, 0);
}

/**
 * Enhances number fields added later (dialogs, fragment swaps, rows built in script) and
 * re-formats one whose `value`, `step`, `min` or `max` attribute was rewritten.
 *
 * @param {MutationRecord[]} records the observed mutations
 */
function onMutations(records) {
    for (const record of records) {
        if (record.type === 'attributes') {
            const target = /** @type {Element} */ (record.target);
            if (target instanceof HTMLInputElement && target.matches(SELECTOR)) {
                if (states.has(target)) {
                    resync(target);
                } else {
                    enhance(target);
                }
            }
            continue;
        }
        record.addedNodes.forEach((node) => {
            if (node instanceof Element) {
                enhanceWithin(node);
            }
        });
    }
}

document.addEventListener('input', onInput, true);
document.addEventListener('compositionend', onCompositionEnd, true);
document.addEventListener('change', onChange, true);
document.addEventListener('formdata', onFormData, true);
document.addEventListener('reset', onReset, true);
enhanceWithin(document);
new MutationObserver(onMutations).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['value', 'data-number-input', 'step', 'min', 'max'],
});

export const krtNumberInput = /** @type {KrtNumberInputApi} */ ({ enhance, enhanceWithin });
