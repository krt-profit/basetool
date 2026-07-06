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
 * Promotion admin topics/categories page module (/promotion/admin/topics), extracted verbatim from
 * the former inline script of promotion-admin-topics.html (ADR-0069, follow-up to #924).
 *
 * In-place CRUD for promotion topics + categories and their level-content textareas: JSON writes
 * through the shared krtCsrf reader (retry-on-403, 409 -> toast + fragment-swap refresh, no reload),
 * up/down reorder by swapping neighbouring sortOrders, per-textarea dirty tracking with a save-all
 * banner + beforeunload guard, and bulk expand/collapse. Wired via window.krtEvents delegation on
 * DOMContentLoaded; the krt:swapped listener drops stale dirty refs after an in-place refresh.
 *
 * The MSG_* strings are defined by the inline Thymeleaf bootstrap block of promotion-admin-topics.html;
 * both that block and this th:src share th:unless="${isAllSquadronsMode}", so neither runs in
 * all-squadrons mode.
 */

/* global MSG_SAVED, MSG_DELETED, MSG_ERROR, MSG_CONFLICT, MSG_DELETE_TOPIC_TITLE, MSG_DELETE_TOPIC_MSG, MSG_DELETE_CATEGORY_TITLE, MSG_DELETE_CATEGORY_MSG, MSG_OK, MSG_CANCEL, MSG_DIRTY_LEAVE, MSG_REFRESH_FAILED */

function toastSuccess(msg) {
    if (window.showFrontendSuccessToast) window.showFrontendSuccessToast(msg);
}
function toastError(msg) {
    if (window.showFrontendErrorToast) window.showFrontendErrorToast(msg);
}

function closeModal(id) {
    document.getElementById(id).classList.remove('active');
}
function openModal(id) {
    document.getElementById(id).classList.add('active');
}

// CSRF readers delegate to the shared krtCsrf module (epic #571); the meta-tag
// fallback keeps the page working if krt-fetch.js failed to load.
function getCsrfToken() {
    return (
        (window.krtCsrf && window.krtCsrf.token && window.krtCsrf.token()) ||
        document.querySelector('meta[name="_csrf"]')?.content ||
        ''
    );
}
function getCsrfHeader() {
    return (
        (window.krtCsrf && window.krtCsrf.headerName && window.krtCsrf.headerName()) ||
        document.querySelector('meta[name="_csrf_header"]')?.content ||
        'X-CSRF-TOKEN'
    );
}

// Re-renders the topics list in place after any structural change (create /
// edit / delete / reorder). The server re-renders the fragment, so every
// card's data-pa-*-version, sortOrder and first/last arrow state come back
// fresh — this is what guarantees a second reorder does not 409 (#580).
function paRefreshTopics() {
    if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
        window.location.reload();
        return Promise.resolve(false);
    }
    return window.krtFetch.swap({
        url: '/promotion/admin/topics',
        container: '#pa-topics-results',
        fragmentValue: 'topicsResults',
        errorMessage: MSG_REFRESH_FAILED,
    });
}

function paCsrfHeaders() {
    if (window.krtCsrf && typeof window.krtCsrf.headers === 'function') {
        return window.krtCsrf.headers();
    }
    const h = {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
    };
    h[getCsrfHeader()] = getCsrfToken();
    return h;
}

// JSON write through the shared CSRF reader with a single retry on a bare 403
// (stale token). Keeps the page's inline conflict UX — on 409 it toasts and
// refreshes the list in place rather than reloading the whole page.
function apiCall(url, method, body) {
    function run() {
        return fetch(url, {
            method: method,
            headers: paCsrfHeaders(),
            body: body ? JSON.stringify(body) : undefined,
        });
    }
    return run()
        .then(function (r) {
            if (
                r.status === 403 &&
                window.krtCsrf &&
                typeof window.krtCsrf.refresh === 'function'
            ) {
                return window.krtCsrf.refresh().then(function (ok) {
                    return ok ? run() : r;
                });
            }
            return r;
        })
        .then(function (r) {
            if (r.status === 409) {
                toastError(MSG_CONFLICT);
                paRefreshTopics();
                return null;
            }
            if (!r.ok) {
                toastError(MSG_ERROR);
                return null;
            }
            return r.status === 204 ? {} : r.json();
        })
        .catch(function () {
            toastError(MSG_ERROR);
            return null;
        });
}

// ----------------------------------------------------------------------
// Topic CRUD
// ----------------------------------------------------------------------
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
    apiCall('/api/proxy/promotion/topics', 'POST', body).then(function (data) {
        if (!data) return;
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
    apiCall('/api/proxy/promotion/topics/' + id, 'PUT', body).then(function (data) {
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
    ).then(function (ok) {
        if (!ok) return;
        apiCall('/api/proxy/promotion/topics/' + id, 'DELETE', null).then(function (data) {
            if (!data) return;
            toastSuccess(MSG_DELETED);
            paRefreshTopics();
        });
    });
}

// ----------------------------------------------------------------------
// Category CRUD
// ----------------------------------------------------------------------
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
    apiCall('/api/proxy/promotion/categories', 'POST', body).then(function (data) {
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
    apiCall('/api/proxy/promotion/categories/' + id, 'PUT', body).then(function (data) {
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
    ).then(function (ok) {
        if (!ok) return;
        apiCall('/api/proxy/promotion/categories/' + id, 'DELETE', null).then(function (data) {
            if (!data) return;
            toastSuccess(MSG_DELETED);
            paRefreshTopics();
        });
    });
}

// ----------------------------------------------------------------------
// Sort: up/down arrows (T4)
// ----------------------------------------------------------------------
// There is no swap endpoint; instead we PUT both the moved item and its
// neighbour with their sortOrder values swapped, then re-render the list in
// place (no reload) so every card's fresh @Version comes back for the next
// move. The full DTO each PUT needs (name/description/sortOrder/version, plus
// topicId for categories) is read straight from the card's edit-button data
// attributes — the page already carries it, so no read-back GET is required
// (the proxy exposes no GET-by-id route anyway).
function paReadCardBody(card, trigger) {
    const btn = card.querySelector('[data-trigger="' + trigger + '"]');
    if (!btn) return null;
    const version = parseInt(btn.getAttribute('data-pa-version'), 10);
    if (!Number.isFinite(version)) return null;
    const body = {
        version: version,
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
        // Tie-break: nudge the other value so the two become distinct,
        // otherwise swapping equal sortOrders is a no-op and the move sticks.
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
    // Sequential, not parallel: a 409 on the first PUT surfaces (and refreshes
    // the list) via apiCall before the second one fires.
    return apiCall(putUrl + '/' + aId, 'PUT', a).then(function (r1) {
        if (!r1) return null;
        return apiCall(putUrl + '/' + bId, 'PUT', b);
    });
}
function findAdjacentSibling(card, direction) {
    const sel = card.tagName.toLowerCase();
    const siblings = card.parentElement.querySelectorAll(':scope > ' + sel);
    const arr = Array.from(siblings);
    const idx = arr.indexOf(card);
    const target = direction === 'up' ? arr[idx - 1] : arr[idx + 1];
    return target || null;
}
function moveTopic(btn, direction) {
    const card = btn.closest('.admin-topic-card');
    const other = findAdjacentSibling(card, direction);
    if (!other) return;
    swapSort(card, other, '/api/proxy/promotion/topics', 'topic').then(function (r) {
        if (r === null) return;
        toastSuccess(MSG_SAVED);
        paRefreshTopics();
    });
}
function moveCategory(btn, direction) {
    const card = btn.closest('.pa-category-card');
    const other = findAdjacentSibling(card, direction);
    if (!other) return;
    swapSort(card, other, '/api/proxy/promotion/categories', 'category').then(function (r) {
        if (r === null) return;
        toastSuccess(MSG_SAVED);
        paRefreshTopics();
    });
}

// ----------------------------------------------------------------------
// Level-content inline save + dirty tracking (T2 + T3)
// ----------------------------------------------------------------------
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
            categoryId: categoryId,
            level: level,
            description: description,
        };
        p = apiCall('/api/proxy/promotion/level-contents/' + lcId, 'PUT', body);
    } else {
        p = apiCall('/api/proxy/promotion/level-contents', 'POST', {
            categoryId: categoryId,
            level: level,
            description: description,
        });
    }
    return p.then(function (data) {
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
    paSaveLevelContent(textarea).then(function (data) {
        if (data) toastSuccess(MSG_SAVED);
    });
}
function paSaveAll() {
    const snapshot = Array.from(paDirtyTextareas);
    if (snapshot.length === 0) return;
    // Sequential — one failure stops the chain so the user can fix the
    // root cause before further saves go out.
    let chain = Promise.resolve(true);
    snapshot.forEach(function (ta) {
        chain = chain.then(function (alive) {
            if (!alive) return false;
            return paSaveLevelContent(ta).then(function (data) {
                return data !== null;
            });
        });
    });
    chain.then(function () {
        toastSuccess(MSG_SAVED);
    });
}
function paDiscardAll() {
    const fn = window.showKrtConfirm;
    if (typeof fn !== 'function') return;
    fn(MSG_DELETE_TOPIC_TITLE, MSG_DIRTY_LEAVE, MSG_OK, MSG_CANCEL).then(function (ok) {
        if (!ok) return;
        paDirtyTextareas.forEach(function (ta) {
            ta.value = ta.getAttribute('data-original') || '';
            ta.classList.remove('pa-dirty');
        });
        paDirtyTextareas.clear();
        paUpdateSaveAllBanner();
    });
}

// ----------------------------------------------------------------------
// Bulk expand/collapse on the topic cards
// ----------------------------------------------------------------------
function paToggleAllTopics(open) {
    document.querySelectorAll('.admin-topic-card').forEach(function (d) {
        d.open = open;
    });
}

// beforeunload guard: warn the admin if they navigate away with unsaved
// textareas. The native dialog is the only allowed pre-navigation prompt;
// showKrtConfirm cannot intercept it.
window.addEventListener('beforeunload', function (e) {
    if (paDirtyTextareas.size > 0) {
        e.preventDefault();
        e.returnValue = '';
        return '';
    }
});

// ----------------------------------------------------------------------
// Wiring
// ----------------------------------------------------------------------
document.addEventListener('DOMContentLoaded', function () {
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

        window.krtEvents.on('click', 'pa-close-modal', function (btn) {
            const id = btn.getAttribute('data-pa-modal');
            if (id) closeModal(id);
        });

        window.krtEvents.on('click', 'pa-move-topic-up', function (btn) {
            moveTopic(btn, 'up');
        });
        window.krtEvents.on('click', 'pa-move-topic-down', function (btn) {
            moveTopic(btn, 'down');
        });
        window.krtEvents.on('click', 'pa-move-cat-up', function (btn) {
            moveCategory(btn, 'up');
        });
        window.krtEvents.on('click', 'pa-move-cat-down', function (btn) {
            moveCategory(btn, 'down');
        });

        window.krtEvents.on('input', 'pa-level-dirty', paMarkDirty);
        window.krtEvents.on('click', 'pa-save-level-content', paSaveLevelContentFromButton);
        window.krtEvents.on('click', 'pa-save-all', paSaveAll);
        window.krtEvents.on('click', 'pa-discard-all', paDiscardAll);

        window.krtEvents.on('click', 'pa-expand-all-topics', function () {
            paToggleAllTopics(true);
        });
        window.krtEvents.on('click', 'pa-collapse-all-topics', function () {
            paToggleAllTopics(false);
        });
    }

    // After an in-place refresh the level-content textareas are re-rendered
    // from authoritative server state, so any tracked "dirty" references now
    // point at detached nodes — drop them and hide the save-all banner.
    document.addEventListener('krt:swapped', function (e) {
        if (e.detail && e.detail.container && e.detail.container.id === 'pa-topics-results') {
            paDirtyTextareas.clear();
            paUpdateSaveAllBanner();
        }
    });
});
