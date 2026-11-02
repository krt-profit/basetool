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
 * The groups of rows the quick access shows for a query: the empty state, the prefix help, and the
 * ranked pages, entity hits and actions (REQ-UI-033).
 */

import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { CRUMB_SEPARATOR, makeItem, textOf } from '/js/kernel/krt-palette-items.js';
import { KRT_SEARCH_PREFIXES, krtSearchFold, krtSearchRank } from '/js/kernel/krt-search.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

const PAGE_MAX = 8;

/**
 * The prefix help: one row per prefix, which puts the prefix into the input.
 *
 * @param {HTMLElement} paletteRoot the palette's root, which carries the prefix names
 * @returns {PaletteItem[]} the rows in the order of the prefix table
 */
function buildHelp(paletteRoot) {
    const uiGerman = (document.documentElement.lang || '').toLowerCase().startsWith('de');
    return KRT_SEARCH_PREFIXES.map((p) => {
        const holder = paletteRoot.querySelector(
            `.palette-source-prefixes [data-prefix-kind="${p.kind}"]`,
        );
        const words = p.words.map((w) => {
            return `${w}:`;
        });
        const tokens = p.symbol ? [p.symbol].concat(words) : words;
        const word = uiGerman ? words[0] : words[words.length - 1];
        return makeItem({
            label: textOf(holder),
            crumb: tokens.join(CRUMB_SEPARATOR),
            icon: 'search',
            keywords: '',
            fill: `${p.symbol || word} `,
        });
    });
}

/**
 * The groups of one palette.
 *
 * @param {object} sources where the rows come from
 * @param {HTMLElement} sources.paletteRoot the palette's root
 * @param {PaletteItem[]} sources.pages the offered pages
 * @param {() => { favourites: PaletteItem[], frequent: PaletteItem[], recent: PaletteItem[] }} sources.emptyStatePages
 *     the pages of the empty state
 * @param {() => PaletteItem[]} sources.currentActions the actions for the page shown now
 * @param {(parsed: { kind: string | null, term: string }) => PaletteItem[]} sources.entityHitsFor
 *     the entity hits that answer the query, none while another query is the one answered
 * @returns {(parsed: { kind: string | null, term: string, help: boolean }) => { label: string, items: PaletteItem[] }[]}
 *     the non-empty groups for a parsed query, in display order: favourite, often used and recent
 *     pages and the actions when it is empty, the prefix help for `?`, otherwise the matching pages
 *     and entity hits in one ranked list, then the matching actions, each narrowed by the query's
 *     prefix
 */
export function createGroups({
    paletteRoot,
    pages,
    emptyStatePages,
    currentActions,
    entityHitsFor,
}) {
    const data = paletteRoot.dataset;
    const help = buildHelp(paletteRoot);
    return function groupsFor(parsed) {
        /** @type {{ label: string, items: PaletteItem[] }[]} */
        const groups = [];
        if (parsed.help) {
            groups.push({ label: krtI18nText(data.help, 'data-help'), items: help });
            return groups;
        }
        const q = krtSearchFold(parsed.term);
        const actions = currentActions();
        if (!q && parsed.kind === 'action') {
            groups.push({ label: krtI18nText(data.actions, 'data-actions'), items: actions });
            return groups;
        }
        if (!q && parsed.kind) return groups;
        if (!q) {
            const empty = emptyStatePages();
            [
                { label: krtI18nText(data.favourites, 'data-favourites'), items: empty.favourites },
                { label: krtI18nText(data.frequent, 'data-frequent'), items: empty.frequent },
                { label: krtI18nText(data.recent, 'data-recent'), items: empty.recent },
                { label: krtI18nText(data.actions, 'data-actions'), items: actions },
            ].forEach((group) => {
                if (group.items.length) groups.push(group);
            });
            return groups;
        }
        const ranked =
            !parsed.kind || parsed.kind === 'page'
                ? krtSearchRank(pages, q).slice(0, PAGE_MAX)
                : [];
        const mixed = krtSearchRank(ranked.concat(entityHitsFor(parsed)), q);
        const acts = !parsed.kind || parsed.kind === 'action' ? krtSearchRank(actions, q) : [];
        if (mixed.length) {
            groups.push({ label: krtI18nText(data.results, 'data-results'), items: mixed });
        }
        if (acts.length) {
            groups.push({ label: krtI18nText(data.actions, 'data-actions'), items: acts });
        }
        return groups;
    };
}
