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

(function () {
    'use strict';
    if (!window.krtFetch) {
        return;
    }
    /** @type {KrtI18nDict} */
    const i18n = window.krtDeletionRequestsI18n || {};

    /**
     * Substitutes {handle} in a template, inserting the handle literally even when it contains `$`
     * replacement patterns.
     *
     * @param {string | undefined} template the localised sentence carrying {handle}
     * @param {string | null | undefined} handle the member's display name, verbatim
     * @returns {string} the filled sentence
     */
    function fillHandle(template, handle) {
        return String(template || '').replace('{handle}', () => String(handle ?? ''));
    }
    const host = document.getElementById('deletionRequestsHost');
    if (!host) {
        return;
    }

    /**
     * The request the open dialog is about; kept here because its row may be swapped out meanwhile.
     * @type {{ id: string, handle: string, version: number | null } | null}
     */
    let current = null;

    /** Reloads the queue table in place. */
    function refresh() {
        return window.krtFetch.swap({
            url: '/admin/deletion-requests',
            container: '#deletionRequestsHost',
            fragmentValue: 'rows',
            errorMessage: i18n.error,
        });
    }

    /**
     * Shows a modal by id through the shared window.krtModal contract.
     * @param {string} id
     */
    function openModal(id) {
        window.krtModal.open(id);
    }

    /** @param {string} id */
    function closeModal(id) {
        window.krtModal.close(id);
    }

    /**
     * Clears a dialog's inline field error.
     * @param {string} modalId
     */
    function clearErrors(modalId) {
        const modal = document.getElementById(modalId);
        if (!modal) {
            return;
        }
        modal.querySelectorAll('.bank-field-error').forEach(function (slot) {
            slot.textContent = '';
        });
    }

    host.addEventListener('click', function (event) {
        const target = event.target;
        if (!(target instanceof Element)) {
            return;
        }
        /** @type {HTMLElement | null} */
        const button = target.closest('button[data-action]');
        if (!button) {
            return;
        }
        /** @type {HTMLElement | null} */
        const row = button.closest('tr[data-id]');
        if (!row) {
            return;
        }
        const version = row.getAttribute('data-version');
        const selected = {
            id: row.getAttribute('data-id') || '',
            handle: row.getAttribute('data-handle') || '',
            version: version === null || version === '' ? null : Number(version),
        };
        current = selected;
        const action = button.getAttribute('data-action');
        if (action === 'decline') {
            /** @type {HTMLTextAreaElement | null} */
            const note = document.querySelector('#decline-note');
            if (note) {
                note.value = '';
            }
            clearErrors('decline-modal');
            openModal('decline-modal');
        } else if (action === 'execute') {
            /** @type {HTMLInputElement | null} */
            const erase = document.querySelector('#execute-erase-history');
            if (erase) {
                erase.checked = false;
            }
            const memberSlot = document.querySelector('[data-execute-member]');
            if (memberSlot) {
                memberSlot.textContent = fillHandle(i18n.confirmMember, selected.handle);
            }
            clearErrors('execute-modal');
            openModal('execute-modal');
        }
    });

    const declineSubmit = document.getElementById('decline-submit');
    if (declineSubmit) {
        declineSubmit.addEventListener('click', function () {
            if (!current) {
                return;
            }
            /** @type {HTMLTextAreaElement | null} */
            const note = document.querySelector('#decline-note');
            const text = note ? note.value.trim() : '';
            const slot = document.querySelector('#decline-modal [data-error-for="note"]');
            if (!text) {
                if (slot) {
                    slot.textContent = i18n.noteRequired;
                }
                return;
            }
            if (slot) {
                slot.textContent = '';
            }
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/deletion-requests/' + current.id + '/decline',
                payload: { note: text, version: current.version },
                successMessage: i18n.declined,
                errorMessage: i18n.error,
                onSuccess() {
                    closeModal('decline-modal');
                    return refresh();
                },
            });
        });
    }

    const executeSubmit = document.getElementById('execute-submit');
    if (executeSubmit) {
        executeSubmit.addEventListener('click', function () {
            if (!current) {
                return;
            }
            /** @type {HTMLInputElement | null} */
            const erase = document.querySelector('#execute-erase-history');
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/deletion-requests/' + current.id + '/execute',
                payload: {
                    grantHistoryErasure: erase ? erase.checked : false,
                    version: current.version,
                },
                successMessage: i18n.executed,
                errorMessage: i18n.error,
                onSuccess() {
                    closeModal('execute-modal');
                    return refresh();
                },
            });
        });
    }
})();
