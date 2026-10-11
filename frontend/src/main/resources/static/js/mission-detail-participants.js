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

document.addEventListener('DOMContentLoaded', () => {
    (function wireParticipantAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }

        const addForm = /** @type {HTMLFormElement | null} */ (
            document.getElementById('add-participant-form')
        );
        if (addForm) {
            addForm.addEventListener('submit', async (ev) => {
                ev.preventDefault();
                const fd = new FormData(addForm);
                const addOrgUnitIds =
                    addForm.querySelector('#participant-user-id') &&
                    /** @type {HTMLInputElement} */ (addForm.querySelector('#participant-user-id'))
                        .value
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
                    url: `/missions/${window.missionId}/participants/ajax`,
                    payload,
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                    addForm.reset();
                    const addUserId = /** @type {HTMLInputElement | null} */ (
                        addForm.querySelector('#participant-user-id')
                    );
                    if (addUserId) {
                        addUserId.value = '';
                    }
                    window.krtRefreshMissionSection(['crew', 'finance']);
                }
            });
        }

        const editForm = /** @type {HTMLFormElement | null} */ (
            document.getElementById('edit-participant-form')
        );
        if (editForm) {
            editForm.addEventListener('submit', async (ev) => {
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
                    version: versionRaw ? parseInt(/** @type {string} */ (versionRaw), 10) : null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: `/missions/${window.missionId}/participants/${pId}/ajax`,
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
                        document.querySelectorAll(`[data-participant-id="${pId}"]`).forEach((c) => {
                            window.krtFetch.syncVersion(c, dto.version);
                        });
                    }
                    window.krtRefreshMissionSection(['crew', 'finance', 'overview']);
                }
            });
        }

        document.addEventListener('submit', async (ev) => {
            const f = /** @type {Element | null} */ (ev.target);
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
                url: `/missions/${window.missionId}/participants/${pId}/${action}/ajax`,
                sectionKey: 'participant',
            });
            if (res.ok) {
                const dto = res.body;
                if (dto && dto.version !== undefined && dto.version !== null && window.krtFetch) {
                    document.querySelectorAll(`[data-participant-id="${pId}"]`).forEach((c) => {
                        window.krtFetch.syncVersion(c, dto.version);
                    });
                }
                window.krtRefreshMissionSection(['crew', 'finance']);
            }
        });

        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async (ev) => {
                const sub = dForm.getAttribute('data-sub-section');
                const subId = dForm.getAttribute('data-sub-id');
                if (sub !== 'participant' || !subId) {
                    return;
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: `/missions/${window.missionId}/participants/${subId}/ajax`,
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
    const editCrewForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('edit-crew-form')
    );
    const editCrewJobs = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('edit-crew-jobs')
    );

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

    window.addEventListener('click', (event) => {
        const target = /** @type {Element} */ (event.target);
        if (target.classList && target.classList.contains('krt-modal-overlay')) {
            window.krtModalClose(target);
        }
    });

    (function wireCrewAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }

        /**
         * Collects the non-empty job-type ids selected in the form's job select.
         *
         * @param {HTMLFormElement} form the crew edit form
         * @returns {string[]} the selected job-type ids
         */
        function collectJobTypeIds(form) {
            const select = /** @type {HTMLSelectElement | null} */ (
                form.querySelector('select[name="jobTypeIds"]')
            );
            if (!select) return [];
            return Array.from(select.selectedOptions || [])
                .map((o) => o.value)
                .filter((v) => v);
        }

        const editForm = /** @type {HTMLFormElement | null} */ (
            document.getElementById('edit-crew-form')
        );
        if (editForm) {
            editForm.addEventListener('submit', async (ev) => {
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
            dForm.addEventListener('submit', async (ev) => {
                const sub = dForm.getAttribute('data-sub-section');
                const crewId = dForm.getAttribute('data-sub-id');
                const unitId = dForm.getAttribute('data-sub-unit-id');
                if (sub !== 'crew' || !crewId || !unitId) {
                    return;
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: `/missions/${window.missionId}/units/${unitId}/crew/${crewId}/ajax`,
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

    const searchInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('participant-search-input')
    );
    const userIdInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('participant-user-id')
    );
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
                window.krtFetch
                    .getJson(`/users/search?query=${encodeURIComponent(val)}`)
                    .then((users) => {
                        closeAllLists();
                        if (!users || users.length === 0) return;

                        users.slice(0, USER_SEARCH_RENDER_CAP).forEach((user) => {
                            const div = document.createElement('div');
                            const regex = new RegExp(
                                `(${val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`,
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

                            div.addEventListener('click', () => {
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
            const list = /** @type {HTMLElement} */ (resultsDiv);
            while (list.firstChild) {
                list.removeChild(list.firstChild);
            }
        }

        document.addEventListener('click', (e) => {
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

    document.addEventListener('input', (e) => {
        if (!e.target || /** @type {Element} */ (e.target).id !== 'party-lead-search-input') {
            return;
        }
        const input = /** @type {HTMLInputElement} */ (e.target);
        const userIdEl = /** @type {HTMLInputElement | null} */ (
            document.getElementById('party-lead-user-id')
        );
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
            window.krtFetch
                .getJson(`/users/search?query=${encodeURIComponent(val)}`)
                .then((users) => {
                    partyLeadCloseLists();
                    if (!users || users.length === 0) return;
                    users.slice(0, USER_SEARCH_RENDER_CAP).forEach((user) => {
                        const div = document.createElement('div');
                        const regex = new RegExp(
                            `(${val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`,
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
                        div.addEventListener('click', () => {
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

    document.addEventListener('click', (e) => {
        if (!e.target || /** @type {Element} */ (e.target).id !== 'party-lead-search-input') {
            partyLeadCloseLists();
        }
    });

    document.addEventListener('click', (e) => {
        const clearBtn =
            /** @type {Element} */ (e.target).closest &&
            /** @type {Element} */ (e.target).closest('#party-lead-clear-btn');
        if (!clearBtn) {
            return;
        }
        const input = /** @type {HTMLInputElement | null} */ (
            document.getElementById('party-lead-search-input')
        );
        const userIdEl = /** @type {HTMLInputElement | null} */ (
            document.getElementById('party-lead-user-id')
        );
        const form = /** @type {HTMLFormElement | null} */ (
            document.getElementById('party-lead-form')
        );
        if (!input || !userIdEl || !form) {
            return;
        }
        input.value = '';
        userIdEl.value = '';
        form.requestSubmit();
    });

    document.addEventListener('submit', async (ev) => {
        if (!ev.target || /** @type {Element} */ (ev.target).id !== 'party-lead-form') {
            return;
        }
        ev.preventDefault();
        const input = /** @type {HTMLInputElement | null} */ (
            document.getElementById('party-lead-search-input')
        );
        const userIdEl = /** @type {HTMLInputElement | null} */ (
            document.getElementById('party-lead-user-id')
        );
        const versionInput = /** @type {HTMLInputElement | null} */ (
            document.getElementById('party-lead-version')
        );
        if (!input || !userIdEl) {
            return;
        }
        const submittedUserId = userIdEl.value || null;
        const submittedGuestName = input.value || null;
        const res = await window.krtMissionWrite({
            method: 'PUT',
            url: `/missions/${window.missionId}/party-lead/ajax`,
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
