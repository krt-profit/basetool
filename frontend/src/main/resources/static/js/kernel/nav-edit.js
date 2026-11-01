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
import {
    announce,
    focusFirst,
    hideChange,
    NAV_EDIT_EVENT,
    stateElement,
    writePreferences,
} from '/js/kernel/nav-preferences.js';

/** Class on the sidebar while „Anpassen“ is on. */
const EDITING_CLASS = 'is-editing';

const TOGGLE_SELECTOR = '[data-trigger="nav-edit-toggle"]';

/**
 * @returns {HTMLElement | null} the sidebar
 */
function sidebar() {
    return document.getElementById('sidebar');
}

/**
 * Turns „Anpassen“ on or off: the sidebar shows the per-link controls and the toggle names the
 * way back.
 *
 * @param {HTMLElement} toggle the Anpassen / Fertig button
 */
function toggleEditing(toggle) {
    const root = sidebar();
    if (!root) {
        return;
    }
    const on = !root.classList.contains(EDITING_CLASS);
    root.classList.toggle(EDITING_CLASS, on);
    const label = toggle.querySelector('[data-nav-edit-label]');
    if (label) {
        label.textContent = (on ? toggle.dataset.labelDone : toggle.dataset.labelStart) || '';
    }
    const region = document.querySelector('[data-nav-live]');
    if (region) {
        region.textContent = (on ? toggle.dataset.announceOn : toggle.dataset.announceOff) || '';
    }
    document.dispatchEvent(new CustomEvent(NAV_EDIT_EVENT, { detail: { editing: on } }));
}

/**
 * Hides the control's page: it moves into „Mehr“, and a favourite is unstarred in the same write.
 *
 * @param {HTMLElement} button the pressed hide control
 */
function hide(button) {
    const key = button.dataset.entryKey;
    if (!stateElement() || !key) {
        return;
    }
    writePreferences((favourites, hidden) => hideChange(favourites, hidden, key), {
        submitter: button,
        onDone: () => {
            announce('announceHidden', button.dataset.label);
            focusFirst([
                `#sidebar [data-nav-action="unhide"][data-entry-key="${key}"]`,
                TOGGLE_SELECTOR,
            ]);
        },
    });
}

/**
 * Shows a hidden page again from „Mehr“.
 *
 * @param {HTMLElement} button the pressed show control
 */
function unhide(button) {
    const key = button.dataset.entryKey;
    if (!stateElement() || !key) {
        return;
    }
    writePreferences(
        (_favourites, hidden) => ({ hidden: hidden.filter((stored) => stored !== key) }),
        {
            submitter: button,
            onDone: () => {
                announce('announceShown', button.dataset.label);
                focusFirst([
                    `#sidebar [data-trigger="nav-hide"][data-entry-key="${key}"]`,
                    `#sidebar .nav-group:not(.nav-more) a.nav-link[data-entry-key="${key}"]`,
                    TOGGLE_SELECTOR,
                ]);
            },
        },
    );
}

/**
 * Shows every hidden page again.
 *
 * @param {HTMLElement} button the pressed reset control
 */
function unhideAll(button) {
    writePreferences(() => ({ hidden: [] }), {
        submitter: button,
        onDone: () => {
            announce('announceAllShown');
            focusFirst([TOGGLE_SELECTOR]);
        },
    });
}

/**
 * Removes every favourite.
 *
 * @param {HTMLElement} button the pressed reset control
 */
function clearFavourites(button) {
    writePreferences(() => ({ favourites: [] }), {
        submitter: button,
        onDone: () => {
            announce('announceFavouritesCleared');
            focusFirst([TOGGLE_SELECTOR]);
        },
    });
}

krtEvents.on('click', 'nav-edit-toggle', toggleEditing);
krtEvents.on('click', 'nav-hide', hide);
krtEvents.on('click', 'nav-unhide', unhide);
krtEvents.on('click', 'nav-unhide-all', unhideAll);
krtEvents.on('click', 'nav-clear-favourites', clearFavourites);
