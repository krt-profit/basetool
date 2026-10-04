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

/* global MISSION_MSG, MISSION_CONFLICT, MISSION_TITLES */

const MISSION_KINDS = ['squadrons', 'jobtypes', 'freqtypes'];

/**
 * Returns the data kind whose list the detail pane shows.
 *
 * @returns {string} one of MISSION_KINDS
 */
function selectedMissionKind() {
    const active = document.querySelector('.md-kind-row[aria-selected="true"]');
    const kind = active ? active.getAttribute('data-md-kind') : null;
    return kind && MISSION_KINDS.indexOf(kind) !== -1 ? kind : MISSION_KINDS[0];
}

function missionFilterParams() {
    function checked(id) {
        const el = document.getElementById(id);
        return el ? el.checked : false;
    }
    return (
        'includeInactiveSquadrons=' +
        checked('includeInactiveSquadrons') +
        '&includeInactiveJobTypes=' +
        checked('includeInactiveJobTypes') +
        '&includeInactiveFrequencyTypes=' +
        checked('includeInactiveFrequencyTypes') +
        '&kind=' +
        selectedMissionKind()
    );
}

/**
 * Shows the list of one data kind, marks its master row and records the choice in the address bar
 * and the page's stored preference.
 *
 * @param {string | null} kind one of MISSION_KINDS; anything else selects the first
 * @param {boolean} focusRow whether the selected master row takes the focus
 */
function selectMissionKind(kind, focusRow) {
    const want = kind && MISSION_KINDS.indexOf(kind) !== -1 ? kind : MISSION_KINDS[0];
    document.querySelectorAll('.md-kind-row').forEach(function (row) {
        const on = row.getAttribute('data-md-kind') === want;
        row.classList.toggle('is-active', on);
        row.setAttribute('aria-selected', String(on));
        row.setAttribute('tabindex', on ? '0' : '-1');
        const pane = document.getElementById(row.getAttribute('aria-controls'));
        if (pane) pane.hidden = !on;
        if (on && focusRow) row.focus();
    });
    try {
        const url = new URL(window.location.href);
        url.searchParams.set('kind', want);
        window.history.replaceState(window.history.state, '', url.pathname + url.search);
    } catch (_e) {}
    persistMissionFilters();
}

function onMissionKindKeydown(e) {
    const list = e.target && e.target.closest ? e.target.closest('#md-kind-list') : null;
    if (!list) return;
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') {
        return;
    }
    e.preventDefault();
    const rows = Array.from(list.querySelectorAll('.md-kind-row'));
    const current = rows.findIndex(function (r) {
        return r.getAttribute('aria-selected') === 'true';
    });
    let idx;
    if (e.key === 'ArrowDown') idx = Math.min(rows.length - 1, current + 1);
    else if (e.key === 'ArrowUp') idx = Math.max(0, current - 1);
    else if (e.key === 'Home') idx = 0;
    else idx = rows.length - 1;
    selectMissionKind(rows[idx].getAttribute('data-md-kind'), true);
}

function missionSectionForAction(action) {
    if (action.indexOf('/squadrons') !== -1) return 'squadrons-results';
    if (action.indexOf('/job-types') !== -1) return 'jobtypes-results';
    if (action.indexOf('/frequency-types') !== -1) return 'freqtypes-results';
    return null;
}

function reswapMissionSection(action) {
    const section = missionSectionForAction(action);
    if (section && window.krtFetch) {
        window.krtFetch.swap({
            url: '/admin/mission-data?' + missionFilterParams(),
            container: '#' + section,
            fragmentValue: section,
            history: false,
        });
    }
}

function missionWrite(form, successMessage, onSuccess) {
    const action = form.getAttribute('action');
    window.krtFetch.submitForm({
        form,
        submitter: form.querySelector('button[type="submit"]'),
        successMessage,
        errorMessage: MISSION_MSG.error,
        conflict: MISSION_CONFLICT,
        onSuccess() {
            if (typeof onSuccess === 'function') {
                onSuccess();
            }
            reswapMissionSection(action);
        },
    });
}

document.addEventListener('DOMContentLoaded', function () {
    const sqModal = document.getElementById('squadron-modal');
    const sqForm = document.getElementById('squadron-form');
    const sqTitle = document.getElementById('squadron-modal-title');
    const sqAddBtn = document.getElementById('add-squadron-btn');
    const sqClose = document.querySelector('.close-squadron-modal');

    if (sqAddBtn) {
        sqAddBtn.onclick = function () {
            sqForm.action = window.safeSameOriginUrl(
                this.getAttribute('data-action'),
                sqForm.action,
            );
            sqTitle.innerText = MISSION_TITLES.squadronCreate;
            document.getElementById('sq-name').value = '';
            document.getElementById('sq-shorthand').value = '';
            document.getElementById('sq-desc').value = '';
            document.getElementById('edit-squadron-version').value = '0';
            window.krtModal.open(sqModal);
        };
    }

    document.addEventListener('click', function (e) {
        const btn = e.target.closest('.edit-squadron-btn');
        if (!btn) return;
        sqForm.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), sqForm.action);
        sqTitle.innerText = MISSION_TITLES.edit;
        document.getElementById('sq-name').value = btn.getAttribute('data-name');
        document.getElementById('sq-shorthand').value = btn.getAttribute('data-shorthand');
        document.getElementById('sq-desc').value = btn.getAttribute('data-desc');
        document.getElementById('edit-squadron-version').value = btn.getAttribute('data-version');
        window.krtModal.open(sqModal);
    });

    if (sqClose) sqClose.onclick = () => window.krtModal.close(sqModal);

    const jtModal = document.getElementById('jobtype-modal');
    const jtForm = document.getElementById('jobtype-form');
    const jtTitle = document.getElementById('jobtype-modal-title');
    const jtAddBtn = document.getElementById('add-jobtype-btn');
    const jtClose = document.querySelector('.close-jobtype-modal');
    const jtArchetypeSelect = document.getElementById('jt-archetype');
    const jtLeadershipGroup = document.getElementById('jt-leadership-group');
    const jtLeadership = document.getElementById('jt-leadership');
    const jtMissionLeadGroup = document.getElementById('jt-missionlead-group');

    function updateMissionLeadVisibility() {
        const eligible =
            jtArchetypeSelect.value === 'MISSION' && jtLeadership && jtLeadership.checked;
        if (jtMissionLeadGroup) {
            jtMissionLeadGroup.style.display = eligible ? 'block' : 'none';
            if (!eligible) {
                const cb = document.getElementById('jt-missionlead');
                if (cb) cb.checked = false;
            }
        }
    }

    function updateLeadershipVisibility() {
        if (jtArchetypeSelect.value === 'MISSION' || jtArchetypeSelect.value === 'CREW') {
            jtLeadershipGroup.style.display = 'block';
        } else {
            jtLeadershipGroup.style.display = 'none';
            if (jtLeadership) jtLeadership.checked = false;
        }
        updateMissionLeadVisibility();
    }

    if (jtArchetypeSelect) {
        jtArchetypeSelect.addEventListener('change', updateLeadershipVisibility);
    }
    if (jtLeadership) {
        jtLeadership.addEventListener('change', updateMissionLeadVisibility);
    }

    if (jtAddBtn) {
        jtAddBtn.onclick = function () {
            jtForm.action = window.safeSameOriginUrl(
                this.getAttribute('data-action'),
                jtForm.action,
            );
            jtTitle.innerText = MISSION_TITLES.jobTypeCreate;
            document.getElementById('jt-name').value = '';
            document.getElementById('jt-desc').value = '';
            document.getElementById('jt-archetype').value = 'MISSION';
            document.getElementById('jt-leadership').checked = false;
            document.getElementById('jt-missionlead').checked = false;
            document.getElementById('edit-jobtype-version').value = '0';
            updateLeadershipVisibility();
            window.krtModal.open(jtModal);
        };
    }

    document.addEventListener('click', function (e) {
        const btn = e.target.closest('.edit-jobtype-btn');
        if (!btn) return;
        jtForm.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), jtForm.action);
        jtTitle.innerText = MISSION_TITLES.edit;
        document.getElementById('jt-name').value = btn.getAttribute('data-name');
        document.getElementById('jt-desc').value = btn.getAttribute('data-desc');
        document.getElementById('jt-archetype').value = btn.getAttribute('data-archetype');
        document.getElementById('jt-leadership').checked =
            btn.getAttribute('data-leadership') === 'true';
        document.getElementById('jt-missionlead').checked =
            btn.getAttribute('data-mission-lead') === 'true';
        document.getElementById('edit-jobtype-version').value = btn.getAttribute('data-version');
        updateLeadershipVisibility();
        window.krtModal.open(jtModal);
    });

    if (jtClose) jtClose.onclick = () => window.krtModal.close(jtModal);

    const ftModal = document.getElementById('frequency-type-modal');
    const ftForm = document.getElementById('freqtype-form');
    const ftTitle = document.getElementById('freqtype-modal-title');
    const ftAddBtn = document.getElementById('add-freqtype-btn');
    const ftClose = document.querySelector('.close-freqtype-modal');

    if (ftAddBtn) {
        ftAddBtn.onclick = function () {
            ftForm.action = window.safeSameOriginUrl(
                this.getAttribute('data-action'),
                ftForm.action,
            );
            ftTitle.innerText = MISSION_TITLES.freqTypeCreate;
            document.getElementById('ft-name').value = '';
            document.getElementById('ft-desc').value = '';
            document.getElementById('edit-freqtype-version').value = '0';
            window.krtModal.open(ftModal);
        };
    }

    document.addEventListener('click', function (e) {
        const btn = e.target.closest('.edit-freqtype-btn');
        if (!btn) return;
        ftForm.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), ftForm.action);
        ftTitle.innerText = MISSION_TITLES.edit;
        document.getElementById('ft-name').value = btn.getAttribute('data-name');
        document.getElementById('ft-desc').value = btn.getAttribute('data-desc');
        document.getElementById('edit-freqtype-version').value = btn.getAttribute('data-version');
        window.krtModal.open(ftModal);
    });

    if (ftClose) ftClose.onclick = () => window.krtModal.close(ftModal);

    window.onclick = function (event) {
        if (event.target === sqModal) window.krtModal.close(sqModal);
        if (event.target === jtModal) window.krtModal.close(jtModal);
        if (event.target === ftModal) window.krtModal.close(ftModal);
    };
    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = document.getElementById('delete-confirm-form');
    const deleteClose = document.querySelectorAll('.close-delete-modal');

    if (deleteModal) {
        document.addEventListener('click', function (e) {
            const btn = e.target.closest('.delete-btn');
            if (!btn) return;
            deleteForm.action = window.safeSameOriginUrl(
                btn.getAttribute('data-action'),
                deleteForm.action,
            );
            window.krtModal.open(deleteModal);
        });

        deleteClose.forEach((btn) => {
            btn.onclick = function () {
                window.krtModal.close(deleteModal);
            };
        });

        window.addEventListener('click', function (event) {
            if (event.target === deleteModal) {
                window.krtModal.close(deleteModal);
            }
        });
    }

    document.addEventListener('keydown', onMissionKindKeydown);

    function wireModalSave(form, modal) {
        if (!form) {
            return;
        }
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!window.krtFetch) {
                form.submit();
                return;
            }
            missionWrite(form, MISSION_MSG.saved, function () {
                if (modal) {
                    window.krtModal.close(modal);
                }
            });
        });
    }
    wireModalSave(sqForm, sqModal);
    wireModalSave(jtForm, jtModal);
    wireModalSave(ftForm, ftModal);

    if (deleteForm) {
        deleteForm.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!window.krtFetch) {
                deleteForm.submit();
                return;
            }
            missionWrite(deleteForm, MISSION_MSG.deleted, function () {
                if (deleteModal) {
                    window.krtModal.close(deleteModal);
                }
            });
        });
    }

    document.addEventListener('submit', function (e) {
        const actForm = e.target.closest('form[action*="/activate"]');
        if (!actForm) {
            return;
        }
        e.preventDefault();
        if (!window.krtFetch) {
            actForm.submit();
            return;
        }
        missionWrite(actForm, MISSION_MSG.saved, null);
    });
});

function setupDragAndDrop(containerId, reorderUrl) {
    const container = document.getElementById(containerId);
    if (!container) return;

    let draggedRow = null;

    container.addEventListener('dragstart', function (e) {
        draggedRow = e.target.closest('tr.draggable-row');
        if (!draggedRow) return;
        draggedRow.style.opacity = '0.5';
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', draggedRow.dataset.id);
    });

    container.addEventListener('dragend', function (_e) {
        if (!draggedRow) return;
        draggedRow.style.opacity = '1';

        const rows = Array.from(container.querySelectorAll('tr.draggable-row'));
        const newOrderIds = rows.map((row) => row.dataset.id);

        const reswapFreqTypes = function () {
            if (window.krtFetch) {
                window.krtFetch.swap({
                    url: '/admin/mission-data?' + missionFilterParams(),
                    container: '#freqtypes-results',
                    fragmentValue: 'freqtypes-results',
                    history: false,
                });
            }
        };
        if (window.krtFetch) {
            window.krtFetch
                .write({
                    method: 'POST',
                    url: reorderUrl,
                    payload: newOrderIds,
                    toast: false,
                    errorMessage: MISSION_MSG.error,
                    conflict: MISSION_CONFLICT,
                    onError(status) {
                        if (status === 409) {
                            return false;
                        }
                        if (window.showFrontendErrorToast) {
                            window.showFrontendErrorToast(MISSION_MSG.error);
                        }
                        return true;
                    },
                })
                .then(reswapFreqTypes);
        }

        draggedRow = null;
    });

    container.addEventListener('dragover', function (e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        const targetRow = e.target.closest('tr.draggable-row');
        if (!targetRow || targetRow === draggedRow) return;

        const bounding = targetRow.getBoundingClientRect();
        const offset = bounding.y + bounding.height / 2;
        if (e.clientY - offset > 0) {
            targetRow.after(draggedRow);
        } else {
            targetRow.before(draggedRow);
        }
    });

    container.addEventListener('drop', function (e) {
        e.preventDefault();
    });
}

document.addEventListener('DOMContentLoaded', () => {
    setupDragAndDrop('freqtypes-results', '/admin/mission-data/frequency-types/reorder');
});

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'mission-data-select-kind', function (el) {
        selectMissionKind(el.getAttribute('data-md-kind'), false);
    });
}

const MISSION_FILTER_PREF_KEY = 'admin_mission_data_filters';

function readMissionFilterPref() {
    try {
        const raw = localStorage.getItem(MISSION_FILTER_PREF_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null;
    }
}

function persistMissionFilters() {
    function checked(id) {
        const el = document.getElementById(id);
        return el ? el.checked : false;
    }
    try {
        localStorage.setItem(
            MISSION_FILTER_PREF_KEY,
            JSON.stringify({
                squadrons: checked('includeInactiveSquadrons'),
                jobTypes: checked('includeInactiveJobTypes'),
                frequencyTypes: checked('includeInactiveFrequencyTypes'),
                kind: selectedMissionKind(),
            }),
        );
    } catch (_e) {}
}

if (window.krtFetch) {
    const wireFilter = function (checkboxId, sectionId) {
        const cb = document.getElementById(checkboxId);
        if (!cb) return;
        cb.addEventListener('change', function () {
            persistMissionFilters();
            window.krtFetch.swap({
                url: '/admin/mission-data?' + missionFilterParams(),
                container: '#' + sectionId,
                fragmentValue: sectionId,
                history: true,
            });
        });
    };
    wireFilter('includeInactiveSquadrons', 'squadrons-results');
    wireFilter('includeInactiveJobTypes', 'jobtypes-results');
    wireFilter('includeInactiveFrequencyTypes', 'freqtypes-results');

    (function () {
        const toggles = [
            { id: 'includeInactiveSquadrons', pref: 'squadrons' },
            { id: 'includeInactiveJobTypes', pref: 'jobTypes' },
            { id: 'includeInactiveFrequencyTypes', pref: 'frequencyTypes' },
        ];
        if (!document.getElementById(toggles[0].id)) {
            return;
        }
        const saved = readMissionFilterPref();
        if (
            !/[?&]kind=/.test(window.location.search) &&
            saved !== null &&
            typeof saved.kind === 'string' &&
            saved.kind !== selectedMissionKind()
        ) {
            selectMissionKind(saved.kind, false);
        }
        if (
            /[?&]includeInactive(Squadrons|JobTypes|FrequencyTypes)=/.test(window.location.search)
        ) {
            persistMissionFilters();
            return;
        }
        if (saved === null) {
            return;
        }
        const changed = [];
        toggles.forEach(function (toggle) {
            const el = document.getElementById(toggle.id);
            const want = saved[toggle.pref] === true;
            if (el && el.checked !== want) {
                el.checked = want;
                changed.push(el);
            }
        });
        changed.forEach(function (el) {
            el.dispatchEvent(new Event('change'));
        });
    })();
}
