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
import { NAV_EDIT_EVENT } from '/js/kernel/nav-preferences.js';

const HOVER_OPEN_MS = 200;
const HOVER_CLOSE_MS = 300;

/**
 * The icon rail's flyouts (REQ-UI-026): disclosure buttons that open on hover after a short delay
 * and on click, Enter or Space, stay while hovered, close shortly after the pointer leaves, and
 * close on Escape with focus back on the icon (WCAG 1.4.13). A click pins a flyout until an outside
 * click, Escape or a followed link.
 */
document.addEventListener('DOMContentLoaded', () => {
    const rail = document.getElementById('nav-rail');
    if (!rail) {
        return;
    }
    const items = Array.from(rail.querySelectorAll('.nav-rail-item'));
    const adminButton = rail.querySelector('[data-testid="nav-rail-admin"]');

    /** @type {Element | null} */
    let openItem = null;
    let pinned = false;
    /** @type {ReturnType<typeof setTimeout> | null} */
    let openTimer = null;
    /** @type {ReturnType<typeof setTimeout> | null} */
    let closeTimer = null;

    /**
     * Whether the rail is the navigation currently shown; the style sheet hides it otherwise.
     *
     * @returns {boolean} true while the rail takes part in the layout
     */
    const railShown = () => window.getComputedStyle(rail).display !== 'none';

    /**
     * The button and the flyout of one rail item.
     *
     * @param {Element} item the `.nav-rail-item`
     * @returns {{ button: HTMLElement | null, flyout: HTMLElement | null }} its two parts
     */
    const partsOf = (item) => ({
        button: item.querySelector('.nav-rail-btn'),
        flyout: item.querySelector('.nav-rail-flyout'),
    });

    const clearTimers = () => {
        if (openTimer !== null) {
            clearTimeout(openTimer);
            openTimer = null;
        }
        if (closeTimer !== null) {
            clearTimeout(closeTimer);
            closeTimer = null;
        }
    };

    /**
     * Closes the open flyout.
     *
     * @param {boolean} restoreFocus whether focus returns to the icon that opened it
     */
    const close = (restoreFocus) => {
        clearTimers();
        if (!openItem) {
            return;
        }
        const { button, flyout } = partsOf(openItem);
        if (flyout) flyout.hidden = true;
        if (button) {
            button.setAttribute('aria-expanded', 'false');
            if (restoreFocus) button.focus({ preventScroll: true });
        }
        openItem = null;
        pinned = false;
    };

    /**
     * Opens the flyout of an item, closing any other, and aligns it with its icon.
     *
     * @param {Element} item the `.nav-rail-item` to open
     * @param {boolean} pin whether the flyout stays until it is dismissed
     */
    const open = (item, pin) => {
        clearTimers();
        if (openItem && openItem !== item) {
            close(false);
        }
        const { button, flyout } = partsOf(item);
        if (!button || !flyout) {
            return;
        }
        flyout.hidden = false;
        button.setAttribute('aria-expanded', 'true');
        if (!item.classList.contains('nav-rail-item--avatar')) {
            flyout.style.setProperty(
                '--nav-rail-flyout-top',
                `${button.getBoundingClientRect().top}px`,
            );
        }
        openItem = item;
        pinned = pin;
    };

    items.forEach((item) => {
        const { button } = partsOf(item);
        if (!button) {
            return;
        }
        button.addEventListener('click', () => {
            if (openItem === item) {
                if (pinned) {
                    close(false);
                } else {
                    pinned = true;
                }
                return;
            }
            open(item, true);
        });
        item.addEventListener('pointerenter', (event) => {
            if (/** @type {PointerEvent} */ (event).pointerType !== 'mouse' || !railShown()) {
                return;
            }
            clearTimers();
            if (openItem !== item) {
                openTimer = setTimeout(() => open(item, false), HOVER_OPEN_MS);
            }
        });
        item.addEventListener('pointerleave', (event) => {
            if (/** @type {PointerEvent} */ (event).pointerType !== 'mouse') {
                return;
            }
            clearTimers();
            if (openItem === item && !pinned) {
                closeTimer = setTimeout(() => close(false), HOVER_CLOSE_MS);
            }
        });
        item.addEventListener('focusout', (event) => {
            const next = /** @type {FocusEvent} */ (event).relatedTarget;
            if (openItem === item && next instanceof Node && !item.contains(next)) {
                close(false);
            }
        });
        item.addEventListener('click', (event) => {
            const target = event.target;
            if (target instanceof Element && target.closest('a[href]')) {
                close(false);
            }
        });
    });

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && openItem && !event.defaultPrevented) {
            event.preventDefault();
            close(true);
        }
    });
    document.addEventListener('pointerdown', (event) => {
        const target = event.target;
        if (openItem && pinned && target instanceof Node && !openItem.contains(target)) {
            close(false);
        }
    });
    document.addEventListener('krt:nav-mode-changed', () => close(false));

    const editToggle = () => document.querySelector('[data-trigger="nav-edit-toggle"]');

    /**
     * Opens the labelled sidebar as an overlay with „Anpassen“ on; „Fertig“ closes it again.
     */
    krtEvents.on('click', 'nav-rail-edit', () => {
        close(false);
        const toggle = editToggle();
        if (!(toggle instanceof HTMLElement) || !krtChrome.nav) {
            return;
        }
        krtChrome.nav.open(toggle);
        if (!document.getElementById('sidebar')?.classList.contains('is-editing')) {
            toggle.click();
        }
    });
    document.addEventListener(NAV_EDIT_EVENT, (event) => {
        const editing = /** @type {CustomEvent} */ (event).detail?.editing;
        if (!editing && railShown() && krtChrome.nav) {
            krtChrome.nav.close();
            const avatar = rail.querySelector('[data-testid="nav-rail-avatar"]');
            if (avatar instanceof HTMLElement) avatar.focus({ preventScroll: true });
        }
    });

    rail.querySelectorAll('[data-rail-switcher]').forEach((select) => {
        select.addEventListener('change', () => {
            const form = /** @type {HTMLSelectElement} */ (select).form;
            if (form) form.submit();
        });
    });

    /**
     * Shows the member groups or the administration groups and mirrors the mode on the shield.
     */
    const syncAdminMode = () => {
        const admin = !!krtChrome.nav && krtChrome.nav.isAdminMode();
        rail.querySelectorAll('[data-nav-rail-mode]').forEach((list) => {
            if (list instanceof HTMLElement) {
                list.hidden = (list.dataset.navRailMode === 'admin') !== admin;
            }
        });
        if (adminButton instanceof HTMLElement) {
            adminButton.setAttribute('aria-pressed', String(admin));
            adminButton.setAttribute(
                'aria-label',
                (admin ? adminButton.dataset.labelLeave : adminButton.dataset.labelEnter) || '',
            );
        }
        close(false);
    };
    if (adminButton) {
        adminButton.addEventListener('click', () => {
            if (krtChrome.nav) krtChrome.nav.setAdminMode(!krtChrome.nav.isAdminMode());
        });
    }
    document.addEventListener('krt:nav-admin-mode', syncAdminMode);
    syncAdminMode();

    const currentGroup = document.querySelector('#sidebar .nav-group.is-active');
    const currentKey = currentGroup ? currentGroup.getAttribute('data-group-key') : null;
    rail.querySelectorAll('.nav-rail-btn[data-group-key]').forEach((button) => {
        button.classList.toggle(
            'is-current',
            currentKey !== null && button.getAttribute('data-group-key') === currentKey,
        );
    });
    document.querySelectorAll('#sidebar a.is-active[data-entry-key]').forEach((link) => {
        const key = link.getAttribute('data-entry-key');
        const twin = rail.querySelector(`.nav-rail-flyout a[data-entry-key="${key}"]`);
        if (twin) twin.setAttribute('aria-current', 'page');
    });
});
