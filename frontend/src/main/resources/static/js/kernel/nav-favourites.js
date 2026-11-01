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
import { showFrontendErrorToast } from '/js/kernel/toast.js';

const FAVOURITES_URL = '/navigation/favourites';
const STATE_SELECTOR = '[data-favourites-state]';
const WRITE_LOCK = 'nav-favourites';

/** The data-* fields of the state element; an empty value is rendered as an absent attribute. */
const STATE_FIELDS = ['loaded', 'version', 'keys', 'max', 'error', 'conflict', 'full'];

/**
 * The element that carries the favourites state of the page.
 *
 * @returns {HTMLElement | null} the state element, or null when the page has no navigation
 */
function stateElement() {
    return document.querySelector(STATE_SELECTOR);
}

/**
 * The stored favourite keys in order.
 *
 * @param {HTMLElement} state the state element
 * @returns {string[]} the keys
 */
function storedKeys(state) {
    return (state.dataset.keys || '').split(' ').filter((key) => key.length > 0);
}

/**
 * Puts every star in line with the stored favourites: pressed for a favourite, disabled for an
 * entry that cannot be added at the cap, and the label that says what a press does.
 */
function renderStars() {
    const state = stateElement();
    if (!state) {
        return;
    }
    const keys = storedKeys(state);
    const loaded = state.dataset.loaded === 'true';
    const full = keys.length >= Number(state.dataset.max || '0');
    document.querySelectorAll('[data-favourite-toggle]').forEach((star) => {
        if (!(star instanceof HTMLButtonElement)) {
            return;
        }
        const pressed = keys.includes(star.dataset.entryKey || '');
        const label = (pressed ? star.dataset.labelRemove : star.dataset.labelAdd) || '';
        star.setAttribute('aria-pressed', String(pressed));
        star.setAttribute('aria-label', label);
        star.title = !pressed && full ? state.dataset.full || label : label;
        star.disabled = !loaded || (!pressed && full);
    });
}

/**
 * Marks the favourite that opens the page being shown.
 */
function markCurrent() {
    const star = document.querySelector('[data-favourite-toggle]');
    const key = star instanceof HTMLElement ? star.dataset.entryKey : null;
    document.querySelectorAll('.nav-favourites a[data-entry-key]').forEach((link) => {
        const current = key !== null && link.getAttribute('data-entry-key') === key;
        link.classList.toggle('is-active', current);
        if (current) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });
}

/**
 * Re-reads the favourites from the server and swaps both modes' lists and the state in place.
 *
 * @returns {Promise<boolean>} whether the favourites were re-rendered
 */
async function reload() {
    const response = await krtFetch.get(FAVOURITES_URL, { accept: 'text/html' });
    if (!response || !response.ok) {
        return false;
    }
    const doc = krtFetch.parseTrustedDocument(await response.text());
    document.querySelectorAll('.nav-favourites[data-favourites-mode]').forEach((container) => {
        const mode = container.getAttribute('data-favourites-mode');
        const fresh = doc.querySelector(`.nav-favourites[data-favourites-mode="${mode}"]`);
        krtFetch.setTrustedHtml(container, fresh ? fresh.innerHTML : '');
    });
    const state = stateElement();
    const freshState = doc.querySelector(STATE_SELECTOR);
    if (state && freshState instanceof HTMLElement) {
        STATE_FIELDS.forEach((field) => {
            state.dataset[field] = freshState.dataset[field] || '';
        });
    }
    renderStars();
    markCurrent();
    return true;
}

/**
 * The favourite keys after the star's entry is added or removed, read from the state at send time.
 *
 * @param {HTMLElement} state the state element
 * @param {string} key the entry key
 * @param {boolean} wanted whether the entry should be a favourite afterwards
 * @returns {string[]} the new keys
 */
function nextKeys(state, key, wanted) {
    const keys = storedKeys(state).filter((stored) => stored !== key);
    return wanted ? [...keys, key] : keys;
}

/**
 * Stars or unstars the star's entry and re-renders the favourites; a version conflict drops the
 * change, reloads the favourites alone and says so.
 *
 * @param {HTMLElement} star the pressed star
 */
function toggle(star) {
    const state = stateElement();
    const key = star.dataset.entryKey;
    if (!state || !key) {
        return;
    }
    const wanted = star.getAttribute('aria-pressed') !== 'true';
    krtFetch.write({
        method: 'PUT',
        url: FAVOURITES_URL,
        payload: () => ({
            keys: nextKeys(state, key, wanted),
            version: Number(state.dataset.version || '0'),
        }),
        serialize: WRITE_LOCK,
        submitter: star,
        toast: false,
        errorMessage: state.dataset.error,
        onSuccess: () => reload(),
        onError: (status) => {
            if (status !== 409) {
                return false;
            }
            showFrontendErrorToast(state.dataset.conflict || '');
            reload();
            return true;
        },
    });
}

krtEvents.on('click', 'nav-favourite-toggle', toggle);
renderStars();
markCurrent();
