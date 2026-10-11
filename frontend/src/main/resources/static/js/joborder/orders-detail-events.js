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

/* global ORDER_CONFLICT, I18N_ADDED, I18N_REMOVED, I18N_NOTE_SAVED, I18N_NOTE_DELETED, I18N_ADD_ERROR, I18N_REMOVE_ERROR, I18N_NOTE_ERROR, I18N_NOTE_CONFLICT, I18N_NOTE_FORBIDDEN, I18N_NOTE_FOR, showFrontendErrorToast, showFrontendSuccessToast, updateStatus, _toggleBlueprintCounting, toggleInventory, unlinkInventoryItem, openClaimCreate, openClaimEdit, submitClaim, withdrawClaimAction, openHandoverModal, openProductionModal, bookProduction, _prodReconcile, _prodSyncPersonalForOwner, _prodRefreshOrgUnitPicker, _prodSyncPersonalAllocate, _odToggleDemandRow, downloadHandoverReport, downloadItemHandoverReport, cancelStatusChange, confirmStatusChange, addHandoverItemRow, previewHandoverReport, updateHandoverIndexes, _odControlById, _odElementById */

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('change', 'od-update-status', (el) => {
        updateStatus(/** @type {HTMLSelectElement} */ (el));
    });
    window.krtEvents.on('change', 'od-toggle-bp-counting', (el) => {
        _toggleBlueprintCounting(/** @type {HTMLInputElement} */ (el));
    });
    window.krtEvents.on('click', 'od-toggle-inventory', (el, ev) => {
        const target = /** @type {Element | null} */ (ev && ev.target);
        if (
            target &&
            typeof target.closest === 'function' &&
            target.closest('[data-claim-control], [data-trigger="od-unlink-inventory"]')
        ) {
            return;
        }
        toggleInventory(el);
    });
    window.krtEvents.on('click', 'od-unlink-inventory', (el) => {
        unlinkInventoryItem(el);
    });
    window.krtEvents.on('click', 'claim-add', (el) => {
        openClaimCreate(el);
    });
    window.krtEvents.on('click', 'claim-edit', (el) => {
        openClaimEdit(el);
    });
    window.krtEvents.on('click', 'claim-submit', () => {
        submitClaim();
    });
    window.krtEvents.on('click', 'claim-withdraw', () => {
        withdrawClaimAction();
    });
    window.krtEvents.on('click', 'od-open-handover', openHandoverModal);
    window.krtEvents.on('click', 'od-open-production', openProductionModal);
    window.krtEvents.on('click', 'od-book-production', bookProduction);
    window.krtEvents.on('change', 'od-production-bookin-changed', () => {
        _prodReconcile();
    });
    window.krtEvents.on('change', 'od-production-owner-changed', () => {
        _prodSyncPersonalForOwner();
        _prodRefreshOrgUnitPicker();
    });
    window.krtEvents.on('change', 'od-production-personal-toggle', () => {
        _prodSyncPersonalAllocate();
    });
    window.krtEvents.on('click', 'od-toggle-demand', _odToggleDemandRow);
    window.krtEvents.on('click', 'od-download-report', (el) => {
        downloadHandoverReport(el);
    });
    window.krtEvents.on('click', 'od-download-item-report', (el) => {
        downloadItemHandoverReport(el);
    });
    window.krtEvents.on('click', 'od-cancel-status', cancelStatusChange);
    window.krtEvents.on('click', 'od-confirm-status', confirmStatusChange);
    window.krtEvents.on('click', 'od-confirm-remove-material', () => {
        if (typeof window.confirmRemoveMaterial === 'function') window.confirmRemoveMaterial();
    });
    window.krtEvents.on('click', 'od-add-handover-item', addHandoverItemRow);
    window.krtEvents.on('click', 'od-preview-handover', (el) => {
        previewHandoverReport(el);
    });
    window.krtEvents.on('click', 'od-remove-handover-row', (el) => {
        const row = el.parentElement && el.parentElement.parentElement;
        if (row) row.remove();
        updateHandoverIndexes();
    });
    window.krtEvents.on('submit', 'od-disable-submit', (el) => {
        const btn = /** @type {HTMLButtonElement | null} */ (
            el.querySelector('button[type=submit]')
        );
        if (btn) btn.disabled = true;
    });
}

(function () {
    function oaOrderId() {
        const sec = document.getElementById('assignees-section');
        return sec ? sec.getAttribute('data-order-id') : null;
    }

    const OA_CONFLICT = {
        title: ORDER_CONFLICT.title,
        reloadLabel: ORDER_CONFLICT.reload,
        reloadQuestion: ORDER_CONFLICT.reloadQuestion,
        dismissLabel: ORDER_CONFLICT.dismiss,
        reloadDetailFallback: I18N_NOTE_CONFLICT,
    };

    /**
     * Sends one assignee mutation through krtFetch (REQ-FE-002) and swaps the returned
     * #assignees-section fragment in place.
     *
     * @param {string} method the HTTP method
     * @param {string} url the endpoint
     * @param {{ json?: object, form?: URLSearchParams, successMsg?: string, errorMsg?: string }} [opts]
     *     `json` is sent as the JSON body, `form` as an urlencoded body; neither sends no body
     * @returns {Promise<boolean>} true when the mutation succeeded and the section was re-rendered
     */
    async function oaSend(method, url, opts) {
        opts ||= {};
        if (!window.krtFetch) return false;
        const errorMsg = opts.errorMsg || I18N_NOTE_ERROR;
        const common = {
            method,
            url,
            accept: 'text/html',
            toast: false,
            errorMessage: errorMsg,
            onSuccess(html) {
                const sec = document.getElementById('assignees-section');
                if (sec && typeof html === 'string') {
                    window.krtFetch.replaceWithTrustedHtml(sec, html);
                    document.dispatchEvent(
                        new CustomEvent('krt:swapped', {
                            detail: { container: document.getElementById('assignees-section') },
                        }),
                    );
                }
                if (opts.successMsg) showFrontendSuccessToast(opts.successMsg);
                if (window.krtNotifyOrderChanged) {
                    window.krtNotifyOrderChanged(['assignees']);
                }
            },
            onError(status, _body, response) {
                if (status === 409) {
                    window.krtFetch.handleProblem(
                        response,
                        { code: 'OPTIMISTIC_LOCK', detail: I18N_NOTE_CONFLICT },
                        { conflict: OA_CONFLICT },
                    );
                } else if (status === 403) {
                    showFrontendErrorToast(I18N_NOTE_FORBIDDEN);
                } else {
                    showFrontendErrorToast(errorMsg);
                }
                return true;
            },
        };
        const result =
            opts.form !== undefined
                ? await window.krtFetch.submitForm(Object.assign(common, { formData: opts.form }))
                : await window.krtFetch.write(Object.assign(common, { payload: opts.json }));
        return result.ok;
    }

    /** Shows the length of the note text next to the note field. */
    function oaUpdateCounter() {
        const ta = /** @type {HTMLTextAreaElement | null} */ (
            document.getElementById('assignee-note-text')
        );
        const counter = document.getElementById('assignee-note-counter');
        if (ta && counter) counter.textContent = `${ta.value.length} / 500`;
    }

    /**
     * Adds a user as assignee of the order.
     *
     * @param {string | null} userId the user to add
     */
    function oaAdd(userId) {
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        oaSend('POST', `/orders/${orderId}/assignees`, {
            form: new URLSearchParams({ userId }),
            successMsg: I18N_ADDED,
            errorMsg: I18N_ADD_ERROR,
        });
    }

    /**
     * Removes an assignee from the order.
     *
     * @param {HTMLElement} el the remove control carrying the user id
     */
    function oaRemove(el) {
        const userId = el.getAttribute('data-user-id');
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        oaSend('DELETE', `/orders/${orderId}/assignees/${userId}`, {
            successMsg: I18N_REMOVED,
            errorMsg: I18N_REMOVE_ERROR,
        });
    }

    /**
     * Opens the note modal for an assignee.
     *
     * @param {HTMLElement} el the edit control carrying the user, note and version
     */
    function oaOpenNote(el) {
        const userId = el.getAttribute('data-user-id');
        const userName = el.getAttribute('data-user-name') || '';
        const note = el.getAttribute('data-note') || '';
        const version = el.getAttribute('data-version') || '';
        _odControlById('assignee-note-user-id').value = userId || '';
        _odControlById('assignee-note-version').value = version;
        const ta = /** @type {HTMLTextAreaElement} */ (
            document.getElementById('assignee-note-text')
        );
        ta.value = note;
        _odElementById('assignee-note-for').textContent = I18N_NOTE_FOR.replace('{0}', userName);
        oaUpdateCounter();
        window.krtModal.open(document.getElementById('assignee-note-modal'));
        ta.focus();
    }

    /** Saves the note typed in the note modal. */
    function oaSaveNote() {
        const userId = _odControlById('assignee-note-user-id').value;
        const version = _odControlById('assignee-note-version').value;
        const note = /** @type {HTMLTextAreaElement} */ (
            document.getElementById('assignee-note-text')
        ).value;
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        const payload = { note, version: version ? parseInt(version, 10) : null };
        oaSend('PUT', `/orders/${orderId}/assignees/${userId}/note`, {
            json: payload,
            successMsg: I18N_NOTE_SAVED,
        }).then((ok) => {
            if (ok) window.krtModal.close(document.getElementById('assignee-note-modal'));
        });
    }

    /**
     * Deletes an assignee's note.
     *
     * @param {HTMLElement} el the delete control carrying the user id and the note version
     */
    function oaDeleteNote(el) {
        const userId = el.getAttribute('data-user-id');
        const version = el.getAttribute('data-version') || '';
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        const q = version ? `?version=${encodeURIComponent(version)}` : '';
        oaSend('DELETE', `/orders/${orderId}/assignees/${userId}/note${q}`, {
            successMsg: I18N_NOTE_DELETED,
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const ta = document.getElementById('assignee-note-text');
        if (ta) ta.addEventListener('input', oaUpdateCounter);
    });

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'oa-add-me', (el) => {
            oaAdd(el.getAttribute('data-user-id'));
        });
        window.krtEvents.on('click', 'oa-add-user', () => {
            const sel = /** @type {HTMLSelectElement | null} */ (
                document.getElementById('assignee-add-select')
            );
            if (sel && sel.value) oaAdd(sel.value);
        });
        window.krtEvents.on('click', 'oa-remove-assignee', (el) => {
            oaRemove(el);
        });
        window.krtEvents.on('click', 'oa-edit-note', (el) => {
            oaOpenNote(el);
        });
        window.krtEvents.on('click', 'oa-save-note', () => {
            oaSaveNote();
        });
        window.krtEvents.on('click', 'oa-delete-note', (el) => {
            oaDeleteNote(el);
        });
    }
})();
