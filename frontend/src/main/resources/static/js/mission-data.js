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

/*
 * Mission-data admin page module (/admin/mission-data), extracted verbatim from the former inline
 * script of admin/mission-data.html (ADR-0069, follow-up to #924).
 *
 * In-place CRUD (#582) for the three sections (squadrons, job-types, frequency-types): the create/edit
 * modals, the shared delete-confirm modal, the section activate toggles and the frequency-type
 * drag-and-drop reorder all go through window.krtFetch and re-swap the affected section fragment in
 * place; the classic POST->redirect forms stay the no-JS fallback. Also the box-collapse persistence
 * and the include-inactive filter swaps.
 *
 * The Thymeleaf-interpolated toast/conflict strings (MISSION_MSG, MISSION_CONFLICT) and the modal
 * titles (MISSION_TITLES, holding the six inline create/edit title expressions) stay inline in the page
 * bootstrap this module reads.
 */

/* global MISSION_MSG, MISSION_CONFLICT, MISSION_TITLES */

// ---- In-place CRUD helpers (#582) ----------------------------------------------------

// The include-inactive filter values, sent on every section re-swap so the swapped fragment
// keeps the same inactive-visibility as the rest of the page.
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
        checked('includeInactiveFrequencyTypes')
    );
}

function missionSectionForAction(action) {
    if (action.indexOf('/squadrons') !== -1) return 'squadrons-results';
    if (action.indexOf('/job-types') !== -1) return 'jobtypes-results';
    if (action.indexOf('/frequency-types') !== -1) return 'freqtypes-results';
    return null;
}

// Re-renders the section the just-saved entity belongs to, picking up the new/edited/removed
// row with correct derived state (active badges, frequency ordering) and fresh @Version
// attributes for the next edit.
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

// FormData POST to a mission-data AJAX twin (keeps the @ModelAttribute binding the classic
// forms use) with X-Requested-With + CSRF + retry-once-on-403. On success runs onSuccess and
// re-swaps the section; on failure delegates to krtFetch.handleProblem for the conflict UX
// (duplicate-name / in-use toast, or OPTIMISTIC_LOCK reload-confirm).
// Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF header
// (no Content-Type so the browser sets the multipart boundary), the bare-403 refresh-and-retry,
// X-Reauthenticate, the double-submit guard (submitter), the success toast and the !ok
// handleProblem (duplicate-name / in-use toast, or OPTIMISTIC_LOCK reload-confirm). This helper
// keeps only its page behaviour: run onSuccess and re-swap the affected section in place.
// Callers guard with if (!window.krtFetch) form.submit() so the classic POST->redirect stays the
// no-JS fallback.
function missionWrite(form, successMessage, onSuccess) {
    const action = form.getAttribute('action');
    window.krtFetch.submitForm({
        form: form,
        submitter: form.querySelector('button[type="submit"]'),
        successMessage: successMessage,
        errorMessage: MISSION_MSG.error,
        conflict: MISSION_CONFLICT,
        onSuccess: function () {
            if (typeof onSuccess === 'function') {
                onSuccess();
            }
            reswapMissionSection(action);
        },
    });
}

document.addEventListener('DOMContentLoaded', function () {
    // Squadron Modal
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
            sqModal.style.display = 'flex';
        };
    }

    // Delegated on document so swapped-in rows (AJAX filter) keep working with zero re-init.
    document.addEventListener('click', function (e) {
        const btn = e.target.closest('.edit-squadron-btn');
        if (!btn) return;
        sqForm.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), sqForm.action);
        sqTitle.innerText = MISSION_TITLES.edit;
        document.getElementById('sq-name').value = btn.getAttribute('data-name');
        document.getElementById('sq-shorthand').value = btn.getAttribute('data-shorthand');
        document.getElementById('sq-desc').value = btn.getAttribute('data-desc');
        document.getElementById('edit-squadron-version').value = btn.getAttribute('data-version');
        sqModal.style.display = 'flex';
    });

    if (sqClose) sqClose.onclick = () => (sqModal.style.display = 'none');

    // JobType Modal
    const jtModal = document.getElementById('jobtype-modal');
    const jtForm = document.getElementById('jobtype-form');
    const jtTitle = document.getElementById('jobtype-modal-title');
    const jtAddBtn = document.getElementById('add-jobtype-btn');
    const jtClose = document.querySelector('.close-jobtype-modal');
    const jtArchetypeSelect = document.getElementById('jt-archetype');
    const jtLeadershipGroup = document.getElementById('jt-leadership-group');
    const jtLeadership = document.getElementById('jt-leadership');
    const jtMissionLeadGroup = document.getElementById('jt-missionlead-group');

    // The Einsatzleiter (mission-lead) designation only makes sense for a MISSION leadership
    // role, so its checkbox is shown only then; hiding it also clears it (the backend rejects a
    // mission-lead designation on a non-MISSION or non-leadership role anyway).
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
        // Leadership flag is now supported for both archetypes MISSION and CREW.
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
            jtModal.style.display = 'flex';
        };
    }

    // Delegated on document so swapped-in rows (AJAX filter) keep working with zero re-init.
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
        jtModal.style.display = 'flex';
    });

    if (jtClose) jtClose.onclick = () => (jtModal.style.display = 'none');

    // FrequencyType Modal
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
            ftModal.style.display = 'flex';
        };
    }

    // Delegated on document so swapped-in rows (AJAX filter) keep working with zero re-init.
    document.addEventListener('click', function (e) {
        const btn = e.target.closest('.edit-freqtype-btn');
        if (!btn) return;
        ftForm.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), ftForm.action);
        ftTitle.innerText = MISSION_TITLES.edit;
        document.getElementById('ft-name').value = btn.getAttribute('data-name');
        document.getElementById('ft-desc').value = btn.getAttribute('data-desc');
        document.getElementById('edit-freqtype-version').value = btn.getAttribute('data-version');
        ftModal.style.display = 'flex';
    });

    if (ftClose) ftClose.onclick = () => (ftModal.style.display = 'none');

    window.onclick = function (event) {
        if (event.target === sqModal) sqModal.style.display = 'none';
        if (event.target === jtModal) jtModal.style.display = 'none';
        if (event.target === ftModal) ftModal.style.display = 'none';
    };
    // Delete Modal Logic
    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = document.getElementById('delete-confirm-form');
    const deleteClose = document.querySelectorAll('.close-delete-modal');

    if (deleteModal) {
        // Delegated: one listener serves all three sections' swapped-in delete buttons.
        document.addEventListener('click', function (e) {
            const btn = e.target.closest('.delete-btn');
            if (!btn) return;
            deleteForm.action = window.safeSameOriginUrl(
                btn.getAttribute('data-action'),
                deleteForm.action,
            );
            deleteModal.style.display = 'flex';
        });

        deleteClose.forEach((btn) => {
            btn.onclick = function () {
                deleteModal.style.display = 'none';
            };
        });

        window.addEventListener('click', function (event) {
            if (event.target === deleteModal) {
                deleteModal.style.display = 'none';
            }
        });
    }

    // Box collapse logic
    ['squadrons-box', 'jobtypes-box', 'freqtypes-box'].forEach((boxId) => {
        const isCollapsed = localStorage.getItem(boxId + '-collapsed') === 'true';
        if (isCollapsed) {
            const box = document.getElementById(boxId);
            if (box) {
                const content = box.querySelector('.box-content');
                const icon = box.querySelector('.toggle-icon');
                if (content && icon) {
                    content.style.display = 'none';
                    icon.textContent = '+';
                }
            }
        }
    });

    // ---- In-place CRUD interceptors (#582) --------------------------------------------
    // Create/edit share one modal form per section; on success the modal closes and the
    // section re-swaps. Delete uses the shared confirm modal. Activate forms live inside the
    // swappable sections, so they are document-delegated to survive a re-swap.
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
                    modal.style.display = 'none';
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
                    deleteModal.style.display = 'none';
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

function toggleBox(event, boxId) {
    if (
        event.target.closest('button') ||
        event.target.closest('form') ||
        event.target.closest('input') ||
        event.target.closest('label')
    ) {
        return;
    }
    const box = document.getElementById(boxId);
    if (!box) return;
    const content = box.querySelector('.box-content');
    const icon = box.querySelector('.toggle-icon');
    if (content && icon) {
        if (window.getComputedStyle(content).display === 'none') {
            content.style.display = 'block';
            icon.textContent = '-';
            localStorage.setItem(boxId + '-collapsed', 'false');
        } else {
            content.style.display = 'none';
            icon.textContent = '+';
            localStorage.setItem(boxId + '-collapsed', 'true');
        }
    }
}

// Drag and Drop Logic. Bound on the STABLE swap container (#freqtypes-results) — NOT the
// swapped inner <tbody> — so reorder survives the include-inactive filter swap with no re-init.
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

        // #582: persist via the existing AJAX endpoint, then re-swap the frequency-type
        // section in place instead of reloading. The swap refreshes every row's order/priority
        // on success and reverts the optimistic drag move on failure. CSRF is sourced from the
        // shared krtCsrf reader.
        const reorderHeaders = {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        };
        const token = window.krtCsrf ? window.krtCsrf.token() : null;
        const headerName = window.krtCsrf ? window.krtCsrf.headerName() : null;
        if (token && headerName) {
            reorderHeaders[headerName] = token;
        }
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
        fetch(reorderUrl, {
            method: 'POST',
            headers: reorderHeaders,
            body: JSON.stringify(newOrderIds),
        })
            .then((response) => {
                if (!response.ok && window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(MISSION_MSG.error);
                }
                reswapFreqTypes();
            })
            .catch(() => {
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(MISSION_MSG.error);
                }
                reswapFreqTypes();
            });

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

// CSP-safe delegated bindings (replaces onclick="toggleBox(event, '<box>')" and
// onchange="document.getElementById('<form>').submit();" inline handlers).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'mission-data-toggle-box', function (el, event) {
        toggleBox(event, el.getAttribute('data-box-id'));
    });
}

// Include-inactive filters -> in-place swap of just the affected section (REQ-FE-002),
// replacing the former data-trigger="submit-form-by-id" full reload. Each swap URL carries
// all three include-inactive values so the address bar / no-JS refresh reproduce full state.
if (window.krtFetch) {
    const wireFilter = function (checkboxId, sectionId) {
        const cb = document.getElementById(checkboxId);
        if (!cb) return;
        cb.addEventListener('change', function () {
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
}
