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

/* global missionId */

import { showKrtConfirm } from '/js/kernel/toast.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { missionHooks } from '/js/mission/mission-hooks.js';

/**
 * Tells the parent operation's room that this mission's overview or finance section changed.
 *
 * @param {string | string[]} keys the changed mission section keys
 */
function crossPublishToParentOperation(keys) {
    const operationId = window.missionOperationId;
    if (!operationId) {
        return;
    }
    const changed = Array.isArray(keys) ? keys : [keys];
    const operationSections = [];
    if (changed.indexOf('overview') !== -1) {
        operationSections.push('missions');
    }
    if (changed.indexOf('finance') !== -1) {
        operationSections.push('finance');
    }
    if (operationSections.length) {
        krtPageSections.publish(`operation:${operationId}`, operationSections);
    }
}

const missionSeam = krtPageSections.create({
    dictName: 'MISSION_SUBRES_I18N',
    dict() {
        return window.MISSION_SUBRES_I18N || {};
    },
    keys: {
        saveSectionPrefix: 'mission.save.section.',
        conflictSectionPrefix: 'mission.conflict.section.',
        successKey: 'mission.save.section.ok',
        errorKey: 'mission.save.section.error',
        conflictTitleKey: 'mission.conflict.toast.title',
        reloadLabelKey: 'mission.conflict.action.reload',
        dismissLabelKey: 'mission.conflict.action.dismiss',
        reloadQuestionKey: 'mission.conflict.action.reload.question',
        reloadDetailKey: 'mission.conflict.toast.detail',
        refreshErrorKey: 'mission.section.refresh.error',
    },
    topicClass: 'MISSION',
    sections: {
        crew: { container: '#crew-board-results', fragmentValue: 'crew-board' },
        finance: { container: '#finance-results', fragmentValue: 'finance' },
        mgmt: { container: '#mission-mgmt-results', fragmentValue: 'mgmt' },
        overview: { container: '#overview-results', fragmentValue: 'overview' },
        steps: { container: '#mission-steps-results', fragmentValue: 'steps-editor' },
        objectives: {
            container: '#mission-objectives-results',
            fragmentValue: 'objectives-editor',
        },
        frequencies: {
            container: '#mission-frequencies-results',
            fragmentValue: 'frequencies-editor',
        },
        organisation: {
            container: '#mission-organisation-results',
            fragmentValue: 'organisation',
        },
    },
    pageUrl() {
        return window.missionId ? `/missions/${window.missionId}` : null;
    },
    broadcast(keys) {
        if (missionHooks.presence && typeof missionHooks.presence.sendChanged === 'function') {
            missionHooks.presence.sendChanged(keys);
        }
        crossPublishToParentOperation(keys);
    },
    receiver: {
        events: { changed: 'krt:mission-changed', resync: 'krt:mission-resync' },
        pill: {
            id: 'mission-livesync-pill',
            label() {
                const dict = window.MISSION_LIVESYNC_I18N || {};
                return krtI18nText(
                    dict['mission.livesync.updates_available'],
                    'MISSION_LIVESYNC_I18N[mission.livesync.updates_available]',
                );
            },
        },
    },
});
missionHooks.write = missionSeam.write;

missionHooks.refresh = missionSeam.refresh;

missionHooks.notify = missionSeam.notify;

document.addEventListener('krt:swapped', (ev) => {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'crew-board-results') {
        return;
    }
    const meta = document.getElementById('crew-count-meta');
    if (!meta) {
        return;
    }
    const registered = (meta.getAttribute('data-registered') || '0').trim();
    const checkedIn = (meta.getAttribute('data-checked-in') || '0').trim();
    const registeredEl = document.getElementById('facts-registered');
    const checkedInEl = document.getElementById('facts-checked-in');
    const badge = document.querySelector('#tab-crew .tab-count');
    if (registeredEl) {
        registeredEl.textContent = registered;
    }
    if (checkedInEl) {
        checkedInEl.textContent = checkedIn;
    }
    if (badge) {
        badge.textContent = `${checkedIn}/${registered}`;
    }
});

document.addEventListener('krt:swapped', (ev) => {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'finance-results') {
        return;
    }
    const meta = document.getElementById('finance-count-meta');
    const badge = document.getElementById('finance-tab-count');
    if (meta && badge) {
        badge.textContent = (meta.textContent || '0').trim();
    }
});

document.addEventListener('krt:swapped', (ev) => {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'overview-results') {
        return;
    }
    const meta = document.getElementById('overview-head-meta');
    if (!meta) {
        return;
    }
    const title = meta.getAttribute('data-title');
    const h1 = document.getElementById('mission-title');
    if (h1 && title != null) {
        h1.textContent = title;
    }
    const status = (meta.getAttribute('data-status') || '').trim();
    const statusLabel = meta.getAttribute('data-status-label');
    const pill = document.getElementById('mission-status-badge');
    if (pill && status) {
        pill.className = `status-badge status-${status}`;
        if (statusLabel != null) {
            pill.textContent = statusLabel;
        }
    }
    /**
     * Writes a UTC attribute of the head metadata onto a fact element and relocalizes it.
     *
     * @param {string} id the fact element's id
     * @param {string} utcAttr the metadata attribute holding the UTC value
     */
    function patchFact(id, utcAttr) {
        const el = document.getElementById(id);
        if (!el) {
            return;
        }
        const utc = /** @type {HTMLElement} */ (meta).getAttribute(utcAttr);
        el.setAttribute('data-utc', utc != null ? utc : 'null');
        if (typeof missionHooks.localizeDates === 'function') {
            missionHooks.localizeDates(el.parentNode || el);
        }
    }
    patchFact('facts-planned-start', 'data-planned-start-utc');
    patchFact('facts-planned-end', 'data-planned-end-utc');
    patchFact('facts-ts', 'data-meeting-utc');
    const lead = meta.getAttribute('data-leader');
    const leadEl = document.getElementById('facts-leader');
    if (leadEl && lead != null) {
        leadEl.textContent = lead;
    }
    const ouSlot = document.getElementById('mission-head-org-badge-slot');
    if (ouSlot) {
        const ouShorthand = meta.getAttribute('data-owning-org-unit-shorthand');
        const ouName = meta.getAttribute('data-owning-org-unit-name');
        ouSlot.textContent = '';
        if (ouShorthand) {
            const badge = document.createElement('span');
            badge.className = 'squadron-badge';
            badge.title = ouName || '';
            badge.textContent = ouShorthand;
            ouSlot.appendChild(badge);
        }
    }
});

function mid() {
    return window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
}
function stepsVersion() {
    const holder = document.querySelector('[data-steps-version]');
    const v = holder ? Number(holder.getAttribute('data-steps-version')) : null;
    return v === null || Number.isNaN(v) ? null : v;
}
/**
 * Writes to the steps section.
 *
 * @param {KrtWriteOpts} opts the write options
 * @returns {Promise<KrtWriteResult>} the write outcome
 */
function writeStep(opts) {
    return missionHooks.write(
        Object.assign({ sectionKey: 'steps', refresh: ['steps', 'overview'] }, opts),
    );
}

/**
 * Reads the row ids of the list in document order.
 *
 * @returns {(string | null)[]} the row ids
 */
function listOrder() {
    return /** @type {(string | null)[]} */ (
        Array.prototype.map.call(document.querySelectorAll('#mission-step-list .ae-row'), (r) => {
            return r.getAttribute('data-step-id');
        })
    );
}

/**
 * Flips the done state of a step.
 *
 * @param {Element} box the toggle trigger of the step
 */
function toggleStep(box) {
    const sid = box.getAttribute('data-step-id');
    if (!sid || stepsVersion() === null) {
        return;
    }
    const done = box.getAttribute('data-done') !== 'true';
    writeStep({
        method: 'PATCH',
        url: `/missions/${mid()}/steps/${sid}/done/ajax`,
        payload() {
            return { done, stepsVersion: stepsVersion() };
        },
    });
}
/**
 * Appends a step with the default title.
 *
 * @param {Element} _btn the add trigger
 */
function addStep(_btn) {
    if (stepsVersion() === null) {
        return;
    }
    const title = krtI18nText(
        window.MISSION_STEP_I18N && window.MISSION_STEP_I18N.default_title,
        'MISSION_STEP_I18N.default_title',
    );
    writeStep({
        method: 'POST',
        url: `/missions/${mid()}/steps/ajax`,
        payload() {
            return { title, meta: null, stepsVersion: stepsVersion() };
        },
    });
}
/**
 * Saves the edited title and meta of a step row.
 *
 * @param {Element} row the step row
 */
function editStep(row) {
    const sid = row.getAttribute('data-step-id');
    const titleInput = /** @type {HTMLInputElement | null} */ (row.querySelector('.ae-title'));
    if (!sid || stepsVersion() === null || !titleInput) {
        return;
    }
    const title = titleInput.value.trim();
    const metaInput = /** @type {HTMLInputElement | null} */ (row.querySelector('.ae-meta'));
    const meta = metaInput ? metaInput.value : null;
    if (!title) {
        return;
    }
    writeStep({
        method: 'PUT',
        url: `/missions/${mid()}/steps/${sid}/ajax`,
        payload() {
            return { title, meta, stepsVersion: stepsVersion() };
        },
    });
}
/**
 * Deletes a step after confirmation.
 *
 * @param {Element} btn the delete trigger inside the step row
 * @returns {Promise<void>} resolves once the request was sent
 */
async function deleteStep(btn) {
    const row = btn.closest('.ae-row');
    if (!row) {
        return;
    }
    const sid = row.getAttribute('data-step-id');
    if (!sid || stepsVersion() === null) {
        return;
    }
    const msg = krtI18nText(
        window.MISSION_STEP_I18N && window.MISSION_STEP_I18N.delete_confirm,
        'MISSION_STEP_I18N.delete_confirm',
    );
    const ok = await showKrtConfirm?.(
        msg,
        msg,
        krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
            'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
        ),
        krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss'],
            'MISSION_SUBRES_I18N[mission.conflict.action.dismiss]',
        ),
    );
    if (!ok) {
        return;
    }
    writeStep({
        method: 'DELETE',
        url() {
            return `/missions/${mid()}/steps/${sid}/ajax?stepsVersion=${stepsVersion()}`;
        },
    });
}
/**
 * Persists a new step order.
 *
 * @param {(string | null)[]} order the step ids in their new order
 */
function reorder(order) {
    if (stepsVersion() === null) {
        return;
    }
    writeStep({
        method: 'PUT',
        url: `/missions/${mid()}/steps/reorder/ajax`,
        payload() {
            return { stepIds: order, stepsVersion: stepsVersion() };
        },
    });
}
/**
 * Moves a step one place up or down.
 *
 * @param {Element} btn the move trigger inside the step row
 * @param {number} dir -1 for up, 1 for down
 */
function moveStep(btn, dir) {
    const row = btn.closest('.ae-row');
    if (!row) {
        return;
    }
    const order = listOrder();
    const i = order.indexOf(row.getAttribute('data-step-id'));
    const j = i + dir;
    if (i < 0 || j < 0 || j >= order.length) {
        return;
    }
    const tmp = order[i];
    order[i] = order[j];
    order[j] = tmp;
    reorder(order);
}

document.addEventListener('click', (e) => {
    const t = /** @type {Element} */ (e.target).closest('[data-trigger]');
    if (!t) {
        return;
    }
    const trig = t.getAttribute('data-trigger');
    if (trig === 'mission-toggle-step') {
        e.preventDefault();
        toggleStep(t);
    } else if (trig === 'mission-step-add') {
        e.preventDefault();
        addStep(t);
    } else if (trig === 'mission-step-delete') {
        e.preventDefault();
        deleteStep(t);
    } else if (trig === 'mission-step-up') {
        e.preventDefault();
        moveStep(t, -1);
    } else if (trig === 'mission-step-down') {
        e.preventDefault();
        moveStep(t, 1);
    }
});
document.addEventListener('keydown', (e) => {
    const t = /** @type {Element} */ (e.target).closest('[data-trigger="mission-toggle-step"]');
    if (!t) {
        return;
    }
    if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        toggleStep(t);
    }
});
document.addEventListener('change', (e) => {
    const inp = /** @type {Element} */ (e.target).closest(
        '#mission-step-list .ae-title, #mission-step-list .ae-meta',
    );
    if (!inp) {
        return;
    }
    const row = inp.closest('.ae-row');
    if (row) {
        editStep(row);
    }
});

/** @type {Element | null} */
let dragRow = null;
document.addEventListener('dragstart', (e) => {
    const row = /** @type {Element} */ (e.target).closest('#mission-step-list .ae-row');
    if (!row) {
        return;
    }
    dragRow = row;
    if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move';
    }
});
document.addEventListener('dragover', (e) => {
    const list = /** @type {Element} */ (e.target).closest('#mission-step-list');
    if (!list || !dragRow) {
        return;
    }
    e.preventDefault();
    const row = /** @type {Element} */ (e.target).closest('.ae-row');
    list.querySelectorAll('.ae-row').forEach((r) => {
        r.classList.remove('drag-over');
    });
    if (row && row !== dragRow) {
        row.classList.add('drag-over');
    }
});
document.addEventListener('drop', (e) => {
    const list = /** @type {Element} */ (e.target).closest('#mission-step-list');
    if (!list || !dragRow) {
        return;
    }
    e.preventDefault();
    const target = /** @type {Element} */ (e.target).closest('.ae-row');
    list.querySelectorAll('.ae-row').forEach((r) => {
        r.classList.remove('drag-over');
    });
    if (target && target !== dragRow) {
        const order = listOrder();
        const from = order.indexOf(dragRow.getAttribute('data-step-id'));
        order.splice(
            order.indexOf(target.getAttribute('data-step-id')),
            0,
            order.splice(from, 1)[0],
        );
        reorder(order);
    }
    dragRow = null;
});
document.addEventListener('dragend', () => {
    document.querySelectorAll('#mission-step-list .ae-row.drag-over').forEach((r) => {
        r.classList.remove('drag-over');
    });
    dragRow = null;
});

function initMissionDetail() {
    function mid() {
        return window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    }
    function objectivesVersion() {
        const holder = document.querySelector('[data-objectives-version]');
        const v = holder ? Number(holder.getAttribute('data-objectives-version')) : null;
        return v === null || Number.isNaN(v) ? null : v;
    }
    /**
     * Writes to the objectives section.
     *
     * @param {KrtWriteOpts} opts the write options
     * @returns {Promise<KrtWriteResult>} the write outcome
     */
    function writeObjective(opts) {
        return missionHooks.write(
            Object.assign({ sectionKey: 'objectives', refresh: ['objectives', 'overview'] }, opts),
        );
    }
    /**
     * Reads the row ids of the list in document order.
     *
     * @returns {(string | null)[]} the row ids
     */
    function listOrder() {
        return /** @type {(string | null)[]} */ (
            Array.prototype.map.call(
                document.querySelectorAll('#mission-objective-list .ae-row'),
                (r) => {
                    return r.getAttribute('data-objective-id');
                },
            )
        );
    }

    /**
     * Appends an objective with the default title.
     *
     * @param {Element} _btn the add trigger
     */
    function addObjective(_btn) {
        if (objectivesVersion() === null) {
            return;
        }
        const title = krtI18nText(
            window.MISSION_OBJECTIVE_I18N && window.MISSION_OBJECTIVE_I18N.default_title,
            'MISSION_OBJECTIVE_I18N.default_title',
        );
        writeObjective({
            method: 'POST',
            url: `/missions/${mid()}/objectives/ajax`,
            payload() {
                return { title, kind: 'PRIMARY', objectivesVersion: objectivesVersion() };
            },
        });
    }
    /**
     * Saves the edited title and kind of an objective row.
     *
     * @param {Element} row the objective row
     */
    function editObjective(row) {
        const oid = row.getAttribute('data-objective-id');
        const titleInput = /** @type {HTMLInputElement | null} */ (row.querySelector('.ae-title'));
        const kindSelect = /** @type {HTMLSelectElement | null} */ (row.querySelector('.ae-kind'));
        if (!oid || objectivesVersion() === null || !titleInput || !kindSelect) {
            return;
        }
        const title = titleInput.value.trim();
        const kind = kindSelect.value;
        if (!title) {
            return;
        }
        writeObjective({
            method: 'PUT',
            url: `/missions/${mid()}/objectives/${oid}/ajax`,
            payload() {
                return { title, kind, objectivesVersion: objectivesVersion() };
            },
        });
    }
    /**
     * Deletes an objective after confirmation.
     *
     * @param {Element} btn the delete trigger inside the objective row
     * @returns {Promise<void>} resolves once the request was sent
     */
    async function deleteObjective(btn) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const oid = row.getAttribute('data-objective-id');
        if (!oid || objectivesVersion() === null) {
            return;
        }
        const msg = krtI18nText(
            window.MISSION_OBJECTIVE_I18N && window.MISSION_OBJECTIVE_I18N.delete_confirm,
            'MISSION_OBJECTIVE_I18N.delete_confirm',
        );
        const ok = await showKrtConfirm?.(
            msg,
            msg,
            krtI18nText(
                window.MISSION_SUBRES_I18N &&
                    window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
                'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
            ),
            krtI18nText(
                window.MISSION_SUBRES_I18N &&
                    window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss'],
                'MISSION_SUBRES_I18N[mission.conflict.action.dismiss]',
            ),
        );
        if (!ok) {
            return;
        }
        writeObjective({
            method: 'DELETE',
            url() {
                return `/missions/${mid()}/objectives/${
                    oid
                }/ajax?objectivesVersion=${objectivesVersion()}`;
            },
        });
    }
    /**
     * Persists a new step order.
     *
     * @param {(string | null)[]} order the step ids in their new order
     */
    function reorder(order) {
        if (objectivesVersion() === null) {
            return;
        }
        writeObjective({
            method: 'PUT',
            url: `/missions/${mid()}/objectives/reorder/ajax`,
            payload() {
                return { objectiveIds: order, objectivesVersion: objectivesVersion() };
            },
        });
    }
    /**
     * Moves an objective one place up or down.
     *
     * @param {Element} btn the move trigger inside the objective row
     * @param {number} dir -1 for up, 1 for down
     */
    function moveObjective(btn, dir) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const order = listOrder();
        const i = order.indexOf(row.getAttribute('data-objective-id'));
        const j = i + dir;
        if (i < 0 || j < 0 || j >= order.length) {
            return;
        }
        const tmp = order[i];
        order[i] = order[j];
        order[j] = tmp;
        reorder(order);
    }

    document.addEventListener('click', (e) => {
        const t = /** @type {Element} */ (e.target).closest('[data-trigger]');
        if (!t) {
            return;
        }
        const trig = t.getAttribute('data-trigger');
        if (trig === 'mission-objective-add') {
            e.preventDefault();
            addObjective(t);
        } else if (trig === 'mission-objective-delete') {
            e.preventDefault();
            deleteObjective(t);
        } else if (trig === 'mission-objective-up') {
            e.preventDefault();
            moveObjective(t, -1);
        } else if (trig === 'mission-objective-down') {
            e.preventDefault();
            moveObjective(t, 1);
        }
    });
    document.addEventListener('change', (e) => {
        const inp = /** @type {Element} */ (e.target).closest(
            '#mission-objective-list .ae-title, #mission-objective-list .ae-kind',
        );
        if (!inp) {
            return;
        }
        const row = inp.closest('.ae-row');
        if (row) {
            editObjective(row);
        }
    });

    /** @type {Element | null} */
    let dragRow = null;
    document.addEventListener('dragstart', (e) => {
        const row = /** @type {Element} */ (e.target).closest('#mission-objective-list .ae-row');
        if (!row) {
            return;
        }
        dragRow = row;
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
        }
    });
    document.addEventListener('dragover', (e) => {
        const list = /** @type {Element} */ (e.target).closest('#mission-objective-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const row = /** @type {Element} */ (e.target).closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach((r) => {
            r.classList.remove('drag-over');
        });
        if (row && row !== dragRow) {
            row.classList.add('drag-over');
        }
    });
    document.addEventListener('drop', (e) => {
        const list = /** @type {Element} */ (e.target).closest('#mission-objective-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const target = /** @type {Element} */ (e.target).closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach((r) => {
            r.classList.remove('drag-over');
        });
        if (target && target !== dragRow) {
            const order = listOrder();
            const from = order.indexOf(dragRow.getAttribute('data-objective-id'));
            order.splice(
                order.indexOf(target.getAttribute('data-objective-id')),
                0,
                order.splice(from, 1)[0],
            );
            reorder(order);
        }
        dragRow = null;
    });
    document.addEventListener('dragend', () => {
        document.querySelectorAll('#mission-objective-list .ae-row.drag-over').forEach((r) => {
            r.classList.remove('drag-over');
        });
        dragRow = null;
    });
}

initMissionDetail();
