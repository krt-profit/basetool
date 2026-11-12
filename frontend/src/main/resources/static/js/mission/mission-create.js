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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { showKrtConfirm } from '/js/kernel/toast.js';
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { createMissionDraftRows } from '/js/mission/mission-create-rows.js';

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */
/** @typedef {ApiDto<'MissionTemplateSummaryDto'>} TemplateChoice */

/** Binds the template preview and the editable planning rows of the mission create form. */
function initMissionCreate() {
    const formElement = document.getElementById('mission-form');
    const selector = document.getElementById('mission-create-template-select');
    if (!(formElement instanceof HTMLFormElement) || !(selector instanceof HTMLSelectElement))
        return;
    const form = formElement;
    const select = selector;

    const labels = select.dataset;
    const owner = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[name="owningOrgUnitId"]')
    );
    const templateId = /** @type {HTMLInputElement} */ (form.elements.namedItem('templateId'));
    const templateVersion = /** @type {HTMLInputElement} */ (
        form.elements.namedItem('templateVersion')
    );
    const error = /** @type {HTMLElement} */ (document.getElementById('mission-create-error'));
    const stale = /** @type {HTMLElement} */ (
        document.getElementById('mission-create-template-stale')
    );
    const detach = /** @type {HTMLButtonElement} */ (
        document.getElementById('mission-create-template-detach')
    );
    const reapply = /** @type {HTMLButtonElement} */ (
        document.getElementById('mission-create-template-reapply')
    );

    const sections = /** @type {const} */ (['objective', 'step', 'unit']);
    const rows = Object.fromEntries(
        sections.map((section) => [
            section,
            createMissionDraftRows(
                /** @type {HTMLElement} */ (
                    document.getElementById(`mission-create-${section}-list`)
                ),
                /** @type {HTMLTemplateElement} */ (
                    document.getElementById(`mission-create-${section}-row`)
                ),
                /** @type {HTMLInputElement} */ (
                    document.getElementById(`mission-${section}s-json`)
                ),
                labels.hiddenShipType || '',
            ),
        ]),
    );

    let choiceGeneration = 0;
    /** @type {AbortController | null} */
    let preview = null;
    let submitting = false;

    /**
     * @param {string} path the same-origin template endpoint
     * @returns {string} the path with the optional owner pick
     */
    function scopedUrl(path) {
        return owner?.value ? `${path}?owningOrgUnitId=${encodeURIComponent(owner.value)}` : path;
    }

    /** @param {string} message the localized error */
    function showError(message) {
        error.textContent = message;
        error.hidden = !message;
    }

    /** Restores the selector to the revision currently applied to the draft. */
    function syncSelection() {
        const hasChoice = Array.from(select.options).some(
            (option) => option.value === templateId.value,
        );
        select.value = hasChoice ? templateId.value : '';
        reapply.hidden = !hasChoice || !templateId.value;
    }

    /** Refreshes choices without modifying the draft or its applied revision. */
    async function loadChoices() {
        const generation = ++choiceGeneration;
        select.disabled = true;
        try {
            /** @type {TemplateChoice[]} */
            const choices = await krtFetch.getJson(scopedUrl('/missions/template-choices'), {
                key: 'mission-create-template-choices',
            });
            if (generation !== choiceGeneration) return;
            select.length = 1;
            choices.forEach(({ id, name }) => {
                if (id && name) select.add(new Option(name, id));
            });
            select.disabled = select.length === 1;
        } catch {
            if (generation !== choiceGeneration) return;
            select.length = 1;
            showError(labels.readError || '');
        }
        syncSelection();
    }

    /** Detaches the applied revision and keeps the planning rows as manual entries. */
    function clearAssociation() {
        preview?.abort();
        preview = null;
        templateId.value = '';
        templateVersion.value = '';
        stale.hidden = true;
        syncSelection();
    }

    /**
     * Applies the selected revision after confirming replacement of existing rows.
     *
     * @param {string} id the selected template id
     */
    async function applyTemplate(id) {
        preview?.abort();
        const request = new AbortController();
        preview = request;
        showError('');
        try {
            /** @type {MissionTemplate} */
            const data = await krtFetch.getJson(
                scopedUrl(`/missions/template-preview/${encodeURIComponent(id)}`),
                { signal: request.signal },
            );
            if (request.signal.aborted) return;
            if (Object.values(rows).some(({ list }) => list.childElementCount > 0)) {
                const accepted = await showKrtConfirm(
                    labels.replaceTitle || '',
                    labels.replace || '',
                );
                if (!accepted || request.signal.aborted) return;
            }
            rows.objective.fill(data.objectives || []);
            rows.step.fill(data.steps || []);
            rows.unit.fill(data.units || []);
            templateId.value = id;
            templateVersion.value = String(data.version);
            stale.hidden = true;
            form.dispatchEvent(new Event('input', { bubbles: true }));
        } catch {
            if (!request.signal.aborted) {
                showError(labels.readError || '');
                void loadChoices();
            }
        } finally {
            if (preview === request) {
                preview = null;
                syncSelection();
            }
        }
    }

    select.addEventListener('change', () => {
        if (select.value) {
            void applyTemplate(select.value);
        } else {
            clearAssociation();
        }
    });
    owner?.addEventListener('change', () => {
        clearAssociation();
        select.length = 1;
        showError('');
        void loadChoices();
    });
    detach.addEventListener('click', () => {
        clearAssociation();
        showError('');
    });
    reapply.addEventListener('click', () => {
        if (templateId.value) void applyTemplate(templateId.value);
    });

    form.addEventListener('click', (event) => {
        const button = /** @type {HTMLElement | null} */ (event.target)?.closest('button');
        const trigger = button?.dataset.trigger;
        if (!trigger) return;
        const [, section, action] = trigger.split('-');
        if (!sections.some((known) => trigger.startsWith(`create-${known}-`))) return;
        const editor = rows[section];
        if (action === 'add') {
            editor.add().querySelector('input')?.focus();
        } else {
            const row = button?.closest('.ae-row');
            if (!row) return;
            if (action === 'delete') row.remove();
            if (action === 'up') row.previousElementSibling?.before(row);
            if (action === 'down') row.nextElementSibling?.after(row);
        }
        editor.renumber();
        form.dispatchEvent(new Event('input', { bubbles: true }));
    });

    /**
     * Shows field validation without replacing the user's DOM draft.
     *
     * @param {Record<string, string>} [messages] localized field messages
     */
    function fieldErrors(messages = {}) {
        form.querySelectorAll('[data-error-for]').forEach((element) => {
            const field = /** @type {HTMLElement} */ (element).dataset.errorFor || '';
            element.textContent = messages[field] || '';
        });
    }

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (preview || submitting) return;
        Object.values(rows).forEach((section) => section.serialize());
        showError('');
        fieldErrors();
        submitting = true;
        const submittedTemplate = templateId.value;
        const submittedVersion = templateVersion.value;
        try {
            await krtFetch.submitForm({
                form,
                url: '/missions/ajax',
                submitter: event.submitter,
                toast: false,
                errorMessage: labels.saveError,
                onError(status, body) {
                    if (status === 409 && body?.code === 'MISSION_TEMPLATE_STALE') {
                        if (
                            templateId.value !== submittedTemplate ||
                            templateVersion.value !== submittedVersion
                        ) {
                            return true;
                        }
                        stale.hidden = false;
                        reapply.hidden = true;
                        detach.focus();
                        void loadChoices();
                        return true;
                    }
                    if (status === 422 && body) {
                        fieldErrors(body);
                        showError(labels.saveError || '');
                        return true;
                    }
                    return false;
                },
                onSuccess(body) {
                    const target = safeSameOriginUrl(body?.targetUrl);
                    if (target) {
                        resetUnsavedChanges();
                        window.location.assign(target);
                    }
                },
            });
        } finally {
            submitting = false;
        }
    });
    void loadChoices();
}

initMissionCreate();
