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

import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { assignFormAction, safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtEvents } from '/js/kernel/event-delegation.js';

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

/**
 * Builds the query string that carries the three inactive-toggles and the selected kind.
 *
 * @returns {string} the query string without a leading question mark
 */
function missionFilterParams() {
    /**
     * Reads a filter checkbox.
     *
     * @param {string} id the checkbox id
     * @returns {boolean} whether it is checked; false when absent
     */
    function checked(id) {
        const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
        return el ? el.checked : false;
    }
    return `includeInactiveSquadrons=${checked(
        'includeInactiveSquadrons',
    )}&includeInactiveJobTypes=${checked(
        'includeInactiveJobTypes',
    )}&includeInactiveFrequencyTypes=${checked(
        'includeInactiveFrequencyTypes',
    )}&kind=${selectedMissionKind()}`;
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
    /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.md-kind-row')).forEach(
        (row) => {
            const on = row.getAttribute('data-md-kind') === want;
            row.classList.toggle('is-active', on);
            row.setAttribute('aria-selected', String(on));
            row.setAttribute('tabindex', on ? '0' : '-1');
            const pane = document.getElementById(
                /** @type {string} */ (row.getAttribute('aria-controls')),
            );
            if (pane) pane.hidden = !on;
            if (on && focusRow) row.focus();
        },
    );
    try {
        const url = new URL(window.location.href);
        url.searchParams.set('kind', want);
        window.history.replaceState(window.history.state, '', url.pathname + url.search);
    } catch (_e) {}
    persistMissionFilters();
}

/**
 * Moves the selection of the master list with the arrow, Home and End keys.
 *
 * @param {KeyboardEvent} e the keydown event
 */
function onMissionKindKeydown(e) {
    const target = /** @type {Element | null} */ (e.target);
    const list = target && target.closest ? target.closest('#md-kind-list') : null;
    if (!list) return;
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') {
        return;
    }
    e.preventDefault();
    const rows = Array.from(list.querySelectorAll('.md-kind-row'));
    const current = rows.findIndex((r) => {
        return r.getAttribute('aria-selected') === 'true';
    });
    /** @type {number} */
    let idx;
    if (e.key === 'ArrowDown') idx = Math.min(rows.length - 1, current + 1);
    else if (e.key === 'ArrowUp') idx = Math.max(0, current - 1);
    else if (e.key === 'Home') idx = 0;
    else idx = rows.length - 1;
    selectMissionKind(rows[idx].getAttribute('data-md-kind'), true);
}

const missionData = krtPageSections.create({
    sections: {
        squadrons: { container: '#squadrons-results', fragmentValue: 'squadrons-results' },
        jobtypes: { container: '#jobtypes-results', fragmentValue: 'jobtypes-results' },
        freqtypes: { container: '#freqtypes-results', fragmentValue: 'freqtypes-results' },
    },
    pageUrl() {
        return `/admin/mission-data?${missionFilterParams()}`;
    },
});

/**
 * Maps a form action to the key of the result section it changes.
 *
 * @param {string} action the form's action URL
 * @returns {string | null} the section key, or null for an unknown action
 */
function missionSectionForAction(action) {
    if (action.indexOf('/squadrons') !== -1) return 'squadrons';
    if (action.indexOf('/job-types') !== -1) return 'jobtypes';
    if (action.indexOf('/frequency-types') !== -1) return 'freqtypes';
    return null;
}

/**
 * Submits a mission-data form through the section seam and re-renders the affected section.
 *
 * @param {HTMLFormElement} form the form to submit
 * @param {string} successMessage the already-localized success toast
 * @param {(() => void) | null} onSuccess runs after a successful save and the re-render
 */
function missionWrite(form, successMessage, onSuccess) {
    const section = missionSectionForAction(/** @type {string} */ (form.getAttribute('action')));
    missionData.submitForm({
        form,
        submitter: form.querySelector('button[type="submit"]'),
        successMessage,
        errorMessage: MISSION_MSG.error,
        conflict: MISSION_CONFLICT,
        refresh: section ? [section] : undefined,
        onSuccess() {
            if (typeof onSuccess === 'function') {
                onSuccess();
            }
        },
    });
}
document.addEventListener('DOMContentLoaded', () => {
    /**
     * Looks up a control of one of the data dialogs by id; the dialogs always render them.
     *
     * @param {string} id the element id
     * @returns {HTMLInputElement} the control
     */
    function dialogField(id) {
        return /** @type {HTMLInputElement} */ (document.getElementById(id));
    }

    const sqModal = document.getElementById('squadron-modal');
    const sqForm = /** @type {HTMLFormElement} */ (document.getElementById('squadron-form'));
    const sqTitle = /** @type {HTMLElement} */ (document.getElementById('squadron-modal-title'));
    const sqAddBtn = document.getElementById('add-squadron-btn');
    const sqClose = /** @type {HTMLElement | null} */ (
        document.querySelector('.close-squadron-modal')
    );

    if (sqAddBtn) {
        sqAddBtn.onclick = () => {
            assignFormAction(
                sqForm,
                safeSameOriginUrl(sqAddBtn.getAttribute('data-action'), sqForm.action),
            );
            sqTitle.innerText = MISSION_TITLES.squadronCreate;
            dialogField('sq-name').value = '';
            dialogField('sq-shorthand').value = '';
            dialogField('sq-desc').value = '';
            dialogField('edit-squadron-version').value = '0';
            krtModal.open(sqModal);
        };
    }

    document.addEventListener('click', (e) => {
        const btn = /** @type {Element} */ (e.target).closest('.edit-squadron-btn');
        if (!btn) return;
        assignFormAction(sqForm, safeSameOriginUrl(btn.getAttribute('data-action'), sqForm.action));
        sqTitle.innerText = MISSION_TITLES.edit;
        dialogField('sq-name').value = btn.getAttribute('data-name') ?? '';
        dialogField('sq-shorthand').value = btn.getAttribute('data-shorthand') ?? '';
        dialogField('sq-desc').value = btn.getAttribute('data-desc') ?? '';
        dialogField('edit-squadron-version').value = btn.getAttribute('data-version') ?? '';
        krtModal.open(sqModal);
    });

    if (sqClose) sqClose.onclick = () => krtModal.close(sqModal);

    const jtModal = document.getElementById('jobtype-modal');
    const jtForm = /** @type {HTMLFormElement} */ (document.getElementById('jobtype-form'));
    const jtTitle = /** @type {HTMLElement} */ (document.getElementById('jobtype-modal-title'));
    const jtAddBtn = document.getElementById('add-jobtype-btn');
    const jtClose = /** @type {HTMLElement | null} */ (
        document.querySelector('.close-jobtype-modal')
    );
    const jtArchetypeSelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('jt-archetype')
    );
    const jtLeadershipGroup = document.getElementById('jt-leadership-group');
    const jtLeadership = /** @type {HTMLInputElement | null} */ (
        document.getElementById('jt-leadership')
    );
    const jtMissionLeadGroup = document.getElementById('jt-missionlead-group');

    /** Shows the mission-lead option only for a leadership mission job type. */
    function updateMissionLeadVisibility() {
        if (!jtArchetypeSelect) {
            return;
        }
        const eligible =
            jtArchetypeSelect.value === 'MISSION' && jtLeadership && jtLeadership.checked;
        if (jtMissionLeadGroup) {
            jtMissionLeadGroup.style.display = eligible ? 'block' : 'none';
            if (!eligible) {
                const cb = /** @type {HTMLInputElement | null} */ (
                    document.getElementById('jt-missionlead')
                );
                if (cb) cb.checked = false;
            }
        }
    }

    /** Shows the leadership option for mission and crew archetypes and hides it otherwise. */
    function updateLeadershipVisibility() {
        if (!jtArchetypeSelect || !jtLeadershipGroup) {
            return;
        }
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
        jtAddBtn.onclick = () => {
            assignFormAction(
                jtForm,
                safeSameOriginUrl(jtAddBtn.getAttribute('data-action'), jtForm.action),
            );
            jtTitle.innerText = MISSION_TITLES.jobTypeCreate;
            dialogField('jt-name').value = '';
            dialogField('jt-desc').value = '';
            dialogField('jt-archetype').value = 'MISSION';
            dialogField('jt-leadership').checked = false;
            dialogField('jt-missionlead').checked = false;
            dialogField('edit-jobtype-version').value = '0';
            updateLeadershipVisibility();
            krtModal.open(jtModal);
        };
    }

    document.addEventListener('click', (e) => {
        const btn = /** @type {Element} */ (e.target).closest('.edit-jobtype-btn');
        if (!btn) return;
        assignFormAction(jtForm, safeSameOriginUrl(btn.getAttribute('data-action'), jtForm.action));
        jtTitle.innerText = MISSION_TITLES.edit;
        dialogField('jt-name').value = btn.getAttribute('data-name') ?? '';
        dialogField('jt-desc').value = btn.getAttribute('data-desc') ?? '';
        dialogField('jt-archetype').value = btn.getAttribute('data-archetype') ?? '';
        dialogField('jt-leadership').checked = btn.getAttribute('data-leadership') === 'true';
        dialogField('jt-missionlead').checked = btn.getAttribute('data-mission-lead') === 'true';
        dialogField('edit-jobtype-version').value = btn.getAttribute('data-version') ?? '';
        updateLeadershipVisibility();
        krtModal.open(jtModal);
    });

    if (jtClose) jtClose.onclick = () => krtModal.close(jtModal);

    const ftModal = document.getElementById('frequency-type-modal');
    const ftForm = /** @type {HTMLFormElement} */ (document.getElementById('freqtype-form'));
    const ftTitle = /** @type {HTMLElement} */ (document.getElementById('freqtype-modal-title'));
    const ftAddBtn = document.getElementById('add-freqtype-btn');
    const ftClose = /** @type {HTMLElement | null} */ (
        document.querySelector('.close-freqtype-modal')
    );

    if (ftAddBtn) {
        ftAddBtn.onclick = () => {
            assignFormAction(
                ftForm,
                safeSameOriginUrl(ftAddBtn.getAttribute('data-action'), ftForm.action),
            );
            ftTitle.innerText = MISSION_TITLES.freqTypeCreate;
            dialogField('ft-name').value = '';
            dialogField('ft-desc').value = '';
            dialogField('edit-freqtype-version').value = '0';
            krtModal.open(ftModal);
        };
    }

    document.addEventListener('click', (e) => {
        const btn = /** @type {Element} */ (e.target).closest('.edit-freqtype-btn');
        if (!btn) return;
        assignFormAction(ftForm, safeSameOriginUrl(btn.getAttribute('data-action'), ftForm.action));
        ftTitle.innerText = MISSION_TITLES.edit;
        dialogField('ft-name').value = btn.getAttribute('data-name') ?? '';
        dialogField('ft-desc').value = btn.getAttribute('data-desc') ?? '';
        dialogField('edit-freqtype-version').value = btn.getAttribute('data-version') ?? '';
        krtModal.open(ftModal);
    });

    if (ftClose) ftClose.onclick = () => krtModal.close(ftModal);

    window.onclick = function (event) {
        if (event.target === sqModal) krtModal.close(sqModal);
        if (event.target === jtModal) krtModal.close(jtModal);
        if (event.target === ftModal) krtModal.close(ftModal);
    };
    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-confirm-form')
    );
    const deleteClose = /** @type {NodeListOf<HTMLElement>} */ (
        document.querySelectorAll('.close-delete-modal')
    );

    if (deleteModal) {
        document.addEventListener('click', (e) => {
            const btn = /** @type {Element} */ (e.target).closest('.delete-btn');
            if (!btn || !deleteForm) return;
            assignFormAction(
                deleteForm,
                safeSameOriginUrl(btn.getAttribute('data-action'), deleteForm.action),
            );
            krtModal.open(deleteModal);
        });

        deleteClose.forEach((btn) => {
            btn.onclick = function () {
                krtModal.close(deleteModal);
            };
        });

        window.addEventListener('click', (event) => {
            if (event.target === deleteModal) {
                krtModal.close(deleteModal);
            }
        });
    }

    document.addEventListener('keydown', onMissionKindKeydown);

    /**
     * Turns a dialog form into an in-place save that closes its modal on success.
     *
     * @param {HTMLFormElement | null} form the dialog form
     * @param {HTMLElement | null} modal the dialog to close after the save
     */
    function wireModalSave(form, modal) {
        if (!form) {
            return;
        }
        form.addEventListener('submit', (e) => {
            e.preventDefault();

            missionWrite(form, MISSION_MSG.saved, () => {
                if (modal) {
                    krtModal.close(modal);
                }
            });
        });
    }
    wireModalSave(sqForm, sqModal);
    wireModalSave(jtForm, jtModal);
    wireModalSave(ftForm, ftModal);

    if (deleteForm) {
        deleteForm.addEventListener('submit', (e) => {
            e.preventDefault();

            missionWrite(deleteForm, MISSION_MSG.deleted, () => {
                if (deleteModal) {
                    krtModal.close(deleteModal);
                }
            });
        });
    }

    document.addEventListener('submit', (e) => {
        const actForm = /** @type {HTMLFormElement | null} */ (
            /** @type {Element} */ (e.target).closest('form[action*="/activate"]')
        );
        if (!actForm) {
            return;
        }
        e.preventDefault();

        missionWrite(actForm, MISSION_MSG.saved, null);
    });
});

/**
 * Makes the rows of a table container draggable and posts the new order after a drop.
 *
 * @param {string} containerId id of the element holding the draggable rows
 * @param {string} reorderUrl endpoint that receives the ordered row ids
 */
function setupDragAndDrop(containerId, reorderUrl) {
    const container = document.getElementById(containerId);
    if (!container) return;

    /** @type {HTMLElement | null} */
    let draggedRow = null;

    container.addEventListener('dragstart', (e) => {
        draggedRow = /** @type {HTMLElement | null} */ (
            /** @type {Element} */ (e.target).closest('tr.draggable-row')
        );
        if (!draggedRow) return;
        draggedRow.style.opacity = '0.5';
        if (!e.dataTransfer) return;
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', /** @type {string} */ (draggedRow.dataset.id));
    });

    container.addEventListener('dragend', (_e) => {
        if (!draggedRow) return;
        draggedRow.style.opacity = '1';

        const rows = /** @type {HTMLElement[]} */ (
            Array.from(container.querySelectorAll('tr.draggable-row'))
        );
        const newOrderIds = rows.map((row) => row.dataset.id);

        missionData
            .write({
                method: 'POST',
                url: reorderUrl,
                payload: newOrderIds,
                toast: false,
                errorMessage: MISSION_MSG.error,
                conflict: MISSION_CONFLICT,
                onError() {
                    showFrontendErrorToast(MISSION_MSG.error);
                    return true;
                },
            })
            .then(() => missionData.refresh('freqtypes'));
        draggedRow = null;
    });

    container.addEventListener('dragover', (e) => {
        e.preventDefault();
        if (!e.dataTransfer) return;
        e.dataTransfer.dropEffect = 'move';
        const targetRow = /** @type {Element} */ (e.target).closest('tr.draggable-row');
        if (!targetRow || targetRow === draggedRow) return;

        const bounding = targetRow.getBoundingClientRect();
        const offset = bounding.y + bounding.height / 2;
        if (e.clientY - offset > 0) {
            targetRow.after(/** @type {HTMLElement} */ (draggedRow));
        } else {
            targetRow.before(/** @type {HTMLElement} */ (draggedRow));
        }
    });

    container.addEventListener('drop', (e) => {
        e.preventDefault();
    });
}

document.addEventListener('DOMContentLoaded', () => {
    setupDragAndDrop('freqtypes-results', '/admin/mission-data/frequency-types/reorder');
});

krtEvents.on('click', 'mission-data-select-kind', (el) => {
    selectMissionKind(el.getAttribute('data-md-kind'), false);
});

const MISSION_FILTER_PREF_KEY = 'admin_mission_data_filters';

/**
 * Reads the stored filter preference.
 *
 * @returns {Record<string, unknown> | null} the stored object, or null when absent or unreadable
 */
function readMissionFilterPref() {
    try {
        const raw = localStorage.getItem(MISSION_FILTER_PREF_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null;
    }
}

/** Stores the three inactive-toggles and the selected kind as the page's preference. */
function persistMissionFilters() {
    /**
     * Reads a filter checkbox.
     *
     * @param {string} id the checkbox id
     * @returns {boolean} whether it is checked; false when absent
     */
    function checked(id) {
        const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
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

{
    /**
     * Re-renders a result section whenever its inactive-toggle changes.
     *
     * @param {string} checkboxId id of the toggle checkbox
     * @param {string} sectionId id of the result section it controls
     */
    const wireFilter = function (checkboxId, sectionId) {
        const cb = document.getElementById(checkboxId);
        if (!cb) return;
        cb.addEventListener('change', () => {
            persistMissionFilters();
            krtFetch.swap({
                url: `/admin/mission-data?${missionFilterParams()}`,
                container: `#${sectionId}`,
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
        /** @type {HTMLInputElement[]} */
        const changed = [];
        toggles.forEach((toggle) => {
            const el = /** @type {HTMLInputElement | null} */ (document.getElementById(toggle.id));
            const want = saved[toggle.pref] === true;
            if (el && el.checked !== want) {
                el.checked = want;
                changed.push(el);
            }
        });
        changed.forEach((el) => {
            el.dispatchEvent(new Event('change'));
        });
    })();
}
