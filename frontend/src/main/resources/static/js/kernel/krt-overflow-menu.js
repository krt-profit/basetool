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
 * The panel a menu's toggle controls.
 *
 * @param {HTMLElement} menu the `[data-overflow-menu]` element
 * @returns {{ toggle: HTMLElement | null, panel: HTMLElement | null }} the two parts
 */
function partsOf(menu) {
    return {
        toggle: /** @type {HTMLElement | null} */ (menu.querySelector('.overflow-menu__toggle')),
        panel: /** @type {HTMLElement | null} */ (menu.querySelector('.overflow-menu__panel')),
    };
}

/**
 * The focusable entries of a panel, in document order.
 *
 * @param {HTMLElement} panel the menu panel
 * @returns {HTMLElement[]} the enabled entries
 */
function itemsOf(panel) {
    const all = panel.querySelectorAll('.overflow-menu__item');
    /** @type {HTMLElement[]} */
    const items = [];
    for (let i = 0; i < all.length; i++) {
        const el = /** @type {HTMLElement} */ (all[i]);
        if (el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true') continue;
        items.push(el);
    }
    return items;
}

/**
 * The vertical band a menu's panel may occupy: the viewport below the sticky page header, narrowed
 * to the body of an enclosing table and to every ancestor that clips its overflow.
 *
 * @param {HTMLElement} menu the menu being placed
 * @returns {{ top: number, bottom: number }} the band in viewport coordinates
 */
function boundsOf(menu) {
    let top = 0;
    let bottom = document.documentElement.clientHeight;
    const header = document.querySelector('body > header');
    if (header) top = Math.max(top, header.getBoundingClientRect().bottom);
    const table = menu.closest('table');
    if (table) {
        const rect = table.getBoundingClientRect();
        const head = table.querySelector('thead th');
        top = Math.max(top, head ? head.getBoundingClientRect().bottom : rect.top);
        bottom = Math.min(bottom, rect.bottom);
    }
    for (let node = menu.parentElement; node && node !== document.body; node = node.parentElement) {
        const style = window.getComputedStyle(node);
        if (style.overflowX === 'visible' && style.overflowY === 'visible') continue;
        const rect = node.getBoundingClientRect();
        top = Math.max(top, rect.top);
        bottom = Math.min(bottom, rect.bottom);
    }
    return { top, bottom };
}

/**
 * Opens the panel upwards when it does not fit below its toggle and there is more room above.
 *
 * @param {HTMLElement} menu the menu being opened
 * @param {HTMLElement} toggle the menu's toggle
 * @param {HTMLElement} panel the menu's visible panel
 */
function place(menu, toggle, panel) {
    panel.classList.remove('overflow-menu__panel--up');
    const { top, bottom } = boundsOf(menu);
    const anchor = toggle.getBoundingClientRect();
    const height = panel.getBoundingClientRect().height;
    const below = bottom - anchor.bottom;
    const above = anchor.top - top;
    if (height > below && above > below) panel.classList.add('overflow-menu__panel--up');
}

/**
 * Opens a menu, closing every other one first.
 *
 * @param {HTMLElement} menu the menu to open
 * @param {'first' | 'last' | null} focus which entry to focus, or none
 */
function open(menu, focus) {
    closeAll(menu);
    const { toggle, panel } = partsOf(menu);
    if (!toggle || !panel) return;
    panel.hidden = false;
    place(menu, toggle, panel);
    toggle.setAttribute('aria-expanded', 'true');
    if (!focus) return;
    const items = itemsOf(panel);
    const target = focus === 'last' ? items[items.length - 1] : items[0];
    if (target) target.focus();
}

/**
 * Closes a menu.
 *
 * @param {HTMLElement} menu the menu to close
 * @param {boolean} returnFocus whether to move focus back to the toggle
 */
function close(menu, returnFocus) {
    const { toggle, panel } = partsOf(menu);
    if (!toggle || !panel || panel.hidden) return;
    panel.hidden = true;
    toggle.setAttribute('aria-expanded', 'false');
    if (returnFocus) toggle.focus();
}

/**
 * Closes every open menu except one.
 *
 * @param {HTMLElement | null} [except] the menu to leave alone
 */
function closeAll(except) {
    const menus = document.querySelectorAll('[data-overflow-menu]');
    for (let i = 0; i < menus.length; i++) {
        const menu = /** @type {HTMLElement} */ (menus[i]);
        if (menu !== except) close(menu, false);
    }
}

/**
 * Whether a menu is open.
 *
 * @param {HTMLElement} menu the menu
 * @returns {boolean} true when its panel is shown
 */
function isOpen(menu) {
    const { panel } = partsOf(menu);
    return !!panel && !panel.hidden;
}

document.addEventListener(
    'click',
    (event) => {
        const target = /** @type {Element | null} */ (
            event.target instanceof Element ? event.target : null
        );
        const item = target ? target.closest('.overflow-menu__item') : null;
        if (!item) return;
        const menu = /** @type {HTMLElement | null} */ (item.closest('[data-overflow-menu]'));
        if (menu) close(menu, true);
    },
    true,
);

document.addEventListener('click', (event) => {
    const target = /** @type {Element | null} */ (
        event.target instanceof Element ? event.target : null
    );
    const toggle = target ? target.closest('.overflow-menu__toggle') : null;
    if (toggle) {
        const menu = /** @type {HTMLElement | null} */ (toggle.closest('[data-overflow-menu]'));
        if (!menu) return;
        if (isOpen(menu)) close(menu, false);
        else open(menu, event.detail === 0 ? 'first' : null);
        return;
    }
    if (!target || !target.closest('[data-overflow-menu]')) closeAll(null);
});

document.addEventListener('keydown', (event) => {
    const target = /** @type {Element | null} */ (
        event.target instanceof Element ? event.target : null
    );
    const menu = /** @type {HTMLElement | null} */ (
        target ? target.closest('[data-overflow-menu]') : null
    );
    if (!menu || !target) return;
    const { panel } = partsOf(menu);
    if (!panel) return;
    const onToggle = !!target.closest('.overflow-menu__toggle');
    if (event.key === 'Escape') {
        if (!isOpen(menu)) return;
        event.preventDefault();
        event.stopPropagation();
        close(menu, true);
        return;
    }
    if (event.key === 'Tab') {
        close(menu, false);
        return;
    }
    if (onToggle) {
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            open(menu, 'first');
        } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            open(menu, 'last');
        }
        return;
    }
    const items = itemsOf(panel);
    if (items.length === 0) return;
    const index = items.indexOf(
        /** @type {HTMLElement} */ (target.closest('.overflow-menu__item')),
    );
    /** @type {number | null} */
    let next = null;
    if (event.key === 'ArrowDown') next = (index + 1) % items.length;
    else if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    if (next === null) return;
    event.preventDefault();
    items[next].focus();
});

/** @type {KrtOverflowMenuApi} */
export const krtOverflowMenu = {
    /**
     * Closes every open overflow menu without moving focus.
     */
    closeAll() {
        closeAll(null);
    },
};
