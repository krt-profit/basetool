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

import { createMissionPlanningRows } from '/js/mission/mission-planning-rows.js';
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

    const liveRegion = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-reorder-live]')
    );
    const sections = /** @type {const} */ (['objectives', 'steps', 'units']);
    const rows = Object.fromEntries(
        sections.map((section) => [
            section,
            createMissionPlanningRows(
                /** @type {HTMLElement} */ (
                    panel.querySelector(`[data-template-rows="${section}"]`)
                ),
                /** @type {HTMLTemplateElement} */ (
                    panel.querySelector(`template[data-template-row="${section}"]`)
                ),
                {
                    liveRegion,
                    hiddenShipType: panel.dataset.hiddenShipType || '',
                    onMove: () => form.dispatchEvent(new Event('input', { bubbles: true })),
                },
            ),
        ]),
    );

    /** Updates the visible section totals after a draft change. */
    function updateCounts() {
        for (const section of sections) {
            const count = form.querySelector(`[data-template-count="${section}"]`);
            if (count) count.textContent = String(rows[section].list.childElementCount);
        }
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

        owner.value = template?.owningOrgUnitId || ownerId;
        const editing = Boolean(template && 'id' in template && template.id);
        owner.disabled = editing;
        name.value = template?.name || '';
        heading.textContent = editing
            ? panel.dataset.editTitle || ''
            : panel.dataset.createTitle || '';
        deleteButton.hidden = !editing;
        rows.objectives.fill(template?.objectives || []);
        rows.steps.fill(template?.steps || []);
        rows.units.fill(template?.units || []);
        updateCounts();
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
            objectives: Array.from(rows.objectives.list.children, (row) => ({
                title: control(row, 'title').value,
                kind: /** @type {NonNullable<MissionTemplate['objectives']>[number]['kind']} */ (
                    control(row, 'kind').value
                ),
            })),
            steps: Array.from(rows.steps.list.children, (row) => ({
                title: control(row, 'title').value,
                meta: control(row, 'meta').value || undefined,
            })),
            units: Array.from(rows.units.list.children, (row) => {
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
            rows[section].add().querySelector('input')?.focus();
        } else {
            if (button.dataset.templateRowAction !== 'remove') return;
            const row = button.closest('[data-planning-row]');
            if (!row) return;
            const section = sections.find((key) => rows[key].list === row.parentElement);
            if (!section) return;
            rows[section].remove(row);
        }
        updateCounts();
        form.dispatchEvent(new Event('input', { bubbles: true }));
    });
    name.addEventListener('input', () => showError(''));

    return { form, owner, open, close, readPlan, showError };
}
