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

/* global ANNOUNCE_MSG, ANNOUNCE_CONFLICT */

document.addEventListener('DOMContentLoaded', function () {
    const deleteModal = document.getElementById('delete-confirm-modal');
    const triggerDeleteBtn = document.getElementById('trigger-delete-confirm');
    const closeDeleteBtns = document.querySelectorAll('.close-delete-modal');
    const updateForm = document.getElementById('announcement-update-form');
    const deleteForm = document.getElementById('announcement-delete-form');

    function closeDeleteModal() {
        if (deleteModal) window.krtModal.close(deleteModal);
    }

    if (triggerDeleteBtn && deleteModal) {
        triggerDeleteBtn.onclick = function () {
            window.krtModal.open(deleteModal);
        };
    }

    closeDeleteBtns.forEach(function (btn) {
        btn.onclick = closeDeleteModal;
    });

    window.onclick = function (event) {
        if (event.target === deleteModal) {
            closeDeleteModal();
        }
    };

    if (updateForm) {
        updateForm.addEventListener('submit', function (event) {
            event.preventDefault();
            if (!window.krtFetch) {
                updateForm.submit();
                return;
            }
            const versionInput = updateForm.querySelector('input[name="version"]');
            const contentInput = updateForm.querySelector('textarea[name="content"]');
            window.krtFetch.write({
                method: 'POST',
                url: updateForm.getAttribute('action'),
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
        deleteForm.addEventListener('submit', function (event) {
            event.preventDefault();
            if (!window.krtFetch) {
                deleteForm.submit();
                return;
            }
            window.krtFetch.write({
                method: 'POST',
                url: deleteForm.getAttribute('action'),
                successMessage: ANNOUNCE_MSG.deleteSuccess,
                errorMessage: ANNOUNCE_MSG.deleteError,
                conflict: ANNOUNCE_CONFLICT,
                onSuccess() {
                    if (updateForm) {
                        const versionInput = updateForm.querySelector('input[name="version"]');
                        const contentInput = updateForm.querySelector('textarea[name="content"]');
                        if (contentInput) contentInput.value = '';
                        if (versionInput) versionInput.value = '0';
                    }
                    closeDeleteModal();
                },
            });
        });
    }
});
