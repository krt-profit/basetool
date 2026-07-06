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
 * Operations-index create/delete module (/operations), extracted verbatim from the two former inline
 * scripts of operations-index.html (ADR-0069, follow-up to #924).
 *
 * Wires the delete-confirmation modal opener and the in-place AJAX create + delete flows (#576): each
 * intercepts the classic form submit, writes through the krtFetch X-Requested-With twin, and refreshes
 * the list fragment via window.krtOperationsReload (exposed by the sibling operations.js filter module)
 * so the active filter query is preserved — no full-page reload. The classic POST->redirect forms stay
 * the no-JS fallback.
 *
 * The localized toast + conflict strings are the only Thymeleaf interpolation, so they stay inline in
 * the page bootstrap as the OPS_MSG global this module reads.
 */

/* global OPS_MSG */

function openDeleteModal(id) {
    const deleteForm = document.getElementById('delete-operation-form');
    deleteForm.action = window.safeSameOriginUrl(
        '/operations/' + id + '/delete',
        deleteForm.action,
    );
    document.getElementById('delete-operation-modal').style.display = 'flex';
}

// CSP-safe delegated binding (replaces onclick="openDeleteModal(this.getAttribute('data-id'))").
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'operations-open-delete', function (el) {
        openDeleteModal(el.getAttribute('data-id'));
    });
}

// #576: create + delete an operation in place (no full-page reload). The classic POST->redirect
// forms stay the no-JS fallback; here we intercept the submit, write via the krtFetch
// X-Requested-With twin, then refresh the list fragment in place via window.krtOperationsReload
// (exposed by operations.js) so the active filter query is preserved.
function opsConflictStrings() {
    return {
        title: OPS_MSG.conflictTitle,
        reloadDetailFallback: OPS_MSG.conflictDetail,
        reloadLabel: OPS_MSG.conflictReload,
        dismissLabel: OPS_MSG.conflictDismiss,
        reloadQuestion: OPS_MSG.conflictQuestion,
    };
}

function closeOperationModal(id) {
    const modal = document.getElementById(id);
    if (modal) modal.style.display = 'none';
}

function reloadOperationsList() {
    if (typeof window.krtOperationsReload === 'function') window.krtOperationsReload();
}

(function () {
    if (!window.krtFetch) return; // no-JS / no-foundation: the classic POST->redirect forms run.

    const createForm = document.getElementById('create-operation-form');
    if (createForm) {
        createForm.addEventListener('submit', function (event) {
            event.preventDefault();
            const owner = createForm.querySelector('[name="owningOrgUnitId"]');
            // Disable the submit while the write is in flight so a double-click cannot create
            // the operation twice (the old POST->redirect flow blocked this via full navigation).
            const submitBtn = createForm.querySelector('button[type="submit"]');
            if (submitBtn) submitBtn.disabled = true;
            window.krtFetch
                .write({
                    method: 'POST',
                    url: createForm.getAttribute('action'),
                    payload: {
                        name: createForm.querySelector('[name="name"]').value,
                        description: createForm.querySelector('[name="description"]').value,
                        status: createForm.querySelector('[name="status"]').value,
                        owningOrgUnitId: owner && owner.value ? owner.value : null,
                    },
                    successMessage: OPS_MSG.createSuccess,
                    errorMessage: OPS_MSG.createError,
                    conflict: opsConflictStrings(),
                    onSuccess: function () {
                        closeOperationModal('create-operation-modal');
                        createForm.reset();
                        reloadOperationsList();
                    },
                })
                .then(function () {
                    if (submitBtn) submitBtn.disabled = false;
                });
        });
    }

    const deleteForm = document.getElementById('delete-operation-form');
    if (deleteForm) {
        deleteForm.addEventListener('submit', function (event) {
            event.preventDefault();
            // Disable the submit while the write is in flight so a double-click cannot fire a
            // second delete (whose stale version would 409 after the first one succeeded).
            const submitBtn = deleteForm.querySelector('button[type="submit"]');
            if (submitBtn) submitBtn.disabled = true;
            window.krtFetch
                .write({
                    method: 'POST',
                    url: deleteForm.action,
                    successMessage: OPS_MSG.deleteSuccess,
                    errorMessage: OPS_MSG.deleteError,
                    conflict: opsConflictStrings(),
                    onSuccess: function () {
                        closeOperationModal('delete-operation-modal');
                        reloadOperationsList();
                    },
                })
                .then(function () {
                    if (submitBtn) submitBtn.disabled = false;
                });
        });
    }
})();
