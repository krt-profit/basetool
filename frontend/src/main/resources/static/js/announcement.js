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
 * Announcement admin page module (/admin/announcement), extracted verbatim from the former
 * inline script of admin/announcement.html (ADR-0069, follow-up to #924).
 *
 * In-place save of the announcement (writes the bumped optimistic-lock version back into the
 * hidden input) and in-place delete (resets the form to its empty create state), both via
 * window.krtFetch with the classic POST->redirect forms as the no-JS fallback; plus the
 * delete-confirm modal wiring.
 *
 * The interpolated toast/conflict strings stay inline in the page bootstrap as the ANNOUNCE_MSG /
 * ANNOUNCE_CONFLICT globals this module reads.
 */

/* global ANNOUNCE_MSG, ANNOUNCE_CONFLICT */

document.addEventListener('DOMContentLoaded', function () {
    const deleteModal = document.getElementById('delete-confirm-modal');
    const triggerDeleteBtn = document.getElementById('trigger-delete-confirm');
    const closeDeleteBtns = document.querySelectorAll('.close-delete-modal');
    const updateForm = document.getElementById('announcement-update-form');
    const deleteForm = document.getElementById('announcement-delete-form');

    function closeDeleteModal() {
        if (deleteModal) deleteModal.style.display = 'none';
    }

    if (triggerDeleteBtn && deleteModal) {
        triggerDeleteBtn.onclick = function () {
            deleteModal.style.display = 'flex';
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

    // In-place save: post the update via the AJAX twin and write the bumped
    // optimistic-lock version back into the hidden input so the next save
    // does not 409. Without krtFetch (no-JS) the native POST->redirect runs.
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
                onSuccess: function (body) {
                    if (versionInput && body && body.version != null) {
                        versionInput.value = body.version;
                    }
                },
            });
        });
    }

    // In-place delete: on success the announcement is gone, so reset the form to
    // its empty create state in place instead of reloading. The no-reload win is
    // the failure path (inline toast, modal stays open).
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
                onSuccess: function () {
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
