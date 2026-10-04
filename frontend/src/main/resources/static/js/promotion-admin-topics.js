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

/* global MSG_SAVED, MSG_DELETED, MSG_ERROR, MSG_CONFLICT, MSG_DELETE_TOPIC_TITLE, MSG_DELETE_TOPIC_MSG, MSG_DELETE_CATEGORY_TITLE, MSG_DELETE_CATEGORY_MSG, MSG_OK, MSG_CANCEL, MSG_DIRTY_LEAVE, MSG_REFRESH_FAILED */

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

let paSelectedTopicId = null;

function paTopicsUrl() {
    return `/promotion/admin/topics${
        paSelectedTopicId ? `?topic=${encodeURIComponent(paSelectedTopicId)}` : ''
    }`;
}

function paRefreshTopics() {
    if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
        window.location.reload();
        return Promise.resolve(false);
    }
    return window.krtFetch.swap({
        url: paTopicsUrl(),
        container: '#pa-topics-results',
        fragmentValue: 'topicsResults',
        errorMessage: MSG_REFRESH_FAILED,
    });
}

/**
 * Shows the detail pane of one topic, marks its master row as selected and records the choice in
 * the address bar so a reload or a shared link reopens the same topic.
 *
 * @param {string | null} topicId id of the topic to show; null keeps the first one
 * @param {boolean} focusRow whether the selected master row takes the focus
 */
function paSelectTopic(topicId, focusRow) {
    const rows = Array.from(document.querySelectorAll('.pa-topic-row'));
    if (rows.length === 0) {
        paSelectedTopicId = null;
        return;
    }
    let target = rows.find((r) => {
        return r.getAttribute('data-pa-select-topic') === topicId;
    });
    if (!target) target = rows[0];
    const id = target.getAttribute('data-pa-select-topic');
    rows.forEach((r) => {
        const on = r === target;
        r.classList.toggle('is-active', on);
        r.setAttribute('aria-selected', String(on));
        r.setAttribute('tabindex', on ? '0' : '-1');
    });
    document.querySelectorAll('.pa-topic-pane').forEach((pane) => {
        pane.hidden = pane.getAttribute('data-pa-topic-id') !== id;
    });
    paSelectedTopicId = id;
    if (focusRow) target.focus();
    try {
        const url = new URL(window.location.href);
        url.searchParams.set('topic', id);
        window.history.replaceState(window.history.state, '', url.pathname + url.search);
    } catch (_e) {}
}

function paSyncSelectionFromDom() {
    const active = document.querySelector('.pa-topic-row[aria-selected="true"]');
    paSelectTopic(active ? active.getAttribute('data-pa-select-topic') : null, false);
}

function paOnTopicListKeydown(e) {
    const list = e.target && e.target.closest ? e.target.closest('#pa-topic-list') : null;
    if (!list) return;
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') {
        return;
    }
    const rows = Array.from(list.querySelectorAll('.pa-topic-row'));
    if (rows.length === 0) return;
    e.preventDefault();
    const current = rows.findIndex((r) => {
        return r.getAttribute('aria-selected') === 'true';
    });
    let idx;
    if (e.key === 'ArrowDown') idx = Math.min(rows.length - 1, current + 1);
    else if (e.key === 'ArrowUp') idx = Math.max(0, current - 1);
    else if (e.key === 'Home') idx = 0;
    else idx = rows.length - 1;
    paSelectTopic(rows[idx].getAttribute('data-pa-select-topic'), true);
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
                    paRefreshTopics();
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

function openCreateTopicModal() {
    document.getElementById('ct-name').value = '';
    document.getElementById('ct-desc').value = '';
    document.getElementById('ct-sort').value = 0;
    openModal('modal-create-topic');
}
function openEditTopicModal(btn) {
    document.getElementById('et-id').value = btn.getAttribute('data-pa-id');
    document.getElementById('et-version').value = btn.getAttribute('data-pa-version');
    document.getElementById('et-name').value = btn.getAttribute('data-pa-name');
    document.getElementById('et-desc').value = btn.getAttribute('data-pa-description') || '';
    document.getElementById('et-sort').value = btn.getAttribute('data-pa-sort');
    openModal('modal-edit-topic');
}
function createTopic() {
    const body = {
        name: document.getElementById('ct-name').value,
        description: document.getElementById('ct-desc').value || null,
        sortOrder: parseInt(document.getElementById('ct-sort').value, 10) || 0,
    };
    apiCall('/api/proxy/promotion/topics', 'POST', body).then((data) => {
        if (!data) return;
        if (data.id) paSelectedTopicId = String(data.id);
        toastSuccess(MSG_SAVED);
        closeModal('modal-create-topic');
        paRefreshTopics();
    });
}
function updateTopic() {
    const id = document.getElementById('et-id').value;
    const body = {
        version: parseInt(document.getElementById('et-version').value, 10),
        name: document.getElementById('et-name').value,
        description: document.getElementById('et-desc').value || null,
        sortOrder: parseInt(document.getElementById('et-sort').value, 10) || 0,
    };
    apiCall(`/api/proxy/promotion/topics/${id}`, 'PUT', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-edit-topic');
        paRefreshTopics();
    });
}
function deleteTopic(btn) {
    const id = btn.getAttribute('data-pa-id');
    const name = btn.getAttribute('data-pa-name') || '';
    const fn = window.showKrtConfirm;
    if (typeof fn !== 'function') return;
    fn(
        MSG_DELETE_TOPIC_TITLE,
        MSG_DELETE_TOPIC_MSG.replace('{name}', name),
        MSG_OK,
        MSG_CANCEL,
    ).then((ok) => {
        if (!ok) return;
        apiCall(`/api/proxy/promotion/topics/${id}`, 'DELETE', null).then((data) => {
            if (!data) return;
            if (paSelectedTopicId === id) paSelectedTopicId = null;
            toastSuccess(MSG_DELETED);
            paRefreshTopics();
        });
    });
}

function openCreateCategoryModal(btn) {
    document.getElementById('cc-topic-id').value = btn.getAttribute('data-pa-topic-id');
    document.getElementById('cc-name').value = '';
    document.getElementById('cc-desc').value = '';
    document.getElementById('cc-sort').value = 0;
    openModal('modal-create-category');
}
function openEditCategoryModal(btn) {
    document.getElementById('ec-id').value = btn.getAttribute('data-pa-id');
    document.getElementById('ec-version').value = btn.getAttribute('data-pa-version');
    document.getElementById('ec-topic-id').value = btn.getAttribute('data-pa-topic-id');
    document.getElementById('ec-name').value = btn.getAttribute('data-pa-name');
    document.getElementById('ec-desc').value = btn.getAttribute('data-pa-description') || '';
    document.getElementById('ec-sort').value = btn.getAttribute('data-pa-sort');
    openModal('modal-edit-category');
}
function createCategory() {
    const body = {
        topicId: document.getElementById('cc-topic-id').value,
        name: document.getElementById('cc-name').value,
        description: document.getElementById('cc-desc').value || null,
        sortOrder: parseInt(document.getElementById('cc-sort').value, 10) || 0,
    };
    apiCall('/api/proxy/promotion/categories', 'POST', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-create-category');
        paRefreshTopics();
    });
}
function updateCategory() {
    const id = document.getElementById('ec-id').value;
    const body = {
        version: parseInt(document.getElementById('ec-version').value, 10),
        topicId: document.getElementById('ec-topic-id').value,
        name: document.getElementById('ec-name').value,
        description: document.getElementById('ec-desc').value || null,
        sortOrder: parseInt(document.getElementById('ec-sort').value, 10) || 0,
    };
    apiCall(`/api/proxy/promotion/categories/${id}`, 'PUT', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-edit-category');
        paRefreshTopics();
    });
}
function deleteCategory(btn) {
    const id = btn.getAttribute('data-pa-id');
    const name = btn.getAttribute('data-pa-name') || '';
    const fn = window.showKrtConfirm;
    if (typeof fn !== 'function') return;
    fn(
        MSG_DELETE_CATEGORY_TITLE,
        MSG_DELETE_CATEGORY_MSG.replace('{name}', name),
        MSG_OK,
        MSG_CANCEL,
    ).then((ok) => {
        if (!ok) return;
        apiCall(`/api/proxy/promotion/categories/${id}`, 'DELETE', null).then((data) => {
            if (!data) return;
            toastSuccess(MSG_DELETED);
            paRefreshTopics();
        });
    });
}

function paReadCardBody(card, trigger) {
    const btn = card.querySelector(`[data-trigger="${trigger}"]`);
    if (!btn) return null;
    const version = parseInt(btn.getAttribute('data-pa-version'), 10);
    if (!Number.isFinite(version)) return null;
    const body = {
        version,
        name: btn.getAttribute('data-pa-name'),
        description: btn.getAttribute('data-pa-description') || null,
        sortOrder: parseInt(btn.getAttribute('data-pa-sort'), 10) || 0,
    };
    const topicId = btn.getAttribute('data-pa-topic-id');
    if (topicId) body.topicId = topicId;
    return body;
}
function swapSort(currentCard, otherCard, putUrl, kind) {
    const trigger = kind === 'topic' ? 'pa-edit-topic' : 'pa-edit-category';
    const idAttr = kind === 'topic' ? 'data-pa-topic-id' : 'data-pa-category-id';
    const a = paReadCardBody(currentCard, trigger);
    const b = paReadCardBody(otherCard, trigger);
    if (!a || !b) {
        toastError(MSG_ERROR);
        return Promise.resolve(null);
    }
    const aSort = a.sortOrder;
    let bSort = b.sortOrder;
    if (aSort === bSort) {
        bSort =
            aSort +
            (otherCard.compareDocumentPosition(currentCard) & Node.DOCUMENT_POSITION_FOLLOWING
                ? -1
                : 1);
    }
    a.sortOrder = bSort;
    b.sortOrder = aSort;
    const aId = currentCard.getAttribute(idAttr);
    const bId = otherCard.getAttribute(idAttr);
    return apiCall(`${putUrl}/${aId}`, 'PUT', a).then((r1) => {
        if (!r1) return null;
        return apiCall(`${putUrl}/${bId}`, 'PUT', b);
    });
}
function findAdjacentSibling(card, direction) {
    const sel = card.tagName.toLowerCase();
    const siblings = card.parentElement.querySelectorAll(`:scope > ${sel}`);
    const arr = Array.from(siblings);
    const idx = arr.indexOf(card);
    const target = direction === 'up' ? arr[idx - 1] : arr[idx + 1];
    return target || null;
}
function moveTopic(btn, direction) {
    const card = btn.closest('.pa-topic-pane');
    const other = findAdjacentSibling(card, direction);
    if (!other) return;
    swapSort(card, other, '/api/proxy/promotion/topics', 'topic').then((r) => {
        if (r === null) return;
        toastSuccess(MSG_SAVED);
        paRefreshTopics();
    });
}
function moveCategory(btn, direction) {
    const card = btn.closest('.pa-category-card');
    const other = findAdjacentSibling(card, direction);
    if (!other) return;
    swapSort(card, other, '/api/proxy/promotion/categories', 'category').then((r) => {
        if (r === null) return;
        toastSuccess(MSG_SAVED);
        paRefreshTopics();
    });
}

const paDirtyTextareas = new Set();

function paUpdateSaveAllBanner() {
    const banner = document.getElementById('pa-save-all-banner');
    const count = document.getElementById('pa-save-all-count');
    if (!banner || !count) return;
    const n = paDirtyTextareas.size;
    count.textContent = String(n);
    banner.classList.toggle('visible', n > 0);
}
function paMarkDirty(textarea) {
    const original = textarea.getAttribute('data-original') || '';
    const dirty = textarea.value !== original;
    textarea.classList.toggle('pa-dirty', dirty);
    if (dirty) paDirtyTextareas.add(textarea);
    else paDirtyTextareas.delete(textarea);
    paUpdateSaveAllBanner();
}
function paSaveLevelContent(textarea) {
    const categoryId = textarea.getAttribute('data-category-id');
    const level = textarea.getAttribute('data-level');
    const lcId = textarea.getAttribute('data-lc-id');
    const lcVersion = textarea.getAttribute('data-lc-version');
    const description = textarea.value;
    let p;
    if (lcId) {
        const body = {
            version: parseInt(lcVersion, 10) || 0,
            categoryId,
            level,
            description,
        };
        p = apiCall(`/api/proxy/promotion/level-contents/${lcId}`, 'PUT', body);
    } else {
        p = apiCall('/api/proxy/promotion/level-contents', 'POST', {
            categoryId,
            level,
            description,
        });
    }
    return p.then((data) => {
        if (!data) return null;
        if (data.id) textarea.setAttribute('data-lc-id', data.id);
        if (data.version !== undefined)
            textarea.setAttribute('data-lc-version', String(data.version));
        textarea.setAttribute('data-original', description);
        textarea.classList.remove('pa-dirty');
        paDirtyTextareas.delete(textarea);
        paUpdateSaveAllBanner();
        return data;
    });
}
function paSaveLevelContentFromButton(btn) {
    const row = btn.closest('.pa-level-row');
    if (!row) return;
    const textarea = row.querySelector('textarea.lc-textarea');
    if (!textarea) return;
    paSaveLevelContent(textarea).then((data) => {
        if (data) toastSuccess(MSG_SAVED);
    });
}
function paSaveAll() {
    const snapshot = Array.from(paDirtyTextareas);
    if (snapshot.length === 0) return;
    let chain = Promise.resolve(true);
    snapshot.forEach((ta) => {
        chain = chain.then((alive) => {
            if (!alive) return false;
            return paSaveLevelContent(ta).then((data) => {
                return data !== null;
            });
        });
    });
    chain.then(() => {
        toastSuccess(MSG_SAVED);
    });
}
function paDiscardAll() {
    const fn = window.showKrtConfirm;
    if (typeof fn !== 'function') return;
    fn(MSG_DELETE_TOPIC_TITLE, MSG_DIRTY_LEAVE, MSG_OK, MSG_CANCEL).then((ok) => {
        if (!ok) return;
        paDirtyTextareas.forEach((ta) => {
            ta.value = ta.getAttribute('data-original') || '';
            ta.classList.remove('pa-dirty');
        });
        paDirtyTextareas.clear();
        paUpdateSaveAllBanner();
    });
}

window.addEventListener('beforeunload', (e) => {
    if (paDirtyTextareas.size > 0) {
        e.preventDefault();
        e.returnValue = '';
        return '';
    }
});

document.addEventListener('DOMContentLoaded', () => {
    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'pa-open-create-topic', openCreateTopicModal);
        window.krtEvents.on('click', 'pa-edit-topic', openEditTopicModal);
        window.krtEvents.on('click', 'pa-delete-topic', deleteTopic);
        window.krtEvents.on('click', 'pa-open-create-category', openCreateCategoryModal);
        window.krtEvents.on('click', 'pa-edit-category', openEditCategoryModal);
        window.krtEvents.on('click', 'pa-delete-category', deleteCategory);

        window.krtEvents.on('click', 'pa-create-topic', createTopic);
        window.krtEvents.on('click', 'pa-update-topic', updateTopic);
        window.krtEvents.on('click', 'pa-create-category', createCategory);
        window.krtEvents.on('click', 'pa-update-category', updateCategory);

        window.krtEvents.on('click', 'pa-close-modal', (btn) => {
            const id = btn.getAttribute('data-pa-modal');
            if (id) closeModal(id);
        });

        window.krtEvents.on('click', 'pa-move-topic-up', (btn) => {
            moveTopic(btn, 'up');
        });
        window.krtEvents.on('click', 'pa-move-topic-down', (btn) => {
            moveTopic(btn, 'down');
        });
        window.krtEvents.on('click', 'pa-move-cat-up', (btn) => {
            moveCategory(btn, 'up');
        });
        window.krtEvents.on('click', 'pa-move-cat-down', (btn) => {
            moveCategory(btn, 'down');
        });

        window.krtEvents.on('input', 'pa-level-dirty', paMarkDirty);
        window.krtEvents.on('click', 'pa-save-level-content', paSaveLevelContentFromButton);
        window.krtEvents.on('click', 'pa-save-all', paSaveAll);
        window.krtEvents.on('click', 'pa-discard-all', paDiscardAll);

        window.krtEvents.on('click', 'pa-select-topic', (row) => {
            paSelectTopic(row.getAttribute('data-pa-select-topic'), false);
        });
    }

    document.addEventListener('keydown', paOnTopicListKeydown);
    paSyncSelectionFromDom();

    document.addEventListener('krt:swapped', (e) => {
        if (e.detail && e.detail.container && e.detail.container.id === 'pa-topics-results') {
            paDirtyTextareas.clear();
            paUpdateSaveAllBanner();
            paSyncSelectionFromDom();
        }
    });
});
