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
 * The rows of the quick access: their shape and the one place that builds them (REQ-UI-033).
 */

import { krtSearchFold } from '/js/kernel/krt-search.js';

/** Separates the parts of a breadcrumb. */
export const CRUMB_SEPARATOR = ' · ';

/**
 * @typedef {object} PaletteItem
 * @property {string} label the visible name
 * @property {string} crumb the group it belongs to, shown as a breadcrumb
 * @property {string} icon the sprite symbol without its `krt-icon-` prefix
 * @property {string} foldLabel the folded label the search matches first
 * @property {string} foldRest the folded breadcrumb and search terms the search matches second
 * @property {string} [key] the page's entry key, or its path for a page without an entry; absent
 *     for an action and an entity hit
 * @property {boolean} [starrable] whether the page can be a favourite
 * @property {number} [frequency] how often the member opens the page, for the ranking
 * @property {string} [href] the page it opens; absent for an action
 * @property {string} [tabHref] the page a link action opens in a new browser tab
 * @property {() => void} [run] what an action does
 * @property {string} [fill] the query a prefix-help row puts into the input
 * @property {boolean} [fromServer] whether it is an entity hit of the backend search, which
 *     never enters the recently visited pages or the often used ones
 */

/**
 * The visible text of an element with its whitespace collapsed.
 *
 * @param {Element | null} el the element
 * @returns {string} its text, or an empty string
 */
export function textOf(el) {
    return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : '';
}

/**
 * A palette row with its folded search text.
 *
 * @param {{ label: string, crumb: string, icon: string, keywords: string, key?: string, starrable?: boolean, href?: string, tabHref?: string, run?: () => void, fill?: string, fromServer?: boolean }} spec
 *     what the row shows and does
 * @returns {PaletteItem} the row
 */
export function makeItem(spec) {
    /** @type {PaletteItem} */
    const item = {
        label: spec.label,
        crumb: spec.crumb,
        icon: spec.icon,
        foldLabel: krtSearchFold(spec.label),
        foldRest: krtSearchFold(`${spec.crumb} ${spec.keywords}`),
    };
    if (spec.key) item.key = spec.key;
    if (spec.starrable) item.starrable = true;
    if (spec.tabHref) item.tabHref = spec.tabHref;
    if (spec.href) item.href = spec.href;
    if (spec.run) item.run = spec.run;
    if (spec.fill) item.fill = spec.fill;
    if (spec.fromServer) item.fromServer = true;
    return item;
}
