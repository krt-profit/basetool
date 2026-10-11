// @ts-check
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

(function () {
    'use strict';

    /**
     * The panel a menu's toggle controls.
     *
     * @param {HTMLElement} menu the `[data-overflow-menu]` element
     * @returns {{ toggle: HTMLElement | null, panel: HTMLElement | null }} the two parts
     */
    function partsOf(menu) {
        return {
            toggle: /** @type {HTMLElement | null} */ (
                menu.querySelector('.overflow-menu__toggle')
            ),
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
            if (el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true')
                continue;
            items.push(el);
        }
        return items;
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

    window.krtOverflowMenu = {
        /**
         * Closes every open overflow menu without moving focus.
         */
        closeAll() {
            closeAll(null);
        },
    };
})();
