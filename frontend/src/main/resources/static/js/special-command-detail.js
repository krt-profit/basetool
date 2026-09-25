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

/* global MEMBER_MSG, MEMBER_CONFLICT */

(function () {
    const membersBox = document.getElementById('members-box');
    const scId = membersBox ? membersBox.getAttribute('data-sc-id') : '';

    function reswapMembers() {
        if (!window.krtFetch || !scId) {
            return;
        }
        window.krtFetch.swap({
            url: '/organisation/special-commands/' + encodeURIComponent(scId) + '?fragment=members',
            container: '#members-results',
            fragmentValue: 'members',
            history: false,
        });
    }

    function memberWrite(theForm, successMessage, onSuccess) {
        window.krtFetch.submitForm({
            form: theForm,
            successMessage,
            errorMessage: MEMBER_MSG.error,
            conflict: MEMBER_CONFLICT,
            onSuccess() {
                if (typeof onSuccess === 'function') {
                    onSuccess();
                }
                reswapMembers();
            },
        });
    }

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

    const modal = document.getElementById('add-member-modal');
    const openBtn = document.getElementById('add-member-btn');
    const closeBtn = document.querySelector('#add-member-modal .close-add-member-modal');
    if (modal && openBtn) {
        openBtn.addEventListener('click', function () {
            window.krtModal.open(modal);
        });
        if (closeBtn) {
            closeBtn.addEventListener('click', function () {
                window.krtModal.close(modal);
            });
        }
        modal.addEventListener('click', function (e) {
            if (e.target === modal) {
                window.krtModal.close(modal);
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
                    window.krtModal.close(modal);
                    addForm.reset();
                });
            });
        }
    }
})();
