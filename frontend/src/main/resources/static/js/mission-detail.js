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

/* global MSG_ERROR_PAYOUT_UPDATE, MSG_ERROR_MANAGER_ADD, MSG_ERROR_MANAGER_REMOVE, MSG_ERROR_OWNER_CHANGE, MSG_CONFIRM_OWNER_CHANGE, MSG_ERROR_OWNING_ORG_UNIT_CHANGE, MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE, MSG_CONFIRM_MANAGER_REMOVE, MSG_ERROR_USER_REQUIRED, MSG_ERROR_MISSION_ID_MISSING, missionId, openEditFinanceModal, showFrontendErrorToast */

const MISSION_SECTIONS = {
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
};

function crossPublishToParentOperation(keys) {
    const operationId = window.missionOperationId;
    if (
        !operationId ||
        !window.krtLiveSync ||
        typeof window.krtLiveSync.sendChanged !== 'function'
    ) {
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
        window.krtLiveSync.sendChanged('operation:' + operationId, operationSections);
    }
}

const missionSeam = window.krtFetch.sectionWrite({
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
    sections: MISSION_SECTIONS,
    pageUrl() {
        return window.missionId ? '/missions/' + window.missionId : null;
    },
    broadcast(keys) {
        if (window.missionPresence && typeof window.missionPresence.sendChanged === 'function') {
            window.missionPresence.sendChanged(keys);
        }
        crossPublishToParentOperation(keys);
    },
});
window.krtMissionWrite = missionSeam.write;

window.krtRefreshMissionSection = missionSeam.refresh;

window.krtNotifyMissionChanged = missionSeam.notify;

document.addEventListener('krt:swapped', function (ev) {
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
        badge.textContent = checkedIn + '/' + registered;
    }
});

document.addEventListener('krt:swapped', function (ev) {
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

document.addEventListener('krt:swapped', function (ev) {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'overview-results') {
        return;
    }
    const meta = document.getElementById('overview-head-meta');
    if (!meta) {
        return;
    }
    const title = meta.getAttribute('data-title');
    const h1 = document.querySelector('.mission-head-title h1');
    if (h1 && title != null) {
        h1.textContent = title;
    }
    const status = (meta.getAttribute('data-status') || '').trim();
    const statusLabel = meta.getAttribute('data-status-label');
    const pill = document.querySelector('.mission-head-title .status-pill');
    if (pill && status) {
        pill.className = 'status-pill status-' + status;
        if (statusLabel != null) {
            pill.textContent = statusLabel;
        }
    }
    function patchFact(id, utcAttr) {
        const el = document.getElementById(id);
        if (!el) {
            return;
        }
        const utc = meta.getAttribute(utcAttr);
        el.setAttribute('data-utc', utc != null ? utc : 'null');
        if (typeof window.krtLocalizeDates === 'function') {
            window.krtLocalizeDates(el.parentNode || el);
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

window.krtLiveSync.createReceiver({
    sections: MISSION_SECTIONS,
    events: { changed: 'krt:mission-changed', resync: 'krt:mission-resync' },
    refresh(keys) {
        if (window.krtRefreshMissionSection) {
            window.krtRefreshMissionSection(keys, { broadcast: false });
        }
    },
    pill: {
        id: 'mission-livesync-pill',
        label() {
            const dict = window.MISSION_LIVESYNC_I18N || {};
            return window.krtI18nText(
                dict['mission.livesync.updates_available'],
                'MISSION_LIVESYNC_I18N[mission.livesync.updates_available]',
            );
        },
    },
});

(function () {
    function mid() {
        return window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    }
    function stepsVersion() {
        const holder = document.querySelector('[data-steps-version]');
        const v = holder ? Number(holder.getAttribute('data-steps-version')) : null;
        return v === null || Number.isNaN(v) ? null : v;
    }
    function refreshSteps() {
        if (window.krtRefreshMissionSection) {
            return window.krtRefreshMissionSection(['steps', 'overview']);
        }
        return Promise.resolve();
    }
    function writeStep(opts) {
        return window.krtMissionWrite(
            Object.assign({ sectionKey: 'steps', onSuccess: refreshSteps }, opts),
        );
    }
    function listOrder() {
        return Array.prototype.map.call(
            document.querySelectorAll('#mission-step-list .ae-row'),
            function (r) {
                return r.getAttribute('data-step-id');
            },
        );
    }

    function toggleStep(box) {
        const sid = box.getAttribute('data-step-id');
        if (!sid || stepsVersion() === null) {
            return;
        }
        const done = box.getAttribute('data-done') !== 'true';
        writeStep({
            method: 'PATCH',
            url: '/missions/' + mid() + '/steps/' + sid + '/done/ajax',
            payload() {
                return { done, stepsVersion: stepsVersion() };
            },
        });
    }
    function addStep(_btn) {
        if (stepsVersion() === null) {
            return;
        }
        const title = window.krtI18nText(
            window.MISSION_STEP_I18N && window.MISSION_STEP_I18N.default_title,
            'MISSION_STEP_I18N.default_title',
        );
        writeStep({
            method: 'POST',
            url: '/missions/' + mid() + '/steps/ajax',
            payload() {
                return { title, meta: null, stepsVersion: stepsVersion() };
            },
        });
    }
    function editStep(row) {
        const sid = row.getAttribute('data-step-id');
        const titleInput = row.querySelector('.ae-title');
        if (!sid || stepsVersion() === null || !titleInput) {
            return;
        }
        const title = titleInput.value.trim();
        const metaInput = row.querySelector('.ae-meta');
        const meta = metaInput ? metaInput.value : null;
        if (!title) {
            return;
        }
        writeStep({
            method: 'PUT',
            url: '/missions/' + mid() + '/steps/' + sid + '/ajax',
            payload() {
                return { title, meta, stepsVersion: stepsVersion() };
            },
        });
    }
    async function deleteStep(btn) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const sid = row.getAttribute('data-step-id');
        if (!sid || stepsVersion() === null) {
            return;
        }
        const msg = window.krtI18nText(
            window.MISSION_STEP_I18N && window.MISSION_STEP_I18N.delete_confirm,
            'MISSION_STEP_I18N.delete_confirm',
        );
        const ok = await window.showKrtConfirm(
            msg,
            msg,
            window.krtI18nText(
                window.MISSION_SUBRES_I18N &&
                    window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
                'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
            ),
            window.krtI18nText(
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
                return (
                    '/missions/' + mid() + '/steps/' + sid + '/ajax?stepsVersion=' + stepsVersion()
                );
            },
        });
    }
    function reorder(order) {
        if (stepsVersion() === null) {
            return;
        }
        writeStep({
            method: 'PUT',
            url: '/missions/' + mid() + '/steps/reorder/ajax',
            payload() {
                return { stepIds: order, stepsVersion: stepsVersion() };
            },
        });
    }
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

    document.addEventListener('click', function (e) {
        const t = e.target.closest('[data-trigger]');
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
    document.addEventListener('keydown', function (e) {
        const t = e.target.closest('[data-trigger="mission-toggle-step"]');
        if (!t) {
            return;
        }
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            toggleStep(t);
        }
    });
    document.addEventListener('change', function (e) {
        const inp = e.target.closest('#mission-step-list .ae-title, #mission-step-list .ae-meta');
        if (!inp) {
            return;
        }
        const row = inp.closest('.ae-row');
        if (row) {
            editStep(row);
        }
    });

    let dragRow = null;
    document.addEventListener('dragstart', function (e) {
        const row = e.target.closest('#mission-step-list .ae-row');
        if (!row) {
            return;
        }
        dragRow = row;
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
        }
    });
    document.addEventListener('dragover', function (e) {
        const list = e.target.closest('#mission-step-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const row = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        if (row && row !== dragRow) {
            row.classList.add('drag-over');
        }
    });
    document.addEventListener('drop', function (e) {
        const list = e.target.closest('#mission-step-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const target = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
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
    document.addEventListener('dragend', function () {
        document.querySelectorAll('#mission-step-list .ae-row.drag-over').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        dragRow = null;
    });
})();

(function () {
    function mid() {
        return window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    }
    function objectivesVersion() {
        const holder = document.querySelector('[data-objectives-version]');
        const v = holder ? Number(holder.getAttribute('data-objectives-version')) : null;
        return v === null || Number.isNaN(v) ? null : v;
    }
    function refreshObjectives() {
        if (window.krtRefreshMissionSection) {
            return window.krtRefreshMissionSection(['objectives', 'overview']);
        }
        return Promise.resolve();
    }
    function writeObjective(opts) {
        return window.krtMissionWrite(
            Object.assign({ sectionKey: 'objectives', onSuccess: refreshObjectives }, opts),
        );
    }
    function listOrder() {
        return Array.prototype.map.call(
            document.querySelectorAll('#mission-objective-list .ae-row'),
            function (r) {
                return r.getAttribute('data-objective-id');
            },
        );
    }

    function addObjective(_btn) {
        if (objectivesVersion() === null) {
            return;
        }
        const title = window.krtI18nText(
            window.MISSION_OBJECTIVE_I18N && window.MISSION_OBJECTIVE_I18N.default_title,
            'MISSION_OBJECTIVE_I18N.default_title',
        );
        writeObjective({
            method: 'POST',
            url: '/missions/' + mid() + '/objectives/ajax',
            payload() {
                return { title, kind: 'PRIMARY', objectivesVersion: objectivesVersion() };
            },
        });
    }
    function editObjective(row) {
        const oid = row.getAttribute('data-objective-id');
        const titleInput = row.querySelector('.ae-title');
        const kindSelect = row.querySelector('.ae-kind');
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
            url: '/missions/' + mid() + '/objectives/' + oid + '/ajax',
            payload() {
                return { title, kind, objectivesVersion: objectivesVersion() };
            },
        });
    }
    async function deleteObjective(btn) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const oid = row.getAttribute('data-objective-id');
        if (!oid || objectivesVersion() === null) {
            return;
        }
        const msg = window.krtI18nText(
            window.MISSION_OBJECTIVE_I18N && window.MISSION_OBJECTIVE_I18N.delete_confirm,
            'MISSION_OBJECTIVE_I18N.delete_confirm',
        );
        const ok = await window.showKrtConfirm(
            msg,
            msg,
            window.krtI18nText(
                window.MISSION_SUBRES_I18N &&
                    window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
                'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
            ),
            window.krtI18nText(
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
                return (
                    '/missions/' +
                    mid() +
                    '/objectives/' +
                    oid +
                    '/ajax?objectivesVersion=' +
                    objectivesVersion()
                );
            },
        });
    }
    function reorder(order) {
        if (objectivesVersion() === null) {
            return;
        }
        writeObjective({
            method: 'PUT',
            url: '/missions/' + mid() + '/objectives/reorder/ajax',
            payload() {
                return { objectiveIds: order, objectivesVersion: objectivesVersion() };
            },
        });
    }
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

    document.addEventListener('click', function (e) {
        const t = e.target.closest('[data-trigger]');
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
    document.addEventListener('change', function (e) {
        const inp = e.target.closest(
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

    let dragRow = null;
    document.addEventListener('dragstart', function (e) {
        const row = e.target.closest('#mission-objective-list .ae-row');
        if (!row) {
            return;
        }
        dragRow = row;
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
        }
    });
    document.addEventListener('dragover', function (e) {
        const list = e.target.closest('#mission-objective-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const row = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        if (row && row !== dragRow) {
            row.classList.add('drag-over');
        }
    });
    document.addEventListener('drop', function (e) {
        const list = e.target.closest('#mission-objective-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const target = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
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
    document.addEventListener('dragend', function () {
        document
            .querySelectorAll('#mission-objective-list .ae-row.drag-over')
            .forEach(function (r) {
                r.classList.remove('drag-over');
            });
        dragRow = null;
    });
})();

(function () {
    const objectiveList = document.getElementById('mission-create-objective-list');
    const stepList = document.getElementById('mission-create-step-list');
    if (!objectiveList && !stepList) {
        return;
    }
    const form = document.getElementById('mission-form');
    const objectivesJson = document.getElementById('mission-objectives-json');
    const stepsJson = document.getElementById('mission-steps-json');
    const objectiveTemplate = document.getElementById('mission-create-objective-row');
    const stepTemplate = document.getElementById('mission-create-step-row');

    function renumber(list) {
        if (!list) {
            return;
        }
        Array.prototype.forEach.call(list.querySelectorAll('.ae-row'), function (row, i) {
            const num = row.querySelector('.ae-num');
            if (num) {
                num.textContent = String(i + 1);
            }
        });
    }

    function addRow(list, template, values) {
        if (!list || !template) {
            return null;
        }
        const row = template.content.firstElementChild.cloneNode(true);
        if (values) {
            const title = row.querySelector('.ae-title');
            if (title && values.title != null) {
                title.value = values.title;
            }
            const kind = row.querySelector('.ae-kind');
            if (kind && values.kind != null) {
                kind.value = values.kind;
            }
            const meta = row.querySelector('.ae-meta');
            if (meta && values.meta != null) {
                meta.value = values.meta;
            }
        }
        list.appendChild(row);
        renumber(list);
        return row;
    }

    function moveRow(row, dir) {
        const list = row.parentNode;
        if (!list) {
            return;
        }
        if (dir < 0 && row.previousElementSibling) {
            list.insertBefore(row, row.previousElementSibling);
        } else if (dir > 0 && row.nextElementSibling) {
            list.insertBefore(row.nextElementSibling, row);
        }
        renumber(list);
    }

    function focusTitle(row) {
        if (!row) {
            return;
        }
        const title = row.querySelector('.ae-title');
        if (title) {
            title.focus();
        }
    }

    function hydrate(list, template, jsonInput) {
        if (!list || !template || !jsonInput || !jsonInput.value) {
            return;
        }
        let parsed;
        try {
            parsed = JSON.parse(jsonInput.value);
        } catch (_e) {
            return;
        }
        if (Array.isArray(parsed)) {
            parsed.forEach(function (item) {
                addRow(list, template, item);
            });
        }
    }

    function serialize(list, extraKey) {
        const out = [];
        if (!list) {
            return out;
        }
        Array.prototype.forEach.call(list.querySelectorAll('.ae-row'), function (row) {
            const titleInput = row.querySelector('.ae-title');
            const title = titleInput ? titleInput.value.trim() : '';
            if (!title) {
                return;
            }
            const entry = { title };
            if (extraKey === 'kind') {
                const kind = row.querySelector('.ae-kind');
                entry.kind = kind ? kind.value : 'PRIMARY';
            } else if (extraKey === 'meta') {
                const meta = row.querySelector('.ae-meta');
                const metaValue = meta ? meta.value.trim() : '';
                entry.meta = metaValue ? metaValue : null;
            }
            out.push(entry);
        });
        return out;
    }

    document.addEventListener('click', function (e) {
        const t = e.target.closest('[data-trigger]');
        if (!t) {
            return;
        }
        const trig = t.getAttribute('data-trigger');
        if (trig === 'create-objective-add') {
            e.preventDefault();
            focusTitle(addRow(objectiveList, objectiveTemplate, null));
        } else if (trig === 'create-step-add') {
            e.preventDefault();
            focusTitle(addRow(stepList, stepTemplate, null));
        } else if (trig === 'create-objective-delete' || trig === 'create-step-delete') {
            e.preventDefault();
            const row = t.closest('.ae-row');
            if (row) {
                const list = row.parentNode;
                row.remove();
                renumber(list);
            }
        } else if (trig === 'create-objective-up' || trig === 'create-step-up') {
            e.preventDefault();
            const row = t.closest('.ae-row');
            if (row) {
                moveRow(row, -1);
            }
        } else if (trig === 'create-objective-down' || trig === 'create-step-down') {
            e.preventDefault();
            const row = t.closest('.ae-row');
            if (row) {
                moveRow(row, 1);
            }
        }
    });

    if (form) {
        form.addEventListener(
            'submit',
            function () {
                if (objectivesJson) {
                    const objectives = serialize(objectiveList, 'kind');
                    objectivesJson.value = objectives.length ? JSON.stringify(objectives) : '';
                }
                if (stepsJson) {
                    const steps = serialize(stepList, 'meta');
                    stepsJson.value = steps.length ? JSON.stringify(steps) : '';
                }
            },
            true,
        );
    }

    hydrate(objectiveList, objectiveTemplate, objectivesJson);
    hydrate(stepList, stepTemplate, stepsJson);
})();

(function () {
    window.krtModalOpen = function (overlay) {
        window.krtModal.open(overlay);
    };
    window.krtModalClose = function (overlay) {
        window.krtModal.close(overlay);
    };
    window.krtSegSet = function (targetId, value) {
        const seg = document.querySelector('.seg[data-seg-target="' + targetId + '"]');
        const input = document.getElementById(targetId);
        if (!seg || !input) return;
        input.value = value;
        seg.querySelectorAll('button[data-type-value]').forEach(function (b) {
            const on = b.getAttribute('data-type-value') === value;
            b.classList.toggle('on-pos', on && value === 'INCOME');
            b.classList.toggle('on-neg', on && value === 'EXPENSE');
            if (!on) {
                b.classList.remove('on-pos', 'on-neg');
            }
            b.setAttribute('aria-pressed', String(on));
        });
    };
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.seg[data-seg-target]').forEach(function (seg) {
            const targetId = seg.getAttribute('data-seg-target');
            const input = document.getElementById(targetId);
            seg.querySelectorAll('button[data-type-value]').forEach(function (b) {
                b.addEventListener('click', function () {
                    window.krtSegSet(targetId, b.getAttribute('data-type-value'));
                });
            });
            window.krtSegSet(targetId, input && input.value ? input.value : 'INCOME');
        });
    });
})();

document.addEventListener('DOMContentLoaded', function () {
    const pModal = document.getElementById('participant-modal');
    const pBtn = document.getElementById('add-participant-btn');

    if (pBtn && pModal) {
        pBtn.onclick = function () {
            window.krtModalOpen(pModal);
        };
    }

    const eModal = document.getElementById('edit-participant-modal');
    const eForm = document.getElementById('edit-participant-form');
    const eJob = document.getElementById('edit-job');
    const ePlannedJob = document.getElementById('edit-planned-job');
    const eOrgUnits = document.getElementById('edit-org-units');
    const eComment = document.getElementById('edit-comment');

    if (eModal) {
        function bindEditParticipantButtons() {
            document.querySelectorAll('.edit-participant-btn').forEach((btn) => {
                if (btn.dataset.epBound) return;
                btn.dataset.epBound = '1';
                btn.addEventListener('click', function () {
                    eForm.action = window.safeSameOriginUrl(
                        this.getAttribute('data-action'),
                        eForm.action,
                    );
                    eForm.setAttribute(
                        'data-participant-id',
                        this.getAttribute('data-participant-id') || '',
                    );
                    eJob.value = this.getAttribute('data-job');
                    if (ePlannedJob) ePlannedJob.value = this.getAttribute('data-planned-job');
                    if (eOrgUnits) {
                        const selected = (this.getAttribute('data-org-units') || '')
                            .split(',')
                            .filter(Boolean);
                        Array.from(eOrgUnits.options).forEach((opt) => {
                            opt.selected = selected.includes(opt.value);
                        });
                    }
                    const isExternal = this.getAttribute('data-external') === 'true';
                    const eOrgUnitsGroup = document.getElementById('edit-org-units-group');
                    const eOrgUnitsReadonlyGroup = document.getElementById(
                        'edit-org-units-readonly-group',
                    );
                    const eOrgUnitsReadonly = document.getElementById('edit-org-units-readonly');
                    if (eOrgUnitsGroup && eOrgUnitsReadonlyGroup) {
                        eOrgUnitsGroup.style.display = isExternal ? '' : 'none';
                        eOrgUnitsReadonlyGroup.classList.toggle(
                            'krtm-display-none-5790',
                            isExternal,
                        );
                        if (!isExternal && eOrgUnitsReadonly) {
                            eOrgUnitsReadonly.textContent = '';
                            const unitNames = (this.getAttribute('data-org-unit-names') || '')
                                .split(',')
                                .map(function (s) {
                                    return s.trim();
                                })
                                .filter(Boolean);
                            if (unitNames.length === 0) {
                                const none = document.createElement('span');
                                none.className = 'squadron-badge squadron-badge-muted';
                                none.textContent = '—';
                                eOrgUnitsReadonly.appendChild(none);
                            } else {
                                unitNames.forEach(function (unitName) {
                                    const badge = document.createElement('span');
                                    badge.className = 'squadron-badge';
                                    badge.textContent = unitName;
                                    eOrgUnitsReadonly.appendChild(badge);
                                });
                            }
                        }
                    }
                    eComment.value = this.getAttribute('data-comment');

                    const ePayout = document.getElementById('edit-payout-preference');
                    if (ePayout)
                        ePayout.value = this.getAttribute('data-payout-preference') || 'PAYOUT';

                    const eVersion = document.getElementById('edit-participant-version');
                    const eStartTimeHidden = document.getElementById(
                        'edit-participant-start-time-hidden',
                    );
                    const eEndTimeHidden = document.getElementById(
                        'edit-participant-end-time-hidden',
                    );
                    const eStartTimeUi = document.getElementById('edit-participant-start-time-ui');
                    const eEndTimeUi = document.getElementById('edit-participant-end-time-ui');

                    if (eVersion) eVersion.value = this.getAttribute('data-version') || '';

                    const eTitle = document.getElementById('edit-participant-title');
                    if (eTitle && this.getAttribute('data-name')) {
                        eTitle.textContent = this.getAttribute('data-name');
                    }

                    const eUnregister = document.getElementById('edit-participant-unregister-btn');
                    if (eUnregister && window.missionId) {
                        const pid = this.getAttribute('data-participant-id') || '';
                        eUnregister.setAttribute(
                            'data-action',
                            '/missions/' + window.missionId + '/participants/' + pid + '/delete',
                        );
                        eUnregister.setAttribute('data-participant-id', pid);
                        eUnregister.setAttribute('data-name', this.getAttribute('data-name') || '');
                    }

                    const startUtc = this.getAttribute('data-start-time') || '';
                    const endUtc = this.getAttribute('data-end-time') || '';
                    if (eStartTimeHidden) eStartTimeHidden.value = startUtc;
                    if (eEndTimeHidden) eEndTimeHidden.value = endUtc;

                    if (eStartTimeUi) {
                        eStartTimeUi.value = startUtc;
                        if (typeof window.krtSyncDatetimeSplitGroup === 'function') {
                            window.krtSyncDatetimeSplitGroup(
                                eStartTimeUi.closest('.datetime-split-group'),
                            );
                        }
                    }
                    if (eEndTimeUi) {
                        eEndTimeUi.value = endUtc;
                        if (typeof window.krtSyncDatetimeSplitGroup === 'function') {
                            window.krtSyncDatetimeSplitGroup(
                                eEndTimeUi.closest('.datetime-split-group'),
                            );
                        }
                    }

                    window.krtModalOpen(eModal);
                });
            });
        }
        bindEditParticipantButtons();
        document.addEventListener('krt:swapped', bindEditParticipantButtons);

        if (eForm) {
            eForm.addEventListener('submit', function () {
                const eStartTimeUi = document.getElementById('edit-participant-start-time-ui');
                const eEndTimeUi = document.getElementById('edit-participant-end-time-ui');
                const eStartTimeHidden = document.getElementById(
                    'edit-participant-start-time-hidden',
                );
                const eEndTimeHidden = document.getElementById('edit-participant-end-time-hidden');

                function toUtcISOString(localDateStr) {
                    return localDateStr || '';
                }

                if (eStartTimeUi !== null && eStartTimeHidden) {
                    eStartTimeHidden.value = toUtcISOString(eStartTimeUi.value);
                }
                if (eEndTimeUi !== null && eEndTimeHidden) {
                    eEndTimeHidden.value = toUtcISOString(eEndTimeUi.value);
                }
            });
        }
    }

    document.querySelectorAll('.freq-input').forEach(function (input) {
        input.addEventListener('input', function () {
            if (this.value.indexOf(',') !== -1) {
                this.value = this.value.replace(/,/g, '.');
            }
        });
    });

    const frequencyModal = document.getElementById('frequency-modal');
    const frequencyForm = document.getElementById('frequency-form');

    if (frequencyModal) {
        document.addEventListener('click', function (event) {
            const btn = event.target.closest && event.target.closest('.set-freq-btn');
            if (!btn) {
                return;
            }
            frequencyForm.setAttribute('data-action', btn.getAttribute('data-action'));
            const typeId = btn.getAttribute('data-type-id');
            document.getElementById('freq-type-id').value = typeId;
            const current = btn.getAttribute('data-current-value') || '';
            document.getElementById('freq-value').value = current;
            window.krtModalOpen(frequencyModal);
        });

        if (frequencyForm) {
            frequencyForm.addEventListener('submit', async function (event) {
                event.preventDefault();
                const url = frequencyForm.getAttribute('data-action');
                if (!url || !window.krtMissionWrite) {
                    frequencyForm.submit();
                    return;
                }
                const typeId = document.getElementById('freq-type-id').value;
                const rawValue = document.getElementById('freq-value').value;
                const parsed = Number.parseFloat(rawValue);
                if (!typeId || Number.isNaN(parsed)) {
                    return;
                }
                const result = await window.krtMissionWrite({
                    method: 'PUT',
                    url,
                    payload: { frequencyTypeId: typeId, value: parsed },
                    sectionKey: 'frequency',
                });
                if (result.ok) {
                    if (Array.isArray(result.body)) {
                        const match = result.body.find(
                            (f) => f && f.frequencyType && f.frequencyType.id === typeId,
                        );
                        if (match) {
                            const formatted = Number(match.value).toFixed(2);
                            document
                                .querySelectorAll(
                                    '.freq-value-display[data-freq-type-id="' + typeId + '"]',
                                )
                                .forEach(function (display) {
                                    display.textContent = formatted;
                                    display.classList.remove('krtm-hidden');
                                });
                            document
                                .querySelectorAll('.set-freq-btn[data-type-id="' + typeId + '"]')
                                .forEach(function (editBtn) {
                                    editBtn.setAttribute('data-current-value', String(match.value));
                                });
                        }
                    }
                    window.krtModalClose(frequencyModal);
                    if (window.krtRefreshMissionSection) {
                        window.krtRefreshMissionSection('overview');
                        if (window.krtNotifyMissionChanged) {
                            window.krtNotifyMissionChanged('organisation');
                        }
                    } else if (window.krtNotifyMissionChanged) {
                        window.krtNotifyMissionChanged(['overview', 'organisation']);
                    }
                }
            });
        }
    }

    (function () {
        const customFreqModal = document.getElementById('custom-frequency-modal');
        const customFreqForm = document.getElementById('custom-frequency-form');
        if (!customFreqModal || !customFreqForm) {
            return;
        }
        const idInput = document.getElementById('custom-freq-id');
        const versionInput = document.getElementById('custom-freq-version');
        const nameInput = document.getElementById('custom-freq-name');
        const valueInput = document.getElementById('custom-freq-value');
        const titleEl = document.getElementById('custom-frequency-modal-title');
        const dict = window.MISSION_CUSTOM_FREQ_I18N || {};
        const mid = function () {
            return window.missionId;
        };

        function openAdd() {
            idInput.value = '';
            versionInput.value = '';
            nameInput.value = '';
            valueInput.value = '';
            if (titleEl && dict.add) {
                titleEl.textContent = dict.add;
            }
            window.krtModalOpen(customFreqModal);
            nameInput.focus();
        }
        function openEdit(row) {
            idInput.value = row.getAttribute('data-custom-freq-id') || '';
            versionInput.value = row.getAttribute('data-version') || '';
            nameInput.value = row.getAttribute('data-name') || '';
            valueInput.value = row.getAttribute('data-value') || '';
            if (titleEl && dict.edit) {
                titleEl.textContent = dict.edit;
            }
            window.krtModalOpen(customFreqModal);
            nameInput.focus();
        }
        async function del(row) {
            const fid = row.getAttribute('data-custom-freq-id');
            if (!fid) {
                return;
            }
            const msg = window.krtI18nText(
                dict.delete_confirm,
                'MISSION_CUSTOM_FREQ_I18N.delete_confirm',
            );
            const ok = await window.showKrtConfirm(
                msg,
                msg,
                window.krtI18nText(
                    window.MISSION_SUBRES_I18N &&
                        window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
                    'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
                ),
                window.krtI18nText(
                    window.MISSION_SUBRES_I18N &&
                        window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss'],
                    'MISSION_SUBRES_I18N[mission.conflict.action.dismiss]',
                ),
            );
            if (!ok) {
                return;
            }
            const result = await window.krtMissionWrite({
                method: 'DELETE',
                url: '/missions/' + mid() + '/frequencies/' + fid + '/ajax',
                sectionKey: 'frequency',
            });
            if (result.ok && window.krtRefreshMissionSection) {
                window.krtRefreshMissionSection(['frequencies', 'overview']);
            }
        }

        document.addEventListener('click', function (e) {
            const t = e.target.closest('[data-trigger]');
            if (!t) {
                return;
            }
            const trig = t.getAttribute('data-trigger');
            if (trig === 'custom-freq-add') {
                e.preventDefault();
                openAdd();
            } else if (trig === 'custom-freq-edit') {
                e.preventDefault();
                const r = t.closest('.custom-freq-row');
                if (r) {
                    openEdit(r);
                }
            } else if (trig === 'custom-freq-delete') {
                e.preventDefault();
                const r = t.closest('.custom-freq-row');
                if (r) {
                    del(r);
                }
            }
        });

        customFreqForm.addEventListener('submit', async function (event) {
            event.preventDefault();
            const name = (nameInput.value || '').trim();
            const parsed = Number.parseFloat(valueInput.value);
            if (!name || Number.isNaN(parsed)) {
                return;
            }
            const fid = idInput.value;
            const isEdit = !!fid;
            const payload = isEdit
                ? { name, value: parsed, version: Number(versionInput.value) }
                : { name, value: parsed };
            const result = await window.krtMissionWrite({
                method: isEdit ? 'PUT' : 'POST',
                url: isEdit
                    ? '/missions/' + mid() + '/frequencies/custom/' + fid + '/ajax'
                    : '/missions/' + mid() + '/frequencies/custom/ajax',
                payload,
                sectionKey: 'frequency',
            });
            if (result.ok) {
                window.krtModalClose(customFreqModal);
                if (isEdit && Array.isArray(result.body) && window.krtFetch) {
                    const updated = result.body.find(function (e) {
                        return e && e.id === fid;
                    });
                    if (updated && updated.version != null) {
                        document
                            .querySelectorAll('.custom-freq-row[data-custom-freq-id="' + fid + '"]')
                            .forEach(function (row) {
                                window.krtFetch.syncVersion(row, updated.version);
                            });
                        versionInput.value = updated.version;
                    }
                }
                if (window.krtRefreshMissionSection) {
                    window.krtRefreshMissionSection(['frequencies', 'overview']);
                }
            }
        });
    })();

    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = document.getElementById('delete-confirm-form');

    if (deleteModal) {
        function deleteMessageFor(btn) {
            const section = btn.getAttribute('data-sub-section') || 'default';
            const dict = window.MISSION_DELETE_I18N || {};
            let msg = dict[section] || dict['default'] || '';
            msg = msg.replace('{name}', btn.getAttribute('data-name') || '');
            msg = msg.replace('{count}', btn.getAttribute('data-crew-count') || '0');
            return msg;
        }
        function bindDeleteOpeners() {
            document
                .querySelectorAll(
                    '.delete-participant-btn, .delete-unit-btn, .delete-crew-btn, .delete-mission-btn, .delete-finance-btn',
                )
                .forEach((btn) => {
                    if (btn.dataset.delBound) return;
                    btn.dataset.delBound = '1';
                    btn.addEventListener('click', function () {
                        deleteForm.action = window.safeSameOriginUrl(
                            this.getAttribute('data-action'),
                            deleteForm.action,
                        );
                        const subSection = this.getAttribute('data-sub-section') || '';
                        let subId;
                        if (subSection === 'crew') {
                            subId = this.getAttribute('data-crew-id') || '';
                            deleteForm.setAttribute(
                                'data-sub-unit-id',
                                this.getAttribute('data-unit-id') || '',
                            );
                        } else {
                            subId =
                                this.getAttribute('data-unit-id') ||
                                this.getAttribute('data-participant-id') ||
                                this.getAttribute('data-crew-id') ||
                                '';
                            deleteForm.removeAttribute('data-sub-unit-id');
                        }
                        deleteForm.setAttribute('data-sub-section', subSection);
                        deleteForm.setAttribute('data-sub-id', subId);
                        const msgEl = document.getElementById('delete-confirm-message');
                        if (msgEl) msgEl.textContent = deleteMessageFor(this);
                        window.krtModalOpen(deleteModal);
                    });
                });
        }
        bindDeleteOpeners();
        document.addEventListener('krt:swapped', bindDeleteOpeners);
    }

    const addUnitModal = document.getElementById('add-unit-modal');
    function bindAddUnitButton() {
        const addUnitBtn = document.getElementById('add-unit-btn');
        if (addUnitBtn && addUnitModal && !addUnitBtn.dataset.auBound) {
            addUnitBtn.dataset.auBound = '1';
            addUnitBtn.addEventListener('click', function () {
                window.krtModalOpen(addUnitModal);
            });
        }
    }
    bindAddUnitButton();
    document.addEventListener('krt:swapped', bindAddUnitButton);

    const editUnitModal = document.getElementById('edit-unit-modal');
    const editUnitForm = document.getElementById('edit-unit-form');
    const editUnitName = document.getElementById('edit-unit-name');
    const editUnitFrequency = document.getElementById('edit-unit-frequency');
    const editUnitHvu = document.getElementById('edit-unit-hvu');

    if (editUnitModal) {
        function bindEditUnitButtons() {
            document.querySelectorAll('.edit-unit-btn').forEach((btn) => {
                if (btn.dataset.euBound) return;
                btn.dataset.euBound = '1';
                btn.addEventListener('click', function () {
                    editUnitForm.action = window.safeSameOriginUrl(
                        this.getAttribute('data-action'),
                        editUnitForm.action,
                    );
                    editUnitForm.setAttribute(
                        'data-unit-id',
                        this.getAttribute('data-unit-id') || '',
                    );
                    editUnitForm.setAttribute(
                        'data-version',
                        this.getAttribute('data-version') || '',
                    );
                    editUnitName.value = this.getAttribute('data-name') || '';
                    const shiptypeVal = this.getAttribute('data-shiptype') || '';
                    const shipVal = this.getAttribute('data-ship') || '';
                    const editTypeSelect = document.getElementById('edit-unit-shiptype');
                    const editShipSelect = document.getElementById('edit-unit-ship');
                    if (editTypeSelect && editShipSelect) {
                        editTypeSelect.value = shiptypeVal;
                        editTypeSelect.dispatchEvent(new Event('change'));
                        if (shipVal) {
                            editShipSelect.value = shipVal;
                        }
                    }
                    const freqVal = this.getAttribute('data-frequency');
                    editUnitFrequency.value = freqVal != null ? freqVal : '';
                    const hvuVal = this.getAttribute('data-hvu') === 'true';
                    editUnitHvu.checked = hvuVal;
                    const editUnitResponsible = document.getElementById('edit-unit-responsible');
                    if (editUnitResponsible) {
                        const responsibleVal = this.getAttribute('data-responsible') || '';
                        if (editUnitResponsible.krtCombobox) {
                            editUnitResponsible.krtCombobox.setValue(responsibleVal);
                        } else {
                            editUnitResponsible.value = responsibleVal;
                        }
                    }
                    const editUnitNote = document.getElementById('edit-unit-note');
                    if (editUnitNote) {
                        const noteVal = this.getAttribute('data-note');
                        editUnitNote.value = noteVal && noteVal !== 'null' ? noteVal : '';
                    }
                    window.krtModalOpen(editUnitModal);
                });
            });
        }
        bindEditUnitButtons();
        document.addEventListener('krt:swapped', bindEditUnitButtons);
    }

    (function wireUnitAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }
        const addForm = document.getElementById('add-unit-form');
        if (addForm) {
            addForm.addEventListener('submit', async function (ev) {
                ev.preventDefault();
                const fd = new FormData(addForm);
                const payload = {
                    name: fd.get('name') || '',
                    shipTypeId: fd.get('shipTypeId') || null,
                    shipId: fd.get('shipId') || null,
                    highValueUnit: fd.get('highValueUnit') === 'true',
                    frequency: fd.get('frequency') ? parseFloat(fd.get('frequency')) : null,
                    responsibleUserId: fd.get('responsibleUserId') || null,
                    note: fd.get('note') || null,
                };
                const res = await window.krtMissionWrite({
                    method: 'POST',
                    url: '/missions/' + window.missionId + '/units/ajax',
                    payload,
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }

        const editForm = document.getElementById('edit-unit-form');
        if (editForm) {
            editForm.addEventListener('submit', async function (ev) {
                ev.preventDefault();
                const unitId = editForm.getAttribute('data-unit-id');
                if (!unitId) {
                    editForm.submit();
                    return;
                }
                const fd = new FormData(editForm);
                const versionAttr = editForm.getAttribute('data-version');
                const payload = {
                    name: fd.get('name') || '',
                    shipTypeId: fd.get('shipTypeId') || null,
                    shipId: fd.get('shipId') || null,
                    highValueUnit: fd.get('highValueUnit') === 'true',
                    frequency: fd.get('frequency') ? parseFloat(fd.get('frequency')) : null,
                    responsibleUserId: fd.get('responsibleUserId') || null,
                    note: fd.get('note') || null,
                    version: versionAttr ? Number(versionAttr) : null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: '/missions/' + window.missionId + '/units/' + unitId + '/ajax',
                    payload,
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }

        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async function (ev) {
                const sub = dForm.getAttribute('data-sub-section');
                const subId = dForm.getAttribute('data-sub-id');
                if (sub !== 'unit' || !subId) {
                    return;
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: '/missions/' + window.missionId + '/units/' + subId + '/ajax',
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }
    })();
});

document.addEventListener('DOMContentLoaded', function () {
    (function wireParticipantAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }

        const addForm = document.getElementById('add-participant-form');
        if (addForm) {
            addForm.addEventListener('submit', async function (ev) {
                ev.preventDefault();
                const fd = new FormData(addForm);
                const addOrgUnitIds =
                    addForm.querySelector('#participant-user-id') &&
                    addForm.querySelector('#participant-user-id').value
                        ? []
                        : fd.getAll('orgUnitIds').filter(Boolean);
                const payload = {
                    userId: fd.get('userId') || null,
                    guestName: fd.get('guestName') || null,
                    desiredJobTypeId: fd.get('desiredJobTypeId') || null,
                    orgUnitIds: addOrgUnitIds,
                    comment: fd.get('comment') || null,
                    payoutPreference: fd.get('payoutPreference') || null,
                };
                const res = await window.krtMissionWrite({
                    method: 'POST',
                    url: '/missions/' + window.missionId + '/participants/ajax',
                    payload,
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                    addForm.reset();
                    const addUserId = addForm.querySelector('#participant-user-id');
                    if (addUserId) {
                        addUserId.value = '';
                    }
                    window.krtRefreshMissionSection(['crew', 'finance']);
                }
            });
        }

        const editForm = document.getElementById('edit-participant-form');
        if (editForm) {
            editForm.addEventListener('submit', async function (ev) {
                const pId = editForm.getAttribute('data-participant-id');
                if (!pId) {
                    return;
                }
                ev.preventDefault();
                const fd = new FormData(editForm);
                const versionRaw = fd.get('version');
                const payload = {
                    desiredMissionJobTypeId: fd.get('desiredJobTypeId') || null,
                    plannedMissionJobTypeId: fd.get('plannedMissionJobTypeId') || null,
                    orgUnitIds: fd.getAll('orgUnitIds').filter(Boolean),
                    payoutPreference: fd.get('payoutPreference') || null,
                    comment: fd.get('comment') || null,
                    startTime: fd.get('startTime') || null,
                    endTime: fd.get('endTime') || null,
                    version: versionRaw ? parseInt(versionRaw, 10) : null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: '/missions/' + window.missionId + '/participants/' + pId + '/ajax',
                    payload,
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                    const dto = res.body;
                    if (
                        dto &&
                        dto.version !== undefined &&
                        dto.version !== null &&
                        window.krtFetch
                    ) {
                        document
                            .querySelectorAll('[data-participant-id="' + pId + '"]')
                            .forEach(function (c) {
                                window.krtFetch.syncVersion(c, dto.version);
                            });
                    }
                    window.krtRefreshMissionSection(['crew', 'finance', 'overview']);
                }
            });
        }

        document.addEventListener('submit', async function (ev) {
            const f = ev.target;
            if (
                !f ||
                typeof f.matches !== 'function' ||
                !f.matches('form.participant-action-form')
            ) {
                return;
            }
            const pId = f.getAttribute('data-participant-id');
            const action = f.getAttribute('data-participant-action');
            if (!pId || (action !== 'check-in' && action !== 'check-out')) {
                return;
            }
            ev.preventDefault();
            const res = await window.krtMissionWrite({
                method: 'POST',
                url:
                    '/missions/' +
                    window.missionId +
                    '/participants/' +
                    pId +
                    '/' +
                    action +
                    '/ajax',
                sectionKey: 'participant',
            });
            if (res.ok) {
                const dto = res.body;
                if (dto && dto.version !== undefined && dto.version !== null && window.krtFetch) {
                    document
                        .querySelectorAll('[data-participant-id="' + pId + '"]')
                        .forEach(function (c) {
                            window.krtFetch.syncVersion(c, dto.version);
                        });
                }
                window.krtRefreshMissionSection(['crew', 'finance']);
            }
        });

        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async function (ev) {
                const sub = dForm.getAttribute('data-sub-section');
                const subId = dForm.getAttribute('data-sub-id');
                if (sub !== 'participant' || !subId) {
                    return;
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: '/missions/' + window.missionId + '/participants/' + subId + '/ajax',
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection(['crew', 'finance', 'overview']);
                }
            });
        }
    })();

    const editCrewModal = document.getElementById('edit-crew-modal');
    const editCrewForm = document.getElementById('edit-crew-form');
    const editCrewJobs = document.getElementById('edit-crew-jobs');

    if (editCrewModal && editCrewForm && editCrewJobs) {
        window.krtOpenEditCrewModal = function (source) {
            editCrewForm.action = window.safeSameOriginUrl(
                source.getAttribute('data-action') || '/missions',
                editCrewForm.action,
            );
            editCrewForm.setAttribute(
                'data-ajax-action',
                source.getAttribute('data-action-ajax') || '',
            );
            editCrewForm.setAttribute('data-unit-id', source.getAttribute('data-unit-id') || '');
            editCrewForm.setAttribute('data-crew-id', source.getAttribute('data-crew-id') || '');
            editCrewForm.setAttribute('data-version', source.getAttribute('data-version') || '');
            const jobIds = (source.getAttribute('data-jobs') || '').split(',');

            for (let i = 0; i < editCrewJobs.options.length; i++) {
                editCrewJobs.options[i].selected = jobIds.includes(editCrewJobs.options[i].value);
            }

            window.krtModalOpen(editCrewModal);
        };
    }

    window.addEventListener('click', function (event) {
        if (event.target.classList && event.target.classList.contains('krt-modal-overlay')) {
            window.krtModalClose(event.target);
        }
    });

    (function wireCrewAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }

        function collectJobTypeIds(form) {
            const select = form.querySelector('select[name="jobTypeIds"]');
            if (!select) return [];
            return Array.from(select.selectedOptions || [])
                .map((o) => o.value)
                .filter((v) => v);
        }

        const editForm = document.getElementById('edit-crew-form');
        if (editForm) {
            editForm.addEventListener('submit', async function (ev) {
                const ajaxUrl = editForm.getAttribute('data-ajax-action');
                const unitId = editForm.getAttribute('data-unit-id');
                const crewId = editForm.getAttribute('data-crew-id');
                if (!ajaxUrl || !unitId || !crewId) {
                    return;
                }
                ev.preventDefault();
                const versionAttr = editForm.getAttribute('data-version');
                const payload = {
                    jobTypeIds: collectJobTypeIds(editForm),
                    version: versionAttr ? Number(versionAttr) : null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: ajaxUrl,
                    payload,
                    sectionKey: 'crew',
                });
                if (res.ok) {
                    window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection('crew');
                }
            });
        }

        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async function (ev) {
                const sub = dForm.getAttribute('data-sub-section');
                const crewId = dForm.getAttribute('data-sub-id');
                const unitId = dForm.getAttribute('data-sub-unit-id');
                if (sub !== 'crew' || !crewId || !unitId) {
                    return;
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url:
                        '/missions/' +
                        window.missionId +
                        '/units/' +
                        unitId +
                        '/crew/' +
                        crewId +
                        '/ajax',
                    sectionKey: 'crew',
                });
                if (res.ok) {
                    window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection('crew');
                }
            });
        }
    })();

    const USER_SEARCH_RENDER_CAP = 50;

    /**
     * Appends the non-selectable "keep typing to narrow the list" row when the relay returned more
     * users than the list renders.
     *
     * @param {HTMLElement} container the autocomplete list the rows went into
     * @param {number} received how many users the relay returned
     */
    function appendUserSearchOverflowHint(container, received) {
        if (received <= USER_SEARCH_RENDER_CAP) {
            return;
        }
        const hint = document.createElement('div');
        hint.className = 'autocomplete-notice';
        hint.setAttribute('aria-disabled', 'true');
        hint.textContent = window.krtI18nText(
            (window.krtComboboxI18n || {}).hint,
            'krtComboboxI18n.hint',
        );
        container.appendChild(hint);
    }

    const searchInput = document.getElementById('participant-search-input');
    const userIdInput = document.getElementById('participant-user-id');
    const resultsDiv = document.getElementById('participant-search-results');

    if (searchInput && resultsDiv && userIdInput) {
        let debounceTimer;

        searchInput.addEventListener('input', function () {
            const val = this.value;

            userIdInput.value = '';

            const orgUnitsGroup = document.getElementById('participant-org-units-group');
            if (orgUnitsGroup) {
                orgUnitsGroup.style.display = '';
            }

            clearTimeout(debounceTimer);

            if (!val) {
                closeAllLists();
                return;
            }

            debounceTimer = setTimeout(() => {
                fetch('/users/search?query=' + encodeURIComponent(val))
                    .then((response) => {
                        if (!response.ok) throw new Error('Network response was not ok');
                        return response.json();
                    })
                    .then((users) => {
                        closeAllLists();
                        if (!users || users.length === 0) return;

                        users.slice(0, USER_SEARCH_RENDER_CAP).forEach((user) => {
                            const div = document.createElement('div');
                            const regex = new RegExp(
                                '(' + val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')',
                                'gi',
                            );
                            const displayName = user.effectiveName || '';
                            displayName.split(regex).forEach((part, i) => {
                                if (i % 2 === 1) {
                                    const strong = document.createElement('strong');
                                    strong.textContent = part;
                                    div.appendChild(strong);
                                } else if (part) {
                                    div.appendChild(document.createTextNode(part));
                                }
                            });

                            div.addEventListener('click', function () {
                                searchInput.value = displayName;
                                userIdInput.value = user.id;
                                const orgUnitsGroup = document.getElementById(
                                    'participant-org-units-group',
                                );
                                if (orgUnitsGroup) {
                                    orgUnitsGroup.style.display = 'none';
                                }
                                closeAllLists();
                            });
                            resultsDiv.appendChild(div);
                        });
                        appendUserSearchOverflowHint(resultsDiv, users.length);
                    })
                    .catch((err) => console.error('Error fetching users:', err));
            }, 300);
        });

        function closeAllLists() {
            while (resultsDiv.firstChild) {
                resultsDiv.removeChild(resultsDiv.firstChild);
            }
        }

        document.addEventListener('click', function (e) {
            if (e.target !== searchInput) {
                closeAllLists();
            }
        });
    }

    let partyLeadDebounce;

    function partyLeadCloseLists() {
        const results = document.getElementById('party-lead-search-results');
        if (!results) {
            return;
        }
        while (results.firstChild) {
            results.removeChild(results.firstChild);
        }
    }

    document.addEventListener('input', function (e) {
        if (!e.target || e.target.id !== 'party-lead-search-input') {
            return;
        }
        const input = e.target;
        const userIdEl = document.getElementById('party-lead-user-id');
        const results = document.getElementById('party-lead-search-results');
        if (!userIdEl || !results) {
            return;
        }
        const val = input.value;
        userIdEl.value = '';
        clearTimeout(partyLeadDebounce);
        if (!val) {
            partyLeadCloseLists();
            return;
        }
        partyLeadDebounce = setTimeout(() => {
            fetch('/users/search?query=' + encodeURIComponent(val))
                .then((response) => {
                    if (!response.ok) throw new Error('Network response was not ok');
                    return response.json();
                })
                .then((users) => {
                    partyLeadCloseLists();
                    if (!users || users.length === 0) return;
                    users.slice(0, USER_SEARCH_RENDER_CAP).forEach((user) => {
                        const div = document.createElement('div');
                        const regex = new RegExp(
                            '(' + val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')',
                            'gi',
                        );
                        const displayName = user.effectiveName || '';
                        displayName.split(regex).forEach((part, i) => {
                            if (i % 2 === 1) {
                                const strong = document.createElement('strong');
                                strong.textContent = part;
                                div.appendChild(strong);
                            } else if (part) {
                                div.appendChild(document.createTextNode(part));
                            }
                        });
                        div.addEventListener('click', function () {
                            input.value = displayName;
                            userIdEl.value = user.id;
                            partyLeadCloseLists();
                        });
                        results.appendChild(div);
                    });
                    appendUserSearchOverflowHint(results, users.length);
                })
                .catch((err) => console.error('Error fetching users:', err));
        }, 300);
    });

    document.addEventListener('click', function (e) {
        if (!e.target || e.target.id !== 'party-lead-search-input') {
            partyLeadCloseLists();
        }
    });

    document.addEventListener('click', function (e) {
        const clearBtn = e.target.closest && e.target.closest('#party-lead-clear-btn');
        if (!clearBtn) {
            return;
        }
        const input = document.getElementById('party-lead-search-input');
        const userIdEl = document.getElementById('party-lead-user-id');
        const form = document.getElementById('party-lead-form');
        if (!input || !userIdEl || !form) {
            return;
        }
        input.value = '';
        userIdEl.value = '';
        form.requestSubmit();
    });

    document.addEventListener('submit', async function (ev) {
        if (!ev.target || ev.target.id !== 'party-lead-form') {
            return;
        }
        ev.preventDefault();
        const input = document.getElementById('party-lead-search-input');
        const userIdEl = document.getElementById('party-lead-user-id');
        const versionInput = document.getElementById('party-lead-version');
        if (!input || !userIdEl) {
            return;
        }
        const submittedUserId = userIdEl.value || null;
        const submittedGuestName = input.value || null;
        const res = await window.krtMissionWrite({
            method: 'PUT',
            url: '/missions/' + window.missionId + '/party-lead/ajax',
            payload() {
                return {
                    userId: submittedUserId,
                    guestName: submittedGuestName,
                    version:
                        versionInput && versionInput.value !== ''
                            ? parseInt(versionInput.value, 10)
                            : 0,
                };
            },
            sectionKey: 'party_lead',
        });
        if (!res.ok || !res.body) {
            return;
        }
        const dto = res.body;
        const display = document.getElementById('party-lead-display');
        const overview = document.getElementById('overview-party-lead');
        const noneLabel = (display && display.getAttribute('data-none-label')) || '';
        const resolvedName = dto.partyLeadUser
            ? dto.partyLeadUser.effectiveName || ''
            : dto.partyLeadGuestName || noneLabel;
        if (display) display.textContent = resolvedName;
        if (overview) overview.textContent = resolvedName;
        if (versionInput && dto.partyLeadVersion != null) {
            versionInput.value = dto.partyLeadVersion;
        }
        userIdEl.value = dto.partyLeadUser ? dto.partyLeadUser.id || '' : '';
        input.value = dto.partyLeadUser
            ? dto.partyLeadUser.effectiveName || ''
            : dto.partyLeadGuestName || '';
        if (window.krtNotifyMissionChanged) {
            window.krtNotifyMissionChanged(['overview', 'organisation']);
        }
    });
});

function setNowToInput(inputId) {
    const input = document.getElementById(inputId);
    if (!input) return;

    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');

    const group = input.closest('.datetime-split-group');
    if (group) {
        const dateInput = group.querySelector('.date-part');
        const timeInput = group.querySelector('.time-part');
        if (dateInput && timeInput) {
            dateInput.value = `${year}-${month}-${day}`;
            timeInput.value = `${hours}:${minutes}`;
            dateInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
    } else {
        input.value = `${year}-${month}-${day}T${hours}:${minutes}`;
        input.dispatchEvent(new Event('change', { bubbles: true }));
    }

    if (inputId === 'actualStartTime' || inputId === 'actualEndTime') {
        saveActualTimeInPlace(inputId, now);
    }
}

async function saveActualTimeInPlace(field, nowDate) {
    const currentMissionId =
        window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    if (!currentMissionId) {
        console.error('[mission-detail] Mission ID nicht gefunden');
        return;
    }
    const versionInput =
        document.getElementById('mission-schedule-version') ||
        document.getElementById('mission-version');
    const version = versionInput ? versionInput.value : null;
    if (version === null || version === '') {
        console.error(
            '[mission-detail] Schedule-Version nicht gefunden (mission-schedule-version input)',
        );
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(
                window.krtI18nText(
                    window.MSG_MISSION_ACTUAL_TIME_ERROR,
                    'MSG_MISSION_ACTUAL_TIME_ERROR',
                ),
            );
        }
        return;
    }

    await window.krtMissionWrite({
        method: 'POST',
        url: '/missions/' + encodeURIComponent(currentMissionId) + '/actual-time',
        payload() {
            return {
                field,
                value: nowDate.toISOString(),
                version: Number(versionInput ? versionInput.value : version),
            };
        },
        sectionKey: 'schedule',
        toast: false,
        onSuccess(dto) {
            if (dto && dto.scheduleVersion != null && versionInput) {
                versionInput.value = dto.scheduleVersion;
            }
            const topVersionInput = document.getElementById('mission-version');
            if (dto && dto.version != null && topVersionInput) {
                topVersionInput.value = dto.version;
            }
            const span = document.getElementById(
                field === 'actualStartTime' ? 'overview-actual-start' : 'overview-actual-end',
            );
            if (span) {
                span.setAttribute('data-utc', String(nowDate.getTime()));
                if (typeof window.krtLocalizeDates === 'function') {
                    window.krtLocalizeDates(span.parentNode || span);
                }
            }
            window.krtRefreshMissionSection(['finance', 'overview']);
        },
    });
}

async function changeMissionOwner() {
    const userId = document.getElementById('new-owner-id').value;
    if (!userId || userId.trim() === '') {
        showFrontendErrorToast(
            typeof MSG_ERROR_USER_REQUIRED !== 'undefined'
                ? MSG_ERROR_USER_REQUIRED
                : 'Bitte wähle einen Benutzer aus',
        );
        return;
    }

    const ownerConfirmed = await window.showKrtConfirm(
        MSG_CONFIRM_OWNER_CHANGE,
        MSG_CONFIRM_OWNER_CHANGE,
        window.krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
            'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
        ),
        window.krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss'],
            'MISSION_SUBRES_I18N[mission.conflict.action.dismiss]',
        ),
    );
    if (!ownerConfirmed) {
        return;
    }

    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            showFrontendErrorToast(MSG_ERROR_MISSION_ID_MISSING);
            return;
        }

        const cleanMissionId = String(currentMissionId).trim();
        const cleanUserId = String(userId).trim();
        const currentOwnershipVersion = function () {
            const liveRow = document.getElementById('owner-row');
            const attr = liveRow ? liveRow.getAttribute('data-ownership-version') : null;
            return attr != null && attr !== '' ? parseInt(attr, 10) : 0;
        };

        await window.krtMissionWrite({
            method: 'PUT',
            url: `/missions/${cleanMissionId}/owner/ajax`,
            payload() {
                return { userId: cleanUserId, version: currentOwnershipVersion() };
            },
            sectionKey: 'owner',
            onSuccess(dto) {
                if (dto && dto.ownershipVersion != null) {
                    const liveRow = document.getElementById('owner-row');
                    if (liveRow) {
                        liveRow.setAttribute(
                            'data-ownership-version',
                            String(dto.ownershipVersion),
                        );
                    }
                }
                window.krtRefreshMissionSection('mgmt');
            },
        });
    } catch (err) {
        showFrontendErrorToast(
            `${typeof MSG_ERROR_OWNER_CHANGE !== 'undefined' ? MSG_ERROR_OWNER_CHANGE : 'Fehler beim Ändern des Besitzers'} (Error: ${err.message})`,
        );
    }
}

function updateMissionHeadOrgBadge(owningSquadron) {
    const slot = document.getElementById('mission-head-org-badge-slot');
    if (!slot) {
        return;
    }
    slot.textContent = '';
    if (owningSquadron && owningSquadron.shorthand) {
        const badge = document.createElement('span');
        badge.className = 'squadron-badge';
        badge.title = owningSquadron.name || '';
        badge.textContent = owningSquadron.shorthand || '';
        slot.appendChild(badge);
    }
}

async function changeMissionOwningOrgUnit() {
    const select = document.getElementById('new-owning-org-unit-id');
    const row = document.getElementById('owning-org-unit-row');
    if (!select || !row) {
        return;
    }
    const rawValue = select.value;
    const owningOrgUnitId = rawValue && rawValue.trim() !== '' ? rawValue.trim() : null;
    const currentOwningOrgUnitVersion = function () {
        const liveRow = document.getElementById('owning-org-unit-row');
        const attr = liveRow ? liveRow.getAttribute('data-owning-org-unit-version') : null;
        return attr != null && attr !== '' ? parseInt(attr, 10) : 0;
    };

    const confirmed = await window.showKrtConfirm(
        MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE,
        MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE,
        window.krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
            'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
        ),
        window.krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss'],
            'MISSION_SUBRES_I18N[mission.conflict.action.dismiss]',
        ),
    );
    if (!confirmed) {
        return;
    }

    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            showFrontendErrorToast(MSG_ERROR_MISSION_ID_MISSING);
            return;
        }
        const cleanMissionId = String(currentMissionId).trim();

        await window.krtMissionWrite({
            method: 'PUT',
            url: `/missions/${cleanMissionId}/owning-org-unit/ajax`,
            payload() {
                return { owningOrgUnitId, version: currentOwningOrgUnitVersion() };
            },
            sectionKey: 'owningOrgUnit',
            onSuccess(dto) {
                if (dto && dto.owningOrgUnitVersion != null) {
                    const liveRow = document.getElementById('owning-org-unit-row');
                    if (liveRow) {
                        liveRow.setAttribute(
                            'data-owning-org-unit-version',
                            String(dto.owningOrgUnitVersion),
                        );
                    }
                }
                if (dto) {
                    updateMissionHeadOrgBadge(dto.owningSquadron);
                }
                window.krtRefreshMissionSection('mgmt');
                if (window.krtNotifyMissionChanged) {
                    window.krtNotifyMissionChanged('overview');
                }
            },
        });
    } catch (err) {
        showFrontendErrorToast(
            `${typeof MSG_ERROR_OWNING_ORG_UNIT_CHANGE !== 'undefined' ? MSG_ERROR_OWNING_ORG_UNIT_CHANGE : 'Fehler beim Ändern der verantwortlichen Einheit'} (Error: ${err.message})`,
        );
    }
}

async function removeMissionManager(userId) {
    const removeConfirmed = await window.showKrtConfirm(
        typeof MSG_CONFIRM_MANAGER_REMOVE !== 'undefined'
            ? MSG_CONFIRM_MANAGER_REMOVE
            : 'Verwalter entfernen?',
        typeof MSG_CONFIRM_MANAGER_REMOVE !== 'undefined'
            ? MSG_CONFIRM_MANAGER_REMOVE
            : 'Verwalter entfernen?',
        window.krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.reload'],
            'MISSION_SUBRES_I18N[mission.conflict.action.reload]',
        ),
        window.krtI18nText(
            window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss'],
            'MISSION_SUBRES_I18N[mission.conflict.action.dismiss]',
        ),
    );
    if (!removeConfirmed) return;
    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            return;
        }

        const result = await window.krtMissionWrite({
            method: 'DELETE',
            url: `/missions/${currentMissionId}/managers/${userId}`,
            sectionKey: 'manager',
        });
        if (result.ok) {
            window.krtRefreshMissionSection('mgmt');
        }
    } catch (err) {
        console.error(err);
        showFrontendErrorToast(MSG_ERROR_MANAGER_REMOVE);
    }
}

async function addMissionManager() {
    const userId = document.getElementById('new-manager-id').value;
    if (!userId || userId.trim() === '') {
        showFrontendErrorToast(
            typeof MSG_ERROR_USER_REQUIRED !== 'undefined'
                ? MSG_ERROR_USER_REQUIRED
                : 'Bitte wähle einen Benutzer aus',
        );
        return;
    }
    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            showFrontendErrorToast(MSG_ERROR_MISSION_ID_MISSING);
            return;
        }

        const cleanMissionId = String(currentMissionId).trim();
        const cleanUserId = String(userId).trim();
        const result = await window.krtMissionWrite({
            method: 'POST',
            url: `/missions/${cleanMissionId}/managers/${cleanUserId}`,
            sectionKey: 'manager',
        });
        if (result.ok) {
            window.krtRefreshMissionSection('mgmt');
        }
    } catch (err) {
        console.error('Network or unexpected error:', err);
        showFrontendErrorToast(`${MSG_ERROR_MANAGER_ADD} (Error: ${err.message})`);
    }
}

async function updatePayoutPreference(selectElement) {
    const url = selectElement.getAttribute('data-payout-url');
    const value = selectElement.value;
    const result = await window.krtFetch.write({
        method: 'POST',
        url,
        payload: { preference: value },
        serialize: 'section:participant',
        toast: false,
        errorMessage:
            typeof MSG_ERROR_PAYOUT_UPDATE !== 'undefined'
                ? MSG_ERROR_PAYOUT_UPDATE
                : 'Speichern fehlgeschlagen.',
    });
    if (!result.ok) {
        selectElement.value = selectElement.getAttribute('data-original-value') || 'PAYOUT';
        return;
    }
    const updatedParticipant = result.body;
    if (!updatedParticipant) return;
    selectElement.setAttribute('data-original-value', value);
    if (window.krtNotifyMissionChanged) {
        window.krtNotifyMissionChanged('finance');
    }
    const participantId = selectElement.getAttribute('data-participant-id');
    if (participantId) {
        if (updatedParticipant.id === participantId) {
            document
                .querySelectorAll(
                    '.edit-participant-btn[data-participant-id="' + participantId + '"]',
                )
                .forEach((btn) => {
                    btn.setAttribute('data-payout-preference', value);
                });
            if (updatedParticipant.version != null) {
                document
                    .querySelectorAll('[data-participant-id="' + participantId + '"]')
                    .forEach((container) => {
                        const current = parseInt(container.getAttribute('data-version'), 10);
                        if (Number.isNaN(current) || updatedParticipant.version > current) {
                            window.krtFetch.syncVersion(container, updatedParticipant.version);
                        }
                    });
            }
        }
    }
}

document.addEventListener('DOMContentLoaded', function () {
    if (!window.krtMissionWrite || !window.missionId) {
        return;
    }
    const financeMissionId = window.missionId;

    function refreshFinanceAndBadge() {
        return window.krtRefreshMissionSection('finance');
    }

    const addForm = document.getElementById('add-finance-form');
    if (addForm) {
        addForm.addEventListener('submit', async function (ev) {
            ev.preventDefault();
            const fd = new FormData(addForm);
            const res = await window.krtMissionWrite({
                method: 'POST',
                url: '/missions/' + financeMissionId + '/finance-entries/ajax',
                payload: {
                    participantId: fd.get('participantId') || null,
                    type: fd.get('type') || 'INCOME',
                    amount: fd.get('amount') || null,
                    note: fd.get('note') || null,
                },
                sectionKey: 'finance',
            });
            if (res.ok) {
                window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                addForm.reset();
                refreshFinanceAndBadge();
            }
        });
    }

    const editForm = document.getElementById('edit-finance-form');
    if (editForm) {
        editForm.addEventListener('submit', async function (ev) {
            const entryId = editForm.getAttribute('data-entry-id');
            if (!entryId) {
                return;
            }
            ev.preventDefault();
            const fd = new FormData(editForm);
            const versionRaw = fd.get('version');
            const res = await window.krtMissionWrite({
                method: 'PUT',
                url: '/missions/' + financeMissionId + '/finance-entries/' + entryId + '/ajax',
                payload: {
                    type: fd.get('type') || 'INCOME',
                    amount: fd.get('amount') || null,
                    note: fd.get('note') || null,
                    version: versionRaw ? parseInt(versionRaw, 10) : null,
                },
                sectionKey: 'finance',
            });
            if (res.ok) {
                window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                const dto = res.body;
                if (dto && dto.version != null && window.krtFetch) {
                    document
                        .querySelectorAll('.edit-finance-btn[data-id="' + entryId + '"]')
                        .forEach(function (btn) {
                            window.krtFetch.syncVersion(btn, dto.version);
                        });
                    const hiddenVersion = document.getElementById('edit-finance-version');
                    if (hiddenVersion) {
                        hiddenVersion.value = dto.version;
                    }
                }
                refreshFinanceAndBadge();
            }
        });
    }

    const dForm = document.getElementById('delete-confirm-form');
    if (dForm) {
        dForm.addEventListener('submit', async function (ev) {
            if (dForm.getAttribute('data-sub-section') !== 'finance') {
                return;
            }
            ev.preventDefault();
            const match = (dForm.getAttribute('action') || '').match(
                /finance-entries\/([^/]+)\/delete/,
            );
            const entryId = match ? match[1] : null;
            if (!entryId) {
                return;
            }
            const res = await window.krtMissionWrite({
                method: 'DELETE',
                url: '/missions/' + financeMissionId + '/finance-entries/' + entryId + '/ajax',
                sectionKey: 'finance',
            });
            if (res.ok) {
                window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                refreshFinanceAndBadge();
            }
        });
    }
});

document.addEventListener('DOMContentLoaded', function () {
    function setupShipFilter(typeSelectId, shipSelectId) {
        const typeSelect = document.getElementById(typeSelectId);
        const shipSelect = document.getElementById(shipSelectId);
        if (!typeSelect || !shipSelect) return;

        function filterShips(resetShip = true) {
            const selectedType = typeSelect.value;
            if (!selectedType) {
                shipSelect.disabled = true;
                if (resetShip) shipSelect.value = '';
            } else {
                shipSelect.disabled = false;
            }

            Array.from(shipSelect.options).forEach((opt) => {
                if (!opt.value) return;
                if (opt.dataset.typeId === selectedType) {
                    opt.hidden = false;
                    opt.disabled = false;
                } else {
                    opt.hidden = true;
                    opt.disabled = true;
                    if (resetShip && shipSelect.value === opt.value) {
                        shipSelect.value = '';
                    }
                }
            });
        }

        typeSelect.addEventListener('change', () => filterShips(true));

        const modal = typeSelect.closest('[data-init-shiptype]');
        if (modal) {
            const initialType = modal.dataset.initShiptype;
            const initialShip = modal.dataset.initShip;
            if (initialType) {
                typeSelect.value = initialType;
                filterShips(false);
                if (initialShip) {
                    shipSelect.value = initialShip;
                }
            }
        }
    }

    setupShipFilter('add-unit-shiptype', 'add-unit-ship');
    setupShipFilter('edit-unit-shiptype', 'edit-unit-ship');
});

function registerMissionDetailEventHandlers() {
    if (!window.krtEvents || typeof window.krtEvents.on !== 'function') {
        console.error(
            '[mission-detail] window.krtEvents missing — page click handlers not registered. ' +
                'event-delegation.js must load before mission-detail body scripts.',
        );
        return;
    }
    window.krtEvents.on('click', 'mission-set-now', function (el) {
        setNowToInput(el.getAttribute('data-input-id'));
    });
    window.krtEvents.on('click', 'mission-change-owner', changeMissionOwner);
    window.krtEvents.on('click', 'mission-change-owning-org-unit', changeMissionOwningOrgUnit);
    window.krtEvents.on('click', 'mission-remove-manager', function (el) {
        removeMissionManager(el.getAttribute('data-manager-id'));
    });
    window.krtEvents.on('click', 'mission-add-manager', addMissionManager);
    window.krtEvents.on('change', 'mission-update-payout', function (el) {
        updatePayoutPreference(el);
    });
    window.krtEvents.on('click', 'mission-open-edit-finance', function (el) {
        openEditFinanceModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-note'),
            el.getAttribute('data-type'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
        );
    });
}
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', registerMissionDetailEventHandlers);
} else {
    registerMissionDetailEventHandlers();
}

(function () {
    const tabs = Array.from(document.querySelectorAll('.tab-nav .tab[data-tab]'));
    if (!tabs.length) {
        return;
    }
    const validKeys = tabs.map((t) => t.getAttribute('data-tab'));
    const storeKey = 'krt.einsatz.' + (window.missionId || 'new') + '.tab';
    let dirty = false;

    const verwPane = document.getElementById('pane-verw');
    if (verwPane) {
        verwPane.addEventListener('input', function () {
            dirty = true;
        });
        verwPane.addEventListener('submit', function () {
            dirty = false;
        });
    }

    function currentKey() {
        const active = tabs.find((t) => t.classList.contains('active'));
        return active ? active.getAttribute('data-tab') : null;
    }

    function apply(key) {
        tabs.forEach((t) => {
            const on = t.getAttribute('data-tab') === key;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', String(on));
            t.tabIndex = on ? 0 : -1;
        });
        document.querySelectorAll('.tab-pane').forEach((p) => {
            p.classList.toggle('on', p.id === 'pane-' + key);
        });
        try {
            localStorage.setItem(storeKey, key);
        } catch {}
    }

    async function show(key, push) {
        if (!validKeys.includes(key) || key === currentKey()) {
            return;
        }
        if (dirty && typeof window.showKrtConfirm === 'function') {
            const i18n = window.MISSION_TAB_I18N || {};
            const go = await window.showKrtConfirm(
                window.krtI18nText(i18n['unsaved.title'], 'MISSION_TAB_I18N[unsaved.title]'),
                window.krtI18nText(i18n['unsaved.message'], 'MISSION_TAB_I18N[unsaved.message]'),
                window.krtI18nText(i18n['unsaved.continue'], 'MISSION_TAB_I18N[unsaved.continue]'),
                window.krtI18nText(i18n['unsaved.cancel'], 'MISSION_TAB_I18N[unsaved.cancel]'),
            );
            if (!go) return;
            dirty = false;
        }
        apply(key);
        if (push) {
            const url = new URL(window.location);
            url.searchParams.set('tab', key);
            url.hash = '';
            history.pushState({ tab: key }, '', url);
        }
    }

    function resolveInitial(useStore) {
        const q = new URLSearchParams(window.location.search).get('tab');
        if (q && validKeys.includes(q)) return q;
        const h = (window.location.hash.match(/tab=([\w-]+)/) || [])[1];
        if (h && validKeys.includes(h)) return h;
        if (
            document.querySelector('#pane-verw #mission-form .field-error:not(:empty)') &&
            validKeys.includes('verw')
        )
            return 'verw';
        if (useStore) {
            try {
                const s = localStorage.getItem(storeKey);
                if (s && validKeys.includes(s)) return s;
            } catch {}
        }
        return 'ueb';
    }

    tabs.forEach((t) =>
        t.addEventListener('click', function () {
            show(t.getAttribute('data-tab'), true);
        }),
    );

    document.querySelector('.tab-nav').addEventListener('keydown', function (e) {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
        const i = tabs.indexOf(document.activeElement);
        if (i < 0) return;
        e.preventDefault();
        const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
        next.focus();
        show(next.getAttribute('data-tab'), true);
    });

    window.addEventListener('popstate', function () {
        apply(resolveInitial(false));
    });

    document.querySelectorAll('[data-tab-jump]').forEach((card) => {
        card.addEventListener('click', function () {
            show(card.getAttribute('data-tab-jump'), true);
        });
    });

    apply(resolveInitial(true));

    const sticky = document.getElementById('mission-head-sticky');
    const pageHeader = document.querySelector('body > header');
    function syncStickyOffset() {
        if (sticky && pageHeader) {
            sticky.style.top = pageHeader.offsetHeight + 'px';
        }
    }
    window.addEventListener('resize', syncStickyOffset);
    syncStickyOffset();
})();

(function () {
    const form = document.getElementById('mission-form');
    if (!form || form.dataset.missionEdit !== 'true') return;
    const D = window.MISSION_SUBRES_I18N || {};
    /**
     * The mission dictionary's string for `key`, rendered as its name and reported when missing.
     *
     * @param {string} key the MISSION_SUBRES_I18N key
     * @returns {string} the localized string
     */
    function msg(key) {
        return window.krtI18nText(D[key], 'MISSION_SUBRES_I18N[' + key + ']');
    }
    const SAVED = msg('mission.save.section.ok');
    const FAILED = msg('mission.save.section.error');

    function writeVersions(v) {
        if (!v) return;
        function set(id, val) {
            const el = document.getElementById(id);
            if (el && val != null) el.value = val;
        }
        set('mission-version', v.version);
        set('mission-core-version', v.coreVersion);
        set('mission-schedule-version', v.scheduleVersion);
        set('mission-flags-version', v.flagsVersion);
    }
    function clearFieldErrors() {
        form.querySelectorAll('.field-error[data-error-for]').forEach(function (el) {
            el.textContent = '';
        });
    }
    function renderFieldErrors(map) {
        clearFieldErrors();
        Object.keys(map || {}).forEach(function (field) {
            const slot = form.querySelector('.field-error[data-error-for="' + field + '"]');
            if (slot) slot.textContent = map[field];
            else if (window.showFrontendErrorToast) window.showFrontendErrorToast(map[field]);
        });
    }
    function handleConflict(problem) {
        const code = problem && problem.code;
        if (code === 'OPTIMISTIC_LOCK' || code === 'PESSIMISTIC_LOCK') {
            if (typeof window.showKrtConfirm === 'function') {
                window
                    .showKrtConfirm(
                        msg('mission.conflict.toast.title'),
                        msg('mission.conflict.action.reload.question'),
                        msg('mission.conflict.action.reload'),
                        msg('mission.conflict.action.dismiss'),
                    )
                    .then(function (ok) {
                        if (ok) window.location.reload();
                    });
            } else {
                window.location.reload();
            }
        } else if (window.showFrontendErrorToast) {
            const ownerRequired =
                window.krtFetch && window.krtFetch.ownerOrgUnitRequiredMessage
                    ? window.krtFetch.ownerOrgUnitRequiredMessage(problem)
                    : null;
            window.showFrontendErrorToast(
                ownerRequired ||
                    (problem && problem.detail) ||
                    msg('mission.conflict.toast.detail'),
            );
        }
    }
    const SECTION_FIELDS = {
        core: ['name', 'description', 'calendarLink', 'status', 'operationId', 'meetingPoint'],
        schedule: [
            'meetingTime',
            'plannedStartTime',
            'plannedEndTime',
            'actualStartTime',
            'actualEndTime',
        ],
        flags: ['isInternal'],
    };
    function sectionSnapshot(fd, fields) {
        return fields
            .map(function (n) {
                return n + '=' + fd.getAll(n).join(',');
            })
            .join('|');
    }
    const initialSnapshot = {};
    (function captureInitialSnapshot() {
        const fd0 = new FormData(form);
        Object.keys(SECTION_FIELDS).forEach(function (sec) {
            initialSnapshot[sec] = sectionSnapshot(fd0, SECTION_FIELDS[sec]);
        });
    })();
    function markDirtySections() {
        const fd = new FormData(form);
        function setFlag(id, dirty) {
            const el = document.getElementById(id);
            if (el) el.value = dirty ? 'true' : 'false';
        }
        setFlag(
            'mission-dirty-core',
            sectionSnapshot(fd, SECTION_FIELDS.core) !== initialSnapshot.core,
        );
        setFlag(
            'mission-dirty-schedule',
            sectionSnapshot(fd, SECTION_FIELDS.schedule) !== initialSnapshot.schedule,
        );
        setFlag(
            'mission-dirty-flags',
            sectionSnapshot(fd, SECTION_FIELDS.flags) !== initialSnapshot.flags,
        );
    }

    async function submitInPlace() {
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        markDirtySections();
        await window.krtFetch.submitForm({
            form,
            url: form.action,
            serialize: 'section:schedule',
            successMessage: SAVED,
            errorMessage: FAILED,
            submitter: document.querySelector('button[type="submit"][form="mission-form"]'),
            onError(status, body) {
                if (status === 422) {
                    renderFieldErrors(body || {});
                    return true;
                }
                if (status === 409) {
                    handleConflict(body || {});
                    return true;
                }
                return false;
            },
            onSuccess(body) {
                writeVersions(body);
                clearFieldErrors();
                if (window.krtRefreshMissionSection) {
                    return window.krtRefreshMissionSection('overview');
                }
                return undefined;
            },
        });
    }
    form.addEventListener('submit', function (e) {
        e.preventDefault();
        submitInPlace();
    });
})();

(function () {
    if (!window.missionCanEdit) {
        return;
    }
    const board = document.getElementById('crew-board-results');
    if (!board) {
        return;
    }

    let dragged = null;
    let selected = null;
    let droppedOnZone = false;

    const EDGE_ZONE_PX = 72;
    const MAX_SCROLL_STEP_PX = 22;
    let autoScrollDir = 0;
    let autoScrollStep = 0;
    let autoScrollRaf = null;

    function autoScrollTick() {
        if (autoScrollDir === 0) {
            autoScrollRaf = null;
            return;
        }
        window.scrollBy(0, autoScrollDir * autoScrollStep);
        autoScrollRaf = window.requestAnimationFrame(autoScrollTick);
    }

    function stopAutoScroll() {
        autoScrollDir = 0;
        autoScrollStep = 0;
        if (autoScrollRaf !== null) {
            window.cancelAnimationFrame(autoScrollRaf);
            autoScrollRaf = null;
        }
    }

    function driveEdgeScroll(y) {
        const h = window.innerHeight;
        if (y <= EDGE_ZONE_PX) {
            autoScrollDir = -1;
            autoScrollStep = Math.ceil((MAX_SCROLL_STEP_PX * (EDGE_ZONE_PX - y)) / EDGE_ZONE_PX);
        } else if (y >= h - EDGE_ZONE_PX) {
            autoScrollDir = 1;
            autoScrollStep = Math.ceil(
                (MAX_SCROLL_STEP_PX * (y - (h - EDGE_ZONE_PX))) / EDGE_ZONE_PX,
            );
        } else {
            autoScrollDir = 0;
            autoScrollStep = 0;
        }
        if (autoScrollDir !== 0 && autoScrollRaf === null) {
            autoScrollRaf = window.requestAnimationFrame(autoScrollTick);
        }
    }

    function setSelected(row) {
        if (selected) {
            selected.classList.remove('is-selected');
            selected.setAttribute('aria-pressed', 'false');
        }
        selected = row;
        if (selected) {
            selected.classList.add('is-selected');
            selected.setAttribute('aria-pressed', 'true');
        }
    }

    async function moveParticipant(row, zone) {
        if (!window.krtMissionWrite || !window.missionId) return;
        const participantId = row.getAttribute('data-participant-id');
        const srcCrewId = row.getAttribute('data-crew-id') || '';
        const srcUnitId = row.getAttribute('data-unit-id') || '';
        const targetUnitId = zone.getAttribute('data-unit-id') || '';
        if (!participantId || srcUnitId === targetUnitId) return;

        if (srcCrewId && srcUnitId) {
            const del = await window.krtMissionWrite({
                method: 'DELETE',
                url:
                    '/missions/' +
                    window.missionId +
                    '/units/' +
                    srcUnitId +
                    '/crew/' +
                    srcCrewId +
                    '/ajax',
                sectionKey: 'crew',
            });
            if (!del.ok) return;
            if (!targetUnitId) {
                window.krtRefreshMissionSection('crew');
                return;
            }
        }
        if (targetUnitId) {
            await window.krtMissionWrite({
                method: 'POST',
                url: '/missions/' + window.missionId + '/units/' + targetUnitId + '/crew/ajax',
                payload: { participantId, jobTypeIds: [] },
                sectionKey: 'crew',
            });
            window.krtRefreshMissionSection('crew');
        }
    }

    board.addEventListener('dragstart', function (e) {
        const row = e.target.closest('.person-row');
        if (!row || !board.contains(row)) return;
        dragged = row;
        droppedOnZone = false;
        if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    });
    board.addEventListener('dragend', function () {
        if (dragged && !droppedOnZone) {
            const pool = document.getElementById('board-pool');
            if (
                pool &&
                dragged.getAttribute('data-crew-id') &&
                dragged.getAttribute('data-unit-id')
            ) {
                moveParticipant(dragged, pool);
            }
        }
        stopAutoScroll();
        dragged = null;
        droppedOnZone = false;
    });

    board.addEventListener('click', function (e) {
        if (suppressClick) {
            suppressClick = false;
            return;
        }
        const row = e.target.closest('.person-row');
        if (row && board.contains(row)) {
            if (
                e.target.closest('button') ||
                e.target.closest('select') ||
                e.target.closest('form') ||
                e.target.closest('a')
            )
                return;
            setSelected(selected === row ? null : row);
            return;
        }
        const zone = e.target.closest('.drop-zone');
        if (zone && board.contains(zone)) {
            if (e.target.closest('button') || e.target.closest('select') || e.target.closest('a'))
                return;
            if (selected && !zone.contains(selected)) {
                const sel = selected;
                setSelected(null);
                moveParticipant(sel, zone);
            }
        }
    });

    board.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        if (!e.target || typeof e.target.closest !== 'function') return;
        const row = e.target.closest('.person-row');
        if (row && e.target === row) {
            e.preventDefault();
            setSelected(selected === row ? null : row);
            return;
        }
        const zone = e.target.closest('.drop-zone');
        if (zone && e.target === zone && selected && !zone.contains(selected)) {
            e.preventDefault();
            const sel = selected;
            setSelected(null);
            moveParticipant(sel, zone);
        }
    });

    board.addEventListener('dragover', function (e) {
        const zone = e.target.closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        e.preventDefault();
        zone.classList.add('is-over');
    });
    board.addEventListener('dragleave', function (e) {
        const zone = e.target.closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        zone.classList.remove('is-over');
    });
    board.addEventListener('drop', function (e) {
        const zone = e.target.closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        e.preventDefault();
        zone.classList.remove('is-over');
        droppedOnZone = true;
        stopAutoScroll();
        if (dragged && !zone.contains(dragged)) {
            moveParticipant(dragged, zone);
        }
    });

    document.addEventListener('dragover', function (e) {
        if (!dragged) return;
        driveEdgeScroll(e.clientY);
    });

    const LONG_PRESS_MS = 320;
    const MOVE_CANCEL_PX = 12;

    let touchRow = null;
    let touchActive = false;
    let touchZone = null;
    let touchPointerId = null;
    let pressTimer = null;
    let pressX = 0;
    let pressY = 0;
    let suppressClick = false;

    function clearPressTimer() {
        if (pressTimer !== null) {
            window.clearTimeout(pressTimer);
            pressTimer = null;
        }
    }

    function zoneAt(x, y) {
        const el = document.elementFromPoint(x, y);
        if (!el || typeof el.closest !== 'function') return null;
        const zone = el.closest('.drop-zone');
        return zone && board.contains(zone) ? zone : null;
    }

    function setTouchZone(zone) {
        if (touchZone === zone) return;
        if (touchZone) touchZone.classList.remove('is-over');
        touchZone = zone;
        if (touchZone) touchZone.classList.add('is-over');
    }

    function endTouchDrag() {
        clearPressTimer();
        if (touchRow) {
            touchRow.classList.remove('is-touch-dragging');
            if (touchPointerId !== null) {
                try {
                    touchRow.releasePointerCapture(touchPointerId);
                } catch (_e) {}
            }
        }
        setTouchZone(null);
        stopAutoScroll();
        touchRow = null;
        touchActive = false;
        touchPointerId = null;
    }

    board.addEventListener('pointerdown', function (e) {
        suppressClick = false;
        if (e.pointerType === 'mouse') return;
        const row = e.target.closest('.person-row');
        if (!row || !board.contains(row)) return;
        if (
            e.target.closest('button') ||
            e.target.closest('select') ||
            e.target.closest('form') ||
            e.target.closest('a')
        )
            return;
        endTouchDrag();
        touchRow = row;
        touchPointerId = e.pointerId;
        pressX = e.clientX;
        pressY = e.clientY;
        pressTimer = window.setTimeout(function () {
            pressTimer = null;
            if (!touchRow) return;
            touchActive = true;
            touchRow.classList.add('is-touch-dragging');
            try {
                touchRow.setPointerCapture(touchPointerId);
            } catch (_e) {}
        }, LONG_PRESS_MS);
    });

    document.addEventListener('pointermove', function (e) {
        if (!touchRow || e.pointerId !== touchPointerId) return;
        if (!touchActive) {
            if (
                Math.abs(e.clientX - pressX) > MOVE_CANCEL_PX ||
                Math.abs(e.clientY - pressY) > MOVE_CANCEL_PX
            ) {
                endTouchDrag();
            }
            return;
        }
        setTouchZone(zoneAt(e.clientX, e.clientY));
        driveEdgeScroll(e.clientY);
    });

    document.addEventListener('pointerup', function (e) {
        if (!touchRow || e.pointerId !== touchPointerId) return;
        if (!touchActive) {
            endTouchDrag();
            return;
        }
        const row = touchRow;
        const zone = zoneAt(e.clientX, e.clientY);
        endTouchDrag();
        suppressClick = true;
        if (zone) {
            if (!zone.contains(row)) moveParticipant(row, zone);
            return;
        }
        const pool = document.getElementById('board-pool');
        if (pool && row.getAttribute('data-crew-id') && row.getAttribute('data-unit-id')) {
            moveParticipant(row, pool);
        }
    });

    document.addEventListener('pointercancel', function (e) {
        if (!touchRow || e.pointerId !== touchPointerId) return;
        endTouchDrag();
    });

    document.addEventListener(
        'touchmove',
        function (e) {
            if (touchActive) e.preventDefault();
        },
        { passive: false },
    );

    board.addEventListener('contextmenu', function (e) {
        if (touchRow) e.preventDefault();
    });

    board.addEventListener('change', async function (e) {
        const sel = e.target.closest('.crew-role-select');
        if (!sel || !board.contains(sel)) return;
        const value = sel.value;
        if (value === '__edit') {
            sel.value = sel.getAttribute('data-current') || '';
            if (typeof window.krtOpenEditCrewModal === 'function') {
                window.krtOpenEditCrewModal(sel);
            }
            return;
        }
        if (value === '__multi') {
            return;
        }
        const ajaxUrl = sel.getAttribute('data-action-ajax');
        if (!ajaxUrl || !window.krtMissionWrite) return;
        const versionAttr = sel.getAttribute('data-version');
        const res = await window.krtMissionWrite({
            method: 'PUT',
            url: ajaxUrl,
            payload: {
                jobTypeIds: value ? [value] : [],
                version: versionAttr ? Number(versionAttr) : null,
            },
            sectionKey: 'crew',
        });
        if (res.ok) {
            window.krtRefreshMissionSection('crew');
        } else {
            sel.value = sel.getAttribute('data-current') || '';
        }
    });

    document.addEventListener('krt:swapped', function (ev) {
        if (ev.detail && ev.detail.container === board) {
            selected = null;
            dragged = null;
            droppedOnZone = false;
            endTouchDrag();
            stopAutoScroll();
        }
    });
})();

function krtFormatLocalDateTime(el) {
    const utcMsStr = el.getAttribute('data-utc');
    if (utcMsStr && utcMsStr !== 'null') {
        const date = new Date(parseInt(utcMsStr, 10));
        if (!isNaN(date)) {
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            if (el.getAttribute('data-format') === 'time') {
                el.innerText = `${hours}:${minutes}`;
                return;
            }
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            el.innerText = `${day}.${month}.${year} ${hours}:${minutes}`;
            return;
        }
    }
    el.innerText = '—';
}

window.krtLocalizeDates = function (root) {
    const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
    scope.querySelectorAll('.krt-local-dt').forEach(krtFormatLocalDateTime);
    scope.querySelectorAll('.refinery-endsat-local').forEach(function (el) {
        const utcMsStr = el.getAttribute('data-utc');
        if (utcMsStr && utcMsStr !== 'null') {
            krtFormatLocalDateTime(el);
        } else {
            el.innerText = '-';
        }
    });
};

document.addEventListener('DOMContentLoaded', function () {
    window.krtLocalizeDates(document);
});
document.addEventListener('krt:swapped', function (ev) {
    window.krtLocalizeDates((ev.detail && ev.detail.container) || document);
});
