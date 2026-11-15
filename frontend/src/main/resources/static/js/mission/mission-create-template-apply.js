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

import { krtModal } from '/js/kernel/krt-modal.js';
import { hasPart, TEMPLATE_PARTS, timeOfDay } from '/js/mission/mission-template-parts.js';

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */
/** @typedef {typeof TEMPLATE_PARTS[number]} TemplatePart */

/**
 * @param {string} part a part key read from the markup
 * @returns {part is TemplatePart} whether it names a template part
 */
function isPart(part) {
    return TEMPLATE_PARTS.some((known) => known === part);
}

/**
 * Asks which parts of a template the planner takes into the mission draft.
 *
 * @param {HTMLElement} overlay the dialog overlay
 * @param {HTMLFormElement} form the dialog's part checklist
 * @param {(id: string) => string} frequencyTypeLabel names a catalogue frequency type
 */
export function createTemplateApplyDialog(overlay, form, frequencyTypeLabel) {
    const labels = form.dataset;
    const name = /** @type {HTMLElement} */ (form.querySelector('#mission-template-apply-name'));
    const submit = /** @type {HTMLButtonElement} */ (
        form.querySelector('#mission-template-apply-submit')
    );
    /** @type {((parts: TemplatePart[] | null) => void) | null} */
    let settle = null;

    /** @returns {HTMLInputElement[]} the checkbox of every offered part */
    function offered() {
        return Array.from(form.querySelectorAll('input[name="part"]')).filter(
            /**
             * @param {Element} input a candidate
             * @returns {input is HTMLInputElement} whether it is an enabled checkbox
             */
            (input) => input instanceof HTMLInputElement && !input.disabled,
        );
    }

    /**
     * @param {MissionTemplate} template the template
     * @param {TemplatePart} part a part the template sets
     * @returns {string} the short preview shown beside the part
     */
    function summary(template, part) {
        switch (part) {
            case 'description':
                return (template.description || '').replace(/\s+/g, ' ').trim();
            case 'schedule':
                return [
                    [labels.timeMeeting, template.meetingTime],
                    [labels.timeStart, template.plannedStartTime],
                    [labels.timeEnd, template.plannedEndTime],
                ]
                    .filter(([, time]) => time)
                    .map(([label, time]) => `${label} ${timeOfDay(time)}`)
                    .join(' · ');
            case 'meetingPoint':
                return template.meetingPoint || '';
            case 'visibility':
                return (template.isInternal ? labels.internal : labels.public) || '';
            case 'frequencies':
                return listPreview(
                    (template.frequencies || []).map(
                        (row) => row.name || frequencyTypeLabel(row.frequencyTypeId || ''),
                    ),
                );
            case 'objectives':
                return listPreview((template.objectives || []).map((row) => row.title));
            case 'steps':
                return listPreview((template.steps || []).map((row) => row.title));
            case 'units':
                return listPreview((template.units || []).map((row) => row.name));
        }
    }

    /**
     * @param {(string | undefined)[]} entries the entry labels in template order
     * @returns {string} the labels joined for a one-line preview
     */
    function listPreview(entries) {
        return entries.filter(Boolean).join(' · ');
    }

    /**
     * @param {MissionTemplate} template the template
     * @param {TemplatePart} part a part the template sets
     * @returns {number | null} the number of rows of a list part, or null for a single value
     */
    function count(template, part) {
        switch (part) {
            case 'frequencies':
            case 'objectives':
            case 'steps':
            case 'units':
                return template[part]?.length ?? 0;
            case 'description':
            case 'schedule':
            case 'meetingPoint':
            case 'visibility':
                return null;
        }
    }

    /** @param {TemplatePart[] | null} parts the chosen parts, or null when dismissed */
    function finish(parts) {
        const done = settle;
        settle = null;
        done?.(parts);
    }

    /** Enables the apply action while at least one part is ticked. */
    function syncSubmit() {
        submit.disabled = !offered().some((input) => input.checked);
    }

    form.addEventListener('change', syncSubmit);
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        finish(
            offered()
                .filter((input) => input.checked)
                .map((input) => input.value)
                .filter(isPart),
        );
        krtModal.close(overlay);
    });
    overlay.addEventListener('close', () => finish(null));

    /**
     * Opens the checklist with every part the template sets and the draft can take, all ticked.
     *
     * @param {MissionTemplate} template the previewed template
     * @param {(part: TemplatePart) => boolean} available whether the draft has a field for the part
     * @param {(part: TemplatePart) => boolean} replaces whether applying the part overwrites entries
     * @returns {Promise<TemplatePart[] | null>} the ticked parts, or null when the planner cancels
     */
    function choose(template, available, replaces) {
        finish(null);
        name.textContent = template.name || '';
        form.querySelectorAll('[data-apply-part]').forEach((row) => {
            const part = /** @type {HTMLElement} */ (row).dataset.applyPart || '';
            const input = row.querySelector('input[name="part"]');
            if (!isPart(part) || !(input instanceof HTMLInputElement)) return;
            const shown = hasPart(template, part) && available(part);
            /** @type {HTMLElement} */ (row).hidden = !shown;
            input.disabled = !shown;
            input.checked = shown;
            const preview = row.querySelector('[data-apply-summary]');
            if (preview) preview.textContent = shown ? summary(template, part) : '';
            const badge = row.querySelector('[data-apply-count]');
            const rows = shown ? count(template, part) : null;
            if (badge instanceof HTMLElement) {
                badge.textContent = rows === null ? '' : String(rows);
                badge.hidden = rows === null;
            }
            const marker = row.querySelector('[data-apply-replaces]');
            if (marker instanceof HTMLElement) marker.hidden = !shown || !replaces(part);
        });
        syncSubmit();
        return new Promise((resolve) => {
            settle = resolve;
            krtModal.open(overlay, { focus: offered()[0] ?? submit });
        });
    }

    return { choose };
}
