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

/** @import { PatchChangeMessages } from '/js/patchchange/patch-change-messages.js' */

import { krtHtml } from '/js/kernel/krt-html.js';
import { formatNumber } from '/js/kernel/krt-number-format.js';
import { format } from '/js/patchchange/patch-change-messages.js';

/** The text of a count cell before the preview has counted it. */
const NOT_COUNTED = '–';

/**
 * A combobox's hidden input once the searchable select upgraded its `select`.
 *
 * @typedef {HTMLInputElement & { krtCombobox?: { setValue: (value: string) => void } }} ComboboxInput
 */

/**
 * One wipe exception the admin added, with its entry's name for the chip.
 *
 * @typedef {object} PendingException
 * @property {'GAME_ITEM' | 'MATERIAL'} target the catalogue of the entry
 * @property {string} id the entry's id
 * @property {'KEEP' | 'WIPE'} mode whether the entry's stock is kept or wiped
 * @property {string} name the entry's name as the picker showed it
 */

/**
 * What the wipe section offers the form.
 *
 * @typedef {object} WipeSection
 * @property {() => ApiDto<'WipeException'>[]} exceptions the exceptions as the backend reads them
 * @property {() => void} clear removes every exception
 * @property {(wipe: ApiDto<'WipePreviewDto'> | null | undefined) => void} showPreview fills the
 *     groups' and the exceptions' counts from a preview
 */

/**
 * Resolves the controls of the exception editor.
 *
 * @param {HTMLFormElement} form the patch-change form
 * @returns {{root: HTMLElement, list: HTMLElement, empty: HTMLElement, target: HTMLSelectElement,
 *     mode: HTMLSelectElement, add: HTMLButtonElement, error: HTMLElement} | null} the controls,
 *     or null when the form renders no exception editor
 */
function resolveControls(form) {
    const root = form.querySelector('[data-pc-exceptions]');
    const list = document.getElementById('pc-exception-list');
    const empty = document.getElementById('pc-exception-empty');
    const target = document.getElementById('pc-exception-target');
    const mode = document.getElementById('pc-exception-mode');
    const add = document.getElementById('pc-exception-add');
    const error = document.getElementById('pc-exception-error');
    if (
        !(root instanceof HTMLElement) ||
        !list ||
        !empty ||
        !(target instanceof HTMLSelectElement) ||
        !(mode instanceof HTMLSelectElement) ||
        !(add instanceof HTMLButtonElement) ||
        !error
    ) {
        return null;
    }
    return { root, list, empty, target, mode, add, error };
}

/**
 * @param {string | undefined} target an exception's catalogue
 * @param {string | undefined} id its entry's id
 * @returns {string} the key that tells two exceptions of one entry apart from the rest
 */
function entryKey(target, id) {
    return `${target}:${id}`;
}

/**
 * Reads the name the picker shows for its chosen entry.
 *
 * @param {HTMLInputElement} input the combobox's hidden input
 * @returns {string} the visible label, or the id when the picker shows none
 */
function chosenLabel(input) {
    const wrapper = input.closest('.krt-combobox');
    const visible = wrapper ? wrapper.querySelector('.krt-combobox__input') : null;
    return visible instanceof HTMLInputElement && visible.value ? visible.value : input.value;
}

/**
 * Installs the wipe section of the patch-change form: the per-group counts of the preview and the
 * exception editor, whose chips show what each exception keeps or wipes (REQ-PATCH-011,
 * REQ-PATCH-016).
 *
 * @param {HTMLFormElement} form the patch-change form
 * @param {PatchChangeMessages} i18n the page's localized strings
 * @param {() => void} onChange runs after an exception was added or removed
 * @returns {WipeSection} the section's seam; without an exception editor it only shows counts
 */
export function initWipeSection(form, i18n, onChange) {
    const el = resolveControls(form);
    /** @type {PendingException[]} */
    let pending = [];
    /** @type {Map<string, number>} */
    let rowsByEntry = new Map();
    const limit = el ? Number(el.root.dataset.limit) || 0 : 0;

    /**
     * Writes a count into a cell of a group row.
     *
     * @param {HTMLElement} row the group's row
     * @param {string} selector the cell's selector
     * @param {number | undefined} value the count, or undefined before the preview counted it
     */
    function setCell(row, selector, value) {
        const cell = row.querySelector(selector);
        if (cell) {
            cell.textContent =
                value === undefined
                    ? NOT_COUNTED
                    : formatNumber(value, 0, document.documentElement.lang);
        }
    }

    /** Renders one removable chip per exception, with what it keeps or wipes. */
    function render() {
        if (!el) {
            return;
        }
        el.empty.hidden = pending.length > 0;
        const lang = document.documentElement.lang;
        krtHtml.set(
            el.list,
            pending.map((exception) => {
                const key = entryKey(exception.target, exception.id);
                const label = format(i18n.exceptionChip, [
                    exception.name,
                    exception.mode === 'KEEP' ? i18n.exceptionKeep : i18n.exceptionWipe,
                ]);
                const rows = rowsByEntry.get(key);
                const text =
                    rows === undefined
                        ? label
                        : `${label} · ${format(i18n.exceptionRows, [formatNumber(rows, 0, lang)])}`;
                const tone = exception.mode === 'KEEP' ? 'chip--primary' : 'chip--danger';
                return krtHtml`<button type="button" class="chip ${tone} filter-chip" data-pc-exception-key="${key}" data-testid="pc-exception-chip" aria-label="${format(i18n.exceptionRemove, [text])}"><span>${text}</span><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-close"></use></svg></button>`;
            }),
        );
    }

    /**
     * Shows a hint below the editor, or hides it.
     *
     * @param {string} message the localized hint, or '' to hide it
     */
    function showError(message) {
        if (el) {
            el.error.textContent = message;
            el.error.hidden = message === '';
        }
    }

    /** @returns {HTMLInputElement | null} the picker of the chosen catalogue */
    function entryInput() {
        if (!el) {
            return null;
        }
        const input = document.getElementById(
            el.target.value === 'MATERIAL' ? 'pc-exception-material' : 'pc-exception-item',
        );
        return input instanceof HTMLInputElement ? input : null;
    }

    /** Shows only the picker of the chosen catalogue. */
    function syncTarget() {
        if (!el) {
            return;
        }
        el.root.querySelectorAll('[data-pc-exception-entry]').forEach((row) => {
            if (row instanceof HTMLElement) {
                row.hidden = row.dataset.pcExceptionEntry !== el.target.value;
            }
        });
    }

    /** Adds the chosen entry as an exception, unless it is missing, named already or one too many. */
    function addException() {
        if (!el) {
            return;
        }
        const input = entryInput();
        if (!input || !input.value) {
            showError(i18n.exceptionPick);
            return;
        }
        const target = el.target.value === 'MATERIAL' ? 'MATERIAL' : 'GAME_ITEM';
        const key = entryKey(target, input.value);
        if (pending.some((exception) => entryKey(exception.target, exception.id) === key)) {
            showError(i18n.exceptionDuplicate);
            return;
        }
        if (pending.length >= limit) {
            showError(format(i18n.exceptionLimit, [limit]));
            return;
        }
        pending = [
            ...pending,
            {
                target,
                id: input.value,
                mode: el.mode.value === 'WIPE' ? 'WIPE' : 'KEEP',
                name: chosenLabel(input),
            },
        ];
        const combobox = /** @type {ComboboxInput} */ (input).krtCombobox;
        if (combobox) {
            combobox.setValue('');
        }
        showError('');
        render();
        onChange();
    }

    if (el) {
        el.add.addEventListener('click', addException);
        el.target.addEventListener('change', syncTarget);
        el.list.addEventListener('click', (event) => {
            const chip =
                event.target instanceof Element
                    ? event.target.closest('[data-pc-exception-key]')
                    : null;
            if (!(chip instanceof HTMLElement)) {
                return;
            }
            pending = pending.filter(
                (exception) =>
                    entryKey(exception.target, exception.id) !== chip.dataset.pcExceptionKey,
            );
            showError('');
            render();
            onChange();
        });
        syncTarget();
        render();
    }

    return {
        exceptions() {
            return pending.map(({ target, id, mode }) => ({ target, id, mode }));
        },
        clear() {
            pending = [];
            rowsByEntry = new Map();
            showError('');
            syncTarget();
            render();
        },
        showPreview(wipe) {
            const groups = wipe && wipe.groups ? wipe.groups : [];
            form.querySelectorAll('[data-pc-group]').forEach((row) => {
                if (!(row instanceof HTMLElement)) {
                    return;
                }
                const count = groups.find((group) => group.group === row.dataset.pcGroup);
                setCell(row, '[data-pc-group-rows]', count ? count.rows : undefined);
                setCell(row, '[data-pc-group-members]', count ? count.members : undefined);
            });
            rowsByEntry = new Map(
                (wipe && wipe.exceptions ? wipe.exceptions : []).map((count) => [
                    entryKey(count.exception?.target, count.exception?.id),
                    count.rows ?? 0,
                ]),
            );
            render();
        },
    };
}
