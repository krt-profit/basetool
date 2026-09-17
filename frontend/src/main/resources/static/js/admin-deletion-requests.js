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

/*
 * Admin queue for the members' Art. 17 erasure requests (REQ-SEC-061, ADR-0181). One IIFE, one
 * global (window.krtDeletionRequestsI18n), classic non-module script — ADR-0069.
 *
 * Both decisions go through krtFetch and are followed by a server-rendered fragment swap of the
 * table (REQ-FE-001): no reload, and no client-side opinion about what the queue now contains.
 *
 * The row actions are bound by DELEGATION on the host, not per button. The table is replaced
 * wholesale by every swap, so a per-button listener would survive exactly one decision — the
 * classic defect this pattern exists to avoid.
 */

(function () {
    'use strict';
    if (!window.krtFetch) {
        return;
    }
    // The dictionary is injected by the page bootstrap and is optional on `window`. Defaulted to an
    // empty dict rather than guarded: TypeScript does not carry the outer narrowing into the
    // closures below, and seven `i18n &&` guards would say nothing a missing label does not already
    // say. A label that fails to arrive renders as `undefined` in a toast, which is a visible
    // bootstrap bug rather than a broken page.
    /** @type {KrtI18nDict} */
    const i18n = window.krtDeletionRequestsI18n || {};

    /**
     * Substitutes {handle} in a template without treating the handle as a replacement pattern.
     *
     * String.prototype.replace reads $ sequences in the REPLACEMENT as patterns: '$&' inserts the
     * match, '$`' everything before it, "$'" everything after. A member's displayName is
     * self-service with only a length limit on it, so '$&' as a display name made this dialog read
     * "Mitglied: {handle}" and "$'" made it read "Mitglied: " -- an admin confirming a permanent,
     * irreversible deletion against a dialog naming the wrong account, or naming nobody. The
     * function form of the second argument is never scanned for patterns.
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
     * The request the open dialog is about. Held here rather than on the dialog, because the row it
     * came from is replaced by the next swap and its id would go with it.
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
     * Shows a modal by id, using the same display contract as the shared close handler.
     * @param {string} id
     */
    function openModal(id) {
        const modal = document.getElementById(id);
        if (modal) {
            modal.style.removeProperty('display');
            modal.classList.add('krtm-modal-open');
            modal.classList.remove('krtm-hidden');
        }
    }

    /** @param {string} id */
    function closeModal(id) {
        const modal = document.getElementById(id);
        if (modal) {
            modal.style.removeProperty('display');
            modal.classList.remove('krtm-modal-open');
            modal.classList.add('krtm-hidden');
        }
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
            // Echoed back on both writes (REQ-FE-003). Null rather than 0 when the attribute is
            // absent: the backend reads null as "decide whatever is there", and 0 would be a claim
            // about the row's state that the page is not in a position to make.
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
                // Never pre-ticked, even when the member asked: granting the wish is a deliberate
                // act, and a pre-ticked box turns it into the default.
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
                // Art. 12(4): a refusal the requester cannot be told the reason for is not one we
                // are allowed to record. Checked here, in the proxy and in a database CHECK.
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
                onSuccess: function () {
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
                    // The execute path is irreversible and @Version cannot protect it on its own:
                    // it never writes the request row, it deletes the account and lets the cascade
                    // take it. The echo plus a pessimistic read is what stops an admin acting on a
                    // queue page the member has already withdrawn from.
                    version: current.version,
                },
                successMessage: i18n.executed,
                errorMessage: i18n.error,
                onSuccess: function () {
                    closeModal('execute-modal');
                    return refresh();
                },
            });
        });
    }
})();
