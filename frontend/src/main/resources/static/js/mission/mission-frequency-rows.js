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

const KIND = 'select[data-frequency-kind]';

/**
 * Keeps each frequency row's label field in step with its kind and offers every catalogue type to
 * one row only.
 *
 * A row whose kind select has the empty value is a custom channel and needs a label; any other
 * value is a catalogue frequency type and carries none.
 *
 * @param {HTMLElement} list the frequency row container
 * @param {HTMLTemplateElement} template the row markup holding the catalogue options
 * @returns {{ sync: () => void, nextType: () => string }} re-applies the rules after rows were
 *     added, filled or removed, and names the first catalogue type no row uses yet
 */
export function bindFrequencyRows(list, template) {
    const catalogue = Array.from(
        template.content.querySelector(KIND)?.querySelectorAll('option') ?? [],
        (option) => option.value,
    ).filter(Boolean);

    /** @returns {HTMLSelectElement[]} the kind select of every row */
    function kinds() {
        return Array.from(list.querySelectorAll(KIND)).filter(
            /**
             * @param {Element} element a candidate
             * @returns {element is HTMLSelectElement} whether it is a select
             */
            (element) => element instanceof HTMLSelectElement,
        );
    }

    /** @param {HTMLSelectElement} kind the row's kind select */
    function syncLabel(kind) {
        const label = kind.closest('[data-planning-row]')?.querySelector('[data-frequency-name]');
        const input = label?.querySelector('input');
        if (!(label instanceof HTMLElement) || !(input instanceof HTMLInputElement)) return;
        const custom = kind.value === '';
        label.hidden = !custom;
        input.required = custom;
        if (!custom) input.value = '';
    }

    /** Disables each catalogue type in every row except the row that uses it. */
    function syncTypes() {
        const selects = kinds();
        const used = new Set(selects.map((kind) => kind.value).filter(Boolean));
        for (const kind of selects) {
            for (const option of kind.options) {
                option.disabled =
                    option.value !== '' && option.value !== kind.value && used.has(option.value);
            }
        }
    }

    /** Re-applies the label and type rules to every row. */
    function sync() {
        kinds().forEach(syncLabel);
        syncTypes();
    }

    /** @returns {string} the first unused catalogue type, or empty for a custom channel */
    function nextType() {
        const used = new Set(kinds().map((kind) => kind.value));
        return catalogue.find((id) => !used.has(id)) ?? '';
    }

    list.addEventListener('change', (event) => {
        const kind = event.target;
        if (!(kind instanceof HTMLSelectElement) || !kind.matches(KIND)) return;
        syncLabel(kind);
        syncTypes();
    });

    return { sync, nextType };
}
