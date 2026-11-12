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

import { krtNumberInput } from '/js/kernel/krt-number-input.js';
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */
/** @typedef {'objectives' | 'steps' | 'units'} PlanningSection */

/**
 * The ordered row controls and the planning payload of a mission template.
 *
 * @param {HTMLElement} panel the independent editor panel
 */
export function createMissionTemplateEditor(panel) {
    const form = /** @type {HTMLFormElement} */ (panel.querySelector('form'));
    const name = /** @type {HTMLInputElement} */ (form.querySelector('#mission-template-name'));
    const owner = /** @type {HTMLSelectElement} */ (form.querySelector('#mission-template-owner'));
    const heading = /** @type {HTMLElement} */ (panel.querySelector('h2'));
    const error = /** @type {HTMLElement} */ (
        panel.querySelector('#mission-template-editor-error')
    );
    const nameError = /** @type {HTMLElement} */ (
        panel.querySelector('#mission-template-name-error')
    );
    const deleteButton = /** @type {HTMLButtonElement} */ (
        form.querySelector('[data-template-delete]')
    );

    /**
     * @param {PlanningSection} section the kind of planning row
     * @returns {HTMLOListElement} the ordered container
     */
    function rows(section) {
        return /** @type {HTMLOListElement} */ (
            form.querySelector(`[data-template-rows="${section}"]`)
        );
    }

    /**
     * @param {Element} row the row to read
     * @param {string} field the named field
     * @returns {HTMLInputElement | HTMLSelectElement} the field
     */
    function control(row, field) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (
            row.querySelector(`[name="${field}"]`)
        );
    }

    /**
     * Updates the available keyboard reorder actions.
     *
     * @param {Element} list the ordered rows
     */
    function updateOrderControls(list) {
        const entries = Array.from(list.children);
        entries.forEach((entry, index) => {
            const up = /** @type {HTMLButtonElement} */ (
                entry.querySelector('[data-template-row-action="up"]')
            );
            const down = /** @type {HTMLButtonElement} */ (
                entry.querySelector('[data-template-row-action="down"]')
            );
            up.disabled = index === 0;
            down.disabled = index === entries.length - 1;
        });
    }

    /**
     * Appends a reusable planning row.
     *
     * @param {PlanningSection} section the kind of row
     * @param {Record<string, string | number | boolean | null | undefined>} [values] the initial values
     * @returns {HTMLElement} the inserted row
     */
    function addRow(section, values = {}) {
        const template = /** @type {HTMLTemplateElement} */ (
            panel.querySelector(`template[data-template-row="${section}"]`)
        );
        const row = /** @type {HTMLElement} */ (
            template.content.firstElementChild?.cloneNode(true)
        );
        krtNumberInput.enhanceWithin(row);
        Object.entries(values).forEach(([field, value]) => {
            const input = control(row, field);
            if (input instanceof HTMLInputElement && input.type === 'checkbox') {
                input.checked = value === true;
            } else {
                const text = value == null ? '' : String(value);
                if (
                    field === 'shipTypeId' &&
                    text &&
                    input instanceof HTMLSelectElement &&
                    !Array.from(input.options).some((option) => option.value === text)
                ) {
                    input.add(new Option(panel.dataset.hiddenShipType || '', text));
                }
                input.value = text;
            }
        });
        const list = rows(section);
        list.append(row);
        updateOrderControls(list);
        return row;
    }

    /**
     * Shows or clears an inline failure.
     *
     * @param {string} message the localized editor message
     * @param {boolean} [duplicate] whether the message belongs to the name field
     */
    function showError(message, duplicate = false) {
        error.textContent = duplicate ? '' : message;
        error.hidden = !error.textContent;
        nameError.textContent = duplicate ? message : '';
        nameError.hidden = !nameError.textContent;
        name.setAttribute('aria-invalid', String(duplicate));
        if (duplicate) name.focus();
    }

    /**
     * Starts an editor in create or edit mode.
     *
     * @param {string} ownerId the active scope for a blank template
     * @param {MissionTemplate | ApiDto<'CreateMissionTemplateRequest'> | null} template the initial planning content, or null for blank
     */
    function open(ownerId, template) {
        form.reset();
        rows('objectives').replaceChildren();
        rows('steps').replaceChildren();
        rows('units').replaceChildren();
        owner.value = template?.owningOrgUnitId || ownerId;
        const editing = Boolean(template && 'id' in template && template.id);
        owner.disabled = editing;
        name.value = template?.name || '';
        heading.textContent = editing
            ? panel.dataset.editTitle || ''
            : panel.dataset.createTitle || '';
        deleteButton.hidden = !editing;
        template?.objectives?.forEach((row) => addRow('objectives', row));
        template?.steps?.forEach((row) => addRow('steps', row));
        template?.units?.forEach((row) => addRow('units', row));
        showError('');
        panel.hidden = false;
        name.focus();
    }

    /** Closes the editor and clears the page's unsaved-form guard. */
    function close() {
        panel.hidden = true;
        resetUnsavedChanges();
    }

    /**
     * Reads the complete plan in DOM order for the next write.
     *
     * @returns {Omit<ApiDto<'CreateMissionTemplateRequest'>, 'owningOrgUnitId'>} the planned content
     */
    function readPlan() {
        return {
            name: name.value,
            objectives: Array.from(rows('objectives').children, (row) => ({
                title: control(row, 'title').value,
                kind: /** @type {NonNullable<MissionTemplate['objectives']>[number]['kind']} */ (
                    control(row, 'kind').value
                ),
            })),
            steps: Array.from(rows('steps').children, (row) => ({
                title: control(row, 'title').value,
                meta: control(row, 'meta').value || undefined,
            })),
            units: Array.from(rows('units').children, (row) => {
                const frequency = control(row, 'frequency').value;
                return {
                    name: control(row, 'name').value,
                    shipTypeId: control(row, 'shipTypeId').value || undefined,
                    note: control(row, 'note').value || undefined,
                    highValueUnit: /** @type {HTMLInputElement} */ (control(row, 'highValueUnit'))
                        .checked,
                    frequency: frequency === '' ? undefined : Number(frequency),
                };
            }),
        };
    }

    form.addEventListener('click', (event) => {
        const button = /** @type {HTMLElement | null} */ (event.target)?.closest('button');
        if (!button) return;
        const section = /** @type {PlanningSection | undefined} */ (button.dataset.templateAdd);
        if (section) {
            addRow(section).querySelector('input')?.focus();
        } else if (!moveOrRemoveRow(button)) {
            return;
        }
        form.dispatchEvent(new Event('input', { bubbles: true }));
    });
    name.addEventListener('input', () => showError(''));

    /**
     * Applies a row action and preserves keyboard focus when the action changes the row order.
     *
     * @param {HTMLButtonElement} button the action button
     * @returns {boolean} whether the action modified planning content
     */
    function moveOrRemoveRow(button) {
        const row = button.closest('li');
        const list = row?.parentElement;
        if (!row || !list) return false;
        const next = row.nextElementSibling;
        const previous = row.previousElementSibling;
        switch (button.dataset.templateRowAction) {
            case 'up':
                if (!previous) return false;
                previous.before(row);
                break;
            case 'down':
                if (!next) return false;
                next.after(row);
                break;
            case 'remove':
                row.remove();
                break;
            default:
                return false;
        }
        updateOrderControls(list);
        if (row.isConnected) {
            (button.disabled ? row.querySelector('input') : button)?.focus();
        } else {
            const focusRow = next || previous;
            (
                focusRow?.querySelector('input') || list.parentElement?.querySelector('button')
            )?.focus();
        }
        return true;
    }

    return { form, owner, open, close, readPlan, showError };
}
