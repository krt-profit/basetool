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
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { showKrtConfirm } from '/js/kernel/toast.js';
import { hasUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { createMissionTemplateEditor } from '/js/mission/mission-template-editor.js';

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */
/** @typedef {MissionTemplate & Required<Pick<MissionTemplate, 'id' | 'owningOrgUnitId' | 'version'>>} EditableTemplate */

const workspace = document.getElementById('mission-template-workspace');
const panel = document.getElementById('mission-template-editor');
const scope = document.querySelector('#mission-template-scope');
const createButton = document.querySelector('[data-testid="mission-template-create"]');
const listError = document.getElementById('mission-template-list-error');

if (
    workspace &&
    panel &&
    scope instanceof HTMLSelectElement &&
    createButton instanceof HTMLButtonElement &&
    listError
) {
    setup(workspace, panel, scope, createButton, listError);
}

/**
 * Wires the live list independently from the open editor.
 *
 * @param {HTMLElement} workspace the unit-scoped list controls
 * @param {HTMLElement} panel the editor panel
 * @param {HTMLSelectElement} scope the selected list scope
 * @param {HTMLButtonElement} createButton the blank editor action
 * @param {HTMLElement} listError the workspace error message
 */
function setup(workspace, panel, scope, createButton, listError) {
    const messages = workspace.dataset;
    const api = messages.templatesUrl || '';
    const editor = createMissionTemplateEditor(panel);
    /** @type {EditableTemplate | null} */
    let current = null;
    /** @type {HTMLElement | null} */
    let opener = null;
    /** @type {string} */
    let renderedScope = scope.value;
    let pending = false;
    let listSequence = 0;
    const topic = () => `mission-templates:${scope.value}`;

    const sections = krtPageSections.create({
        topicClass: 'MISSION_TEMPLATES',
        sections: { list: { container: '#mission-template-list' } },
        topic,
        pageUrl: () => `${messages.listUrl}?ownerId=${encodeURIComponent(scope.value)}`,
        broadcast() {},
        receiver: {
            retargetable: true,
            refresh(keys, run) {
                refreshList(false, () => run(keys));
            },
        },
    });

    /**
     * Displays a list-level error.
     *
     * @param {string} message the localized failure text, or empty to clear
     */
    function showListError(message) {
        listError.textContent = message;
        listError.hidden = !message;
    }

    /**
     * Reloads the list and subscribes to the selected owner.
     *
     * @param {boolean} retarget whether the selected owner changed
     * @param {() => Promise<boolean[]>} [refresh] the receiver's refresh callback
     */
    async function refreshList(
        retarget,
        refresh = () => sections.refresh('list', { broadcast: false }),
    ) {
        const sequence = ++listSequence;
        const requestedScope = scope.value;
        if (retarget) sections.receiver?.retarget(topic());
        showListError('');
        const [updated] = await refresh();
        if (sequence !== listSequence || requestedScope !== scope.value) return;
        if (updated) {
            renderedScope = requestedScope;
        } else {
            scope.value = renderedScope;
            sections.receiver?.retarget(topic());
            showListError(messages.listError || '');
        }
    }

    /** Restores focus to the editor opener or to the blank-editor action. */
    function closeEditor() {
        editor.close();
        current = null;
        (opener?.isConnected ? opener : createButton).focus();
    }

    /** @returns {Promise<boolean>} whether an open draft can be replaced */
    async function canReplaceEditor() {
        if (panel.hidden || !hasUnsavedChanges()) return true;
        return showKrtConfirm(messages.discardConfirm || '');
    }

    /** @param {boolean} value whether an editor action is in progress */
    function setPending(value) {
        pending = value;
        for (const button of editor.form.querySelectorAll(
            'button[type="submit"], [data-template-delete], [data-template-cancel]',
        )) {
            if (button instanceof HTMLButtonElement) button.disabled = value;
        }
    }

    /**
     * Handles a save or delete failure in the editor while preserving the draft.
     *
     * @param {unknown} problem the backend problem response
     */
    function onConflict(problem) {
        editor.form.inert = false;
        const duplicate =
            problem !== null &&
            typeof problem === 'object' &&
            'code' in problem &&
            problem.code === 'DUPLICATE_ENTITY';
        editor.showError(
            (duplicate ? messages.duplicateError : messages.revisionError) || '',
            duplicate,
        );
    }

    if (messages.templateDraft) {
        const draft = /** @type {ApiDto<'CreateMissionTemplateRequest'>} */ (
            JSON.parse(messages.templateDraft)
        );
        editor.open(scope.value, draft);
        const url = new URL(window.location.href);
        url.searchParams.delete('sourceMissionId');
        window.history.replaceState(window.history.state, '', url);
    }

    scope.addEventListener('change', () => refreshList(true));
    createButton.addEventListener('click', async () => {
        if (pending) return;
        setPending(true);
        try {
            if (!(await canReplaceEditor())) return;
            if (!panel.hidden) editor.close();
            opener = createButton;
            current = null;
            editor.open(scope.value, null);
        } finally {
            setPending(false);
        }
    });

    workspace.addEventListener('click', async (event) => {
        const editButton = /** @type {Element | null} */ (event.target)?.closest(
            '[data-template-edit]',
        );
        if (!(editButton instanceof HTMLButtonElement) || !editButton.dataset.templateId || pending)
            return;
        setPending(true);
        try {
            if (!(await canReplaceEditor())) return;
            showListError('');
            const detail = /** @type {MissionTemplate | null} */ (
                await krtFetch.getJson(
                    `${api}/${encodeURIComponent(editButton.dataset.templateId)}`,
                )
            );
            if (!detail?.id || !detail.owningOrgUnitId || typeof detail.version !== 'number') {
                showListError(messages.loadError || '');
                return;
            }
            if (!panel.hidden) editor.close();
            opener = editButton;
            current = {
                ...detail,
                id: detail.id,
                owningOrgUnitId: detail.owningOrgUnitId,
                version: detail.version,
            };
            editor.open(scope.value, detail);
        } catch {
            showListError(messages.loadError || '');
        } finally {
            setPending(false);
        }
    });

    editor.form.addEventListener('submit', async (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (pending) return;
        const ownerId = current?.owningOrgUnitId || editor.owner.value;
        const plan = editor.readPlan();
        /** @type {ApiDto<'CreateMissionTemplateRequest'> | ApiDto<'UpdateMissionTemplateRequest'>} */
        const payload = current
            ? { ...plan, version: current.version }
            : { ...plan, owningOrgUnitId: ownerId };
        setPending(true);
        editor.showError('');
        editor.form.inert = true;
        try {
            await sections.write({
                url: current ? `${api}/${encodeURIComponent(current.id)}` : api,
                method: current ? 'PUT' : 'POST',
                payload,
                errorMessage: messages.saveError,
                onConflict,
                onSuccess: async () => {
                    const retarget = scope.value !== ownerId;
                    scope.value = ownerId;
                    await refreshList(retarget);
                    closeEditor();
                },
            });
        } finally {
            editor.form.inert = false;
            setPending(false);
        }
    });

    editor.form.querySelector('[data-template-cancel]')?.addEventListener('click', async () => {
        if (pending) return;
        setPending(true);
        try {
            if (await canReplaceEditor()) closeEditor();
        } finally {
            setPending(false);
        }
    });

    editor.form.querySelector('[data-template-delete]')?.addEventListener('click', async () => {
        if (!current || pending) return;
        const template = current;
        setPending(true);
        try {
            if (
                !(await showKrtConfirm(
                    messages.deleteConfirm || '',
                    '',
                    messages.deleteLabel,
                    messages.cancelLabel,
                ))
            )
                return;
            await sections.write({
                url: `${api}/${encodeURIComponent(template.id)}?version=${template.version}`,
                method: 'DELETE',
                errorMessage: messages.deleteError,
                onConflict,
                onSuccess: async () => {
                    if (scope.value === template.owningOrgUnitId) await refreshList(false);
                    closeEditor();
                },
            });
        } finally {
            setPending(false);
        }
    });
}
