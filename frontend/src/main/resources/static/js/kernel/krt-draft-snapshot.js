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
