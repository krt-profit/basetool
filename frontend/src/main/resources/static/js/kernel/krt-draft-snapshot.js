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

/** Input types whose value a draft never keeps (REQ-FE-041). */
const SKIPPED_TYPES = new Set(['hidden', 'password', 'file', 'submit', 'button', 'reset', 'image']);

/** The marker that keeps a control, or every control inside an element, out of a draft. */
const SKIP_SELECTOR = '[data-krt-draft-skip]';

/** The marker of a control holding a lock version that an edit form's draft is made against. */
const BASE_SELECTOR = '[data-krt-draft-base]';

/**
 * Names the key a control is stored under: its name, else its id.
 *
 * @param {Element} control the form control
 * @returns {string} the key; empty when the control has neither
 */
function controlKey(control) {
    return control.getAttribute('name') || control.id || '';
}

/**
 * Tells whether a key names a token or a lock version, which a draft never keeps.
 *
 * @param {string} key the control key
 * @returns {boolean} true for `_csrf` and every `version` field
 */
function isGuardKey(key) {
    return key === '_csrf' || key === 'version' || key.endsWith('.version');
}

/**
 * Tells whether a control's value belongs in a draft: a visible, enabled, editable control with a
 * key, outside every `data-krt-draft-skip` element and outside a combobox, whose selection is kept
 * through its controller instead.
 *
 * @param {Element} control the form control
 * @returns {control is HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} whether to keep it
 */
function isDraftable(control) {
    if (
        !(control instanceof HTMLInputElement) &&
        !(control instanceof HTMLSelectElement) &&
        !(control instanceof HTMLTextAreaElement)
    ) {
        return false;
    }
    if (control.disabled || control.closest(SKIP_SELECTOR) || control.closest('.krt-combobox')) {
        return false;
    }
    if (control instanceof HTMLInputElement && SKIPPED_TYPES.has(control.type)) {
        return false;
    }
    if (!(control instanceof HTMLSelectElement) && control.readOnly) {
        return false;
    }
    const key = controlKey(control);
    return key !== '' && !isGuardKey(key);
}

/**
 * Lists the hidden inputs of the form's enhanced comboboxes whose selection a draft keeps.
 *
 * @param {HTMLFormElement} form the form
 * @returns {HTMLInputElement[]} the hidden inputs carrying a combobox controller
 */
function comboboxInputs(form) {
    /** @type {HTMLInputElement[]} */
    const inputs = [];
    form.querySelectorAll('.krt-combobox > input[type="hidden"]').forEach((hidden) => {
        const key = controlKey(hidden);
        if (
            hidden instanceof HTMLInputElement &&
            hidden.krtCombobox &&
            !hidden.disabled &&
            !hidden.closest(SKIP_SELECTOR) &&
            key !== '' &&
            !isGuardKey(key)
        ) {
            inputs.push(hidden);
        }
    });
    return inputs;
}

/**
 * Names the key of a checkbox, which carries its value, so checkboxes sharing a name stay apart.
 *
 * @param {HTMLInputElement} checkbox the checkbox
 * @returns {string} `<key>=<value>`
 */
function checkboxKey(checkbox) {
    return `${controlKey(checkbox)}=${checkbox.value}`;
}

/**
 * Captures the values of a form's draftable controls (REQ-FE-041): text values, checked states,
 * selected options and combobox selections with their labels. Hidden fields, passwords, files,
 * tokens, lock versions and skipped controls are never read.
 *
 * @param {HTMLFormElement} form the form
 * @returns {KrtDraftSnapshot} the captured values
 */
export function snapshotForm(form) {
    /** @type {Record<string, KrtDraftValue>} */
    const fields = {};
    Array.from(form.elements).forEach((control) => {
        if (!isDraftable(control)) {
            return;
        }
        const key = controlKey(control);
        if (control instanceof HTMLInputElement && control.type === 'checkbox') {
            fields[checkboxKey(control)] = control.checked;
        } else if (control instanceof HTMLInputElement && control.type === 'radio') {
            if (control.checked || !(key in fields)) {
                fields[key] = control.checked ? control.value : '';
            }
        } else if (control instanceof HTMLSelectElement && control.multiple) {
            fields[key] = Array.from(control.selectedOptions).map((option) => option.value);
        } else {
            fields[key] = control.value;
        }
    });
    /** @type {Record<string, KrtComboboxSelection>} */
    const combos = {};
    comboboxInputs(form).forEach((hidden) => {
        const controller = /** @type {KrtComboboxController} */ (hidden.krtCombobox);
        combos[controlKey(hidden)] = controller.selection();
    });
    return { fields, combos };
}

/**
 * Writes one stored value into a draftable control.
 *
 * @param {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} control the control
 * @param {Record<string, KrtDraftValue>} fields the stored values
 * @returns {boolean} whether the draft held a value for the control
 */
function restoreControl(control, fields) {
    const key = controlKey(control);
    if (control instanceof HTMLInputElement && control.type === 'checkbox') {
        const own = checkboxKey(control);
        if (!(own in fields)) {
            return false;
        }
        control.checked = fields[own] === true;
        return true;
    }
    if (!(key in fields)) {
        return false;
    }
    const value = fields[key];
    if (control instanceof HTMLInputElement && control.type === 'radio') {
        control.checked = value === control.value;
    } else if (control instanceof HTMLSelectElement && control.multiple) {
        const chosen = Array.isArray(value) ? value : [];
        Array.from(control.options).forEach((option) => {
            option.selected = chosen.includes(option.value);
        });
    } else {
        control.value = typeof value === 'string' ? value : '';
    }
    return true;
}

/**
 * Writes a snapshot back into a form, then raises `input` and `change` on every restored control,
 * so the page's derived displays and the unsaved-changes guard react as they do to an edit.
 *
 * @param {HTMLFormElement} form the form, with every script-built row already in place
 * @param {KrtDraftSnapshot} snapshot the stored values
 */
export function restoreForm(form, snapshot) {
    /** @type {HTMLElement[]} */
    const restored = [];
    Array.from(form.elements).forEach((control) => {
        if (isDraftable(control) && restoreControl(control, snapshot.fields)) {
            restored.push(control);
        }
    });
    comboboxInputs(form).forEach((hidden) => {
        const selection = snapshot.combos[controlKey(hidden)];
        if (!selection) {
            return;
        }
        const controller = /** @type {KrtComboboxController} */ (hidden.krtCombobox);
        controller.setValue(selection.value, selection.label, selection.data);
        restored.push(hidden);
    });
    restored.forEach((control) => {
        control.dispatchEvent(new Event('input', { bubbles: true }));
        control.dispatchEvent(new Event('change', { bubbles: true }));
    });
}

/**
 * Reads the lock versions an edit form's draft is made against: the values of the controls marked
 * `data-krt-draft-base`, keyed like the snapshot (REQ-FE-041).
 *
 * @param {HTMLFormElement} form the form
 * @returns {Record<string, string>} the versions; empty for a form without any
 */
export function readBase(form) {
    /** @type {Record<string, string>} */
    const base = {};
    form.querySelectorAll(BASE_SELECTOR).forEach((control) => {
        const key = controlKey(control);
        if (key && control instanceof HTMLInputElement) {
            base[key] = control.value;
        }
    });
    return base;
}

/**
 * Reads the versions a stored draft recorded.
 *
 * @param {unknown} stored the stored versions
 * @returns {Record<string, unknown>} the versions; empty for anything that is not an object
 */
function recordedBase(stored) {
    return stored && typeof stored === 'object'
        ? /** @type {Record<string, unknown>} */ (stored)
        : {};
}

/**
 * Tells whether a stored draft was made against the versions the form was rendered with.
 *
 * @param {unknown} stored the versions the draft recorded
 * @param {Record<string, string>} current the versions the form holds now
 * @returns {boolean} true when every version matches, as for a draft and a form without any
 */
export function sameBase(stored, current) {
    const recorded = recordedBase(stored);
    const keys = new Set([...Object.keys(recorded), ...Object.keys(current)]);
    return Array.from(keys).every((key) => recorded[key] === current[key]);
}

/**
 * Writes the versions a restored draft was made against back into the form, so its save is checked
 * against them and a change saved meanwhile is refused instead of overwritten.
 *
 * @param {HTMLFormElement} form the form
 * @param {unknown} stored the versions the draft recorded
 */
export function restoreBase(form, stored) {
    const recorded = recordedBase(stored);
    form.querySelectorAll(BASE_SELECTOR).forEach((control) => {
        const value = recorded[controlKey(control)];
        if (control instanceof HTMLInputElement && typeof value === 'string') {
            control.value = value;
        }
    });
}
