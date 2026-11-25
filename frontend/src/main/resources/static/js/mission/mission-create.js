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
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { registerDraftAdapter } from '/js/kernel/krt-drafts.js';
import { readMissionCreateDraft } from '/js/mission/mission-create-draft.js';
import { createMissionDraftRows } from '/js/mission/mission-create-rows.js';
import { createMissionScheduleDraft } from '/js/mission/mission-create-schedule.js';
import { createTemplateApplyDialog } from '/js/mission/mission-create-template-apply.js';
import { bindFrequencyRows } from '/js/mission/mission-frequency-rows.js';
import { TEMPLATE_PARTS } from '/js/mission/mission-template-parts.js';

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */
/** @typedef {ApiDto<'PageResponseMissionTemplateSummaryDto'>} TemplateChoicePage */
/** @typedef {typeof TEMPLATE_PARTS[number]} TemplatePart */

/** The draft row sections with the carrier each one serializes into. */
const SECTIONS = /** @type {const} */ ({
    objective: 'mission-objectives-json',
    step: 'mission-steps-json',
    unit: 'mission-units-json',
    frequency: 'mission-frequencies-json',
});

/** @typedef {keyof typeof SECTIONS} DraftSection */

/** The draft row section each list part of a template fills. */
const PART_SECTIONS = /** @type {const} */ ({
    frequencies: 'frequency',
    objectives: 'objective',
    steps: 'step',
    units: 'unit',
});

/** Binds the template preview and the editable planning rows of the mission create form. */
function initMissionCreate() {
    const formElement = document.getElementById('mission-form');
    const selector = document.getElementById('mission-create-template-select');
    const more = document.getElementById('mission-create-template-more');
    const applyOverlay = document.getElementById('mission-template-apply-modal');
    const applyForm = document.getElementById('mission-template-apply-form');
    if (
        !(formElement instanceof HTMLFormElement) ||
        !(selector instanceof HTMLSelectElement) ||
        !(more instanceof HTMLButtonElement) ||
        !applyOverlay ||
        !(applyForm instanceof HTMLFormElement)
    )
        return;
    const loadMore = more;
    const form = formElement;
    const select = selector;
    const frequencyRow = /** @type {HTMLTemplateElement} */ (
        document.getElementById('mission-create-frequency-row')
    );
    const frequencyKind = /** @type {HTMLSelectElement} */ (
        frequencyRow.content.querySelector('select[data-frequency-kind]')
    );
    const frequencyTypes = new Map(
        Array.from(frequencyKind.options, (option) => [option.value, option.text.trim()]),
    );
    const applyDialog = createTemplateApplyDialog(
        applyOverlay,
        applyForm,
        (id) => frequencyTypes.get(id) || '',
    );

    const labels = select.dataset;
    const owner = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('select[name="owningOrgUnitId"]')
    );
    const ownerHint = /** @type {HTMLElement} */ (
        document.getElementById('mission-create-template-owner-hint')
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
    const description = /** @type {HTMLTextAreaElement | null} */ (
        form.querySelector('textarea[name="description"]')
    );
    const meetingPoint = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="meetingPoint"]')
    );
    const internal = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="isInternal"][type="checkbox"]')
    );
    const schedule = createMissionScheduleDraft(form);

    const liveRegion = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-reorder-live]')
    );
    const sections = /** @type {DraftSection[]} */ (Object.keys(SECTIONS));
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
                /** @type {HTMLInputElement} */ (document.getElementById(SECTIONS[section])),
                {
                    liveRegion,
                    hiddenShipType: labels.hiddenShipType || '',
                    onMove: () => form.dispatchEvent(new Event('input', { bubbles: true })),
                },
            ),
        ]),
    );
    const frequencies = bindFrequencyRows(rows.frequency.list, frequencyRow);
    frequencies.sync();

    let choiceGeneration = 0;
    let nextChoicePage = 0;
    /** @type {AbortController | null} */
    let preview = null;
    let submitting = false;

    /**
     * @param {string} path the same-origin template endpoint
     * @param {URLSearchParams} [params] the additional query parameters
     * @returns {string} the path with the optional owner pick
     */
    function scopedUrl(path, params = new URLSearchParams()) {
        if (owner?.value) params.set('owningOrgUnitId', owner.value);
        const query = params.toString();
        return query ? `${path}?${query}` : path;
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
        ownerHint.hidden = !owner || owner.value !== '';
    }

    /**
     * Appends a template that the caller may apply.
     *
     * @param {string | undefined} id the template id
     * @param {string | undefined} name the template name
     */
    function appendChoice(id, name) {
        if (id && name && !Array.from(select.options).some((option) => option.value === id)) {
            select.add(new Option(name, id));
        }
    }

    /**
     * Restores an applied selection outside the first page if it is still available.
     *
     * @param {number} generation the current choice request
     */
    async function restoreAppliedChoice(generation) {
        const id = templateId.value;
        try {
            /** @type {MissionTemplate} */
            const data = await krtFetch.getJson(
                scopedUrl(`/missions/template-preview/${encodeURIComponent(id)}`),
                { key: 'mission-create-template-current' },
            );
            if (generation !== choiceGeneration || templateId.value !== id) return;
            appendChoice(data.id, data.name);
            select.disabled = select.length === 1;
            syncSelection();
        } catch (failure) {
            if (generation !== choiceGeneration || templateId.value !== id) return;
            if (!(
                failure &&
                typeof failure === 'object' &&
                'status' in failure &&
                failure.status === 404
            )) {
                showError(labels.readError || '');
            }
        }
    }

    /**
     * Loads a page of choices without modifying the draft or its applied revision.
     *
     * @param {number} [page] the zero-based page to append, or zero to refresh the scope
     */
    async function loadChoices(page = 0) {
        const generation = ++choiceGeneration;
        const hadFocus = document.activeElement === loadMore;
        loadMore.disabled = true;
        if (page === 0) {
            select.length = 1;
            select.disabled = true;
            loadMore.hidden = true;
            syncSelection();
        }
        try {
            /** @type {TemplateChoicePage} */
            const choices = await krtFetch.getJson(
                scopedUrl(
                    '/missions/template-choices',
                    new URLSearchParams({ page: String(page) }),
                ),
                { key: 'mission-create-template-choices' },
            );
            if (generation !== choiceGeneration) return;
            choices.content?.forEach(({ id, name }) => appendChoice(id, name));
            nextChoicePage = page + 1;
            loadMore.hidden = nextChoicePage >= (choices.totalPages ?? 0);
            select.disabled = select.length === 1;
            if (hadFocus && loadMore.hidden && !select.disabled) select.focus();
            syncSelection();
            if (page === 0 && templateId.value && !select.value) {
                await restoreAppliedChoice(generation);
            }
        } catch {
            if (generation === choiceGeneration) showError(labels.readError || '');
        } finally {
            if (generation === choiceGeneration) loadMore.disabled = false;
        }
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
     * @param {TemplatePart} part a part the template sets
     * @returns {boolean} whether the create form has a field that takes it
     */
    function available(part) {
        switch (part) {
            case 'description':
                return description !== null;
            case 'schedule':
                return schedule !== null;
            case 'meetingPoint':
                return meetingPoint !== null;
            case 'visibility':
                return internal !== null && !internal.disabled;
            case 'frequencies':
            case 'objectives':
            case 'steps':
            case 'units':
                return true;
        }
    }

    /**
     * @param {MissionTemplate} template the previewed template
     * @param {TemplatePart} part a part the template sets
     * @returns {boolean} whether applying the part overwrites what the planner entered
     */
    function replaces(template, part) {
        switch (part) {
            case 'description':
                return Boolean(description?.value.trim());
            case 'schedule':
                return schedule?.hasEntries(template) ?? false;
            case 'meetingPoint':
                return Boolean(meetingPoint?.value.trim());
            case 'visibility':
                return internal?.checked !== Boolean(template.isInternal);
            case 'frequencies':
            case 'objectives':
            case 'steps':
            case 'units':
                return rows[PART_SECTIONS[part]].list.childElementCount > 0;
        }
    }

    /**
     * Copies the chosen parts of a template into the editable draft.
     *
     * @param {MissionTemplate} template the previewed template
     * @param {TemplatePart[]} parts the parts the planner ticked
     */
    function applyParts(template, parts) {
        for (const part of TEMPLATE_PARTS.filter((known) => parts.includes(known))) {
            switch (part) {
                case 'description':
                    if (description) description.value = template.description || '';
                    break;
                case 'schedule':
                    schedule?.apply(template);
                    break;
                case 'meetingPoint':
                    if (meetingPoint) meetingPoint.value = template.meetingPoint || '';
                    break;
                case 'visibility':
                    if (internal) {
                        internal.checked = Boolean(template.isInternal);
                        internal.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    break;
                case 'frequencies':
                case 'objectives':
                case 'steps':
                case 'units':
                    rows[PART_SECTIONS[part]].fill(template[part] || []);
                    break;
            }
        }
        frequencies.sync();
    }

    /**
     * Previews the selected revision and applies the parts the planner chooses.
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
            const parts = await applyDialog.choose(data, available, (part) => replaces(data, part));
            if (!parts || request.signal.aborted) return;
            applyParts(data, parts);
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
    loadMore.addEventListener('click', () => {
        showError('');
        void loadChoices(nextChoicePage);
    });
    owner?.addEventListener('change', () => {
        clearAssociation();
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
        const editor = sections.find((known) => trigger.startsWith(`create-${known}-`));
        if (!editor || editor !== section) return;
        if (action === 'add') {
            const row =
                editor === 'frequency'
                    ? rows.frequency.add({ frequencyTypeId: frequencies.nextType() })
                    : rows[editor].add();
            /** @type {HTMLElement | null} */ (row.querySelector('input, select'))?.focus();
        } else if (action === 'delete') {
            const row = button?.closest('[data-planning-row]');
            if (!row) return;
            rows[editor].remove(row);
        } else {
            return;
        }
        if (editor === 'frequency') frequencies.sync();
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

    registerDraftAdapter('mission-create', {
        serialize() {
            return {
                templateId: templateId.value,
                templateVersion: templateVersion.value,
                rows: Object.fromEntries(
                    sections.map((section) => [section, rows[section].values()]),
                ),
            };
        },
        restore(_form, extra) {
            const draft = readMissionCreateDraft(extra, sections);
            sections.forEach((section) => rows[section].fill(draft.rows[section]));
            frequencies.sync();
        },
        finish(_form, extra) {
            const draft = readMissionCreateDraft(extra, sections);
            templateId.value = draft.templateId;
            templateVersion.value = draft.templateVersion;
            stale.hidden = true;
            void loadChoices();
        },
    });
    void loadChoices();
}

initMissionCreate();
