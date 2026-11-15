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

import { showKrtConfirm } from '/js/kernel/toast.js';
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { bindFrequencyRows } from '/js/mission/mission-frequency-rows.js';
import { createMissionPlanningRows } from '/js/mission/mission-planning-rows.js';
import { hasPart, TEMPLATE_PARTS, timeOfDay } from '/js/mission/mission-template-parts.js';

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */
/** @typedef {ApiDto<'CreateMissionTemplateRequest'>} TemplateDraft */
/** @typedef {typeof TEMPLATE_PARTS[number]} TemplatePart */
/** @typedef {'frequencies' | 'objectives' | 'steps' | 'units'} PlanningSection */

const SECTIONS = /** @type {const} */ (['frequencies', 'objectives', 'steps', 'units']);
const TIMES = /** @type {const} */ (['meetingTime', 'plannedStartTime', 'plannedEndTime']);

/**
 * @param {string} part a part key read from the markup
 * @returns {part is PlanningSection} whether the part is an ordered row list
 */
function isSection(part) {
    return SECTIONS.some((section) => section === part);
}

/**
 * The parts, row controls and payload of a mission template editor.
 *
 * Only the parts shown in the editor belong to the template; a hidden part is saved as absent.
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
    const partBar = /** @type {HTMLElement} */ (form.querySelector('[data-template-part-bar]'));
    const emptyHint = /** @type {HTMLElement} */ (
        form.querySelector('[data-template-parts-empty]')
    );
    const description = /** @type {HTMLTextAreaElement} */ (
        form.querySelector('textarea[name="description"]')
    );
    const meetingPoint = /** @type {HTMLInputElement} */ (
        form.querySelector('input[name="meetingPoint"]')
    );
    const internal = /** @type {HTMLInputElement} */ (
        form.querySelector('input[name="isInternal"]')
    );
    const times = Object.fromEntries(
        TIMES.map((time) => [
            time,
            /** @type {HTMLInputElement} */ (form.querySelector(`input[name="${time}"]`)),
        ]),
    );

    const liveRegion = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-reorder-live]')
    );
    const rows = Object.fromEntries(
        SECTIONS.map((section) => [
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
    const frequencies = bindFrequencyRows(
        rows.frequencies.list,
        /** @type {HTMLTemplateElement} */ (
            panel.querySelector('template[data-template-row="frequencies"]')
        ),
    );

    /**
     * @param {TemplatePart} part the part
     * @returns {HTMLElement} the part's card
     */
    function card(part) {
        return /** @type {HTMLElement} */ (form.querySelector(`[data-template-part="${part}"]`));
    }

    /**
     * @param {TemplatePart} part the part
     * @returns {HTMLButtonElement} the part's add action in the part bar
     */
    function addButton(part) {
        return /** @type {HTMLButtonElement} */ (
            partBar.querySelector(`[data-template-part-add="${part}"]`)
        );
    }

    /**
     * @param {TemplatePart} part the part
     * @returns {boolean} whether the part belongs to the draft
     */
    function shown(part) {
        return !card(part).hidden;
    }

    /** Synchronizes the part bar, the empty hint and the section totals with the draft. */
    function syncChrome() {
        let shownCount = 0;
        for (const part of TEMPLATE_PARTS) {
            const visible = shown(part);
            addButton(part).hidden = visible;
            if (visible) shownCount++;
        }
        partBar.hidden = shownCount === TEMPLATE_PARTS.length;
        emptyHint.hidden = shownCount > 0;
        for (const section of SECTIONS) {
            const count = form.querySelector(`[data-template-count="${section}"]`);
            if (count) count.textContent = String(rows[section].list.childElementCount);
        }
    }

    /**
     * @param {TemplatePart} part the part
     * @returns {boolean} whether the part holds any entry the planner made
     */
    function hasEntries(part) {
        switch (part) {
            case 'description':
                return description.value.trim() !== '';
            case 'schedule':
                return TIMES.some((time) => times[time].value !== '');
            case 'meetingPoint':
                return meetingPoint.value.trim() !== '';
            case 'visibility':
                return false;
            case 'frequencies':
            case 'objectives':
            case 'steps':
            case 'units':
                return rows[part].list.childElementCount > 0;
        }
    }

    /** @param {TemplatePart} part the part whose inputs return to blank */
    function clear(part) {
        switch (part) {
            case 'description':
                description.value = '';
                break;
            case 'schedule':
                TIMES.forEach((time) => (times[time].value = ''));
                break;
            case 'meetingPoint':
                meetingPoint.value = '';
                break;
            case 'visibility':
                internal.checked = true;
                break;
            case 'frequencies':
            case 'objectives':
            case 'steps':
            case 'units':
                rows[part].fill([]);
                break;
        }
    }

    /** @param {PlanningSection} section the list that gets a blank row */
    function addRow(section) {
        const row =
            section === 'frequencies'
                ? rows.frequencies.add({ frequencyTypeId: frequencies.nextType() })
                : rows[section].add();
        if (section === 'frequencies') frequencies.sync();
        /** @type {HTMLElement | null} */ (row.querySelector('input, select'))?.focus();
    }

    /** @param {TemplatePart} part the part the planner adds to the template */
    function showPart(part) {
        card(part).hidden = false;
        if (isSection(part) && rows[part].list.childElementCount === 0) {
            addRow(part);
        } else {
            /** @type {HTMLElement | null} */ (
                card(part).querySelector('textarea, input, select')
            )?.focus();
        }
        syncChrome();
    }

    /** @param {TemplatePart} part the part the planner takes out of the template */
    async function hidePart(part) {
        if (
            hasEntries(part) &&
            !(await showKrtConfirm(
                panel.dataset.partRemoveConfirm || '',
                '',
                panel.dataset.partRemoveLabel,
                panel.dataset.cancelLabel,
            ))
        ) {
            return;
        }
        clear(part);
        card(part).hidden = true;
        syncChrome();
        if (partBar.hidden) {
            name.focus();
        } else {
            addButton(part).focus();
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
     * Starts an editor in create or edit mode with exactly the parts the template sets.
     *
     * @param {string} ownerId the active scope for a blank template
     * @param {MissionTemplate | TemplateDraft | null} template the initial content, or null for blank
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
        description.value = template?.description || '';
        meetingPoint.value = template?.meetingPoint || '';
        internal.checked = template?.isInternal ?? true;
        TIMES.forEach((time) => (times[time].value = timeOfDay(template?.[time])));
        for (const section of SECTIONS) rows[section].fill(template?.[section] || []);
        frequencies.sync();
        for (const part of TEMPLATE_PARTS) {
            card(part).hidden = !template || !hasPart(template, part);
        }
        syncChrome();
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
     * @param {PlanningSection} section the list to read
     * @returns {Element[]} its rows in DOM order, or none while the part is not in the template
     */
    function sectionRows(section) {
        return shown(section) ? Array.from(rows[section].list.children) : [];
    }

    /**
     * Reads the parts shown in the editor in DOM order for the next write.
     *
     * @returns {Omit<TemplateDraft, 'owningOrgUnitId'>} the planned content
     */
    function readPlan() {
        /**
         * @param {TemplatePart} part the part the value belongs to
         * @param {string} value the raw input
         * @returns {string | undefined} the value while the part is shown and the input is filled
         */
        const text = (part, value) => (shown(part) && value.trim() ? value : undefined);
        return {
            name: name.value,
            description: text('description', description.value),
            meetingPoint: text('meetingPoint', meetingPoint.value),
            isInternal: shown('visibility') ? internal.checked : undefined,
            meetingTime: text('schedule', times.meetingTime.value),
            plannedStartTime: text('schedule', times.plannedStartTime.value),
            plannedEndTime: text('schedule', times.plannedEndTime.value),
            frequencies: sectionRows('frequencies').map((row) => {
                const type = control(row, 'frequencyTypeId').value;
                return {
                    frequencyTypeId: type || undefined,
                    name: type ? undefined : control(row, 'name').value,
                    value: Number(control(row, 'value').value),
                };
            }),
            objectives: sectionRows('objectives').map((row) => ({
                title: control(row, 'title').value,
                kind: /** @type {NonNullable<MissionTemplate['objectives']>[number]['kind']} */ (
                    control(row, 'kind').value
                ),
            })),
            steps: sectionRows('steps').map((row) => ({
                title: control(row, 'title').value,
                meta: control(row, 'meta').value || undefined,
            })),
            units: sectionRows('units').map((row) => {
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
        const added = /** @type {TemplatePart | undefined} */ (button.dataset.templatePartAdd);
        const removed = /** @type {TemplatePart | undefined} */ (button.dataset.templatePartRemove);
        const section = /** @type {PlanningSection | undefined} */ (button.dataset.templateAdd);
        if (added) {
            showPart(added);
        } else if (removed) {
            void hidePart(removed).then(() =>
                form.dispatchEvent(new Event('input', { bubbles: true })),
            );
            return;
        } else if (section) {
            addRow(section);
        } else {
            if (button.dataset.templateRowAction !== 'remove') return;
            const row = button.closest('[data-planning-row]');
            if (!row) return;
            const owning = SECTIONS.find((key) => rows[key].list === row.parentElement);
            if (!owning) return;
            rows[owning].remove(row);
            if (owning === 'frequencies') frequencies.sync();
        }
        syncChrome();
        form.dispatchEvent(new Event('input', { bubbles: true }));
    });
    name.addEventListener('input', () => showError(''));

    return { form, owner, open, close, readPlan, showError };
}
