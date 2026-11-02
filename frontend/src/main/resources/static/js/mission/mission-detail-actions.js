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

/* global MSG_ERROR_PAYOUT_UPDATE, MSG_ERROR_MANAGER_ADD, MSG_ERROR_MANAGER_REMOVE, MSG_ERROR_OWNER_CHANGE, MSG_CONFIRM_OWNER_CHANGE, MSG_ERROR_OWNING_ORG_UNIT_CHANGE, MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE, MSG_CONFIRM_MANAGER_REMOVE, MSG_ERROR_USER_REQUIRED, MSG_ERROR_MISSION_ID_MISSING, missionId */

import { showFrontendErrorToast, showKrtConfirm } from '/js/kernel/toast.js';
import { assignFormAction, safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { missionHooks } from '/js/mission/mission-hooks.js';

/**
 * Fills a date-time input (plain or split into date and time parts) with the current local time;
 * the actual-start and actual-end inputs are saved in place right away.
 *
 * @param {string} inputId the id of the date-time input
 */
function setNowToInput(inputId) {
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById(inputId));
    if (!input) return;

    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');

    const group = input.closest('.datetime-split-group');
    if (group) {
        const dateInput = /** @type {HTMLInputElement | null} */ (
            group.querySelector('.date-part')
        );
        const timeInput = /** @type {HTMLInputElement | null} */ (
            group.querySelector('.time-part')
        );
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

/**
 * Saves the actual start or end time of the mission through the schedule section.
 *
 * @param {string} field 'actualStartTime' or 'actualEndTime'
 * @param {Date} nowDate the moment to store
 * @returns {Promise<void>} settles once the write finished or was refused
 */
async function saveActualTimeInPlace(field, nowDate) {
    const currentMissionId =
        window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    if (!currentMissionId) {
        console.error('[mission-detail] Mission ID nicht gefunden');
        return;
    }
    const versionInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('mission-schedule-version') ||
            document.getElementById('mission-version')
    );
    const version = versionInput ? versionInput.value : null;
    if (version === null || version === '') {
        console.error(
            '[mission-detail] Schedule-Version nicht gefunden (mission-schedule-version input)',
        );
        showFrontendErrorToast(
            krtI18nText(window.MSG_MISSION_ACTUAL_TIME_ERROR, 'MSG_MISSION_ACTUAL_TIME_ERROR'),
        );
        return;
    }

    await missionHooks.write({
        method: 'POST',
        url: `/missions/${encodeURIComponent(currentMissionId)}/actual-time`,
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
            const topVersionInput = /** @type {HTMLInputElement | null} */ (
                document.getElementById('mission-version')
            );
            if (dto && dto.version != null && topVersionInput) {
                topVersionInput.value = dto.version;
            }
            const span = document.getElementById(
                field === 'actualStartTime' ? 'overview-actual-start' : 'overview-actual-end',
            );
            if (span) {
                span.setAttribute('data-utc', String(nowDate.getTime()));
                if (typeof missionHooks.localizeDates === 'function') {
                    missionHooks.localizeDates(span.parentNode || span);
                }
            }
            missionHooks.refresh(['finance', 'overview']);
        },
    });
}

async function changeMissionOwner() {
    const userId = /** @type {HTMLSelectElement} */ (document.getElementById('new-owner-id')).value;
    if (!userId || userId.trim() === '') {
        showFrontendErrorToast(krtI18nText(MSG_ERROR_USER_REQUIRED, 'MSG_ERROR_USER_REQUIRED'));
        return;
    }

    const ownerConfirmed = await showKrtConfirm?.(
        MSG_CONFIRM_OWNER_CHANGE,
        MSG_CONFIRM_OWNER_CHANGE,
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

        await missionHooks.write({
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
                missionHooks.refresh('mgmt');
            },
        });
    } catch (_err) {
        showFrontendErrorToast(krtI18nText(MSG_ERROR_OWNER_CHANGE, 'MSG_ERROR_OWNER_CHANGE'));
    }
}

/**
 * Replaces the owning-unit badge in the mission head.
 *
 * @param {ApiDto<'SquadronReferenceDto'> | null | undefined} owningSquadron the new owning unit;
 *   no badge is rendered without a shorthand
 */
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
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('new-owning-org-unit-id')
    );
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

    const confirmed = await showKrtConfirm?.(
        MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE,
        MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE,
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

        await missionHooks.write({
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
                    const internalToggle = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('isInternal')
                    );
                    if (internalToggle) {
                        internalToggle.disabled = !dto.owningSquadron;
                    }
                }
                missionHooks.refresh('mgmt');
                if (missionHooks.notify) {
                    missionHooks.notify('overview');
                }
            },
        });
    } catch (_err) {
        showFrontendErrorToast(
            krtI18nText(MSG_ERROR_OWNING_ORG_UNIT_CHANGE, 'MSG_ERROR_OWNING_ORG_UNIT_CHANGE'),
        );
    }
}

/**
 * Removes a manager from the mission after a confirmation.
 *
 * @param {string | null} userId the id of the manager to remove
 * @returns {Promise<void>} settles once the removal finished or was declined
 */
async function removeMissionManager(userId) {
    const removeConfirmed = await showKrtConfirm?.(
        krtI18nText(MSG_CONFIRM_MANAGER_REMOVE, 'MSG_CONFIRM_MANAGER_REMOVE'),
        krtI18nText(MSG_CONFIRM_MANAGER_REMOVE, 'MSG_CONFIRM_MANAGER_REMOVE'),
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
    if (!removeConfirmed) return;
    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            return;
        }

        const result = await missionHooks.write({
            method: 'DELETE',
            url: `/missions/${currentMissionId}/managers/${userId}`,
            sectionKey: 'manager',
        });
        if (result.ok) {
            missionHooks.refresh('mgmt');
        }
    } catch (err) {
        console.error(err);
        showFrontendErrorToast(MSG_ERROR_MANAGER_REMOVE);
    }
}

async function addMissionManager() {
    const userId = /** @type {HTMLSelectElement} */ (document.getElementById('new-manager-id'))
        .value;
    if (!userId || userId.trim() === '') {
        showFrontendErrorToast(krtI18nText(MSG_ERROR_USER_REQUIRED, 'MSG_ERROR_USER_REQUIRED'));
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
        const result = await missionHooks.write({
            method: 'POST',
            url: `/missions/${cleanMissionId}/managers/${cleanUserId}`,
            sectionKey: 'manager',
        });
        if (result.ok) {
            missionHooks.refresh('mgmt');
        }
    } catch (err) {
        console.error('Network or unexpected error:', err);
        showFrontendErrorToast(
            `${MSG_ERROR_MANAGER_ADD} (Error: ${err instanceof Error ? err.message : String(err)})`,
        );
    }
}

/**
 * Saves a participant's payout preference and restores the previous one when the write fails.
 *
 * @param {HTMLSelectElement} selectElement the payout-preference select of one participant
 * @returns {Promise<void>} settles once the write finished
 */
async function updatePayoutPreference(selectElement) {
    const url = selectElement.getAttribute('data-payout-url');
    const value = selectElement.value;
    const result = await krtFetch.write({
        method: 'POST',
        url: /** @type {string} */ (url),
        payload: { preference: value },
        serialize: 'section:participant',
        toast: false,
        errorMessage: krtI18nText(MSG_ERROR_PAYOUT_UPDATE, 'MSG_ERROR_PAYOUT_UPDATE'),
    });
    if (!result.ok) {
        selectElement.value = selectElement.getAttribute('data-original-value') || 'PAYOUT';
        return;
    }
    const updatedParticipant = result.body;
    if (!updatedParticipant) return;
    selectElement.setAttribute('data-original-value', value);
    if (missionHooks.notify) {
        missionHooks.notify('finance');
    }
    const participantId = selectElement.getAttribute('data-participant-id');
    if (participantId) {
        if (updatedParticipant.id === participantId) {
            document
                .querySelectorAll(`.edit-participant-btn[data-participant-id="${participantId}"]`)
                .forEach((btn) => {
                    btn.setAttribute('data-payout-preference', value);
                });
            if (updatedParticipant.version != null) {
                document
                    .querySelectorAll(`[data-participant-id="${participantId}"]`)
                    .forEach((container) => {
                        const current = parseInt(
                            /** @type {string} */ (container.getAttribute('data-version')),
                            10,
                        );
                        if (Number.isNaN(current) || updatedParticipant.version > current) {
                            krtFetch.syncVersion(container, updatedParticipant.version);
                        }
                    });
            }
        }
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!missionHooks.write || !window.missionId) {
        return;
    }
    const financeMissionId = window.missionId;

    function refreshFinanceAndBadge() {
        return missionHooks.refresh('finance');
    }

    const addForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('add-finance-form')
    );
    if (addForm) {
        addForm.addEventListener('submit', async (ev) => {
            ev.preventDefault();
            const fd = new FormData(addForm);
            const res = await missionHooks.write({
                method: 'POST',
                url: `/missions/${financeMissionId}/finance-entries/ajax`,
                payload: {
                    participantId: fd.get('participantId') || null,
                    type: fd.get('type') || 'INCOME',
                    amount: fd.get('amount') || null,
                    note: fd.get('note') || null,
                },
                sectionKey: 'finance',
            });
            if (res.ok) {
                missionHooks.modalClose(addForm.closest('.krt-modal-overlay'));
                addForm.reset();
                refreshFinanceAndBadge();
            }
        });
    }

    const editForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('edit-finance-form')
    );
    if (editForm) {
        editForm.addEventListener('submit', async (ev) => {
            const entryId = editForm.getAttribute('data-entry-id');
            if (!entryId) {
                return;
            }
            ev.preventDefault();
            const fd = new FormData(editForm);
            const versionRaw = fd.get('version');
            const res = await missionHooks.write({
                method: 'PUT',
                url: `/missions/${financeMissionId}/finance-entries/${entryId}/ajax`,
                payload: {
                    type: fd.get('type') || 'INCOME',
                    amount: fd.get('amount') || null,
                    note: fd.get('note') || null,
                    version: versionRaw ? parseInt(/** @type {string} */ (versionRaw), 10) : null,
                },
                sectionKey: 'finance',
            });
            if (res.ok) {
                missionHooks.modalClose(editForm.closest('.krt-modal-overlay'));
                const dto = res.body;
                if (dto && dto.version != null) {
                    document
                        .querySelectorAll(`.edit-finance-btn[data-id="${entryId}"]`)
                        .forEach((btn) => {
                            krtFetch.syncVersion(btn, dto.version);
                        });
                    const hiddenVersion = /** @type {HTMLInputElement | null} */ (
                        document.getElementById('edit-finance-version')
                    );
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
        dForm.addEventListener('submit', async (ev) => {
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
            const res = await missionHooks.write({
                method: 'DELETE',
                url: `/missions/${financeMissionId}/finance-entries/${entryId}/ajax`,
                sectionKey: 'finance',
            });
            if (res.ok) {
                missionHooks.modalClose(dForm.closest('.krt-modal-overlay'));
                refreshFinanceAndBadge();
            }
        });
    }
});

document.addEventListener('DOMContentLoaded', () => {
    /**
     * Limits a ship select to the ships of the ship type chosen in the type select.
     *
     * @param {string} typeSelectId the id of the ship-type select
     * @param {string} shipSelectId the id of the ship select
     */
    function setupShipFilter(typeSelectId, shipSelectId) {
        const typeSelectEl = /** @type {HTMLSelectElement | null} */ (
            document.getElementById(typeSelectId)
        );
        const shipSelectEl = /** @type {HTMLSelectElement | null} */ (
            document.getElementById(shipSelectId)
        );
        if (!typeSelectEl || !shipSelectEl) return;
        const typeSelect = typeSelectEl;
        const shipSelect = shipSelectEl;

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

        const modal = /** @type {HTMLElement | null} */ (
            typeSelect.closest('[data-init-shiptype]')
        );
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

/**
 * Fills the finance-entry edit dialog with one entry's values and opens it.
 *
 * @param {string | null} id the entry id
 * @param {string | null} note the entry note; the string 'null' counts as none
 * @param {string | null} type 'EXPENSE' or anything else for income
 * @param {string | null} amount the amount
 * @param {string | null} version the entry's optimistic-lock version
 */
function openEditFinanceModal(id, note, type, amount, version) {
    const form = /** @type {HTMLFormElement} */ (document.getElementById('edit-finance-form'));
    assignFormAction(
        form,
        safeSameOriginUrl(
            `/missions/${window.missionId}/finance-entries/${id}/update`,
            form.action,
        ),
    );
    form.setAttribute('data-entry-id', /** @type {string} */ (id));
    /** @type {HTMLTextAreaElement} */ (document.getElementById('edit-finance-note')).value =
        note && note !== 'null' ? note : '';
    missionHooks.segSet('edit-finance-type', type === 'EXPENSE' ? 'EXPENSE' : 'INCOME');
    /** @type {HTMLInputElement} */ (document.getElementById('edit-finance-amount')).value =
        /** @type {string} */ (amount);
    /** @type {HTMLInputElement} */ (document.getElementById('edit-finance-version')).value =
        /** @type {string} */ (version);
    missionHooks.modalOpen(document.getElementById('edit-finance-entry-modal'));
}

function registerMissionDetailEventHandlers() {
    krtEvents.on('click', 'mission-set-now', (el) => {
        setNowToInput(/** @type {string} */ (el.getAttribute('data-input-id')));
    });
    krtEvents.on('click', 'mission-change-owner', changeMissionOwner);
    krtEvents.on('click', 'mission-change-owning-org-unit', changeMissionOwningOrgUnit);
    krtEvents.on('click', 'mission-remove-manager', (el) => {
        removeMissionManager(el.getAttribute('data-manager-id'));
    });
    krtEvents.on('click', 'mission-add-manager', addMissionManager);
    krtEvents.on('change', 'mission-update-payout', (el) => {
        updatePayoutPreference(/** @type {HTMLSelectElement} */ (el));
    });
    krtEvents.on('click', 'mission-open-edit-finance', (el) => {
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
