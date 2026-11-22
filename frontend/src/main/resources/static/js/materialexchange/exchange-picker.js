/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The searchable picker of the Materialbörse offer and request dialogs: the kernel combobox fed by
 * a relay search (REQ-MARKET-002/015).
 */

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtSearchableSelect } from '/js/kernel/krt-searchable-select.js';

/**
 * How a picker searches its relay and turns the result rows into options.
 *
 * @template T
 * @typedef {object} ExchangePickerSource
 * @property {(query: string) => string} url the relay URL for a trimmed search text
 * @property {(body: any) => T[]} rows the result rows of a relay response body
 * @property {(row: T) => string} value the option value of a row, unique among all rows
 * @property {(row: T) => string} label the option label of a row
 */

/**
 * The texts a picker shows besides the shared combobox texts.
 *
 * @typedef {object} ExchangePickerTexts
 * @property {string} [placeholder] the empty textbox's placeholder
 * @property {string} [noResults] the notice for a search without results
 */

/**
 * A bound picker.
 *
 * @typedef {object} ExchangePicker
 * @property {() => void} clear drops the pick without calling `onPick`
 * @property {() => void} focus moves focus to the search textbox
 */

/**
 * Turns a `<select>` into the kernel's searchable combobox and reports every pick as the row the
 * relay returned for it. The list opens only on a click into, a key press in or typing in the
 * textbox.
 *
 * @template T
 * @param {HTMLSelectElement} select the connected select to enhance; it needs an id, and its
 *     `label[for]` becomes the textbox's label
 * @param {ExchangePickerSource<T>} source the relay search
 * @param {ExchangePickerTexts} texts the picker's own texts
 * @param {(row: T | null) => void} onPick runs with the picked row, or null when the pick is dropped
 * @returns {ExchangePicker} the bound picker
 * @throws {Error} when the select has no id or the enhancement did not take place
 */
export function bindExchangePicker(select, source, texts, onPick) {
    const id = select.id;
    if (!id) {
        throw new Error('A Materialbörse picker select needs an id');
    }
    /** @type {Map<string, T>} */
    const rows = new Map();
    const shared = window.krtComboboxI18n || {};
    krtSearchableSelect(select, {
        placeholder: texts.placeholder,
        noResultsText: texts.noResults,
        hintText: shared.hint,
        invalidText: shared.invalid,
        loadingText: shared.loading,
        remoteSource: (query) =>
            krtFetch.getJson(source.url(query.trim())).then((body) =>
                source.rows(body).map((row) => {
                    const value = source.value(row);
                    rows.set(value, row);
                    return { value, label: source.label(row) };
                }),
            ),
    });
    const value = document.getElementById(id);
    const textbox = value?.closest('.krt-combobox')?.querySelector('.krt-combobox__input');
    if (!(value instanceof HTMLInputElement) || !(textbox instanceof HTMLInputElement)) {
        throw new Error(`The Materialbörse picker #${id} was not enhanced`);
    }
    value.addEventListener('change', () => {
        onPick(rows.get(value.value) ?? null);
    });
    return {
        clear() {
            rows.clear();
            value.krtCombobox?.setValue('');
        },
        focus() {
            textbox.focus();
        },
    };
}
