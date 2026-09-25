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

/* global SC_MSG, SC_CONFLICT */

(function () {
    const modal = document.getElementById('specialcommand-modal');
    const form = document.getElementById('sc-form');
    const title = document.getElementById('sc-modal-title');
    const versionInput = document.getElementById('edit-sc-version');
    const nameInput = document.getElementById('sc-name');
    const shorthandInput = document.getElementById('sc-shorthand');
    const descInput = document.getElementById('sc-desc');

    function openCreate() {
        title.textContent = SC_MSG.createTitle;
        form.action = '/admin/special-commands';
        versionInput.value = '0';
        nameInput.value = '';
        shorthandInput.value = '';
        descInput.value = '';
        window.krtModal.open(modal);
    }

    function openEdit(btn) {
        title.textContent = SC_MSG.editTitle;
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        versionInput.value = btn.getAttribute('data-version') || '0';
        nameInput.value = btn.getAttribute('data-name') || '';
        shorthandInput.value = btn.getAttribute('data-shorthand') || '';
        descInput.value = btn.getAttribute('data-desc') || '';
        window.krtModal.open(modal);
    }

    function closeModal() {
        window.krtModal.close(modal);
    }

    const deleteModal = document.getElementById('sc-delete-modal');
    const deleteForm = document.getElementById('sc-delete-form');
    const deleteNameEl = document.getElementById('sc-delete-name');

    function openDelete(btn) {
        if (!deleteModal || !deleteForm) return;
        deleteForm.action = window.safeSameOriginUrl(
            btn.getAttribute('data-action'),
            deleteForm.action,
        );
        if (deleteNameEl) deleteNameEl.textContent = btn.getAttribute('data-name') || '';
        window.krtModal.open(deleteModal);
    }

    function closeDelete() {
        if (deleteModal) window.krtModal.close(deleteModal);
    }

    document.getElementById('add-sc-btn').addEventListener('click', openCreate);
    document.addEventListener('click', function (e) {
        const editBtn = e.target.closest('.edit-sc-btn');
        if (editBtn) {
            openEdit(editBtn);
            return;
        }
        const deleteBtn = e.target.closest('.delete-btn');
        if (deleteBtn) openDelete(deleteBtn);
    });
    document
        .querySelector('#specialcommand-modal .close-sc-modal')
        .addEventListener('click', closeModal);
    document
        .querySelectorAll('#sc-delete-modal .close-sc-delete-modal')
        .forEach((btn) => btn.addEventListener('click', closeDelete));
    modal.addEventListener('click', (e) => {
        if (e.target === modal) closeModal();
    });
    if (deleteModal) {
        deleteModal.addEventListener('click', (e) => {
            if (e.target === deleteModal) closeDelete();
        });
    }

    const SC_FILTER_PREF_KEY = 'admin_special_commands_filter';
    const includeInactive = document.getElementById('includeInactive');
    if (includeInactive && window.krtFetch) {
        const persistScFilter = function () {
            try {
                localStorage.setItem(
                    SC_FILTER_PREF_KEY,
                    JSON.stringify({ includeInactive: includeInactive.checked }),
                );
            } catch (_e) {}
        };
        includeInactive.addEventListener('change', function () {
            persistScFilter();
            const url =
                '/admin/special-commands' +
                (includeInactive.checked ? '?includeInactive=true' : '');
            window.krtFetch.swap({ url, container: '#sc-results', history: true });
        });

        if (/[?&]includeInactive=/.test(window.location.search)) {
            persistScFilter();
        } else {
            let saved;
            try {
                saved = JSON.parse(localStorage.getItem(SC_FILTER_PREF_KEY));
            } catch (_e) {
                saved = null;
            }
            if (saved && typeof saved === 'object') {
                const want = saved.includeInactive === true;
                if (want !== includeInactive.checked) {
                    includeInactive.checked = want;
                    includeInactive.dispatchEvent(new Event('change'));
                }
            }
        }
    }

    function reswapScResults() {
        if (!window.krtFetch) return;
        const url =
            '/admin/special-commands' +
            (includeInactive && includeInactive.checked ? '?includeInactive=true' : '');
        window.krtFetch.swap({ url, container: '#sc-results', history: false });
    }

    function scWrite(theForm, successMessage, onSuccess) {
        window.krtFetch.submitForm({
            form: theForm,
            successMessage,
            errorMessage: SC_MSG.error,
            conflict: SC_CONFLICT,
            onSuccess() {
                if (typeof onSuccess === 'function') {
                    onSuccess();
                }
                reswapScResults();
            },
        });
    }

    if (form) {
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!window.krtFetch) {
                form.submit();
                return;
            }
            scWrite(form, SC_MSG.saved, closeModal);
        });
    }
    if (deleteForm) {
        deleteForm.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!window.krtFetch) {
                deleteForm.submit();
                return;
            }
            scWrite(deleteForm, SC_MSG.deleted, closeDelete);
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
        scWrite(actForm, SC_MSG.saved, null);
    });
})();
