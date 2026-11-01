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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

const MENU_URL = '/navigation/menu';
const FAVOURITES_URL = '/navigation/favourites';
const HIDDEN_URL = '/navigation/hidden';
const BOTH_URL = '/navigation/favourites-and-hidden';
const STATE_SELECTOR = '[data-favourites-state]';

/** Lock scope that queues every preference write of the page, so each echoes a current version. */
export const WRITE_LOCK = 'nav-preferences';

/** Fired on the document after the personalised lists were swapped in place. */
export const NAV_RENDERED_EVENT = 'krt:nav-rendered';

/** Fired on the document when „Anpassen“ turns on or off. */
export const NAV_EDIT_EVENT = 'krt:nav-edit-changed';

/** The data-* fields of the state element; an empty value is rendered as an absent attribute. */
const STATE_FIELDS = [
    'loaded',
    'version',
    'keys',
    'max',
    'hiddenVersion',
    'hiddenKeys',
    'error',
    'conflict',
    'full',
    'hiddenError',
    'orderMemberVersion',
    'orderAdminVersion',
    'orderError',
    'tabbarMemberVersion',
    'tabbarMemberStored',
    'tabbarMemberShown',
    'tabbarAdminVersion',
    'tabbarAdminStored',
    'tabbarAdminShown',
    'tabbarError',
];

/**
 * Says something to assistive technology without moving focus.
 *
 * @param {string} dataKey the dataset key of the message pattern on the live region
 * @param {string} [name] the page name that replaces `{name}` in the pattern
 */
export function announce(dataKey, name = '') {
    const region = document.querySelector('[data-nav-live]');
    const pattern = region instanceof HTMLElement ? region.dataset[dataKey] : undefined;
    if (region && pattern) {
        region.textContent = pattern.replace('{name}', name);
    }
}

/**
 * Moves focus to the first of the selectors that matches a visible element, so that the keyboard
 * position survives a control that was swapped out.
 *
 * @param {string[]} selectors candidates in order of preference
 */
export function focusFirst(selectors) {
    for (const selector of selectors) {
        const element = document.querySelector(selector);
        if (element instanceof HTMLElement && element.getClientRects().length > 0) {
            element.focus({ preventScroll: false });
            return;
        }
    }
}

/**
 * @typedef {object} NavChange
 * @property {string[]} [favourites] the new favourite keys; absent when the section stays
 * @property {string[]} [hidden] the new hidden page keys; absent when the section stays
 */

/**
 * The element that carries the preference state of the page.
 *
 * @returns {HTMLElement | null} the state element, or null when the page has no navigation
 */
export function stateElement() {
    return document.querySelector(STATE_SELECTOR);
}

/**
 * @param {string | undefined} value a space-separated key list
 * @returns {string[]} the keys
 */
function split(value) {
    return (value || '').split(' ').filter((key) => key.length > 0);
}

/**
 * The stored favourite keys in order.
 *
 * @param {HTMLElement} state the state element
 * @returns {string[]} the keys
 */
export function favouriteKeys(state) {
    return split(state.dataset.keys);
}

/**
 * The stored hidden page keys.
 *
 * @param {HTMLElement} state the state element
 * @returns {string[]} the keys
 */
export function hiddenKeys(state) {
    return split(state.dataset.hiddenKeys);
}

/**
 * The change that stars or unstars a page. Starring a hidden page shows it again in the same
 * change, as a page is never both.
 *
 * @param {string[]} favourites the stored favourites
 * @param {string[]} hidden the stored hidden pages
 * @param {string} key the entry key
 * @param {boolean} wanted whether the page should be a favourite afterwards
 * @returns {NavChange} the change
 */
export function starChange(favourites, hidden, key, wanted) {
    const rest = favourites.filter((stored) => stored !== key);
    if (!wanted) {
        return { favourites: rest };
    }
    return hidden.includes(key)
        ? { favourites: [...rest, key], hidden: hidden.filter((stored) => stored !== key) }
        : { favourites: [...rest, key] };
}

/**
 * The change that hides a page. Hiding a favourite removes the favourite in the same change.
 *
 * @param {string[]} favourites the stored favourites
 * @param {string[]} hidden the stored hidden pages
 * @param {string} key the entry key
 * @returns {NavChange} the change
 */
export function hideChange(favourites, hidden, key) {
    const next = [...hidden.filter((stored) => stored !== key), key];
    return favourites.includes(key)
        ? { favourites: favourites.filter((stored) => stored !== key), hidden: next }
        : { hidden: next };
}

/**
 * Swaps both modes' lists and the preference state for the server's current rendering and tells
 * the rest of the page.
 *
 * @returns {Promise<boolean>} whether the lists were re-rendered
 */
export async function reloadMenu() {
    const response = await krtFetch.get(MENU_URL, { accept: 'text/html' });
    if (!response || !response.ok) {
        return false;
    }
    const doc = krtFetch.parseTrustedDocument(await response.text());
    document.querySelectorAll('#sidebar .nav-mode[data-nav-mode]').forEach((container) => {
        const mode = container.getAttribute('data-nav-mode');
        const fresh = doc.querySelector(`.nav-mode[data-nav-mode="${mode}"]`);
        krtFetch.setTrustedHtml(container, fresh ? fresh.innerHTML : '');
    });
    const pages = document.querySelector('.palette-source-pages');
    const freshPages = doc.querySelector('.palette-source-pages');
    if (pages && freshPages) {
        krtFetch.setTrustedHtml(pages, freshPages.innerHTML);
    }
    document.querySelectorAll('[data-nav-swap]').forEach((host) => {
        const fresh = doc.querySelector(`[data-nav-swap="${host.getAttribute('data-nav-swap')}"]`);
        if (fresh) {
            krtFetch.setTrustedHtml(host, fresh.innerHTML);
        }
    });
    const state = stateElement();
    const freshState = doc.querySelector(STATE_SELECTOR);
    if (state && freshState instanceof HTMLElement) {
        STATE_FIELDS.forEach((field) => {
            state.dataset[field] = freshState.dataset[field] || '';
        });
    }
    document.dispatchEvent(new CustomEvent(NAV_RENDERED_EVENT));
    return true;
}

/**
 * Writes a change of the favourites, the hidden pages or both through the endpoint that checks
 * exactly the versions involved. The change is computed when the write is sent, so a queued write
 * starts from the state the previous one left. A version conflict drops the change, reloads the
 * lists and says so.
 *
 * @param {(favourites: string[], hidden: string[]) => NavChange} compute derives the change from
 *     the stored state
 * @param {object} [options] write options
 * @param {HTMLElement} [options.submitter] the control to disable while the write is in flight
 * @param {() => void} [options.onDone] runs after the write and the reload succeeded
 */
export function writePreferences(compute, options = {}) {
    const state = stateElement();
    if (!state) {
        return;
    }
    const plan = () => {
        const favourites = favouriteKeys(state);
        const hidden = hiddenKeys(state);
        const change = compute(favourites, hidden);
        return {
            favourites: change.favourites ?? favourites,
            hidden: change.hidden ?? hidden,
            favouritesChanged: change.favourites !== undefined,
            hiddenChanged: change.hidden !== undefined,
        };
    };
    const favouritesVersion = () => Number(state.dataset.version || '0');
    const hiddenVersion = () => Number(state.dataset.hiddenVersion || '0');
    krtFetch.write({
        method: 'PUT',
        url: () => {
            const next = plan();
            if (next.favouritesChanged && next.hiddenChanged) {
                return BOTH_URL;
            }
            return next.hiddenChanged ? HIDDEN_URL : FAVOURITES_URL;
        },
        payload: () => {
            const next = plan();
            const favourites = { keys: next.favourites, version: favouritesVersion() };
            const hidden = { keys: next.hidden, version: hiddenVersion() };
            if (next.favouritesChanged && next.hiddenChanged) {
                return { favourites, hidden };
            }
            return next.hiddenChanged ? hidden : favourites;
        },
        serialize: WRITE_LOCK,
        submitter: options.submitter,
        toast: false,
        errorMessage: state.dataset.hiddenError || state.dataset.error,
        onSuccess: async () => {
            if ((await reloadMenu()) && options.onDone) {
                options.onDone();
            }
        },
        onError: (status) => {
            if (status !== 409) {
                return false;
            }
            showFrontendErrorToast(state.dataset.conflict || '');
            reloadMenu();
            return true;
        },
    });
}
