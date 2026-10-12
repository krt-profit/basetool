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

// @ts-check

import { showKrtConfirm } from '/js/kernel/toast.js';
import { krtSyncDatetimeSplitGroup } from '/js/kernel/datetime-splitter.js';
import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';

(function () {
    const objectiveList = document.getElementById('mission-create-objective-list');
    const stepList = document.getElementById('mission-create-step-list');
    if (!objectiveList && !stepList) {
        return;
    }
    const form = document.getElementById('mission-form');
    const objectivesJson = /** @type {HTMLInputElement | null} */ (
        document.getElementById('mission-objectives-json')
    );
    const stepsJson = /** @type {HTMLInputElement | null} */ (
        document.getElementById('mission-steps-json')
    );
    const objectiveTemplate = document.getElementById('mission-create-objective-row');
    const stepTemplate = document.getElementById('mission-create-step-row');

    function renumber(list) {
        if (!list) {
            return;
        }
        Array.prototype.forEach.call(list.querySelectorAll('.ae-row'), (row, i) => {
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
            parsed.forEach((item) => {
                addRow(list, template, item);
            });
        }
    }

    function serialize(list, extraKey) {
        const out = [];
        if (!list) {
            return out;
        }
        Array.prototype.forEach.call(list.querySelectorAll('.ae-row'), (row) => {
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

    document.addEventListener('click', (e) => {
        const t = /** @type {Element} */ (e.target).closest('[data-trigger]');
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
            () => {
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

window.krtModalOpen = function (overlay) {
    krtModal.open(overlay);
};
window.krtModalClose = function (overlay) {
    krtModal.close(overlay);
};
window.krtSegSet = function (targetId, value) {
    const seg = document.querySelector(`.seg[data-seg-target="${targetId}"]`);
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById(targetId));
    if (!seg || !input) return;
    input.value = value;
    seg.querySelectorAll('button[data-type-value]').forEach((b) => {
        const on = b.getAttribute('data-type-value') === value;
        b.classList.toggle('on-pos', on && value === 'INCOME');
        b.classList.toggle('on-neg', on && value === 'EXPENSE');
        if (!on) {
            b.classList.remove('on-pos', 'on-neg');
        }
        b.setAttribute('aria-pressed', String(on));
    });
};
document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.seg[data-seg-target]').forEach((seg) => {
        const targetId = /** @type {string} */ (seg.getAttribute('data-seg-target'));
        const input = /** @type {HTMLInputElement | null} */ (document.getElementById(targetId));
        seg.querySelectorAll('button[data-type-value]').forEach((b) => {
            b.addEventListener('click', () => {
                window.krtSegSet(
                    targetId,
                    /** @type {string} */ (b.getAttribute('data-type-value')),
                );
            });
        });
        window.krtSegSet(targetId, input && input.value ? input.value : 'INCOME');
    });
});

document.addEventListener('DOMContentLoaded', () => {
    const pModal = document.getElementById('participant-modal');
    const pBtn = document.getElementById('add-participant-btn');

    if (pBtn && pModal) {
        pBtn.onclick = function () {
            window.krtModalOpen(pModal);
        };
    }

    const eModal = document.getElementById('edit-participant-modal');
    const eForm = /** @type {HTMLFormElement} */ (document.getElementById('edit-participant-form'));
    const eJob = /** @type {HTMLSelectElement} */ (document.getElementById('edit-job'));
    const ePlannedJob = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('edit-planned-job')
    );
    const eOrgUnits = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('edit-org-units')
    );
    const eComment = /** @type {HTMLTextAreaElement} */ (document.getElementById('edit-comment'));

    if (eModal) {
        function bindEditParticipantButtons() {
            /** @type {NodeListOf<HTMLElement>} */ (
                document.querySelectorAll('.edit-participant-btn')
            ).forEach((btn) => {
                if (btn.dataset.epBound) return;
                btn.dataset.epBound = '1';
                btn.addEventListener('click', function () {
                    eForm.action = safeSameOriginUrl(
                        this.getAttribute('data-action'),
                        eForm.action,
                    );
                    eForm.setAttribute(
                        'data-participant-id',
                        this.getAttribute('data-participant-id') || '',
                    );
                    eJob.value = /** @type {string} */ (this.getAttribute('data-job'));
                    if (ePlannedJob)
                        ePlannedJob.value = /** @type {string} */ (
                            this.getAttribute('data-planned-job')
                        );
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
                        eOrgUnitsGroup.hidden = !isExternal;
                        eOrgUnitsReadonlyGroup.hidden = isExternal;
                        if (!isExternal && eOrgUnitsReadonly) {
                            eOrgUnitsReadonly.textContent = '';
                            const unitNames = (this.getAttribute('data-org-unit-names') || '')
                                .split(',')
                                .map((s) => {
                                    return s.trim();
                                })
                                .filter(Boolean);
                            if (unitNames.length === 0) {
                                const none = document.createElement('span');
                                none.className = 'squadron-badge squadron-badge-muted';
                                none.textContent = '—';
                                eOrgUnitsReadonly.appendChild(none);
                            } else {
                                unitNames.forEach((unitName) => {
                                    const badge = document.createElement('span');
                                    badge.className = 'squadron-badge';
                                    badge.textContent = unitName;
                                    eOrgUnitsReadonly.appendChild(badge);
                                });
                            }
                        }
                    }
                    eComment.value = /** @type {string} */ (this.getAttribute('data-comment'));

                    const ePayout = /** @type {HTMLSelectElement | null} */ (
                        document.getElementById('edit-payout-preference')
                    );
                    if (ePayout)
                        ePayout.value = this.getAttribute('data-payout-preference') || 'PAYOUT';

                    const eVersion = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-participant-version')
                    );
                    const eStartTimeHidden = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-participant-start-time-hidden')
                    );
                    const eEndTimeHidden = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-participant-end-time-hidden')
                    );
                    const eStartTimeUi = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-participant-start-time-ui')
                    );
                    const eEndTimeUi = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-participant-end-time-ui')
                    );

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
                            `/missions/${window.missionId}/participants/${pid}/delete`,
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
                        if (typeof krtSyncDatetimeSplitGroup === 'function') {
                            krtSyncDatetimeSplitGroup(
                                /** @type {ParentNode} */ (
                                    eStartTimeUi.closest('.datetime-split-group')
                                ),
                            );
                        }
                    }
                    if (eEndTimeUi) {
                        eEndTimeUi.value = endUtc;
                        if (typeof krtSyncDatetimeSplitGroup === 'function') {
                            krtSyncDatetimeSplitGroup(
                                /** @type {ParentNode} */ (
                                    eEndTimeUi.closest('.datetime-split-group')
                                ),
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
            eForm.addEventListener('submit', () => {
                const eStartTimeUi = /** @type {HTMLInputElement | null} */ (
                    document.getElementById('edit-participant-start-time-ui')
                );
                const eEndTimeUi = /** @type {HTMLInputElement | null} */ (
                    document.getElementById('edit-participant-end-time-ui')
                );
                const eStartTimeHidden = /** @type {HTMLInputElement | null} */ (
                    document.getElementById('edit-participant-start-time-hidden')
                );
                const eEndTimeHidden = /** @type {HTMLInputElement | null} */ (
                    document.getElementById('edit-participant-end-time-hidden')
                );

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

    /** @type {NodeListOf<HTMLInputElement>} */ (document.querySelectorAll('.freq-input')).forEach(
        (input) => {
            input.addEventListener('input', function () {
                if (this.value.indexOf(',') !== -1) {
                    this.value = this.value.replace(/,/g, '.');
                }
            });
        },
    );

    const frequencyModal = document.getElementById('frequency-modal');
    const frequencyForm = /** @type {HTMLFormElement} */ (
        document.getElementById('frequency-form')
    );

    if (frequencyModal) {
        document.addEventListener('click', (event) => {
            const btn =
                /** @type {Element} */ (event.target).closest &&
                /** @type {Element} */ (event.target).closest('.set-freq-btn');
            if (!btn) {
                return;
            }
            frequencyForm.setAttribute(
                'data-action',
                /** @type {string} */ (btn.getAttribute('data-action')),
            );
            const typeId = /** @type {string} */ (btn.getAttribute('data-type-id'));
            /** @type {HTMLInputElement} */ (document.getElementById('freq-type-id')).value =
                typeId;
            const current = btn.getAttribute('data-current-value') || '';
            /** @type {HTMLInputElement} */ (document.getElementById('freq-value')).value = current;
            window.krtModalOpen(frequencyModal);
        });

        if (frequencyForm) {
            frequencyForm.addEventListener('submit', async (event) => {
                event.preventDefault();
                const url = frequencyForm.getAttribute('data-action');
                if (!url || !window.krtMissionWrite) {
                    frequencyForm.submit();
                    return;
                }
                const typeId = /** @type {HTMLInputElement} */ (
                    document.getElementById('freq-type-id')
                ).value;
                const rawValue = /** @type {HTMLInputElement} */ (
                    document.getElementById('freq-value')
                ).value;
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
                            /** @type {NodeListOf<HTMLElement>} */ (
                                document.querySelectorAll(
                                    `.freq-value-display[data-freq-type-id="${typeId}"]`,
                                )
                            ).forEach((display) => {
                                display.textContent = formatted;
                                display.hidden = false;
                            });
                            document
                                .querySelectorAll(`.set-freq-btn[data-type-id="${typeId}"]`)
                                .forEach((editBtn) => {
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
        const idInput = /** @type {HTMLInputElement} */ (document.getElementById('custom-freq-id'));
        const versionInput = /** @type {HTMLInputElement} */ (
            document.getElementById('custom-freq-version')
        );
        const nameInput = /** @type {HTMLInputElement} */ (
            document.getElementById('custom-freq-name')
        );
        const valueInput = /** @type {HTMLInputElement} */ (
            document.getElementById('custom-freq-value')
        );
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
        /**
         * Fills the custom-frequency dialog with one row's values and opens it.
         *
         * @param {Element} row the custom-frequency row to edit
         */
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
        /**
         * Deletes one custom frequency after a confirmation.
         *
         * @param {Element} row the custom-frequency row to delete
         * @returns {Promise<void>} settles once the deletion finished or was declined
         */
        async function del(row) {
            const fid = row.getAttribute('data-custom-freq-id');
            if (!fid) {
                return;
            }
            const msg = window.krtI18nText(
                dict.delete_confirm,
                'MISSION_CUSTOM_FREQ_I18N.delete_confirm',
            );
            const ok = await showKrtConfirm?.(
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
                url: `/missions/${mid()}/frequencies/${fid}/ajax`,
                sectionKey: 'frequency',
            });
            if (result.ok && window.krtRefreshMissionSection) {
                window.krtRefreshMissionSection(['frequencies', 'overview']);
            }
        }

        document.addEventListener('click', (e) => {
            const t = /** @type {Element} */ (e.target).closest('[data-trigger]');
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

        customFreqForm.addEventListener('submit', async (event) => {
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
                    ? `/missions/${mid()}/frequencies/custom/${fid}/ajax`
                    : `/missions/${mid()}/frequencies/custom/ajax`,
                payload,
                sectionKey: 'frequency',
            });
            if (result.ok) {
                window.krtModalClose(customFreqModal);
                if (isEdit && Array.isArray(result.body) && krtFetch) {
                    const updated = result.body.find((e) => {
                        return e && e.id === fid;
                    });
                    if (updated && updated.version != null) {
                        document
                            .querySelectorAll(`.custom-freq-row[data-custom-freq-id="${fid}"]`)
                            .forEach((row) => {
                                krtFetch.syncVersion(row, updated.version);
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
    const deleteForm = /** @type {HTMLFormElement} */ (
        document.getElementById('delete-confirm-form')
    );

    if (deleteModal) {
        /**
         * Builds the delete-confirmation text for the section of the opening button.
         *
         * @param {Element} btn the delete button carrying the section, name and crew-count data
         * @returns {string} the localized confirmation message
         */
        function deleteMessageFor(btn) {
            const section = btn.getAttribute('data-sub-section') || 'default';
            const dict = window.MISSION_DELETE_I18N || {};
            let msg = dict[section] || dict['default'] || '';
            msg = msg.replace('{name}', btn.getAttribute('data-name') || '');
            msg = msg.replace('{count}', btn.getAttribute('data-crew-count') || '0');
            return msg;
        }
        function bindDeleteOpeners() {
            /** @type {NodeListOf<HTMLElement>} */ (
                document.querySelectorAll(
                    '.delete-participant-btn, .delete-unit-btn, .delete-crew-btn, .delete-mission-btn, .delete-finance-btn',
                )
            ).forEach((btn) => {
                if (btn.dataset.delBound) return;
                btn.dataset.delBound = '1';
                btn.addEventListener('click', function () {
                    deleteForm.action = safeSameOriginUrl(
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
            addUnitBtn.addEventListener('click', () => {
                window.krtModalOpen(addUnitModal);
            });
        }
    }
    bindAddUnitButton();
    document.addEventListener('krt:swapped', bindAddUnitButton);

    const editUnitModal = document.getElementById('edit-unit-modal');
    const editUnitForm = /** @type {HTMLFormElement} */ (document.getElementById('edit-unit-form'));
    const editUnitName = /** @type {HTMLInputElement} */ (
        document.getElementById('edit-unit-name')
    );
    const editUnitFrequency = /** @type {HTMLInputElement} */ (
        document.getElementById('edit-unit-frequency')
    );
    const editUnitHvu = /** @type {HTMLInputElement} */ (document.getElementById('edit-unit-hvu'));

    if (editUnitModal) {
        function bindEditUnitButtons() {
            /** @type {NodeListOf<HTMLElement>} */ (
                document.querySelectorAll('.edit-unit-btn')
            ).forEach((btn) => {
                if (btn.dataset.euBound) return;
                btn.dataset.euBound = '1';
                btn.addEventListener('click', function () {
                    editUnitForm.action = safeSameOriginUrl(
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
                    const editTypeSelect = /** @type {HTMLSelectElement | null} */ (
                        document.getElementById('edit-unit-shiptype')
                    );
                    const editShipSelect = /** @type {HTMLSelectElement | null} */ (
                        document.getElementById('edit-unit-ship')
                    );
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
                    const editUnitResponsible = /** @type {HTMLSelectElement | null} */ (
                        document.getElementById('edit-unit-responsible')
                    );
                    if (editUnitResponsible) {
                        const responsibleVal = this.getAttribute('data-responsible') || '';
                        if (editUnitResponsible.krtCombobox) {
                            editUnitResponsible.krtCombobox.setValue(responsibleVal);
                        } else {
                            editUnitResponsible.value = responsibleVal;
                        }
                    }
                    const editUnitNote = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-unit-note')
                    );
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
        const addForm = /** @type {HTMLFormElement | null} */ (
            document.getElementById('add-unit-form')
        );
        if (addForm) {
            addForm.addEventListener('submit', async (ev) => {
                ev.preventDefault();
                const fd = new FormData(addForm);
                const payload = {
                    name: fd.get('name') || '',
                    shipTypeId: fd.get('shipTypeId') || null,
                    shipId: fd.get('shipId') || null,
                    highValueUnit: fd.get('highValueUnit') === 'true',
                    frequency: fd.get('frequency')
                        ? parseFloat(/** @type {string} */ (fd.get('frequency')))
                        : null,
                    responsibleUserId: fd.get('responsibleUserId') || null,
                    note: fd.get('note') || null,
                };
                const res = await window.krtMissionWrite({
                    method: 'POST',
                    url: `/missions/${window.missionId}/units/ajax`,
                    payload,
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }

        const editForm = /** @type {HTMLFormElement | null} */ (
            document.getElementById('edit-unit-form')
        );
        if (editForm) {
            editForm.addEventListener('submit', async (ev) => {
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
                    frequency: fd.get('frequency')
                        ? parseFloat(/** @type {string} */ (fd.get('frequency')))
                        : null,
                    responsibleUserId: fd.get('responsibleUserId') || null,
                    note: fd.get('note') || null,
                    version: versionAttr ? Number(versionAttr) : null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: `/missions/${window.missionId}/units/${unitId}/ajax`,
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
            dForm.addEventListener('submit', async (ev) => {
                const sub = dForm.getAttribute('data-sub-section');
                const subId = dForm.getAttribute('data-sub-id');
                if (sub !== 'unit' || !subId) {
                    return;
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: `/missions/${window.missionId}/units/${subId}/ajax`,
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
