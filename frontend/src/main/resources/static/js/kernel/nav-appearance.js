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
import { NAV_SHORTCUTS_EVENT } from '/js/kernel/nav-preferences.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

const APPEARANCE_URL = '/navigation/appearance';
const STATE_SELECTOR = '[data-appearance-state]';
const SWITCH_SELECTOR = '[data-segmented^="desktop-mode-"]';
const SHORTCUTS_SELECTOR = 'input[data-shortcuts-switch]';
const WRITE_LOCK = 'nav-appearance';

/**
 * The element that carries the appearance state of the page.
 *
 * @returns {HTMLElement | null} the state element, or null when the page has no navigation
 */
function stateElement() {
    return document.querySelector(STATE_SELECTOR);
}

/**
 * Puts the page in line with a stored appearance: the mode on the body, the version in the state
 * and the selected option of every switch, then tells the navigation to re-evaluate its layout.
 *
 * @param {{ desktopMode: string, shortcutsEnabled: boolean, version: number }} stored the
 *     appearance section as stored
 */
function apply(stored) {
    const state = stateElement();
    const mode = String(stored.desktopMode).toLowerCase();
    if (state) {
        state.dataset.version = String(stored.version);
        state.dataset.mode = mode;
        state.dataset.shortcuts = String(stored.shortcutsEnabled);
    }
    document.body.dataset.navMode = mode;
    document.querySelectorAll(`${SWITCH_SELECTOR} input[type="radio"]`).forEach((input) => {
        if (input instanceof HTMLInputElement) {
            input.checked = input.value === mode;
        }
    });
    document.querySelectorAll(SHORTCUTS_SELECTOR).forEach((input) => {
        if (input instanceof HTMLInputElement) {
            input.checked = stored.shortcutsEnabled;
        }
    });
    document.dispatchEvent(new CustomEvent('krt:nav-mode-changed', { detail: { mode } }));
    document.dispatchEvent(new CustomEvent(NAV_SHORTCUTS_EVENT));
}

/**
 * Re-reads the stored appearance and applies it, after a version conflict.
 */
async function reload() {
    const stored = await krtFetch.getJson(APPEARANCE_URL);
    if (stored) {
        apply(stored);
    }
}

/**
 * Stores a change of the appearance and applies it in place; a version conflict drops the change,
 * re-reads the section alone and says so. The section is replaced as a whole, so the value the
 * change leaves alone is echoed from the state.
 *
 * @param {HTMLInputElement} input the control the member used
 * @param {{ desktopMode?: string, shortcutsEnabled?: boolean }} change the changed value
 */
function write(input, change) {
    const state = stateElement();
    if (!state) {
        return;
    }
    krtFetch.write({
        method: 'PUT',
        url: APPEARANCE_URL,
        payload: () => ({
            desktopMode: (change.desktopMode ?? state.dataset.mode ?? 'drawer').toUpperCase(),
            shortcutsEnabled: change.shortcutsEnabled ?? state.dataset.shortcuts === 'true',
            version: Number(state.dataset.version || '0'),
        }),
        serialize: WRITE_LOCK,
        submitter: input,
        toast: false,
        errorMessage: state.dataset.error,
        onSuccess: (stored) => apply(stored),
        onError: (status) => {
            if (status !== 409) {
                reload();
                return false;
            }
            showFrontendErrorToast(state.dataset.conflict || '');
            reload();
            return true;
        },
    });
}

/**
 * Switches the options off while the stored appearance could not be read, so a write cannot start
 * from a version that is not known.
 */
function disableUnlessLoaded() {
    const state = stateElement();
    if (state && state.dataset.loaded === 'true') {
        return;
    }
    document
        .querySelectorAll(`${SWITCH_SELECTOR} input[type="radio"], ${SHORTCUTS_SELECTOR}`)
        .forEach((input) => {
            if (input instanceof HTMLInputElement) {
                input.disabled = true;
            }
        });
}

disableUnlessLoaded();

document.addEventListener('change', (event) => {
    const target = event.target;
    if (
        target instanceof HTMLInputElement &&
        target.type === 'radio' &&
        target.closest(SWITCH_SELECTOR)
    ) {
        write(target, { desktopMode: target.value });
    } else if (target instanceof HTMLInputElement && target.matches(SHORTCUTS_SELECTOR)) {
        write(target, { shortcutsEnabled: target.checked });
    }
});
