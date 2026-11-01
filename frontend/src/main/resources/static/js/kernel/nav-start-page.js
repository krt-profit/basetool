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
import { applyAppearance } from '/js/kernel/nav-appearance.js';
import { NAV_START_PAGE_EVENT } from '/js/kernel/nav-preferences.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

const APPEARANCE_URL = '/navigation/appearance';
const STATE_SELECTOR = '[data-appearance-state]';
const TOGGLE_SELECTOR = '[data-start-page-toggle]';
const SELECT_SELECTOR = 'select[data-start-page-select]';
const WRITE_LOCK = 'nav-appearance';

/**
 * The stored start page of a mode.
 *
 * @param {HTMLElement} state the appearance state element
 * @param {string} mode `member` or `admin`
 * @returns {string} the entry key, or the empty string for the dashboard
 */
function storedPage(state, mode) {
    return (mode === 'admin' ? state.dataset.startAdmin : state.dataset.startMember) || '';
}

/**
 * Puts every start-page control in line with the stored start pages.
 */
function render() {
    const state = document.querySelector(STATE_SELECTOR);
    if (!(state instanceof HTMLElement)) {
        return;
    }
    const loaded = state.dataset.loaded === 'true';
    document.querySelectorAll(TOGGLE_SELECTOR).forEach((button) => {
        if (!(button instanceof HTMLButtonElement)) {
            return;
        }
        const pressed =
            storedPage(state, button.dataset.mode || 'member') === (button.dataset.entryKey || '');
        button.setAttribute('aria-pressed', String(pressed));
        button.textContent = (pressed ? button.dataset.labelRemove : button.dataset.labelAdd) || '';
        button.disabled = !loaded;
    });
    document.querySelectorAll(SELECT_SELECTOR).forEach((select) => {
        if (select instanceof HTMLSelectElement) {
            select.value = storedPage(state, select.dataset.mode || 'member');
            select.disabled = !loaded;
        }
    });
}

/**
 * Re-reads the stored appearance after a version conflict.
 */
async function reload() {
    const stored = await krtFetch.getJson(APPEARANCE_URL);
    if (stored) {
        applyAppearance(stored);
    }
}

/**
 * Stores the start page of a mode; the version and the other mode's page come from the state, and
 * a conflict drops the change, re-reads the section and says so.
 *
 * @param {string} mode `member` or `admin`
 * @param {string | null} key the entry key, or null for the dashboard
 * @param {HTMLElement} submitter the control the member used
 */
function write(mode, key, submitter) {
    const state = document.querySelector(STATE_SELECTOR);
    if (!(state instanceof HTMLElement)) {
        return;
    }
    krtFetch.write({
        method: 'PUT',
        url: `${APPEARANCE_URL}/start-page/${mode}`,
        payload: () => ({ key, version: Number(state.dataset.version || '0') }),
        serialize: WRITE_LOCK,
        submitter: submitter instanceof HTMLButtonElement ? submitter : undefined,
        toast: false,
        errorMessage: state.dataset.startError,
        onSuccess: (stored) => applyAppearance(stored),
        onError: (status) => {
            if (status !== 409) {
                render();
                return false;
            }
            showFrontendErrorToast(state.dataset.conflict || '');
            reload();
            return true;
        },
    });
}

/**
 * Sets or clears the start page that the header button belongs to.
 *
 * @param {HTMLElement} button the pressed header button
 */
function toggle(button) {
    const wanted = button.getAttribute('aria-pressed') !== 'true';
    write(button.dataset.mode || 'member', wanted ? button.dataset.entryKey || null : null, button);
}

krtEvents.on('click', 'nav-start-page-toggle', toggle);
document.addEventListener('change', (event) => {
    const target = event.target;
    if (target instanceof HTMLSelectElement && target.matches(SELECT_SELECTOR)) {
        write(target.dataset.mode || 'member', target.value || null, target);
    }
});
document.addEventListener(NAV_START_PAGE_EVENT, render);
