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
 * What the quick access remembers in the browser's storage: the recently visited pages and the
 * often used ones. Only pages are stored, never an entity name (REQ-UI-033).
 */

import { krtFrequentParse, krtFrequentRecord } from '/js/kernel/krt-frequent.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

const RECENT_KEY = 'krt.recent';
const RECENT_STORED_MAX = 8;
const FREQUENT_KEY = 'krt.frequent';

/** How many recent pages the empty state shows. */
export const RECENT_MAX = 4;

/** How many often used pages the empty state shows. */
export const FREQUENT_MAX = 5;

/**
 * Reads the often used ranking, tolerating blocked storage.
 *
 * @returns {Record<string, number[]>} the visit times by entry key
 */
export function readFrequent() {
    try {
        return krtFrequentParse(localStorage.getItem(FREQUENT_KEY));
    } catch (_e) {
        return {};
    }
}

/**
 * Reads the recently visited pages, tolerating blocked storage and foreign content.
 *
 * @returns {{ key: string, href: string, label: string, group: string }[]} newest first; the
 *     key is the page's path for a record written without one
 */
export function readRecent() {
    try {
        const raw = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
        if (!Array.isArray(raw)) return [];
        return raw
            .filter((e) => {
                return e && typeof e.href === 'string' && e.href.charAt(0) === '/';
            })
            .map((e) => {
                return {
                    key: typeof e.key === 'string' && e.key ? e.key : e.href,
                    href: e.href,
                    label: String(e.label || ''),
                    group: String(e.group || ''),
                };
            });
    } catch (_e) {
        return [];
    }
}

/**
 * Puts a page at the top of the recently visited pages; a page is one record however its
 * address changed.
 *
 * @param {PaletteItem} entry the page
 */
export function remember(entry) {
    const href = entry.href;
    if (!href) return;
    const key = entry.key || href;
    const list = readRecent().filter((e) => {
        return e.key !== key;
    });
    list.unshift({ key, href, label: entry.label, group: entry.crumb });
    try {
        localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_STORED_MAX)));
    } catch (_e) {}
}

/**
 * Counts a visit of a page in the often used ranking.
 *
 * @param {PaletteItem} entry the visited page
 */
export function countVisit(entry) {
    try {
        localStorage.setItem(
            FREQUENT_KEY,
            JSON.stringify(krtFrequentRecord(readFrequent(), entry.key || '', Date.now())),
        );
    } catch (_e) {}
}
