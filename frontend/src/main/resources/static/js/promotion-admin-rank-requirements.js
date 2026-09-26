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

function toastSuccess(msg) {
    if (window.showFrontendSuccessToast) window.showFrontendSuccessToast(msg);
}
function toastError(msg) {
    if (window.showFrontendErrorToast) window.showFrontendErrorToast(msg);
}

function closeModal(id) {
    window.krtModal.close(id);
}
function openModal(id) {
    window.krtModal.open(id);
}

function arRefresh() {
    if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
        window.location.reload();
        return Promise.resolve(false);
    }
    return window.krtFetch.swap({
        url: '/promotion/admin/rank-requirements',
        container: '#ar-results',
        fragmentValue: 'ranksResults',
        errorMessage: MSG_REFRESH_FAILED,
    });
}

function syncToRank(fromInput) {
    if (!fromInput) return;
    const form = fromInput.closest('.krt-modal');
    if (!form) return;
    const toInput = form.querySelector('.rank-to-input');
    if (!toInput) return;
    const fromVal = parseInt(fromInput.value, 10);
    toInput.value = Number.isFinite(fromVal) ? String(fromVal - 1) : '';
}

function rebuildCategoryDropdown(topicSelect) {
    const form = topicSelect.closest('.krt-modal');
    if (!form) return;
    const catSelect = form.querySelector('.ar-category-select');
    if (!catSelect) return;
    const previousValue = catSelect.value;
    while (catSelect.options.length > 1) catSelect.remove(1);
    const topicId = topicSelect.value;
    if (topicId && AR_CATEGORIES_BY_TOPIC && AR_CATEGORIES_BY_TOPIC[topicId]) {
        AR_CATEGORIES_BY_TOPIC[topicId].forEach(function (cat) {
            const opt = document.createElement('option');
            opt.value = cat.id;
            opt.textContent = cat.name;
            catSelect.appendChild(opt);
        });
        if (previousValue && catSelect.querySelector('option[value="' + previousValue + '"]')) {
            catSelect.value = previousValue;
        }
    }
}

function isValidSingleStep(fromVal, toVal) {
    return Number.isFinite(fromVal) && Number.isFinite(toVal) && fromVal - toVal === 1;
}

function apiCall(url, method, body) {
    if (!window.krtFetch) {
        toastError(MSG_ERROR);
        return Promise.resolve(null);
    }
    return window.krtFetch
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
        .then(function (result) {
            if (!result.ok) {
                return null;
            }
            return result.body && typeof result.body === 'object' ? result.body : {};
        });
}

function openCreateModal() {
    document.getElementById('cr-from').value = 20;
    document.getElementById('cr-to').value = 19;
    document.getElementById('cr-topic').value = '';
    rebuildCategoryDropdown(document.getElementById('cr-topic'));
    document.getElementById('cr-level').value = 'LEVEL_A';
    document.getElementById('cr-count').value = 1;
    document.getElementById('cr-desc').value = '';
    openModal('modal-create');
}

function openEditModal(btn) {
    document.getElementById('er-id').value = btn.getAttribute('data-ar-id');
    document.getElementById('er-version').value = btn.getAttribute('data-ar-version');
    document.getElementById('er-from').value = btn.getAttribute('data-ar-from');
    document.getElementById('er-to').value = btn.getAttribute('data-ar-to');
    const topicId = btn.getAttribute('data-ar-topic-id') || '';
    document.getElementById('er-topic').value = topicId;
    rebuildCategoryDropdown(document.getElementById('er-topic'));
    document.getElementById('er-category').value = btn.getAttribute('data-ar-category-id') || '';
    document.getElementById('er-level').value = btn.getAttribute('data-ar-level');
    document.getElementById('er-count').value = btn.getAttribute('data-ar-count');
    document.getElementById('er-desc').value = btn.getAttribute('data-ar-description') || '';
    syncToRank(document.getElementById('er-from'));
    openModal('modal-edit');
}

function submitCreate() {
    const from = parseInt(document.getElementById('cr-from').value, 10);
    const to = parseInt(document.getElementById('cr-to').value, 10);
    if (!isValidSingleStep(from, to)) {
        toastError(MSG_INVALID_STEP);
        return;
    }
    const body = {
        fromRank: from,
        toRank: to,
        topicId: document.getElementById('cr-topic').value || null,
        categoryId: document.getElementById('cr-category').value || null,
        minimumLevel: document.getElementById('cr-level').value,
        requiredCount: parseInt(document.getElementById('cr-count').value, 10),
        description: document.getElementById('cr-desc').value || null,
    };
    apiCall('/api/proxy/promotion/rank-requirements', 'POST', body).then(function (data) {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-create');
        arRefresh();
    });
}

function submitEdit() {
    const id = document.getElementById('er-id').value;
    const from = parseInt(document.getElementById('er-from').value, 10);
    const to = parseInt(document.getElementById('er-to').value, 10);
    if (!isValidSingleStep(from, to)) {
        toastError(MSG_INVALID_STEP);
        return;
    }
    const body = {
        version: parseInt(document.getElementById('er-version').value, 10),
        fromRank: from,
        toRank: to,
        topicId: document.getElementById('er-topic').value || null,
        categoryId: document.getElementById('er-category').value || null,
        minimumLevel: document.getElementById('er-level').value,
        requiredCount: parseInt(document.getElementById('er-count').value, 10),
        description: document.getElementById('er-desc').value || null,
    };
    apiCall('/api/proxy/promotion/rank-requirements/' + id, 'PUT', body).then(function (data) {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-edit');
        arRefresh();
    });
}

function deleteRequirement(id) {
    if (!id) return Promise.resolve(false);
    return apiCall('/api/proxy/promotion/rank-requirements/' + id, 'DELETE', null);
}

function confirmAndDelete(id) {
    const fn = window.showKrtConfirm;
    if (typeof fn !== 'function') return;
    fn(MSG_DELETE_TITLE, MSG_DELETE_MSG, MSG_OK, MSG_CANCEL).then(function (ok) {
        if (!ok) return;
        deleteRequirement(id).then(function (data) {
            if (!data) return;
            toastSuccess(MSG_DELETED);
            arRefresh();
        });
    });
}

function confirmAndDeleteGroup(fromRank, toRank) {
    const fn = window.showKrtConfirm;
    if (typeof fn !== 'function') return;
    const rows = document.querySelectorAll(
        '.rank-group[data-ar-from-rank="' +
            fromRank +
            '"][data-ar-to-rank="' +
            toRank +
            '"] tr[data-ar-req-id]',
    );
    if (rows.length === 0) return;
    const msg = MSG_DELETE_GROUP_MSG.replace('{from}', String(fromRank))
        .replace('{to}', String(toRank))
        .replace('{count}', String(rows.length));
    fn(MSG_DELETE_GROUP_TITLE, msg, MSG_OK, MSG_CANCEL).then(function (ok) {
        if (!ok) return;
        const ids = Array.from(rows).map(function (r) {
            return r.getAttribute('data-ar-req-id');
        });
        let chain = Promise.resolve(true);
        ids.forEach(function (id) {
            chain = chain.then(function (alive) {
                if (!alive) return false;
                return deleteRequirement(id).then(function (data) {
                    return data !== null;
                });
            });
        });
        chain.then(function () {
            toastSuccess(MSG_GROUP_DELETED);
            arRefresh();
        });
    });
}

function applyFilter() {
    const input = document.getElementById('ar-filter');
    const query = (input ? input.value : '').trim().toLowerCase();
    const groups = document.querySelectorAll('.rank-group');
    let anyVisible = false;
    groups.forEach(function (group) {
        const fromRank = group.getAttribute('data-ar-from-rank') || '';
        const toRank = group.getAttribute('data-ar-to-rank') || '';
        const rankKey = fromRank + '->' + toRank;
        const rows = group.querySelectorAll('tr[data-ar-search]');
        let groupVisible = 0;
        rows.forEach(function (row) {
            const text = row.getAttribute('data-ar-search') || '';
            const match =
                query === '' ||
                text.indexOf(query) !== -1 ||
                rankKey.indexOf(query) !== -1 ||
                fromRank === query ||
                toRank === query;
            row.style.display = match ? '' : 'none';
            if (match) groupVisible++;
        });
        group.classList.toggle('is-hidden', groupVisible === 0 && query !== '');
        if (groupVisible > 0 || query === '') anyVisible = true;
    });
    const empty = document.getElementById('ar-empty');
    if (empty) empty.hidden = anyVisible;
}

document.addEventListener('DOMContentLoaded', function () {
    rebuildCategoryDropdown(document.getElementById('cr-topic'));

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'ar-open-create', openCreateModal);
        window.krtEvents.on('click', 'ar-open-edit', openEditModal);
        window.krtEvents.on('click', 'ar-open-delete', function (btn) {
            confirmAndDelete(btn.getAttribute('data-ar-id'));
        });
        window.krtEvents.on('click', 'ar-delete-group', function (btn) {
            confirmAndDeleteGroup(
                parseInt(btn.getAttribute('data-ar-from-rank'), 10),
                parseInt(btn.getAttribute('data-ar-to-rank'), 10),
            );
        });
        window.krtEvents.on('click', 'ar-close-create', function () {
            closeModal('modal-create');
        });
        window.krtEvents.on('click', 'ar-close-edit', function () {
            closeModal('modal-edit');
        });
        window.krtEvents.on('click', 'ar-submit-create', submitCreate);
        window.krtEvents.on('click', 'ar-submit-edit', submitEdit);
        window.krtEvents.on('change', 'ar-topic-change', rebuildCategoryDropdown);
        window.krtEvents.on('input', 'ar-rank-from', syncToRank);
    }

    document.querySelectorAll('.ar-rank-from').forEach(function (el) {
        el.addEventListener('input', function () {
            syncToRank(el);
        });
    });

    const filter = document.getElementById('ar-filter');
    if (filter) filter.addEventListener('input', applyFilter);

    document.addEventListener('krt:swapped', function (e) {
        if (e.detail && e.detail.container && e.detail.container.id === 'ar-results') {
            applyFilter();
        }
    });
});
