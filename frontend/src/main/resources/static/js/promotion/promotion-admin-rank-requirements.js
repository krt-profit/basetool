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

/* global AR_CATEGORIES_BY_TOPIC, MSG_SAVED, MSG_DELETED, MSG_ERROR, MSG_CONFLICT, MSG_INVALID_STEP, MSG_DELETE_TITLE, MSG_DELETE_MSG, MSG_DELETE_GROUP_TITLE, MSG_DELETE_GROUP_MSG, MSG_GROUP_DELETED, MSG_OK, MSG_CANCEL, MSG_REFRESH_FAILED */

import {
    showFrontendErrorToast,
    showFrontendSuccessToast,
    showKrtConfirm,
} from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';

/**
 * Shows a success toast.
 *
 * @param {string} msg the toast text
 */
function toastSuccess(msg) {
    showFrontendSuccessToast(msg);
}
/**
 * Shows an error toast.
 *
 * @param {string} msg the toast text
 */
function toastError(msg) {
    showFrontendErrorToast(msg);
}

/**
 * Looks up a form control of the page's modals; the template always renders them.
 *
 * @param {string} id the control's element id
 * @returns {HTMLInputElement} the control (an input, select or textarea; only `value` is used)
 */
function arControl(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
}

/**
 * Closes a dialog.
 *
 * @param {string} id the dialog's element id
 */
function closeModal(id) {
    krtModal.close(id);
}
/**
 * Opens a dialog.
 *
 * @param {string} id the dialog's element id
 */
function openModal(id) {
    krtModal.open(id);
}

function arRefresh() {
    return krtFetch.swap({
        url: '/promotion/admin/rank-requirements',
        container: '#ar-results',
        fragmentValue: 'ranksResults',
        errorMessage: MSG_REFRESH_FAILED,
    });
}

/**
 * Sets the "to" rank of a dialog to one below its "from" rank.
 *
 * @param {HTMLElement | null} fromInput the "from" rank input
 */
function syncToRank(fromInput) {
    if (!fromInput) return;
    const form = fromInput.closest('.krt-modal');
    if (!form) return;
    const toInput = /** @type {HTMLInputElement | null} */ (form.querySelector('.rank-to-input'));
    if (!toInput) return;
    const fromVal = parseInt(/** @type {HTMLInputElement} */ (fromInput).value, 10);
    toInput.value = Number.isFinite(fromVal) ? String(fromVal - 1) : '';
}

/**
 * Refills a dialog's category select with the categories of the chosen topic.
 *
 * @param {HTMLElement} topicSelect the topic select
 */
function rebuildCategoryDropdown(topicSelect) {
    const form = topicSelect.closest('.krt-modal');
    if (!form) return;
    const catSelect = /** @type {HTMLSelectElement | null} */ (
        form.querySelector('.ar-category-select')
    );
    if (!catSelect) return;
    const previousValue = catSelect.value;
    while (catSelect.options.length > 1) catSelect.remove(1);
    const topicId = /** @type {HTMLSelectElement} */ (topicSelect).value;
    if (topicId && AR_CATEGORIES_BY_TOPIC && AR_CATEGORIES_BY_TOPIC[topicId]) {
        AR_CATEGORIES_BY_TOPIC[topicId].forEach((cat) => {
            const opt = document.createElement('option');
            opt.value = cat.id;
            opt.textContent = cat.name;
            catSelect.appendChild(opt);
        });
        if (previousValue && catSelect.querySelector(`option[value="${previousValue}"]`)) {
            catSelect.value = previousValue;
        }
    }
}

/**
 * Whether the rank step lowers the rank by exactly one.
 *
 * @param {number} fromVal the "from" rank
 * @param {number} toVal the "to" rank
 * @returns {boolean} true for a single step
 */
function isValidSingleStep(fromVal, toVal) {
    return Number.isFinite(fromVal) && Number.isFinite(toVal) && fromVal - toVal === 1;
}

/**
 * Sends one rank-requirement write.
 *
 * @param {string} url the proxy endpoint
 * @param {string} method the HTTP method
 * @param {object | null} body the JSON payload, if any
 * @returns {Promise<any>} the parsed body, `{}` for an empty one, null when the write failed
 */
function apiCall(url, method, body) {
    return krtFetch
        .write({
            method,
            url,
            payload: body || undefined,
            toast: false,
            onError(status) {
                if (status === 409) {
                    toastError(MSG_CONFLICT);
                    arRefresh();
                } else {
                    toastError(MSG_ERROR);
                }
                return true;
            },
            onNetworkError() {
                toastError(MSG_ERROR);
                return true;
            },
        })
        .then((result) => {
            if (!result.ok) {
                return null;
            }
            return result.body && typeof result.body === 'object' ? result.body : {};
        });
}

/**
 * Opens the create dialog, optionally preset from a matrix cell.
 *
 * @param {HTMLElement | null} btn the matrix cell button, or null for a blank dialog
 */
function openCreateModal(btn) {
    const presetFrom = btn ? parseInt(btn.getAttribute('data-ar-from') || '', 10) : NaN;
    const from = Number.isFinite(presetFrom) ? presetFrom : 20;
    arControl('cr-from').value = String(from);
    arControl('cr-to').value = String(from - 1);
    arControl('cr-topic').value = (btn && btn.getAttribute('data-ar-topic-id')) || '';
    rebuildCategoryDropdown(/** @type {HTMLElement} */ (document.getElementById('cr-topic')));
    arControl('cr-level').value = 'LEVEL_A';
    arControl('cr-count').value = '1';
    arControl('cr-desc').value = '';
    openModal('modal-create');
}

/**
 * Scrolls to and flashes the requirement rows of one matrix cell.
 *
 * @param {HTMLElement} btn the matrix cell button
 */
function showMatrixCell(btn) {
    const from = btn.getAttribute('data-ar-from');
    const to = btn.getAttribute('data-ar-to');
    const topicId = btn.getAttribute('data-ar-topic-id') || '';
    const group = document.getElementById(`ar-group-${from}-${to}`);
    if (!group) return;
    const rows = Array.from(
        /** @type {NodeListOf<HTMLElement>} */ (group.querySelectorAll('tr[data-ar-req-id]')),
    ).filter((row) => {
        return (row.getAttribute('data-ar-topic') || '') === topicId;
    });
    const filter = /** @type {HTMLInputElement | null} */ (document.getElementById('ar-filter'));
    if (
        filter &&
        filter.value &&
        rows.some((row) => {
            return row.hidden;
        })
    ) {
        filter.value = '';
        applyFilter();
    }
    group.scrollIntoView({ block: 'start' });
    rows.forEach((row) => {
        row.classList.remove('is-flash');
        row.getBoundingClientRect();
        row.classList.add('is-flash');
    });
}

/**
 * Opens the edit dialog filled from a requirement row's button.
 *
 * @param {HTMLElement} btn the edit button carrying the requirement's data attributes
 */
function openEditModal(btn) {
    arControl('er-id').value = btn.getAttribute('data-ar-id') ?? '';
    arControl('er-version').value = btn.getAttribute('data-ar-version') ?? '';
    arControl('er-from').value = btn.getAttribute('data-ar-from') ?? '';
    arControl('er-to').value = btn.getAttribute('data-ar-to') ?? '';
    const topicId = btn.getAttribute('data-ar-topic-id') || '';
    arControl('er-topic').value = topicId;
    rebuildCategoryDropdown(/** @type {HTMLElement} */ (document.getElementById('er-topic')));
    arControl('er-category').value = btn.getAttribute('data-ar-category-id') || '';
    arControl('er-level').value = btn.getAttribute('data-ar-level') ?? '';
    arControl('er-count').value = btn.getAttribute('data-ar-count') ?? '';
    arControl('er-desc').value = btn.getAttribute('data-ar-description') || '';
    syncToRank(document.getElementById('er-from'));
    openModal('modal-edit');
}

function submitCreate() {
    const from = parseInt(arControl('cr-from').value, 10);
    const to = parseInt(arControl('cr-to').value, 10);
    if (!isValidSingleStep(from, to)) {
        toastError(MSG_INVALID_STEP);
        return;
    }
    const body = {
        fromRank: from,
        toRank: to,
        topicId: arControl('cr-topic').value || null,
        categoryId: arControl('cr-category').value || null,
        minimumLevel: arControl('cr-level').value,
        requiredCount: parseInt(arControl('cr-count').value, 10),
        description: arControl('cr-desc').value || null,
    };
    apiCall('/api/proxy/promotion/rank-requirements', 'POST', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-create');
        arRefresh();
    });
}

function submitEdit() {
    const id = arControl('er-id').value;
    const from = parseInt(arControl('er-from').value, 10);
    const to = parseInt(arControl('er-to').value, 10);
    if (!isValidSingleStep(from, to)) {
        toastError(MSG_INVALID_STEP);
        return;
    }
    const body = {
        version: parseInt(arControl('er-version').value, 10),
        fromRank: from,
        toRank: to,
        topicId: arControl('er-topic').value || null,
        categoryId: arControl('er-category').value || null,
        minimumLevel: arControl('er-level').value,
        requiredCount: parseInt(arControl('er-count').value, 10),
        description: arControl('er-desc').value || null,
    };
    apiCall(`/api/proxy/promotion/rank-requirements/${id}`, 'PUT', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-edit');
        arRefresh();
    });
}

/**
 * Deletes one requirement.
 *
 * @param {string | null} id the requirement id
 * @returns {Promise<any>} the parsed body, null when the write failed, false when no id was given
 */
function deleteRequirement(id) {
    if (!id) return Promise.resolve(false);
    return apiCall(`/api/proxy/promotion/rank-requirements/${id}`, 'DELETE', null);
}

/**
 * Asks for confirmation, then deletes one requirement.
 *
 * @param {string | null} id the requirement id
 */
function confirmAndDelete(id) {
    const fn = showKrtConfirm;
    if (typeof fn !== 'function') return;
    fn(MSG_DELETE_TITLE, MSG_DELETE_MSG, MSG_OK, MSG_CANCEL).then((ok) => {
        if (!ok) return;
        deleteRequirement(id).then((data) => {
            if (!data) return;
            toastSuccess(MSG_DELETED);
            arRefresh();
        });
    });
}

/**
 * Asks for confirmation, then deletes every requirement of one rank step.
 *
 * @param {number} fromRank the "from" rank of the group
 * @param {number} toRank the "to" rank of the group
 */
function confirmAndDeleteGroup(fromRank, toRank) {
    const fn = showKrtConfirm;
    if (typeof fn !== 'function') return;
    const rows = document.querySelectorAll(
        `.rank-group[data-ar-from-rank="${fromRank}"][data-ar-to-rank="${
            toRank
        }"] tr[data-ar-req-id]`,
    );
    if (rows.length === 0) return;
    const msg = MSG_DELETE_GROUP_MSG.replace('{from}', String(fromRank))
        .replace('{to}', String(toRank))
        .replace('{count}', String(rows.length));
    fn(MSG_DELETE_GROUP_TITLE, msg, MSG_OK, MSG_CANCEL).then((ok) => {
        if (!ok) return;
        const ids = Array.from(rows).map((r) => {
            return r.getAttribute('data-ar-req-id');
        });
        let chain = Promise.resolve(true);
        ids.forEach((id) => {
            chain = chain.then((alive) => {
                if (!alive) return false;
                return deleteRequirement(id).then((data) => {
                    return data !== null;
                });
            });
        });
        chain.then(() => {
            toastSuccess(MSG_GROUP_DELETED);
            arRefresh();
        });
    });
}

function applyFilter() {
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById('ar-filter'));
    const query = (input ? input.value : '').trim().toLowerCase();
    const groups = /** @type {NodeListOf<HTMLElement>} */ (
        document.querySelectorAll('.rank-group')
    );
    let anyVisible = false;
    groups.forEach((group) => {
        const fromRank = group.getAttribute('data-ar-from-rank') || '';
        const toRank = group.getAttribute('data-ar-to-rank') || '';
        const rankKey = `${fromRank}->${toRank}`;
        const rows = /** @type {NodeListOf<HTMLElement>} */ (
            group.querySelectorAll('tr[data-ar-search]')
        );
        let groupVisible = 0;
        rows.forEach((row) => {
            const text = row.getAttribute('data-ar-search') || '';
            const match =
                query === '' ||
                text.indexOf(query) !== -1 ||
                rankKey.indexOf(query) !== -1 ||
                fromRank === query ||
                toRank === query;
            row.hidden = !match;
            if (match) groupVisible++;
        });
        group.hidden = groupVisible === 0 && query !== '';
        if (groupVisible > 0 || query === '') anyVisible = true;
    });
    const empty = document.getElementById('ar-empty');
    if (empty) empty.hidden = anyVisible || groups.length === 0;
}

document.addEventListener('DOMContentLoaded', () => {
    rebuildCategoryDropdown(/** @type {HTMLElement} */ (document.getElementById('cr-topic')));

    krtEvents.on('click', 'ar-open-create', () => {
        openCreateModal(null);
    });
    krtEvents.on('click', 'ar-matrix-add', openCreateModal);
    krtEvents.on('click', 'ar-matrix-show', showMatrixCell);
    krtEvents.on('click', 'ar-open-edit', openEditModal);
    krtEvents.on('click', 'ar-open-delete', (btn) => {
        confirmAndDelete(btn.getAttribute('data-ar-id'));
    });
    krtEvents.on('click', 'ar-delete-group', (btn) => {
        confirmAndDeleteGroup(
            parseInt(btn.getAttribute('data-ar-from-rank') ?? '', 10),
            parseInt(btn.getAttribute('data-ar-to-rank') ?? '', 10),
        );
    });
    krtEvents.on('click', 'ar-close-create', () => {
        closeModal('modal-create');
    });
    krtEvents.on('click', 'ar-close-edit', () => {
        closeModal('modal-edit');
    });
    krtEvents.on('click', 'ar-submit-create', submitCreate);
    krtEvents.on('click', 'ar-submit-edit', submitEdit);
    krtEvents.on('change', 'ar-topic-change', rebuildCategoryDropdown);
    krtEvents.on('input', 'ar-rank-from', syncToRank);

    /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.ar-rank-from')).forEach(
        (el) => {
            el.addEventListener('input', () => {
                syncToRank(el);
            });
        },
    );

    const filter = document.getElementById('ar-filter');
    if (filter) filter.addEventListener('input', applyFilter);

    document.addEventListener('krt:swapped', (e) => {
        if (e.detail && e.detail.container && e.detail.container.id === 'ar-results') {
            applyFilter();
        }
    });
});
