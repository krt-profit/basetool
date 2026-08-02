// @ts-check
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
 * Special-command-detail admin page module (/admin/special-commands/{id}), extracted verbatim
 * from the former inline script of admin/special-command-detail.html (ADR-0069, follow-up to #924).
 *
 * In-place member roster management: the flags/lead/remove forms (document-delegated inside the
 * swappable roster) and the add-member modal all save via the krtFetch AJAX twins and re-swap the
 * #members-results fragment; the classic POST->redirect forms stay the no-JS fallback. The whole
 * page logic is one IIFE.
 *
 * The Thymeleaf-interpolated toast + conflict strings (MEMBER_MSG, MEMBER_CONFLICT) stay inline in
 * the page bootstrap this module reads.
 */

/* global MEMBER_MSG, MEMBER_CONFLICT */

(function () {
    const membersBox = document.getElementById('members-box');
    const scId = membersBox ? membersBox.getAttribute('data-sc-id') : '';

    // Re-renders the member roster fragment, refreshing role/lead badges and the fresh @Version on
    // every form so the next member action does not 409.
    function reswapMembers() {
        if (!window.krtFetch || !scId) {
            return;
        }
        window.krtFetch.swap({
            url: '/admin/special-commands/' + encodeURIComponent(scId) + '?fragment=members',
            container: '#members-results',
            fragmentValue: 'members',
            history: false,
        });
    }

    // FormData POST to a member AJAX twin (keeps the @ModelAttribute / @RequestParam binding) with
    // X-Requested-With + CSRF + retry-once-on-403. On success re-swaps the roster; on failure
    // delegates to krtFetch.handleProblem (already-member toast / OPTIMISTIC_LOCK reload-confirm).
    // Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF header
    // (no Content-Type so the browser sets the multipart boundary), the bare-403 refresh-and-retry,
    // X-Reauthenticate, the success toast and the !ok handleProblem (already-member toast or
    // OPTIMISTIC_LOCK reload-confirm). This helper keeps only its page behaviour: run onSuccess and
    // re-swap the roster in place. Callers guard with if (!window.krtFetch) form.submit() so the
    // classic POST->redirect stays the no-JS fallback.
    function memberWrite(theForm, successMessage, onSuccess) {
        window.krtFetch.submitForm({
            form: theForm,
            successMessage: successMessage,
            errorMessage: MEMBER_MSG.error,
            conflict: MEMBER_CONFLICT,
            onSuccess: function () {
                if (typeof onSuccess === 'function') {
                    onSuccess();
                }
                reswapMembers();
            },
        });
    }

    // The flags / lead / remove forms live inside the swappable roster fragment, so they are
    // document-delegated (survive a re-swap). Remove uses the delete message, the rest "saved".
    document.addEventListener('submit', function (e) {
        const memberForm = /** @type {HTMLFormElement | null} */ (
            /** @type {Element} */ (e.target).closest('#members-results form')
        );
        if (!memberForm) {
            return;
        }
        e.preventDefault();
        if (!window.krtFetch) {
            memberForm.submit();
            return;
        }
        const isRemove = (memberForm.getAttribute('action') || '').indexOf('/delete') !== -1;
        memberWrite(memberForm, isRemove ? MEMBER_MSG.deleted : MEMBER_MSG.saved, null);
    });

    // Add-member modal (outside the fragment): open/close + in-place add.
    const modal = document.getElementById('add-member-modal');
    const openBtn = document.getElementById('add-member-btn');
    const closeBtn = document.querySelector('#add-member-modal .close-add-member-modal');
    if (modal && openBtn) {
        openBtn.addEventListener('click', function () {
            modal.style.display = 'flex';
        });
        if (closeBtn) {
            closeBtn.addEventListener('click', function () {
                modal.style.display = 'none';
            });
        }
        modal.addEventListener('click', function (e) {
            if (e.target === modal) {
                modal.style.display = 'none';
            }
        });
        const addForm = modal.querySelector('form');
        if (addForm) {
            addForm.addEventListener('submit', function (e) {
                e.preventDefault();
                if (!window.krtFetch) {
                    addForm.submit();
                    return;
                }
                memberWrite(addForm, MEMBER_MSG.saved, function () {
                    modal.style.display = 'none';
                    addForm.reset();
                });
            });
        }
    }
})();
