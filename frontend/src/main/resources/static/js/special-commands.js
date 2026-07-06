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
 * Special-commands admin page module (/admin/special-commands), extracted verbatim from the former
 * inline script of admin/special-commands.html (ADR-0069, follow-up to #924).
 *
 * The create/edit modal, the soft-deactivate delete-confirm modal (#587) and the include-inactive
 * filter, plus the in-place CRUD (#582): create/edit/delete/activate save via the krtFetch AJAX twins
 * and re-swap the #sc-results fragment, so the page never reloads; the classic POST->redirect forms
 * stay the no-JS fallback. Edit/Delete/activate buttons live inside the swap target, so they are
 * document-delegated to survive the include-inactive swap. The whole page logic is one IIFE.
 *
 * The Thymeleaf-interpolated toast + conflict strings (SC_MSG, SC_CONFLICT) stay inline in the page
 * bootstrap this module reads.
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
        title.textContent =
            document.documentElement.lang === 'en'
                ? 'New Special Command'
                : 'Neues Spezialkommando';
        form.action = '/admin/special-commands';
        versionInput.value = '0';
        nameInput.value = '';
        shorthandInput.value = '';
        descInput.value = '';
        modal.style.display = 'flex';
    }

    function openEdit(btn) {
        title.textContent =
            document.documentElement.lang === 'en'
                ? 'Edit Special Command'
                : 'Spezialkommando bearbeiten';
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        versionInput.value = btn.getAttribute('data-version') || '0';
        nameInput.value = btn.getAttribute('data-name') || '';
        shorthandInput.value = btn.getAttribute('data-shorthand') || '';
        descInput.value = btn.getAttribute('data-desc') || '';
        modal.style.display = 'flex';
    }

    function closeModal() {
        modal.style.display = 'none';
    }

    // --- Delete (soft-deactivate) confirmation (#587) ---
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
        deleteModal.style.display = 'flex';
    }

    function closeDelete() {
        if (deleteModal) deleteModal.style.display = 'none';
    }

    // The Edit and Delete buttons live INSIDE the AJAX swap target (#sc-results), so they are bound
    // via ONE document-delegated click listener (e.target.closest) that survives the include-inactive
    // filter swap with zero re-init. The add button + modal close live outside the swap (bound once).
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

    // Include-inactive toggle -> in-place swap of the SK list (REQ-FE-002), with the URL kept in
    // sync so a refresh re-renders the same filter. Edit/Delete are document-delegated above, so the
    // swapped-in rows need no re-init.
    const includeInactive = document.getElementById('includeInactive');
    if (includeInactive && window.krtFetch) {
        includeInactive.addEventListener('change', function () {
            const url =
                '/admin/special-commands' +
                (includeInactive.checked ? '?includeInactive=true' : '');
            window.krtFetch.swap({ url: url, container: '#sc-results', history: true });
        });
    }

    // ---- In-place CRUD (#582): create/edit/delete/activate save via the AJAX twins and re-swap
    // the SK-list fragment, so the page never reloads. -------------------------------------------

    function reswapScResults() {
        if (!window.krtFetch) return;
        const url =
            '/admin/special-commands' +
            (includeInactive && includeInactive.checked ? '?includeInactive=true' : '');
        window.krtFetch.swap({ url: url, container: '#sc-results', history: false });
    }

    // FormData POST to an SK AJAX twin (keeps the @ModelAttribute / @RequestParam binding) with
    // X-Requested-With + CSRF + retry-once-on-403. On success runs onSuccess and re-swaps the list;
    // on failure delegates to krtFetch.handleProblem for the conflict UX.
    // Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF header
    // (no Content-Type so the browser sets the multipart boundary), the bare-403 refresh-and-retry,
    // X-Reauthenticate, the success toast and the !ok handleProblem (duplicate-name / in-use toast or
    // OPTIMISTIC_LOCK reload-confirm). This helper keeps only its page behaviour: run onSuccess and
    // re-swap the SK list in place. Callers guard with if (!window.krtFetch) form.submit() so the
    // classic POST->redirect stays the no-JS fallback.
    function scWrite(theForm, successMessage, onSuccess) {
        window.krtFetch.submitForm({
            form: theForm,
            successMessage: successMessage,
            errorMessage: SC_MSG.error,
            conflict: SC_CONFLICT,
            onSuccess: function () {
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
    // Activate forms live inside the swappable #sc-results, so they are document-delegated.
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
