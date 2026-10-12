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

/* global ORDER_CONFLICT, I18N_ADDED, I18N_REMOVED, I18N_NOTE_SAVED, I18N_NOTE_DELETED, I18N_ADD_ERROR, I18N_REMOVE_ERROR, I18N_NOTE_ERROR, I18N_NOTE_CONFLICT, I18N_NOTE_FORBIDDEN, I18N_NOTE_FOR */

import {
    _toggleBlueprintCounting,
    cancelStatusChange,
    confirmStatusChange,
    openClaimCreate,
    openClaimEdit,
    submitClaim,
    updateStatus,
    withdrawClaimAction,
} from '/js/joborder/orders-detail-claims.js';
import { unlinkInventoryItem } from '/js/joborder/orders-detail-edit.js';
import {
    _odToggleDemandRow,
    _prodReconcile,
    _prodRefreshOrgUnitPicker,
    _prodSyncPersonalAllocate,
    _prodSyncPersonalForOwner,
    bookProduction,
    openProductionModal,
} from '/js/joborder/orders-detail-production.js';
import {
    downloadHandoverReport,
    downloadItemHandoverReport,
    previewHandoverReport,
    toggleInventory,
} from '/js/joborder/orders-detail-reports.js';
import {
    _odControlById,
    _odElementById,
    addHandoverItemRow,
    openHandoverModal,
    updateHandoverIndexes,
} from '/js/joborder/orders-detail.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

if (krtEvents && typeof krtEvents.on === 'function') {
    krtEvents.on('change', 'od-update-status', (el) => {
        updateStatus(/** @type {HTMLSelectElement} */ (el));
    });
    krtEvents.on('change', 'od-toggle-bp-counting', (el) => {
        _toggleBlueprintCounting(/** @type {HTMLInputElement} */ (el));
    });
    krtEvents.on('click', 'od-toggle-inventory', (el, ev) => {
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
    krtEvents.on('click', 'od-unlink-inventory', (el) => {
        unlinkInventoryItem(el);
    });
    krtEvents.on('click', 'claim-add', (el) => {
        openClaimCreate(el);
    });
    krtEvents.on('click', 'claim-edit', (el) => {
        openClaimEdit(el);
    });
    krtEvents.on('click', 'claim-submit', () => {
        submitClaim();
    });
    krtEvents.on('click', 'claim-withdraw', () => {
        withdrawClaimAction();
    });
    krtEvents.on('click', 'od-open-handover', openHandoverModal);
    krtEvents.on('click', 'od-open-production', openProductionModal);
    krtEvents.on('click', 'od-book-production', bookProduction);
    krtEvents.on('change', 'od-production-bookin-changed', () => {
        _prodReconcile();
    });
    krtEvents.on('change', 'od-production-owner-changed', () => {
        _prodSyncPersonalForOwner();
        _prodRefreshOrgUnitPicker();
    });
    krtEvents.on('change', 'od-production-personal-toggle', () => {
        _prodSyncPersonalAllocate();
    });
    krtEvents.on('click', 'od-toggle-demand', _odToggleDemandRow);
    krtEvents.on('click', 'od-download-report', (el) => {
        downloadHandoverReport(el);
    });
    krtEvents.on('click', 'od-download-item-report', (el) => {
        downloadItemHandoverReport(el);
    });
    krtEvents.on('click', 'od-cancel-status', cancelStatusChange);
    krtEvents.on('click', 'od-confirm-status', confirmStatusChange);
    krtEvents.on('click', 'od-confirm-remove-material', () => {
        if (typeof joborderHooks.confirmRemoveMaterial === 'function')
            joborderHooks.confirmRemoveMaterial();
    });
    krtEvents.on('click', 'od-add-handover-item', addHandoverItemRow);
    krtEvents.on('click', 'od-preview-handover', (el) => {
        previewHandoverReport(el);
    });
    krtEvents.on('click', 'od-remove-handover-row', (el) => {
        const row = el.parentElement && el.parentElement.parentElement;
        if (row) row.remove();
        updateHandoverIndexes();
    });
    krtEvents.on('submit', 'od-disable-submit', (el) => {
        const btn = /** @type {HTMLButtonElement | null} */ (
            el.querySelector('button[type=submit]')
        );
        if (btn) btn.disabled = true;
    });
}

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
    if (!krtFetch) return false;
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
                krtFetch.replaceWithTrustedHtml(sec, html);
                document.dispatchEvent(
                    new CustomEvent('krt:swapped', {
                        detail: { container: document.getElementById('assignees-section') },
                    }),
                );
            }
            if (opts.successMsg) showFrontendSuccessToast(opts.successMsg);
            if (joborderHooks.notifyOrderChanged) {
                joborderHooks.notifyOrderChanged(['assignees']);
            }
        },
        onError(status, _body, response) {
            if (status === 409) {
                krtFetch.handleProblem(
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
            ? await krtFetch.submitForm(Object.assign(common, { formData: opts.form }))
            : await krtFetch.write(Object.assign(common, { payload: opts.json }));
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
    const ta = /** @type {HTMLTextAreaElement} */ (document.getElementById('assignee-note-text'));
    ta.value = note;
    _odElementById('assignee-note-for').textContent = I18N_NOTE_FOR.replace('{0}', userName);
    oaUpdateCounter();
    krtModal.open(document.getElementById('assignee-note-modal'));
    ta.focus();
}

/** Saves the note typed in the note modal. */
function oaSaveNote() {
    const userId = _odControlById('assignee-note-user-id').value;
    const version = _odControlById('assignee-note-version').value;
    const note = /** @type {HTMLTextAreaElement} */ (document.getElementById('assignee-note-text'))
        .value;
    const orderId = oaOrderId();
    if (!userId || !orderId) return;
    const payload = { note, version: version ? parseInt(version, 10) : null };
    oaSend('PUT', `/orders/${orderId}/assignees/${userId}/note`, {
        json: payload,
        successMsg: I18N_NOTE_SAVED,
    }).then((ok) => {
        if (ok) krtModal.close(document.getElementById('assignee-note-modal'));
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

if (krtEvents && typeof krtEvents.on === 'function') {
    krtEvents.on('click', 'oa-add-me', (el) => {
        oaAdd(el.getAttribute('data-user-id'));
    });
    krtEvents.on('click', 'oa-add-user', () => {
        const sel = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('assignee-add-select')
        );
        if (sel && sel.value) oaAdd(sel.value);
    });
    krtEvents.on('click', 'oa-remove-assignee', (el) => {
        oaRemove(el);
    });
    krtEvents.on('click', 'oa-edit-note', (el) => {
        oaOpenNote(el);
    });
    krtEvents.on('click', 'oa-save-note', () => {
        oaSaveNote();
    });
    krtEvents.on('click', 'oa-delete-note', (el) => {
        oaDeleteNote(el);
    });
}
