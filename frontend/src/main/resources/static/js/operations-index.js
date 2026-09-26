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

/* global OPS_MSG */

function openDeleteModal(id) {
    const deleteForm = document.getElementById('delete-operation-form');
    deleteForm.action = window.safeSameOriginUrl(
        '/operations/' + id + '/delete',
        deleteForm.action,
    );
    window.krtModal.open(document.getElementById('delete-operation-modal'));
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'operations-open-delete', function (el) {
        openDeleteModal(el.getAttribute('data-id'));
    });
}

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
    if (modal) window.krtModal.close(modal);
}

function reloadOperationsList() {
    if (typeof window.krtOperationsReload === 'function') window.krtOperationsReload();
}

(function () {
    if (!window.krtFetch) return;

    const createForm = document.getElementById('create-operation-form');
    if (createForm) {
        createForm.addEventListener('submit', function (event) {
            event.preventDefault();
            const owner = createForm.querySelector('[name="owningOrgUnitId"]');
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
                    onSuccess() {
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
            const submitBtn = deleteForm.querySelector('button[type="submit"]');
            if (submitBtn) submitBtn.disabled = true;
            window.krtFetch
                .write({
                    method: 'POST',
                    url: deleteForm.action,
                    successMessage: OPS_MSG.deleteSuccess,
                    errorMessage: OPS_MSG.deleteError,
                    conflict: opsConflictStrings(),
                    onSuccess() {
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
