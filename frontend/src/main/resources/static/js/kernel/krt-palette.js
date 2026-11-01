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

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtChrome } from '/js/kernel/krt-chrome.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { favouriteKeys, NAV_RENDERED_EVENT, stateElement } from '/js/kernel/nav-preferences.js';
import { showFrontendSuccessToast } from '/js/kernel/toast.js';
import {
    krtEmptyState,
    krtFrequentParse,
    krtFrequentRecord,
    krtFrequentScores,
    krtFrequentTop,
} from '/js/kernel/krt-frequent.js';
import {
    contextActions,
    isFavourite,
    pageActions,
    toggleFavourite,
} from '/js/kernel/krt-palette-personalise.js';
import {
    KRT_SEARCH_PREFIXES,
    krtSearchFind,
    krtSearchHitHref,
    krtSearchFold,
    krtSearchParse,
    krtSearchRank,
} from '/js/kernel/krt-search.js';

document.addEventListener('DOMContentLoaded', () => {
    const RECENT_KEY = 'krt.recent';
    const RECENT_MAX = 4;
    const RECENT_STORED_MAX = 8;
    const FREQUENT_KEY = 'krt.frequent';
    const FREQUENT_MAX = 5;
    const PAGE_MAX = 8;
    const SVG_NS = 'http://www.w3.org/2000/svg';
    const SEARCH_DEBOUNCE_MS = 250;
    const SEARCH_MIN_LENGTH = 2;
    const SEARCH_MIN_NUMBER_LENGTH = 1;
    const NUMBER_TERM = /^[0-9]+$/;
    const LOCAL_KINDS = ['action', 'page'];
    const CRUMB_SEPARATOR = ' · ';

    const dialog = document.getElementById('nav-palette');
    const root = dialog ? dialog.querySelector('.palette') : null;
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById('palette-input'));
    const results = document.getElementById('palette-results');
    const emptyEl = dialog ? dialog.querySelector('.palette-empty') : null;
    const liveEl = dialog ? dialog.querySelector('[data-palette-live]') : null;
    if (!dialog || !(root instanceof HTMLElement) || !input || !results) return;
    const paletteRoot = root;
    const paletteInput = input;
    const resultsEl = results;
    const data = paletteRoot.dataset;

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
     * @property {string} [adminHref] the page it opens in administration mode instead
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
    const textOf = function (el) {
        return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : '';
    };

    /**
     * A palette row with its folded search text.
     *
     * @param {{ label: string, crumb: string, icon: string, keywords: string, key?: string, starrable?: boolean, href?: string, tabHref?: string, adminHref?: string, run?: () => void, fill?: string, fromServer?: boolean }} spec
     *     what the row shows and does
     * @returns {PaletteItem} the row
     */
    const makeItem = function (spec) {
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
        if (spec.adminHref) item.adminHref = spec.adminHref;
        if (spec.run) item.run = spec.run;
        if (spec.fill) item.fill = spec.fill;
        if (spec.fromServer) item.fromServer = true;
        return item;
    };

    /** @type {PaletteItem[]} */
    const index = [];
    const seen = new Set();
    /** @type {Map<string, number>} */
    let frequencies = new Map();

    /**
     * Reads the often used ranking, tolerating blocked storage.
     *
     * @returns {Record<string, number[]>} the visit times by entry key
     */
    const readFrequent = function () {
        try {
            return krtFrequentParse(localStorage.getItem(FREQUENT_KEY));
        } catch (_e) {
            return {};
        }
    };

    /** Loads the often used scores onto the pages, for the ranking of typed results. */
    const refreshFrequencies = function () {
        frequencies = krtFrequentScores(readFrequent(), Date.now());
        index.forEach((item) => {
            item.frequency = frequencies.get(item.key || '') || 0;
        });
    };

    /**
     * Adds a page unless its target is already offered.
     *
     * @param {{ href: string, label: string, crumb: string, icon: string, keywords: string, entryKey?: string, starrable?: boolean }} page
     *     the page's path, name, group, sprite symbol, additional search terms, entry key and
     *     whether it can be a favourite
     * @returns {boolean} whether the page was added
     */
    const addPage = function (page) {
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
        index.push(item);
        return true;
    };

    /**
     * Collects every page the member may open from the rendered menu and the palette's own page
     * list, so the palette offers exactly the links the server rendered for this member.
     */
    const buildIndex = function () {
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
    };

    buildIndex();

    /**
     * The actions behind the server-rendered links of one source list.
     *
     * @param {string} selector the source list
     * @returns {PaletteItem[]} one action per link, in document order
     */
    const linkActions = function (selector) {
        const crumb = data.actionCrumb || '';
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
    };

    /**
     * The actions the palette offers besides pages.
     *
     * @returns {PaletteItem[]} the actions this member can take on this page
     */
    const buildActions = function () {
        const crumb = data.actionCrumb || '';
        /** @type {PaletteItem[]} */
        const out = linkActions('.palette-source-actions');
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
        linkActions('.palette-source-tail').forEach((item) => {
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
    };

    const baseActions = buildActions();

    /**
     * Says that a page became or stopped being a favourite: in the dialog's status line while it
     * is open, otherwise as a toast.
     *
     * @param {string} name the page's name
     * @param {boolean} favourite the page's new state
     */
    const announceFavourite = function (name, favourite) {
        const pattern = favourite
            ? krtI18nText(data.liveFavouriteAdded, 'data-live-favourite-added')
            : krtI18nText(data.liveFavouriteRemoved, 'data-live-favourite-removed');
        const message = pattern.replace('{name}', name);
        if (krtModal.isOpen(dialog) && liveEl) {
            liveEl.textContent = '';
            liveEl.textContent = message;
        } else {
            showFrontendSuccessToast(message);
        }
    };

    /**
     * The actions for the page shown now: the ones its own controls allow and the ones it
     * contributes, then the ones every page has.
     *
     * @returns {PaletteItem[]} the actions in display order
     */
    const currentActions = function () {
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

    /**
     * Reads the recently visited pages, tolerating blocked storage and foreign content.
     *
     * @returns {{ key: string, href: string, label: string, group: string }[]} newest first; the
     *     key is the page's path for a record written without one
     */
    const readRecent = function () {
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
    };

    /**
     * Puts a page at the top of the recently visited pages; a page is one record however its
     * address changed.
     *
     * @param {PaletteItem} entry the page
     */
    const remember = function (entry) {
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
    };

    /**
     * Counts a visit of a page in the often used ranking.
     *
     * @param {PaletteItem} entry the visited page
     */
    const countVisit = function (entry) {
        try {
            localStorage.setItem(
                FREQUENT_KEY,
                JSON.stringify(krtFrequentRecord(readFrequent(), entry.key || '', Date.now())),
            );
        } catch (_e) {}
    };

    /**
     * Records the current page: the offered page with exactly this address (path and query, then
     * path), otherwise the menu link the drawer marks as current. Only pages are recorded, so no
     * entity name reaches the storage.
     */
    const recordVisit = function () {
        const here = window.location.pathname;
        const hereWithQuery = here + window.location.search;
        let entry =
            index.find((e) => {
                return e.href === hereWithQuery;
            }) ||
            index.find((e) => {
                return e.href === here;
            });
        if (!entry) {
            const sidebar = document.getElementById('sidebar');
            const active = sidebar ? sidebar.querySelector('a.is-active[href]') : null;
            const href = active ? active.getAttribute('href') : null;
            entry = href
                ? index.find((e) => {
                      return e.href === href;
                  })
                : undefined;
        }
        if (entry) {
            remember(entry);
            countVisit(entry);
        }
    };
    recordVisit();
    refreshFrequencies();

    const uiGerman = (document.documentElement.lang || '').toLowerCase().startsWith('de');

    /**
     * The prefix help: one row per prefix, which puts the prefix into the input.
     *
     * @returns {PaletteItem[]} the rows in the order of the prefix table
     */
    const buildHelp = function () {
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
    };

    const help = buildHelp();

    /** @type {PaletteItem[]} */
    let entityHits = [];
    let entityQuery = '';

    /**
     * The identity of a query for the backend search: the same kind and term ask the same.
     *
     * @param {{ kind: string | null, term: string }} parsed the parsed query
     * @returns {string} the key
     */
    const searchKeyOf = function (parsed) {
        return `${parsed.kind || ''}|${parsed.term}`;
    };

    /**
     * The pages of the empty state: the favourites, then the often used pages, then the recent
     * ones, each page only in the highest section it belongs to. Pages the member cannot open
     * here are left out.
     *
     * @returns {{ favourites: PaletteItem[], frequent: PaletteItem[], recent: PaletteItem[] }} the
     *     pages of each section
     */
    const emptyStatePages = function () {
        /** @type {Map<string, PaletteItem>} */
        const byKey = new Map();
        index.forEach((item) => {
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
                        const item = byKey.get(e.key) || index.find((i) => i.href === e.href);
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
    };

    /**
     * The groups shown for a query: favourite, often used and recent pages and the actions when it
     * is empty, the prefix help for `?`, otherwise the matching pages and entity hits in one
     * ranked list, then the matching actions, each narrowed by the query's prefix.
     *
     * @param {{ kind: string | null, term: string, help: boolean }} parsed the parsed query
     * @returns {{ label: string, items: PaletteItem[] }[]} the non-empty groups in display order
     */
    const groupsFor = function (parsed) {
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
            const pages = emptyStatePages();
            [
                { label: krtI18nText(data.favourites, 'data-favourites'), items: pages.favourites },
                { label: krtI18nText(data.frequent, 'data-frequent'), items: pages.frequent },
                { label: krtI18nText(data.recent, 'data-recent'), items: pages.recent },
                { label: krtI18nText(data.actions, 'data-actions'), items: actions },
            ].forEach((group) => {
                if (group.items.length) groups.push(group);
            });
            return groups;
        }
        const pages =
            !parsed.kind || parsed.kind === 'page'
                ? krtSearchRank(index, q).slice(0, PAGE_MAX)
                : [];
        const hits = entityQuery === searchKeyOf(parsed) ? entityHits : [];
        const mixed = krtSearchRank(pages.concat(hits), q);
        const acts = !parsed.kind || parsed.kind === 'action' ? krtSearchRank(actions, q) : [];
        if (mixed.length) {
            groups.push({ label: krtI18nText(data.results, 'data-results'), items: mixed });
        }
        if (acts.length) {
            groups.push({ label: krtI18nText(data.actions, 'data-actions'), items: acts });
        }
        return groups;
    };

    /**
     * A sprite icon element.
     *
     * @param {string} name the symbol without its `krt-icon-` prefix
     * @param {string} className the class to give the svg
     * @returns {SVGSVGElement} the icon
     */
    const icon = function (name, className) {
        const svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('class', `krt-icon ${className}`);
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS(SVG_NS, 'use');
        use.setAttribute('href', `#krt-icon-${name}`);
        svg.appendChild(use);
        return svg;
    };

    /**
     * The label with the first occurrence of the query marked.
     *
     * @param {string} label the visible name
     * @param {string} q the folded query
     * @returns {HTMLSpanElement} the label element
     */
    const labelWithHit = function (label, q) {
        const span = document.createElement('span');
        span.className = 'palette-row-label';
        const hit = krtSearchFind(label, q);
        if (!hit) {
            span.textContent = label;
            return span;
        }
        span.appendChild(document.createTextNode(label.slice(0, hit.start)));
        const mark = document.createElement('mark');
        mark.textContent = label.slice(hit.start, hit.end);
        span.appendChild(mark);
        span.appendChild(document.createTextNode(label.slice(hit.end)));
        return span;
    };

    /** @type {{ row: HTMLElement, item: PaletteItem }[]} */
    let rows = [];
    let selected = 0;

    /**
     * Moves the selection, clamped to the rows shown.
     *
     * @param {number} next the row index to select
     */
    const select = function (next) {
        if (!rows.length) {
            selected = 0;
            paletteInput.removeAttribute('aria-activedescendant');
            return;
        }
        selected = Math.max(0, Math.min(rows.length - 1, next));
        rows.forEach((r, i) => {
            const on = i === selected;
            r.row.classList.toggle('is-selected', on);
            r.row.setAttribute('aria-selected', String(on));
        });
        const current = rows[selected].row;
        paletteInput.setAttribute('aria-activedescendant', current.id);
        current.scrollIntoView({ block: 'nearest' });
    };

    /**
     * The path a row opens in a new browser tab.
     *
     * @param {PaletteItem} item the row's item
     * @returns {string | null} the path, or null for a row that opens nothing
     */
    const newTabTarget = function (item) {
        if (item.fill) return null;
        if (item.fromServer) {
            const adminMode = !!krtChrome.nav && krtChrome.nav.isAdminMode();
            return krtSearchHitHref(/** @type {any} */ (item), adminMode);
        }
        return item.href || item.tabHref || null;
    };

    /**
     * Opens a row in a new browser tab, closing the palette.
     *
     * @param {PaletteItem} item the highlighted row's item
     */
    const chooseInNewTab = function (item) {
        const target = newTabTarget(item);
        if (!target) return;
        krtModal.close(dialog);
        if (item.href && !item.fromServer) remember(item);
        window.open(target, '_blank', 'noopener');
    };

    /**
     * Opens a page or runs an action, closing the palette first.
     *
     * @param {PaletteItem} item the chosen row
     */
    const choose = function (item) {
        if (item.fill) {
            paletteInput.value = item.fill;
            paletteInput.focus();
            update();
            return;
        }
        krtModal.close(dialog);
        if (item.run) {
            item.run();
        } else if (item.href) {
            if (!item.fromServer) remember(item);
            const adminMode = !!krtChrome.nav && krtChrome.nav.isAdminMode();
            window.location.assign(
                item.fromServer
                    ? krtSearchHitHref(/** @type {any} */ (item), adminMode)
                    : item.href,
            );
        }
    };

    /**
     * The identity of a row across renders.
     *
     * @param {PaletteItem} item the row's item
     * @returns {string} its entry key or target, or its label for an action
     */
    const rowKey = function (item) {
        return item.key || item.href || `${item.crumb}|${item.label}`;
    };

    /**
     * Shows the rows for the current query.
     *
     * @param {boolean} [keepSelection] whether the highlighted row stays highlighted when it is
     *     still shown, wherever it moved; otherwise the first row is highlighted
     */
    const render = function (keepSelection) {
        const previous = keepSelection && rows[selected] ? rowKey(rows[selected].item) : null;
        const parsed = krtSearchParse(paletteInput.value);
        const q = krtSearchFold(parsed.term);
        resultsEl.textContent = '';
        rows = [];
        groupsFor(parsed).forEach((group) => {
            const wrap = document.createElement('div');
            wrap.setAttribute('role', 'group');
            wrap.setAttribute('aria-label', group.label);
            const heading = document.createElement('span');
            heading.className = 'palette-group-label';
            heading.setAttribute('aria-hidden', 'true');
            heading.textContent = group.label;
            wrap.appendChild(heading);
            group.items.forEach((item) => {
                const n = rows.length;
                const row = document.createElement('div');
                row.className = 'palette-row';
                row.id = `palette-opt-${n}`;
                row.setAttribute('role', 'option');
                row.setAttribute('aria-selected', 'false');
                if (item.href) row.setAttribute('data-href', item.href);
                if (item.key) row.setAttribute('data-key', item.key);
                row.appendChild(icon(item.icon, 'palette-row-icon'));
                const text = document.createElement('span');
                text.className = 'palette-row-text';
                text.appendChild(labelWithHit(item.label, q));
                const crumb = document.createElement('span');
                crumb.className = 'palette-row-crumb';
                crumb.textContent = item.crumb;
                text.appendChild(crumb);
                row.appendChild(text);
                if (item.starrable && item.key && isFavourite(item.key)) {
                    row.setAttribute('data-favourite', 'true');
                    row.appendChild(icon('star-filled', 'palette-row-fav'));
                }
                const enter = document.createElement('span');
                enter.className = 'palette-row-enter';
                enter.setAttribute('aria-hidden', 'true');
                enter.textContent = '↵';
                row.appendChild(enter);
                row.appendChild(icon('chevron-right', 'palette-row-chevron'));
                row.addEventListener('mousemove', () => {
                    if (selected !== n) select(n);
                });
                row.addEventListener('click', () => {
                    choose(item);
                });
                wrap.appendChild(row);
                rows.push({ row, item });
            });
            resultsEl.appendChild(wrap);
        });
        if (emptyEl instanceof HTMLElement) {
            emptyEl.hidden = rows.length > 0 || searchTimer !== 0 || searchAbort !== null;
        }
        const kept =
            previous === null
                ? -1
                : rows.findIndex((r) => {
                      return rowKey(r.item) === previous;
                  });
        select(Math.max(kept, 0));
    };

    /** @type {AbortController | null} */
    let searchAbort = null;
    let searchTimer = 0;

    /**
     * Turns one answer of the backend search into a row, or null for a malformed one.
     *
     * @param {any} e the answer's element
     * @returns {PaletteItem | null} the row
     */
    const toHit = function (e) {
        if (!e || typeof e.href !== 'string' || e.href.charAt(0) !== '/') return null;
        if (typeof e.label !== 'string' || !e.label) return null;
        const date = typeof e.date === 'string' ? new Date(e.date) : null;
        const crumb = [
            typeof e.crumb === 'string' ? e.crumb : '',
            typeof e.status === 'string' ? e.status : '',
            date && !Number.isNaN(date.getTime())
                ? date.toLocaleDateString(document.documentElement.lang || undefined)
                : '',
        ]
            .filter(Boolean)
            .join(CRUMB_SEPARATOR);
        return makeItem({
            href: e.href,
            adminHref: typeof e.adminHref === 'string' ? e.adminHref : undefined,
            label: e.label,
            crumb,
            icon: typeof e.icon === 'string' && e.icon ? e.icon : 'list',
            keywords: '',
            fromServer: true,
        });
    };

    /** Drops the entity hits and stops the search still pending or in flight. */
    const cancelSearch = function () {
        window.clearTimeout(searchTimer);
        searchTimer = 0;
        if (searchAbort) searchAbort.abort();
        searchAbort = null;
        entityHits = [];
        entityQuery = '';
    };

    /**
     * Asks the backend for the entities matching a query and shows them when the query is still
     * the one typed.
     *
     * @param {{ kind: string | null, term: string }} parsed the parsed query
     */
    const runSearch = function (parsed) {
        const key = searchKeyOf(parsed);
        const params = new URLSearchParams({ q: parsed.term });
        if (parsed.kind) params.append('kind', parsed.kind);
        const controller = new AbortController();
        searchAbort = controller;
        krtFetch
            .getJson(`${data.entitySearchUrl}?${params.toString()}`, { signal: controller.signal })
            .then((list) => {
                if (searchAbort !== controller) return;
                entityHits = Array.isArray(list)
                    ? list.map(toHit).filter((h) => {
                          return h !== null;
                      })
                    : [];
                entityQuery = key;
            })
            .catch(() => {})
            .finally(() => {
                if (searchAbort !== controller) return;
                searchAbort = null;
                if (krtModal.isOpen(dialog)) render(true);
            });
    };

    /**
     * Debounces the backend search for the current query; a query of a local kind, the help or a
     * term shorter than two characters, or one digit for an order number, asks nothing.
     */
    const scheduleSearch = function () {
        const parsed = krtSearchParse(paletteInput.value);
        if (searchKeyOf(parsed) === entityQuery) return;
        cancelSearch();
        if (
            !data.entitySearchUrl ||
            parsed.help ||
            (parsed.kind !== null && LOCAL_KINDS.includes(parsed.kind)) ||
            parsed.term.length <
                (NUMBER_TERM.test(parsed.term) ? SEARCH_MIN_NUMBER_LENGTH : SEARCH_MIN_LENGTH)
        ) {
            return;
        }
        searchTimer = window.setTimeout(() => {
            searchTimer = 0;
            runSearch(parsed);
        }, SEARCH_DEBOUNCE_MS);
    };

    /** Shows the rows for the edited query and searches the backend for it. */
    const update = function () {
        scheduleSearch();
        render();
    };

    let extraRequested = false;

    document.addEventListener(NAV_RENDERED_EVENT, () => {
        index.length = 0;
        seen.clear();
        buildIndex();
        extraRequested = false;
        if (krtModal.isOpen(dialog)) {
            render(true);
            loadExtraPages();
        }
    });

    /**
     * Loads, once per page, the pages whose access the server decides with a backend read, and
     * shows them when the palette is still open.
     */
    const loadExtraPages = function () {
        const url = data.entriesUrl;
        if (extraRequested || !url) return;
        extraRequested = true;
        krtFetch
            .getJson(url)
            .then((list) => {
                if (!Array.isArray(list)) return;
                let added = false;
                list.forEach((/** @type {any} */ e) => {
                    if (!e || typeof e.href !== 'string' || typeof e.label !== 'string') return;
                    const iconName = typeof e.icon === 'string' && e.icon ? e.icon : 'list';
                    const crumb = typeof e.crumb === 'string' ? e.crumb : '';
                    const keywords = typeof e.keywords === 'string' ? e.keywords : '';
                    if (
                        addPage({ href: e.href, label: e.label, crumb, icon: iconName, keywords })
                    ) {
                        added = true;
                    }
                });
                if (added && krtModal.isOpen(dialog)) render(true);
            })
            .catch(() => {});
    };

    /**
     * Opens the palette.
     *
     * @param {string} [prefill] text to start the search with
     */
    const open = function (prefill) {
        paletteInput.value = prefill || '';
        refreshFrequencies();
        update();
        krtModal.open(dialog, { focus: paletteInput });
        const end = paletteInput.value.length;
        paletteInput.setSelectionRange(end, end);
        loadExtraPages();
    };

    krtChrome.palette = { open };

    paletteInput.addEventListener('input', () => {
        update();
    });
    paletteInput.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            select(selected + 1);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            select(selected - 1);
        } else if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            const current = rows[selected];
            if (current) choose(current.item);
        } else if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) {
            const current = rows[selected];
            if (current && current.item.fill) {
                e.preventDefault();
                choose(current.item);
            }
        }
    });

    dialog.addEventListener('keydown', (e) => {
        if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
        const current = rows[selected];
        if (e.key === 'Enter') {
            e.preventDefault();
            if (current) chooseInNewTab(current.item);
        } else if ((e.key === 'd' || e.key === 'D') && !e.shiftKey) {
            e.preventDefault();
            const key = current && current.item.starrable ? current.item.key : undefined;
            if (key) {
                const name = current.item.label;
                toggleFavourite(key, (favourite) => {
                    announceFavourite(name, favourite);
                });
            }
        }
    });

    dialog.addEventListener('click', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        if (target === dialog || (target && target.closest('.palette-cancel'))) {
            krtModal.close(dialog);
        }
    });

    dialog.addEventListener('close', () => {
        paletteInput.value = '';
        cancelSearch();
    });

    document.querySelectorAll('[data-palette-open]').forEach((trigger) => {
        trigger.addEventListener('click', () => {
            if (krtChrome.nav) krtChrome.nav.close();
            open('');
        });
    });

    document.addEventListener('keydown', (e) => {
        if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return;
        if (e.key !== 'k' && e.key !== 'K') return;
        const top = krtModal.topmost();
        if (top && top !== dialog) return;
        e.preventDefault();
        if (krtModal.isOpen(dialog)) {
            paletteInput.focus();
            paletteInput.select();
            return;
        }
        if (krtChrome.nav) krtChrome.nav.close();
        open('');
    });

    if (/mac|iphone|ipad/i.test(navigator.platform || navigator.userAgent)) {
        document.querySelectorAll('[data-palette-kbd]').forEach((kbd) => {
            kbd.textContent = '⌘ K';
        });
    }
});
