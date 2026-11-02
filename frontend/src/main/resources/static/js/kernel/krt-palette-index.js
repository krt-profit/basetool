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
 * The pages the quick access offers: the links the server rendered for this member, plus the pages
 * whose access a backend read decides. Also the current page's visit record and the pages of the
 * empty state (REQ-UI-033).
 */

import { krtEmptyState, krtFrequentScores, krtFrequentTop } from '/js/kernel/krt-frequent.js';
import { makeItem, textOf } from '/js/kernel/krt-palette-items.js';
import {
    countVisit,
    FREQUENT_MAX,
    readFrequent,
    readRecent,
    RECENT_MAX,
    remember,
} from '/js/kernel/krt-palette-history.js';
import { favouriteKeys, stateElement } from '/js/kernel/nav-preferences.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

/**
 * The page index of one palette.
 *
 * @param {HTMLElement} paletteRoot the palette's root, which carries its own page list
 * @returns {{
 *     items: PaletteItem[],
 *     rebuild: () => void,
 *     refreshFrequencies: () => void,
 *     emptyStatePages: () => { favourites: PaletteItem[], frequent: PaletteItem[], recent: PaletteItem[] },
 * }} the pages, the way to collect them again, to reload the often used scores and the pages of
 *     the empty state
 */
export function createPageIndex(paletteRoot) {
    /** @type {PaletteItem[]} */
    const items = [];
    const seen = new Set();
    /** @type {Map<string, number>} */
    let frequencies = new Map();

    /** Loads the often used scores onto the pages, for the ranking of typed results. */
    function refreshFrequencies() {
        frequencies = krtFrequentScores(readFrequent(), Date.now());
        items.forEach((item) => {
            item.frequency = frequencies.get(item.key || '') || 0;
        });
    }

    /**
     * Adds a page unless its target is already offered.
     *
     * @param {{ href: string, label: string, crumb: string, icon: string, keywords: string, entryKey?: string, starrable?: boolean }} page
     *     the page's path, name, group, sprite symbol, additional search terms, entry key and
     *     whether it can be a favourite
     * @returns {boolean} whether the page was added
     */
    function addPage(page) {
        const { href, label } = page;
        if (!label || href.charAt(0) !== '/' || href.indexOf('/oauth2/') === 0 || seen.has(href)) {
            return false;
        }
        seen.add(href);
        const item = makeItem({
            href,
            label,
            crumb: page.crumb,
            icon: page.icon,
            keywords: page.keywords,
            key: page.entryKey || href,
            starrable: page.starrable,
        });
        item.frequency = frequencies.get(item.key || '') || 0;
        items.push(item);
        return true;
    }

    /**
     * Collects every page the member may open from the rendered menu and the palette's own page
     * list, so the palette offers exactly the links the server rendered for this member.
     */
    function collect() {
        const sidebar = document.getElementById('sidebar');
        const menuLinks = sidebar
            ? ['.nav-mode-main', '.nav-personal', '.nav-mode-admin', '.nav-legal'].flatMap(
                  (zone) => {
                      return Array.from(sidebar.querySelectorAll(`${zone} a[href]`));
                  },
              )
            : [];
        const ownLinks = Array.from(paletteRoot.querySelectorAll('.palette-source-pages a[href]'));
        menuLinks.concat(ownLinks).forEach((a) => {
            if (a.hasAttribute('data-nav-skip') || a.closest('.nav-lang-menu')) return;
            const groupHolder = a.closest('[data-nav-group]');
            const iconHolder = a.closest('[data-nav-icon]');
            addPage({
                href: a.getAttribute('href') || '',
                label: textOf(a.querySelector('.nav-top-label') || a),
                crumb: groupHolder ? groupHolder.getAttribute('data-nav-group') || '' : '',
                icon: iconHolder ? iconHolder.getAttribute('data-nav-icon') || 'list' : 'list',
                keywords: a.getAttribute('data-nav-keywords') || '',
                entryKey: a.getAttribute('data-entry-key') || undefined,
                starrable: a.hasAttribute('data-starrable'),
            });
        });
    }

    /** Collects the pages again, after the drawer was rendered anew. */
    function rebuild() {
        items.length = 0;
        seen.clear();
        collect();
    }

    /**
     * Records the current page: the offered page with exactly this address (path and query, then
     * path), otherwise the menu link the drawer marks as current. Only pages are recorded, so no
     * entity name reaches the storage.
     */
    function recordVisit() {
        const here = window.location.pathname;
        const hereWithQuery = here + window.location.search;
        let entry =
            items.find((e) => {
                return e.href === hereWithQuery;
            }) ||
            items.find((e) => {
                return e.href === here;
            });
        if (!entry) {
            const sidebar = document.getElementById('sidebar');
            const active = sidebar ? sidebar.querySelector('a.is-active[href]') : null;
            const href = active ? active.getAttribute('href') : null;
            entry = href
                ? items.find((e) => {
                      return e.href === href;
                  })
                : undefined;
        }
        if (entry) {
            remember(entry);
            countVisit(entry);
        }
    }

    /**
     * The pages of the empty state: the favourites, then the often used pages, then the recent
     * ones, each page only in the highest section it belongs to. Pages the member cannot open
     * here are left out.
     *
     * @returns {{ favourites: PaletteItem[], frequent: PaletteItem[], recent: PaletteItem[] }} the
     *     pages of each section
     */
    function emptyStatePages() {
        /** @type {Map<string, PaletteItem>} */
        const byKey = new Map();
        items.forEach((item) => {
            if (item.key && !byKey.has(item.key)) byKey.set(item.key, item);
        });
        const known = (/** @type {string} */ key) => byKey.has(key);
        const state = stateElement();
        const keys = krtEmptyState(
            {
                favourites: (state ? favouriteKeys(state) : []).filter(known),
                frequent: krtFrequentTop(readFrequent(), Date.now()).filter(known),
                recent: readRecent()
                    .map((e) => {
                        const item = byKey.get(e.key) || items.find((i) => i.href === e.href);
                        return item && item.key ? item.key : '';
                    })
                    .filter(known),
            },
            { frequent: FREQUENT_MAX, recent: RECENT_MAX },
        );
        const pages = (/** @type {string[]} */ list) => {
            return list.flatMap((key) => {
                const item = byKey.get(key);
                return item ? [item] : [];
            });
        };
        return {
            favourites: pages(keys.favourites),
            frequent: pages(keys.frequent),
            recent: pages(keys.recent),
        };
    }

    collect();
    recordVisit();
    refreshFrequencies();

    return { items, rebuild, refreshFrequencies, emptyStatePages };
}
