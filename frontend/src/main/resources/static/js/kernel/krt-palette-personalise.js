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

/**
 * What the quick access does to the member's navigation preferences: the favourite toggle, the
 * actions on the current page and the actions pages contribute (REQ-UI-033). Every write goes
 * through the favourites write of the drawer, so the 409 handling and the in-place re-render are
 * the same.
 */

import { krtChrome } from '/js/kernel/krt-chrome.js';
import {
    favouriteKeys,
    stateElement,
    starChange,
    writePreferences,
} from '/js/kernel/nav-preferences.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

const CONTEXT_ACTION_ATTRIBUTE = 'data-palette-action';
const EDITING_CLASS = 'is-editing';

/**
 * @typedef {object} PersonaliseAction
 * @property {string} label the visible name
 * @property {string} icon the sprite symbol without its `krt-icon-` prefix
 * @property {() => void} run what the action does
 */

/**
 * The star of the page being shown: the one outside the drawer and the palette.
 *
 * @returns {HTMLButtonElement | null} the star, or null on a page that is no entry
 */
function currentStar() {
    const star = Array.from(document.querySelectorAll('[data-favourite-toggle]')).find(
        (candidate) => !candidate.closest('#sidebar') && !candidate.closest('#nav-palette'),
    );
    return star instanceof HTMLButtonElement ? star : null;
}

/**
 * Whether an entry is a favourite.
 *
 * @param {string} key the entry key
 * @returns {boolean} true when it is one of the stored favourites
 */
export function isFavourite(key) {
    const state = stateElement();
    return state !== null && favouriteKeys(state).includes(key);
}

/**
 * Stars or unstars an entry through the favourites write; at the cap, an entry that is no
 * favourite yet is refused with the cap's message instead.
 *
 * @param {string} key the entry key
 * @param {(favourite: boolean) => void} [onDone] runs with the new state once the write and the
 *     re-render succeeded
 * @returns {boolean} whether a write was sent
 */
export function toggleFavourite(key, onDone) {
    const state = stateElement();
    if (!state || state.dataset.loaded !== 'true') return false;
    const wanted = !favouriteKeys(state).includes(key);
    if (wanted && favouriteKeys(state).length >= Number(state.dataset.max || '0')) {
        showFrontendErrorToast(state.dataset.full || '');
        return false;
    }
    writePreferences((favourites, hidden) => starChange(favourites, hidden, key, wanted), {
        onDone: () => {
            if (onDone) onDone(wanted);
        },
    });
    return true;
}

/**
 * The drawer's hide control for the page being shown: the one of its own entry, or, for a tab of a
 * merged page, the one of the menu link the drawer marks as current, which hides the whole page.
 *
 * @param {string} key the entry key of the page being shown
 * @returns {HTMLElement | null} the control, or null when the page cannot be hidden
 */
function hideControlOf(key) {
    const own = document.querySelector(
        `#sidebar [data-trigger="nav-hide"][data-entry-key="${CSS.escape(key)}"]`,
    );
    if (own instanceof HTMLElement) return own;
    const active = document.querySelector(
        '#sidebar .nav-group:not(.nav-more) a.nav-link.is-active',
    );
    const row = active ? active.closest('.nav-row') : null;
    const owner = row ? row.querySelector('[data-trigger="nav-hide"]') : null;
    return owner instanceof HTMLElement ? owner : null;
}

/**
 * Turns the drawer's „Anpassen“ on, opening the drawer first.
 */
function openCustomising() {
    const toggle = document.querySelector('#sidebar [data-trigger="nav-edit-toggle"]');
    if (!(toggle instanceof HTMLElement)) return;
    if (krtChrome.nav) krtChrome.nav.open(toggle);
    const sidebar = document.getElementById('sidebar');
    if (sidebar && !sidebar.classList.contains(EDITING_CLASS)) toggle.click();
}

/**
 * The actions on the page being shown: star or unstar it, hide it and open „Anpassen“. Each is
 * offered only where its control exists, so the gates of the drawer decide.
 *
 * @param {string} customiseLabel the name of the „Anpassen“ action
 * @param {(name: string, favourite: boolean) => void} onToggled runs with the page's name and its
 *     new favourite state once the star action's write and re-render succeeded
 * @returns {PersonaliseAction[]} the actions in display order
 */
export function pageActions(customiseLabel, onToggled) {
    /** @type {PersonaliseAction[]} */
    const actions = [];
    const state = stateElement();
    if (!state || state.dataset.loaded !== 'true') return actions;
    const star = currentStar();
    const key = star ? star.dataset.entryKey || '' : '';
    if (star && key) {
        const pressed = favouriteKeys(state).includes(key);
        const full = favouriteKeys(state).length >= Number(state.dataset.max || '0');
        const label = (pressed ? star.dataset.labelRemove : star.dataset.labelAdd) || '';
        if (label && (pressed || !full)) {
            actions.push({
                label,
                icon: 'star',
                run: () => {
                    toggleFavourite(key, (favourite) => {
                        onToggled(star.dataset.label || '', favourite);
                    });
                },
            });
        }
        const hide = hideControlOf(key);
        if (hide) {
            const hideLabel = hide.getAttribute('aria-label') || '';
            if (hideLabel) {
                actions.push({ label: hideLabel, icon: 'eye-off', run: () => hide.click() });
            }
        }
    }
    if (document.querySelector('#sidebar [data-trigger="nav-edit-toggle"]')) {
        actions.push({ label: customiseLabel, icon: 'edit', run: openCustomising });
    }
    return actions;
}

/**
 * The actions the page being shown contributes: every element marked `data-palette-action`, whose
 * value is the action's name. An element may only navigate or open a dialog or form the page
 * already has, so choosing the action is a click on it and never a mutation of its own.
 *
 * @returns {PersonaliseAction[]} the actions in document order
 */
export function contextActions() {
    return Array.from(document.querySelectorAll(`[${CONTEXT_ACTION_ATTRIBUTE}]`))
        .filter(
            (el) =>
                el instanceof HTMLElement &&
                !el.closest('#sidebar') &&
                !el.closest('#nav-palette') &&
                !(el instanceof HTMLButtonElement && el.disabled) &&
                (el.getAttribute(CONTEXT_ACTION_ATTRIBUTE) || '').trim() !== '',
        )
        .map((el) => ({
            label: (el.getAttribute(CONTEXT_ACTION_ATTRIBUTE) || '').trim(),
            icon: el.getAttribute('data-palette-icon') || 'list',
            run: () => {
                if (el instanceof HTMLElement) el.click();
            },
        }));
}
