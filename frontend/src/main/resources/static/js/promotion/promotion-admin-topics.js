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
 * @param {string} msg the message text
 */
function toastSuccess(msg) {
    showFrontendSuccessToast(msg);
}
/**
 * Shows an error toast.
 *
 * @param {string} msg the message text
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
function paControl(id) {
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

/** @type {string | null} */
let paSelectedTopicId = null;

function paTopicsUrl() {
    return `/promotion/admin/topics${
        paSelectedTopicId ? `?topic=${encodeURIComponent(paSelectedTopicId)}` : ''
    }`;
}

function paRefreshTopics() {
    return krtFetch.swap({
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
    const rows = Array.from(
        /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.pa-topic-row')),
    );
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
    /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.pa-topic-pane')).forEach(
        (pane) => {
            pane.hidden = pane.getAttribute('data-pa-topic-id') !== id;
        },
    );
    paSelectedTopicId = id;
    if (focusRow) target.focus();
    try {
        const url = new URL(window.location.href);
        url.searchParams.set('topic', String(id));
        window.history.replaceState(window.history.state, '', url.pathname + url.search);
    } catch (_e) {}
}

function paSyncSelectionFromDom() {
    const active = document.querySelector('.pa-topic-row[aria-selected="true"]');
    paSelectTopic(active ? active.getAttribute('data-pa-select-topic') : null, false);
}

/**
 * Moves the topic selection with the arrow, Home and End keys while the topic list has focus.
 *
 * @param {KeyboardEvent} e the keydown event
 */
function paOnTopicListKeydown(e) {
    const target = /** @type {Element | null} */ (e.target);
    const list = target && target.closest ? target.closest('#pa-topic-list') : null;
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

/**
 * Sends one proxied write and resolves its JSON body, or null after the failure was toasted.
 *
 * @param {string} url the proxy URL
 * @param {'POST' | 'PUT' | 'DELETE'} method the HTTP method
 * @param {object | null} body the JSON payload; none for a DELETE
 * @returns {Promise<any>} the parsed body (an empty object for a body-less success), or null
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
    paControl('ct-name').value = '';
    paControl('ct-desc').value = '';
    paControl('ct-sort').value = '0';
    openModal('modal-create-topic');
}
/**
 * Fills and opens the edit-topic dialog from the trigger's data attributes.
 *
 * @param {HTMLElement} btn the edit button
 */
function openEditTopicModal(btn) {
    paControl('et-id').value = btn.getAttribute('data-pa-id') ?? '';
    paControl('et-version').value = btn.getAttribute('data-pa-version') ?? '';
    paControl('et-name').value = btn.getAttribute('data-pa-name') ?? '';
    paControl('et-desc').value = btn.getAttribute('data-pa-description') || '';
    paControl('et-sort').value = btn.getAttribute('data-pa-sort') ?? '';
    openModal('modal-edit-topic');
}
function createTopic() {
    const body = {
        name: paControl('ct-name').value,
        description: paControl('ct-desc').value || null,
        sortOrder: parseInt(paControl('ct-sort').value, 10) || 0,
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
    const id = paControl('et-id').value;
    const body = {
        version: parseInt(paControl('et-version').value, 10),
        name: paControl('et-name').value,
        description: paControl('et-desc').value || null,
        sortOrder: parseInt(paControl('et-sort').value, 10) || 0,
    };
    apiCall(`/api/proxy/promotion/topics/${id}`, 'PUT', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-edit-topic');
        paRefreshTopics();
    });
}
/**
 * Asks for confirmation, then deletes the topic of the trigger.
 *
 * @param {HTMLElement} btn the delete button
 */
function deleteTopic(btn) {
    const id = btn.getAttribute('data-pa-id');
    const name = btn.getAttribute('data-pa-name') || '';
    const fn = showKrtConfirm;
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

/**
 * Opens the create-category dialog for the trigger's topic.
 *
 * @param {HTMLElement} btn the add-category button
 */
function openCreateCategoryModal(btn) {
    paControl('cc-topic-id').value = btn.getAttribute('data-pa-topic-id') ?? '';
    paControl('cc-name').value = '';
    paControl('cc-desc').value = '';
    paControl('cc-sort').value = '0';
    openModal('modal-create-category');
}
/**
 * Fills and opens the edit-category dialog from the trigger's data attributes.
 *
 * @param {HTMLElement} btn the edit button
 */
function openEditCategoryModal(btn) {
    paControl('ec-id').value = btn.getAttribute('data-pa-id') ?? '';
    paControl('ec-version').value = btn.getAttribute('data-pa-version') ?? '';
    paControl('ec-topic-id').value = btn.getAttribute('data-pa-topic-id') ?? '';
    paControl('ec-name').value = btn.getAttribute('data-pa-name') ?? '';
    paControl('ec-desc').value = btn.getAttribute('data-pa-description') || '';
    paControl('ec-sort').value = btn.getAttribute('data-pa-sort') ?? '';
    openModal('modal-edit-category');
}
function createCategory() {
    const body = {
        topicId: paControl('cc-topic-id').value,
        name: paControl('cc-name').value,
        description: paControl('cc-desc').value || null,
        sortOrder: parseInt(paControl('cc-sort').value, 10) || 0,
    };
    apiCall('/api/proxy/promotion/categories', 'POST', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-create-category');
        paRefreshTopics();
    });
}
function updateCategory() {
    const id = paControl('ec-id').value;
    const body = {
        version: parseInt(paControl('ec-version').value, 10),
        topicId: paControl('ec-topic-id').value,
        name: paControl('ec-name').value,
        description: paControl('ec-desc').value || null,
        sortOrder: parseInt(paControl('ec-sort').value, 10) || 0,
    };
    apiCall(`/api/proxy/promotion/categories/${id}`, 'PUT', body).then((data) => {
        if (!data) return;
        toastSuccess(MSG_SAVED);
        closeModal('modal-edit-category');
        paRefreshTopics();
    });
}
/**
 * Asks for confirmation, then deletes the category of the trigger.
 *
 * @param {HTMLElement} btn the delete button
 */
function deleteCategory(btn) {
    const id = btn.getAttribute('data-pa-id');
    const name = btn.getAttribute('data-pa-name') || '';
    const fn = showKrtConfirm;
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

/**
 * @typedef {object} PaCardBody
 * @property {number} version the optimistic-lock version
 * @property {string | null} name the name
 * @property {string | null} description the description
 * @property {number} sortOrder the sort position
 * @property {string} [topicId] the owning topic, for a category
 */

/**
 * Reads the update body of a topic or category card from its edit button's data attributes.
 *
 * @param {Element} card the topic pane or category card
 * @param {string} trigger the `data-trigger` of the card's edit button
 * @returns {PaCardBody | null} the body, or null when the button or its version is missing
 */
function paReadCardBody(card, trigger) {
    const btn = card.querySelector(`[data-trigger="${trigger}"]`);
    if (!btn) return null;
    const version = parseInt(/** @type {string} */ (btn.getAttribute('data-pa-version')), 10);
    if (!Number.isFinite(version)) return null;
    /** @type {PaCardBody} */
    const body = {
        version,
        name: btn.getAttribute('data-pa-name'),
        description: btn.getAttribute('data-pa-description') || null,
        sortOrder: parseInt(/** @type {string} */ (btn.getAttribute('data-pa-sort')), 10) || 0,
    };
    const topicId = btn.getAttribute('data-pa-topic-id');
    if (topicId) body.topicId = topicId;
    return body;
}
/**
 * Swaps the sort positions of two sibling cards with two PUT requests.
 *
 * @param {Element} currentCard the card being moved
 * @param {Element} otherCard the adjacent card it trades places with
 * @param {string} putUrl the collection URL the cards are updated under
 * @param {'topic' | 'category'} kind the card kind
 * @returns {Promise<any>} the second response body, or null when a read or request failed
 */
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
/**
 * Finds the sibling element of the same tag next to a card.
 *
 * @param {Element} card the reference card
 * @param {'up' | 'down'} direction which neighbour to return
 * @returns {Element | null} the neighbour, or null at the edge
 */
function findAdjacentSibling(card, direction) {
    const sel = card.tagName.toLowerCase();
    const siblings = /** @type {HTMLElement} */ (card.parentElement).querySelectorAll(
        `:scope > ${sel}`,
    );
    const arr = Array.from(siblings);
    const idx = arr.indexOf(card);
    const target = direction === 'up' ? arr[idx - 1] : arr[idx + 1];
    return target || null;
}
/**
 * Moves a topic one position up or down.
 *
 * @param {HTMLElement} btn the move button
 * @param {'up' | 'down'} direction the direction
 */
function moveTopic(btn, direction) {
    const card = /** @type {Element} */ (btn.closest('.pa-topic-pane'));
    const other = findAdjacentSibling(card, direction);
    if (!other) return;
    swapSort(card, other, '/api/proxy/promotion/topics', 'topic').then((r) => {
        if (r === null) return;
        toastSuccess(MSG_SAVED);
        paRefreshTopics();
    });
}
/**
 * Moves a category one position up or down.
 *
 * @param {HTMLElement} btn the move button
 * @param {'up' | 'down'} direction the direction
 */
function moveCategory(btn, direction) {
    const card = /** @type {Element} */ (btn.closest('.pa-category-card'));
    const other = findAdjacentSibling(card, direction);
    if (!other) return;
    swapSort(card, other, '/api/proxy/promotion/categories', 'category').then((r) => {
        if (r === null) return;
        toastSuccess(MSG_SAVED);
        paRefreshTopics();
    });
}

/** @type {Set<HTMLTextAreaElement>} */
const paDirtyTextareas = new Set();

function paUpdateSaveAllBanner() {
    const banner = document.getElementById('pa-save-all-banner');
    const count = document.getElementById('pa-save-all-count');
    if (!banner || !count) return;
    const n = paDirtyTextareas.size;
    count.textContent = String(n);
    banner.classList.toggle('visible', n > 0);
}
/**
 * Tracks whether a level-content textarea differs from its saved text.
 *
 * @param {HTMLElement} el the textarea that fired `input`
 */
function paMarkDirty(el) {
    const textarea = /** @type {HTMLTextAreaElement} */ (el);
    const original = textarea.getAttribute('data-original') || '';
    const dirty = textarea.value !== original;
    textarea.classList.toggle('pa-dirty', dirty);
    if (dirty) paDirtyTextareas.add(textarea);
    else paDirtyTextareas.delete(textarea);
    paUpdateSaveAllBanner();
}
/**
 * Creates or updates the level content held by a textarea.
 *
 * @param {HTMLTextAreaElement} textarea the textarea carrying the content and its ids
 * @returns {Promise<any>} the saved DTO, or null when the request failed
 */
function paSaveLevelContent(textarea) {
    const categoryId = textarea.getAttribute('data-category-id');
    const level = textarea.getAttribute('data-level');
    const lcId = textarea.getAttribute('data-lc-id');
    const lcVersion = textarea.getAttribute('data-lc-version');
    const description = textarea.value;
    let p;
    if (lcId) {
        const body = {
            version: parseInt(/** @type {string} */ (lcVersion), 10) || 0,
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
/**
 * Saves the level content in the row of the clicked button.
 *
 * @param {HTMLElement} btn the save button
 */
function paSaveLevelContentFromButton(btn) {
    const row = btn.closest('.pa-level-row');
    if (!row) return;
    const textarea = /** @type {HTMLTextAreaElement | null} */ (
        row.querySelector('textarea.lc-textarea')
    );
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
    const fn = showKrtConfirm;
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
    krtEvents.on('click', 'pa-open-create-topic', openCreateTopicModal);
    krtEvents.on('click', 'pa-edit-topic', openEditTopicModal);
    krtEvents.on('click', 'pa-delete-topic', deleteTopic);
    krtEvents.on('click', 'pa-open-create-category', openCreateCategoryModal);
    krtEvents.on('click', 'pa-edit-category', openEditCategoryModal);
    krtEvents.on('click', 'pa-delete-category', deleteCategory);

    krtEvents.on('click', 'pa-create-topic', createTopic);
    krtEvents.on('click', 'pa-update-topic', updateTopic);
    krtEvents.on('click', 'pa-create-category', createCategory);
    krtEvents.on('click', 'pa-update-category', updateCategory);

    krtEvents.on('click', 'pa-close-modal', (btn) => {
        const id = btn.getAttribute('data-pa-modal');
        if (id) closeModal(id);
    });

    krtEvents.on('click', 'pa-move-topic-up', (btn) => {
        moveTopic(btn, 'up');
    });
    krtEvents.on('click', 'pa-move-topic-down', (btn) => {
        moveTopic(btn, 'down');
    });
    krtEvents.on('click', 'pa-move-cat-up', (btn) => {
        moveCategory(btn, 'up');
    });
    krtEvents.on('click', 'pa-move-cat-down', (btn) => {
        moveCategory(btn, 'down');
    });

    krtEvents.on('input', 'pa-level-dirty', paMarkDirty);
    krtEvents.on('click', 'pa-save-level-content', paSaveLevelContentFromButton);
    krtEvents.on('click', 'pa-save-all', paSaveAll);
    krtEvents.on('click', 'pa-discard-all', paDiscardAll);

    krtEvents.on('click', 'pa-select-topic', (row) => {
        paSelectTopic(row.getAttribute('data-pa-select-topic'), false);
    });

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
