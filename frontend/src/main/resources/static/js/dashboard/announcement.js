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

/* global ANNOUNCE_MSG, ANNOUNCE_CONFLICT */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';

document.addEventListener('DOMContentLoaded', () => {
    const deleteModal = document.getElementById('delete-confirm-modal');
    const triggerDeleteBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('trigger-delete-confirm')
    );
    const closeDeleteBtns = /** @type {NodeListOf<HTMLElement>} */ (
        document.querySelectorAll('.close-delete-modal')
    );
    const updateForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('announcement-update-form')
    );
    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('announcement-delete-form')
    );
    const contentField = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('info-edit-content')
    );
    const counter = document.getElementById('info-edit-counter');

    /** Writes the textarea's current character count into the counter under it. */
    function updateCounter() {
        if (!contentField || !counter) {
            return;
        }
        const template = counter.getAttribute('data-template') || '{0}';
        const count = contentField.value.length.toLocaleString(
            document.documentElement.lang || undefined,
        );
        counter.textContent = template.replace('{0}', count);
    }

    if (contentField) {
        contentField.addEventListener('input', updateCounter);
    }

    function closeDeleteModal() {
        if (deleteModal) krtModal.close(deleteModal);
    }

    if (triggerDeleteBtn && deleteModal) {
        triggerDeleteBtn.onclick = function () {
            krtModal.open(deleteModal);
        };
    }

    closeDeleteBtns.forEach((btn) => {
        btn.onclick = closeDeleteModal;
    });

    window.onclick = function (event) {
        if (event.target === deleteModal) {
            closeDeleteModal();
        }
    };

    if (updateForm) {
        updateForm.addEventListener('submit', (event) => {
            event.preventDefault();
            if (!krtFetch) {
                updateForm.submit();
                return;
            }
            const versionInput = /** @type {HTMLInputElement | null} */ (
                updateForm.querySelector('input[name="version"]')
            );
            const contentInput = /** @type {HTMLTextAreaElement | null} */ (
                updateForm.querySelector('textarea[name="content"]')
            );
            krtFetch.write({
                method: 'POST',
                url: updateForm.getAttribute('action') || '',
                payload: {
                    content: contentInput ? contentInput.value : '',
                    version:
                        versionInput && versionInput.value !== ''
                            ? Number(versionInput.value)
                            : null,
                },
                successMessage: ANNOUNCE_MSG.saveSuccess,
                errorMessage: ANNOUNCE_MSG.saveError,
                conflict: ANNOUNCE_CONFLICT,
                onSuccess(body) {
                    if (versionInput && body && body.version != null) {
                        versionInput.value = body.version;
                    }
                },
            });
        });
    }

    if (deleteForm) {
        deleteForm.addEventListener('submit', (event) => {
            event.preventDefault();
            if (!krtFetch) {
                deleteForm.submit();
                return;
            }
            krtFetch.write({
                method: 'POST',
                url: deleteForm.getAttribute('action') || '',
                successMessage: ANNOUNCE_MSG.deleteSuccess,
                errorMessage: ANNOUNCE_MSG.deleteError,
                conflict: ANNOUNCE_CONFLICT,
                onSuccess() {
                    if (updateForm) {
                        const versionInput = /** @type {HTMLInputElement | null} */ (
                            updateForm.querySelector('input[name="version"]')
                        );
                        const contentInput = /** @type {HTMLTextAreaElement | null} */ (
                            updateForm.querySelector('textarea[name="content"]')
                        );
                        if (contentInput) contentInput.value = '';
                        if (versionInput) versionInput.value = '0';
                    }
                    updateCounter();
                    closeDeleteModal();
                },
            });
        });
    }
});
