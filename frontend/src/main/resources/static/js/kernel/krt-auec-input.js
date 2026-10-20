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
    readCanonical,
    readDisplay,
    typedDecimalDot,
} from '/js/kernel/krt-auec-format.js';

/**
 * The state of one enhanced field: the text last written into it and the canonical value of that
 * text.
 *
 * @typedef {{ shown: string, canonical: string }} KrtAuecState
 */

const SELECTOR = 'input[data-auec-input]';

const NATIVE_VALUE = /** @type {PropertyDescriptor} */ (
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
);

/** @type {WeakMap<HTMLInputElement, KrtAuecState>} */
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
 * The decimals a field allows, from its `step`: none for a whole step or none given, two for
 * `any`, otherwise the step's own decimals.
 *
 * @param {HTMLInputElement} el the field
 * @returns {number} the allowed decimals
 */
function fractionDigits(el) {
    const step = (el.getAttribute('step') || '').trim();
    if (step === '' || /^\d+$/.test(step)) {
        return 0;
    }
    if (step === 'any') {
        return 2;
    }
    const dot = step.indexOf('.');
    return dot < 0 ? 0 : step.length - dot - 1;
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
 * Fills a message's `{0}` with an amount in display form.
 *
 * @param {string} message the localized message
 * @param {number} bound the amount
 * @param {number} digits the field's allowed decimals
 * @returns {string} the message with the amount
 */
function withBound(message, bound, digits) {
    return message.replace('{0}', formatParts(readCanonical(String(bound), digits)));
}

/**
 * Computes a field's validity message from its canonical value against `min`, `max` and a whole
 * `step`. An empty field is left to `required`.
 *
 * @param {HTMLInputElement} el the field
 * @param {string} canonical the field's canonical value
 * @returns {string} the message, or "" when valid
 */
function validityMessage(el, canonical) {
    if (canonical === '') {
        return '';
    }
    const i18n = window.krtAuecI18n || {};
    const digits = fractionDigits(el);
    const n = Number(canonical);
    if (digits === 0 && !Number.isInteger(n)) {
        return krtI18nText(i18n.whole, 'krtAuecI18n.whole');
    }
    const min = numberAttribute(el, 'min');
    if (min !== null && n < min) {
        return withBound(krtI18nText(i18n.min, 'krtAuecI18n.min'), min, digits);
    }
    const max = numberAttribute(el, 'max');
    if (max !== null && n > max) {
        return withBound(krtI18nText(i18n.max, 'krtAuecI18n.max'), max, digits);
    }
    return '';
}

/**
 * Shows an amount in a field, keeps its canonical value and refreshes its validity.
 *
 * @param {HTMLInputElement} el the field
 * @param {{ int: string, frac: string | null }} parts the amount
 * @param {number | null} significantBeforeCaret where the caret goes, or null to leave it
 */
function show(el, parts, significantBeforeCaret) {
    const shown = formatParts(parts);
    const canonical = canonicalOf(parts);
    if (shownText(el) !== shown) {
        writeShown(el, shown);
    }
    states.set(el, { shown, canonical });
    if (significantBeforeCaret !== null && el === document.activeElement) {
        const caret = caretAfter(shown, significantBeforeCaret);
        try {
            el.setSelectionRange(caret, caret);
        } catch (_e) {}
    }
    el.setCustomValidity(validityMessage(el, canonical));
}

/**
 * The canonical value of a field. Text this module did not write is read as the server's
 * canonical value when it equals the `value` attribute (a form reset), else as display text (a
 * browser restoring what the user saw).
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
    const digits = fractionDigits(el);
    const parts =
        text === el.defaultValue ? readCanonical(text, digits) : readDisplay(text, 0, digits).parts;
    return canonicalOf(parts);
}

/**
 * Turns a field into a live-formatted aUEC field: it shows `1.500.000`, while its `value`,
 * `valueAsNumber` and every form entry it contributes stay canonical (`1500000`).
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
            show(el, readCanonical(v, fractionDigits(el)), null);
        },
    });
    Object.defineProperty(el, 'valueAsNumber', {
        configurable: true,
        get() {
            const canonical = canonicalValue(el);
            return canonical === '' ? NaN : Number(canonical);
        },
        set(v) {
            show(el, readCanonical(Number.isFinite(v) ? String(v) : '', fractionDigits(el)), null);
        },
    });
    if (!el.hasAttribute('inputmode')) {
        el.setAttribute('inputmode', fractionDigits(el) > 0 ? 'decimal' : 'numeric');
    }
    el.setAttribute('autocomplete', 'off');
    const digits = fractionDigits(el);
    const text = shownText(el);
    show(
        el,
        text === el.defaultValue ? readCanonical(text, digits) : readDisplay(text, 0, digits).parts,
        null,
    );
}

/**
 * Enhances every aUEC field in a subtree, the root included.
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
 * Re-formats an enhanced field whose text changed without an input event, such as a reset.
 *
 * @param {HTMLInputElement} el the field
 */
function resync(el) {
    if (!states.has(el)) {
        return;
    }
    const state = states.get(el);
    if (state && state.shown === shownText(el)) {
        return;
    }
    show(el, readCanonical(canonicalValue(el), fractionDigits(el)), null);
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
    const digits = fractionDigits(el);
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
        );
        text = fixed.text;
        caret = fixed.caret;
    }
    const typedDot =
        type.startsWith('insert') && (input.data === null || input.data.includes('.'))
            ? typedDecimalDot(text, digits)
            : -1;
    const reading = readDisplay(text, caret, digits, typedDot);
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
    const reading = readDisplay(text, caret, fractionDigits(el));
    show(el, reading.parts, reading.significantBeforeCaret);
}

/**
 * Tidies a field when it is committed: a dangling `,` or leading zeros go.
 *
 * @param {Event} e the change event
 */
function onChange(e) {
    const el = /** @type {HTMLInputElement} */ (e.target);
    if (!(el instanceof HTMLInputElement) || !el.matches(SELECTOR) || !states.has(el)) {
        return;
    }
    show(el, readCanonical(canonicalValue(el), fractionDigits(el)), null);
}

/**
 * Replaces the shown text of every aUEC field in a submitted or serialized form with its canonical
 * value, so the server and every `FormData` reader get `1500000`, never `1.500.000`.
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
 * Enhances aUEC fields added later (dialogs, fragment swaps) and re-formats one whose `value`
 * attribute was rewritten.
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
    attributeFilter: ['value', 'data-auec-input'],
});

export const krtAuecInput = /** @type {KrtAuecInputApi} */ ({ enhance, enhanceWithin });
