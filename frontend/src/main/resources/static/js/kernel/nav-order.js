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

import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtReorder } from '/js/kernel/krt-reorder.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import {
    announce,
    focusFirst,
    NAV_RENDERED_EVENT,
    reloadMenu,
    stateElement,
    WRITE_LOCK,
} from '/js/kernel/nav-preferences.js';

const ORDER_URL = '/navigation/order';

/** Scope of the stored order that holds a mode's groups; every other scope is a group key. */
const GROUPS_SCOPE = 'groups';

const GROUP_SELECTOR = '.nav-group[data-group-key]:not(.nav-more):not(.nav-group-filter-only)';
const LINKS_SELECTOR = `${GROUP_SELECTOR} > .nav-group-links`;
const ROW_SELECTOR = '.nav-row[data-entry-key]';
const TOGGLE_SELECTOR = '[data-trigger="nav-edit-toggle"]';

/** @type {WeakSet<Element>} lists that already carry their reorder handlers */
const attached = new WeakSet();

/** @type {Map<Element, import('/js/kernel/krt-reorder.js').KrtReorderHandle>} */
const groupLists = new Map();

/**
 * @param {Element} element an element of the navigation list
 * @returns {'member' | 'admin'} the navigation mode the element belongs to
 */
function modeOf(element) {
    const mode = element.closest('.nav-mode');
    return mode && mode.getAttribute('data-nav-mode') === 'admin' ? 'admin' : 'member';
}

/**
 * @param {'member' | 'admin'} mode the navigation mode
 * @returns {string} the state element's dataset key of the mode's order version
 */
function versionField(mode) {
    return mode === 'admin' ? 'orderAdminVersion' : 'orderMemberVersion';
}

/**
 * Handles the 409 of an order write: the change is dropped, the lists are reloaded and the
 * member is told.
 *
 * @param {HTMLElement} state the preference state element
 * @param {number} status the response status
 * @returns {boolean} true when the status was handled here
 */
function conflict(state, status) {
    if (status !== 409) {
        return false;
    }
    showFrontendErrorToast(state.dataset.conflict || '');
    reloadMenu();
    return true;
}

/**
 * Saves the order of one scope and, on success, takes over the version the server returned. A
 * failed save puts the lists back as the server has them.
 *
 * @param {'member' | 'admin'} mode the navigation mode
 * @param {string} scope {@link GROUPS_SCOPE} or the key of the group whose links moved
 * @param {string[]} keys the scope's keys in their new order
 * @returns {Promise<boolean>} whether the server accepted the order
 */
async function saveScope(mode, scope, keys) {
    const state = stateElement();
    if (!state) {
        return false;
    }
    const field = versionField(mode);
    const result = await krtFetch.write({
        method: 'PUT',
        url: `${ORDER_URL}/${mode}/${scope}`,
        payload: () => ({ keys, version: Number(state.dataset[field] || '0') }),
        serialize: WRITE_LOCK,
        toast: false,
        errorMessage: state.dataset.orderError,
        onError: (status) => conflict(state, status),
    });
    if (!result.ok) {
        if (result.status !== 409) {
            await reloadMenu();
        }
        focusFirst([TOGGLE_SELECTOR]);
        return false;
    }
    state.dataset[field] = String(result.body?.version ?? state.dataset[field]);
    document
        .querySelectorAll(`[data-trigger="nav-order-reset"][data-mode="${mode}"]`)
        .forEach((button) => {
            if (button instanceof HTMLButtonElement) {
                button.disabled = false;
            }
        });
    return true;
}

/**
 * Makes the groups of one mode movable; the lists inside the groups are handled apart.
 *
 * @param {HTMLElement} container the mode's list container
 */
function attachGroups(container) {
    if (attached.has(container)) {
        return;
    }
    attached.add(container);
    const handle = krtReorder.attach({
        container,
        itemSelector: GROUP_SELECTOR,
        liveRegion: document.querySelector('[data-nav-live]'),
        owns: (target) => !target.closest('.nav-group-links'),
        persist: ({ rows }) =>
            saveScope(
                modeOf(container),
                GROUPS_SCOPE,
                rows.map((row) => row.dataset.groupKey || ''),
            ),
    });
    groupLists.set(container, handle);
}

/**
 * Makes the links of one group movable within the group.
 *
 * @param {HTMLElement} links the group's `.nav-group-links` element
 */
function attachLinks(links) {
    if (attached.has(links)) {
        return;
    }
    attached.add(links);
    const group = links.closest('.nav-group');
    const groupKey = group instanceof HTMLElement ? group.dataset.groupKey : undefined;
    if (!groupKey) {
        return;
    }
    krtReorder.attach({
        container: links,
        itemSelector: ROW_SELECTOR,
        liveRegion: document.querySelector('[data-nav-live]'),
        persist: ({ rows }) =>
            saveScope(
                modeOf(links),
                groupKey,
                rows.map((row) => row.dataset.entryKey || ''),
            ),
    });
}

/** Attaches the handlers to every list of the navigation that has none yet. */
function attachAll() {
    document
        .querySelectorAll('#sidebar .nav-mode[data-nav-mode]')
        .forEach((mode) => mode instanceof HTMLElement && attachGroups(mode));
    document
        .querySelectorAll(`#sidebar ${LINKS_SELECTOR}`)
        .forEach((links) => links instanceof HTMLElement && attachLinks(links));
    groupLists.forEach((handle) => handle.syncBounds());
}

/**
 * Clears the order of the control's mode back to the default order.
 *
 * @param {HTMLElement} button the pressed reset control
 */
function resetOrder(button) {
    const state = stateElement();
    if (!state) {
        return;
    }
    const mode = button.dataset.mode === 'admin' ? 'admin' : 'member';
    const field = versionField(mode);
    krtFetch.write({
        method: 'POST',
        url: `${ORDER_URL}/${mode}/reset`,
        payload: () => ({ version: Number(state.dataset[field] || '0') }),
        serialize: WRITE_LOCK,
        submitter: button,
        toast: false,
        errorMessage: state.dataset.orderError,
        onError: (status) => conflict(state, status),
        onSuccess: async () => {
            if (await reloadMenu()) {
                announce('announceOrderReset');
                focusFirst([TOGGLE_SELECTOR]);
            }
        },
    });
}

krtEvents.on('click', 'nav-order-reset', resetOrder);
document.addEventListener(NAV_RENDERED_EVENT, attachAll);
attachAll();
