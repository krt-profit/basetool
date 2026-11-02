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
 * The actions the quick access offers besides pages: the server-rendered links, the drawer and
 * shortcut controls, the logout and the ones the current page contributes (REQ-UI-033).
 */

import { krtChrome } from '/js/kernel/krt-chrome.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { makeItem, textOf } from '/js/kernel/krt-palette-items.js';
import { contextActions, pageActions } from '/js/kernel/krt-palette-personalise.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

/**
 * The actions behind the server-rendered links of one source list.
 *
 * @param {HTMLElement} paletteRoot the palette's root, which carries the source lists
 * @param {string} selector the source list
 * @returns {PaletteItem[]} one action per link, in document order
 */
function linkActions(paletteRoot, selector) {
    const crumb = paletteRoot.dataset.actionCrumb || '';
    return Array.from(paletteRoot.querySelectorAll(`${selector} a[href]`)).map((a) => {
        return makeItem({
            label: textOf(a),
            crumb,
            icon: a.getAttribute('data-nav-icon') || 'list',
            keywords: a.getAttribute('data-nav-keywords') || '',
            tabHref: a instanceof HTMLAnchorElement ? a.href : undefined,
            run() {
                if (a instanceof HTMLElement) a.click();
            },
        });
    });
}

/**
 * The actions every page has.
 *
 * @param {HTMLElement} paletteRoot the palette's root
 * @returns {PaletteItem[]} the actions this member can take on this page
 */
function buildBaseActions(paletteRoot) {
    const data = paletteRoot.dataset;
    const crumb = data.actionCrumb || '';
    /** @type {PaletteItem[]} */
    const out = linkActions(paletteRoot, '.palette-source-actions');
    const select = document.getElementById('squadron-switcher-select');
    if (select) {
        out.push(
            makeItem({
                label: krtI18nText(data.actionOuSwitch, 'data-action-ou-switch'),
                crumb,
                icon: 'swap',
                keywords: '',
                run() {
                    if (krtChrome.nav) krtChrome.nav.open(select);
                },
            }),
        );
    }
    if (document.getElementById('nav-shortcuts')) {
        out.push(
            makeItem({
                label: krtI18nText(data.actionShortcuts, 'data-action-shortcuts'),
                crumb,
                icon: 'list',
                keywords: '',
                run() {
                    if (krtChrome.shortcuts) krtChrome.shortcuts.open();
                },
            }),
        );
    }
    if (document.querySelector('#sidebar .nav-admin-entry')) {
        out.push(
            makeItem({
                label: krtI18nText(data.actionAdmin, 'data-action-admin'),
                crumb,
                icon: 'gear',
                keywords: '',
                run() {
                    if (krtChrome.nav) krtChrome.nav.openAdmin();
                },
            }),
        );
    }
    linkActions(paletteRoot, '.palette-source-tail').forEach((item) => {
        out.push(item);
    });
    const logoutForm = document.getElementById('nav-logout-form');
    if (logoutForm instanceof HTMLFormElement) {
        out.push(
            makeItem({
                label: krtI18nText(data.actionLogout, 'data-action-logout'),
                crumb,
                icon: 'logout',
                keywords: '',
                run() {
                    logoutForm.requestSubmit();
                },
            }),
        );
    }
    return out;
}

/**
 * The actions of one palette.
 *
 * @param {HTMLElement} paletteRoot the palette's root
 * @param {(name: string, favourite: boolean) => void} announceFavourite says that a page became or
 *     stopped being a favourite
 * @returns {() => PaletteItem[]} the actions for the page shown now: the ones its own controls
 *     allow and the ones it contributes, then the ones every page has
 */
export function createActions(paletteRoot, announceFavourite) {
    const data = paletteRoot.dataset;
    const baseActions = buildBaseActions(paletteRoot);
    return function currentActions() {
        const crumb = data.actionCrumb || '';
        return pageActions(
            krtI18nText(data.actionCustomise, 'data-action-customise'),
            announceFavourite,
        )
            .concat(contextActions())
            .map((action) => {
                return makeItem({
                    label: action.label,
                    crumb,
                    icon: action.icon,
                    keywords: '',
                    run: action.run,
                });
            })
            .concat(baseActions);
    };
}
