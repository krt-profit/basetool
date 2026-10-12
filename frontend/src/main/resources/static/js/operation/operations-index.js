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

/* global OPS_MSG */

import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';

function openDeleteModal(id) {
    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-operation-form')
    );
    if (!deleteForm) return;
    deleteForm.action = safeSameOriginUrl(`/operations/${id}/delete`, deleteForm.action);
    krtModal.open(document.getElementById('delete-operation-modal'));
}

if (krtEvents && typeof krtEvents.on === 'function') {
    krtEvents.on('click', 'operations-open-delete', (el) => {
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
    if (modal) krtModal.close(modal);
}

function reloadOperationsList() {
    if (typeof window.krtOperationsReload === 'function') window.krtOperationsReload();
}

function initOperationsIndex() {
    if (!krtFetch) return;

    const createForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('create-operation-form')
    );
    if (createForm) {
        createForm.addEventListener('submit', (event) => {
            event.preventDefault();
            const owner = /** @type {HTMLInputElement | null} */ (
                createForm.querySelector('[name="owningOrgUnitId"]')
            );
            const submitBtn = /** @type {HTMLButtonElement | null} */ (
                createForm.querySelector('button[type="submit"]')
            );
            if (submitBtn) submitBtn.disabled = true;
            krtFetch
                .write({
                    method: 'POST',
                    url: /** @type {string} */ (createForm.getAttribute('action')),
                    payload: {
                        name: /** @type {HTMLInputElement} */ (
                            createForm.querySelector('[name="name"]')
                        ).value,
                        description: /** @type {HTMLTextAreaElement} */ (
                            createForm.querySelector('[name="description"]')
                        ).value,
                        status: /** @type {HTMLSelectElement} */ (
                            createForm.querySelector('[name="status"]')
                        ).value,
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
                .then(() => {
                    if (submitBtn) submitBtn.disabled = false;
                });
        });
    }

    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-operation-form')
    );
    if (deleteForm) {
        deleteForm.addEventListener('submit', (event) => {
            event.preventDefault();
            const submitBtn = /** @type {HTMLButtonElement | null} */ (
                deleteForm.querySelector('button[type="submit"]')
            );
            if (submitBtn) submitBtn.disabled = true;
            krtFetch
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
                .then(() => {
                    if (submitBtn) submitBtn.disabled = false;
                });
        });
    }
}

initOperationsIndex();
