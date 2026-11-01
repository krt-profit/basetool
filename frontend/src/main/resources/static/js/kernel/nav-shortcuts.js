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
import { krtChrome } from '/js/kernel/krt-chrome.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import {
    DASHBOARD_SLOT,
    LAST_FAVOURITE_SLOT,
    ariaKeys,
    decideShortcut,
    keyLabel,
    shortcutSlot,
} from '/js/kernel/krt-shortcuts.js';
import { NAV_RENDERED_EVENT, NAV_SHORTCUTS_EVENT } from '/js/kernel/nav-preferences.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

const STATE_SELECTOR = '[data-appearance-state]';
const FAVOURITE_ROWS = '#sidebar .nav-favourites[data-favourites-mode="member"] .nav-row';
const RAIL_FAVOURITES = '#nav-rail-flyout-favourites a.nav-link';
const DASHBOARD_LINK = 'header .brand';
const DIALOG_ID = 'nav-shortcuts';
const TITLE_MARK = 'shortcutTitle';

const mac = /mac|iphone|ipad/i.test(navigator.platform || navigator.userAgent);

/**
 * The element that carries the appearance state of the page.
 *
 * @returns {HTMLElement | null} the state element, or null when the page has no navigation
 */
function stateElement() {
    return document.querySelector(STATE_SELECTOR);
}

/**
 * Whether the member's switch is on and the stored appearance was read; a failed read leaves the
 * shortcuts off.
 *
 * @returns {boolean} true while the keys may act
 */
function switchedOn() {
    const state = stateElement();
    return !!state && state.dataset.loaded === 'true' && state.dataset.shortcuts === 'true';
}

/**
 * @returns {boolean} whether the navigation shows the administration groups
 */
function inAdminMode() {
    return !!krtChrome.nav && krtChrome.nav.isAdminMode();
}

/**
 * The favourites of the member group in order, as the navigation currently renders them.
 *
 * @returns {{ link: HTMLAnchorElement | null }[]} per favourite its link, or null while greyed
 */
function favouriteSlots() {
    return Array.from(document.querySelectorAll(FAVOURITE_ROWS)).map((row) => {
        const link = row.querySelector('a.nav-link');
        return { link: link instanceof HTMLAnchorElement ? link : null };
    });
}

/**
 * Puts or removes the key hint of one link.
 *
 * @param {Element | null} link the link
 * @param {number} slot the slot whose key it gets
 * @param {boolean} active whether the key works now
 */
function markLink(link, slot, active) {
    if (!(link instanceof HTMLElement)) return;
    if (active) {
        link.setAttribute('aria-keyshortcuts', ariaKeys(slot));
        if (!link.hasAttribute('title')) {
            link.setAttribute('title', keyLabel(slot, mac));
            link.dataset[TITLE_MARK] = 'true';
        }
        return;
    }
    link.removeAttribute('aria-keyshortcuts');
    if (link.dataset[TITLE_MARK]) {
        link.removeAttribute('title');
        delete link.dataset[TITLE_MARK];
    }
}

/**
 * Shows the key on the dashboard link and the first nine favourites where the keys work, and
 * nowhere else.
 */
function syncHints() {
    const active = switchedOn() && !inAdminMode();
    markLink(document.querySelector(DASHBOARD_LINK), DASHBOARD_SLOT, active);
    favouriteSlots().forEach((slot, index) => {
        markLink(slot.link, index + 1, active && index < LAST_FAVOURITE_SLOT);
    });
    document.querySelectorAll(RAIL_FAVOURITES).forEach((link, index) => {
        markLink(link, index + 1, active && index < LAST_FAVOURITE_SLOT);
    });
}

/**
 * Shows the line of the dialog that matches the state: the keys, „ausgeschaltet“ or „nur im
 * Mitgliedermodus“.
 *
 * @param {HTMLElement} dialog the dialog
 */
function syncDialog(dialog) {
    const status = !switchedOn() ? 'off' : inAdminMode() ? 'member' : 'on';
    dialog.querySelectorAll('[data-shortcuts-status]').forEach((el) => {
        if (el instanceof HTMLElement) el.hidden = el.dataset.shortcutsStatus !== status;
    });
}

/**
 * Writes the key labels of the platform into the dialog.
 *
 * @param {HTMLElement} dialog the dialog
 */
function labelKeys(dialog) {
    dialog.querySelectorAll('[data-shortcut-slot]').forEach((el) => {
        el.textContent = keyLabel(Number(el.getAttribute('data-shortcut-slot')), mac);
    });
}

/**
 * Says that a slot opens nothing.
 *
 * @param {'unavailable' | 'empty'} reason why
 * @param {number} slot the slot
 */
function explain(reason, slot) {
    const state = stateElement();
    if (!state) return;
    const pattern =
        reason === 'empty' ? state.dataset.shortcutEmpty : state.dataset.shortcutUnavailable;
    showFrontendErrorToast(
        krtI18nText(pattern, `data-shortcut-${reason}`).replace('{n}', String(slot)),
    );
}

/**
 * Handles one key press.
 *
 * @param {KeyboardEvent} event the key press
 */
function onKeydown(event) {
    const slot = shortcutSlot(event);
    if (slot === null) return;
    const slots = favouriteSlots();
    const decision = decideShortcut(slot, {
        enabled: switchedOn(),
        adminMode: inAdminMode(),
        dialogOpen: krtModal.topmost() !== null,
        favourites: slots.map((s) => s.link !== null),
    });
    if (decision.action === 'ignore') return;
    event.preventDefault();
    if (decision.action === 'dashboard') {
        const home = document.querySelector(DASHBOARD_LINK);
        if (home instanceof HTMLElement) home.click();
    } else if (decision.action === 'open') {
        slots[decision.slot - 1].link?.click();
    } else {
        explain(decision.action, decision.slot);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const dialog = document.getElementById(DIALOG_ID);
    if (dialog) {
        labelKeys(dialog);
        const open = () => {
            syncDialog(dialog);
            krtModal.open(dialog);
        };
        krtChrome.shortcuts = { open };
        krtEvents.on('click', 'open-shortcuts', () => {
            if (krtChrome.nav) krtChrome.nav.close();
            open();
        });
    }
    syncHints();
    document.addEventListener('keydown', onKeydown);
    [NAV_RENDERED_EVENT, 'krt:nav-admin-mode', NAV_SHORTCUTS_EVENT].forEach((name) => {
        document.addEventListener(name, syncHints);
    });
});
