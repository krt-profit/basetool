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
    'use strict';

    const SERIALIZE_KEY = 'sk-roster';

    /**
     * Re-renders the container a roster names in its `data-refresh-*` attributes.
     *
     * @param {Element} roster the `[data-sk-roster]` table
     * @returns {Promise<boolean> | undefined} the swap, or nothing when the roster names none
     */
    function refresh(roster) {
        const url = roster.getAttribute('data-refresh-url');
        const container = roster.getAttribute('data-refresh-container');
        const fragment = roster.getAttribute('data-refresh-fragment');
        if (!url || !container || !fragment || !window.krtFetch) {
            return undefined;
        }
        return window.krtFetch.swap({
            url,
            container,
            fragmentValue: fragment,
            history: false,
        });
    }

    /**
     * The roster row of one member as it is rendered now, which a fragment swap may have replaced.
     *
     * @param {string} skId the Spezialkommando id
     * @param {string} userId the member's user id
     * @returns {Element | null} the row, or null when it is gone
     */
    function currentRow(skId, userId) {
        return document.querySelector(
            '[data-sk-roster][data-sk-id="' +
                CSS.escape(skId) +
                '"] tr[data-user-id="' +
                CSS.escape(userId) +
                '"]',
        );
    }

    /**
     * Asks for confirmation, then runs the action; runs it directly when no dialog is available.
     *
     * @param {string | null} title the dialog title
     * @param {string} message the question
     * @param {() => void} run the confirmed action
     */
    function confirmThen(title, message, run) {
        if (typeof window.showKrtConfirm !== 'function') {
            run();
            return;
        }
        window
            .showKrtConfirm(title, message, MEMBER_MSG.confirm, MEMBER_MSG.cancel)
            .then(function (ok) {
                if (ok) {
                    run();
                }
            });
    }

    /**
     * The identity of the row a control sits in.
     *
     * @param {Element} control a control inside a roster row
     * @returns {{ roster: Element, row: Element, skId: string, userId: string } | null} the parts
     */
    function rowOf(control) {
        const roster = control.closest('[data-sk-roster]');
        const row = control.closest('tr[data-user-id]');
        if (!roster || !row) {
            return null;
        }
        return {
            roster,
            row,
            skId: roster.getAttribute('data-sk-id') || '',
            userId: row.getAttribute('data-user-id') || '',
        };
    }

    /**
     * Toggles one role flag and saves both flags at once, reading the row's state when the write
     * runs so queued toggles build on each other.
     *
     * @param {HTMLElement} button the flag button
     */
    function toggleFlag(button) {
        const parts = rowOf(button);
        const flag = button.getAttribute('data-sk-flag');
        const url = parts ? parts.row.getAttribute('data-flags-url') : null;
        if (!parts || !flag || !url || !window.krtFetch) {
            return;
        }
        button.setAttribute('aria-busy', 'true');
        window.krtFetch
            .serialize(SERIALIZE_KEY, function () {
                const row = currentRow(parts.skId, parts.userId) || parts.row;
                let logistician = row.getAttribute('data-logistician') === 'true';
                let missionManager = row.getAttribute('data-mission-manager') === 'true';
                if (flag === 'isLogistician') {
                    logistician = !logistician;
                } else {
                    missionManager = !missionManager;
                }
                const params = new URLSearchParams();
                params.set('isLogistician', String(logistician));
                params.set('isMissionManager', String(missionManager));
                params.set('version', row.getAttribute('data-version') || '0');
                return window.krtFetch.submitForm({
                    url,
                    method: 'POST',
                    formData: params,
                    successMessage: MEMBER_MSG.saved,
                    errorMessage: MEMBER_MSG.error,
                    conflict: MEMBER_CONFLICT,
                    onSuccess() {
                        return refresh(parts.roster);
                    },
                });
            })
            .finally(function () {
                button.removeAttribute('aria-busy');
            });
    }

    /**
     * Appoints or removes the member as the Spezialkommando's lead after confirmation, through
     * the endpoint the roster's lead mode names.
     *
     * @param {HTMLElement} item the menu item
     */
    function toggleLead(item) {
        const parts = rowOf(item);
        const url = parts ? parts.row.getAttribute('data-lead-url') : null;
        if (!parts || !url || !window.krtFetch) {
            return;
        }
        const lead = parts.row.getAttribute('data-lead') === 'true';
        const mode = parts.roster.getAttribute('data-lead-mode');
        const name = parts.row.getAttribute('data-user-name');
        confirmThen(name, lead ? MEMBER_MSG.confirmDemote : MEMBER_MSG.confirmPromote, function () {
            window.krtFetch.serialize(SERIALIZE_KEY, function () {
                const row = currentRow(parts.skId, parts.userId) || parts.row;
                const version = row.getAttribute('data-version') || '0';
                const onSuccess = function () {
                    return refresh(parts.roster);
                };
                if (mode === 'admin') {
                    const params = new URLSearchParams();
                    params.set('isLead', String(!lead));
                    params.set('version', version);
                    return window.krtFetch.submitForm({
                        url,
                        method: 'POST',
                        formData: params,
                        successMessage: MEMBER_MSG.saved,
                        errorMessage: MEMBER_MSG.error,
                        conflict: MEMBER_CONFLICT,
                        onSuccess,
                    });
                }
                return window.krtFetch.write({
                    url,
                    method: 'PATCH',
                    payload: { isLead: !lead, version: Number(version) },
                    successMessage: MEMBER_MSG.saved,
                    errorMessage: MEMBER_MSG.error,
                    conflict: MEMBER_CONFLICT,
                    onSuccess,
                });
            });
        });
    }

    /**
     * Removes the member from the Spezialkommando after confirmation.
     *
     * @param {HTMLElement} item the menu item
     */
    function removeMember(item) {
        const parts = rowOf(item);
        const url = parts ? parts.row.getAttribute('data-remove-url') : null;
        if (!parts || !url || !window.krtFetch) {
            return;
        }
        confirmThen(
            parts.row.getAttribute('data-user-name'),
            MEMBER_MSG.confirmRemove,
            function () {
                window.krtFetch.serialize(SERIALIZE_KEY, function () {
                    return window.krtFetch.submitForm({
                        url,
                        method: 'POST',
                        formData: new URLSearchParams(),
                        successMessage: MEMBER_MSG.deleted,
                        errorMessage: MEMBER_MSG.error,
                        conflict: MEMBER_CONFLICT,
                        onSuccess() {
                            return refresh(parts.roster);
                        },
                    });
                });
            },
        );
    }

    document.addEventListener('click', function (e) {
        const target = e.target instanceof Element ? e.target : null;
        if (!target || !target.closest('[data-sk-roster]')) {
            return;
        }
        const flag = /** @type {HTMLElement | null} */ (target.closest('.unit-flag'));
        if (flag) {
            e.preventDefault();
            if (flag.getAttribute('aria-busy') !== 'true') {
                toggleFlag(flag);
            }
            return;
        }
        const item = /** @type {HTMLElement | null} */ (target.closest('[data-sk-action]'));
        if (!item) {
            return;
        }
        e.preventDefault();
        const action = item.getAttribute('data-sk-action');
        if (action === 'toggle-lead') {
            toggleLead(item);
        } else if (action === 'remove') {
            removeMember(item);
        }
    });

    const membersBox = document.getElementById('members-box');
    const scId = membersBox ? membersBox.getAttribute('data-sc-id') : '';

    /** Re-renders the member page's roster fragment. */
    function reswapMembers() {
        if (!window.krtFetch || !scId) {
            return undefined;
        }
        return window.krtFetch.swap({
            url: '/organisation/special-commands/' + encodeURIComponent(scId) + '?fragment=members',
            container: '#members-results',
            fragmentValue: 'members',
            history: false,
        });
    }

    /** Writes the rendered row count into the member page's tab counter. */
    function updateCount() {
        const count = document.getElementById('sk-members-count');
        const results = document.getElementById('members-results');
        if (count && results) {
            count.textContent = String(
                results.querySelectorAll('[data-testid="sk-member-row"]').length,
            );
        }
    }

    document.addEventListener('krt:swapped', function (e) {
        const container = e.detail ? e.detail.container : null;
        if (container && container.id === 'members-results') {
            updateCount();
        }
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
                window.krtFetch.submitForm({
                    form: addForm,
                    successMessage: MEMBER_MSG.saved,
                    errorMessage: MEMBER_MSG.error,
                    conflict: MEMBER_CONFLICT,
                    serialize: SERIALIZE_KEY,
                    onSuccess() {
                        window.krtModal.close(modal);
                        addForm.reset();
                        return reswapMembers();
                    },
                });
            });
        }
    }
})();
