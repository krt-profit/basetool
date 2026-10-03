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

document.addEventListener('DOMContentLoaded', function () {
    const RECENT_KEY = 'krt.recent';
    const RECENT_MAX = 4;
    const PAGE_MAX = 8;
    const SVG_NS = 'http://www.w3.org/2000/svg';

    const dialog = document.getElementById('nav-palette');
    const root = dialog ? dialog.querySelector('.palette') : null;
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById('palette-input'));
    const results = document.getElementById('palette-results');
    const emptyEl = dialog ? dialog.querySelector('.palette-empty') : null;
    if (!dialog || !(root instanceof HTMLElement) || !input || !results) return;
    const paletteInput = input;
    const resultsEl = results;
    const data = root.dataset;

    /**
     * @typedef {object} PaletteItem
     * @property {string} label the visible name
     * @property {string} crumb the group it belongs to, shown as a breadcrumb
     * @property {string} icon the sprite symbol without its `krt-icon-` prefix
     * @property {string} [href] the page it opens; absent for an action
     * @property {() => void} [run] what an action does
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
     * Collects every page the member may open from the rendered menu, so the palette offers
     * exactly the links the server rendered for this member.
     *
     * @returns {PaletteItem[]} the pages, deduplicated by target
     */
    const buildIndex = function () {
        /** @type {PaletteItem[]} */
        const out = [];
        const seen = new Set();
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return out;
        const links = ['.nav-mode-main', '.nav-personal', '.nav-mode-admin', '.nav-legal'].flatMap(
            function (zone) {
                return Array.from(sidebar.querySelectorAll(zone + ' a[href]'));
            },
        );
        links.forEach(function (a) {
            if (a.hasAttribute('data-nav-skip') || a.closest('.nav-lang-menu')) return;
            const href = a.getAttribute('href') || '';
            if (href.charAt(0) !== '/' || href.indexOf('/oauth2/') === 0 || seen.has(href)) return;
            const label = textOf(a.querySelector('.nav-top-label') || a);
            if (!label) return;
            const groupHolder = a.closest('[data-nav-group]');
            const iconHolder = a.closest('[data-nav-icon]');
            seen.add(href);
            out.push({
                href,
                label,
                crumb: groupHolder ? groupHolder.getAttribute('data-nav-group') || '' : '',
                icon: iconHolder ? iconHolder.getAttribute('data-nav-icon') || 'list' : 'list',
            });
        });
        return out;
    };

    const index = buildIndex();

    /**
     * The actions the palette offers besides pages.
     *
     * @returns {PaletteItem[]} the actions this member can take on this page
     */
    const buildActions = function () {
        /** @type {PaletteItem[]} */
        const out = [];
        const crumb = data.actionCrumb || '';
        const createHref = data.createOrderHref;
        if (createHref) {
            out.push({
                label: window.krtI18nText(data.actionCreateOrder, 'data-action-create-order'),
                crumb,
                icon: 'plus',
                run() {
                    window.location.assign(createHref);
                },
            });
        }
        const select = document.getElementById('squadron-switcher-select');
        if (select) {
            out.push({
                label: window.krtI18nText(data.actionOuSwitch, 'data-action-ou-switch'),
                crumb,
                icon: 'swap',
                run() {
                    if (window.krtNav) window.krtNav.open(select);
                },
            });
        }
        const logoutForm = document.getElementById('nav-logout-form');
        if (logoutForm instanceof HTMLFormElement) {
            out.push({
                label: window.krtI18nText(data.actionLogout, 'data-action-logout'),
                crumb,
                icon: 'logout',
                run() {
                    logoutForm.requestSubmit();
                },
            });
        }
        return out;
    };

    const actions = buildActions();

    /**
     * Reads the recently visited pages, tolerating blocked storage and foreign content.
     *
     * @returns {{ href: string, label: string, group: string }[]} newest first
     */
    const readRecent = function () {
        try {
            const raw = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
            return Array.isArray(raw)
                ? raw.filter(function (e) {
                      return e && typeof e.href === 'string' && e.href.charAt(0) === '/';
                  })
                : [];
        } catch (_e) {
            return [];
        }
    };

    const recordVisit = function () {
        const sidebar = document.getElementById('sidebar');
        const active = sidebar ? sidebar.querySelector('a.is-active[href]') : null;
        const href = active ? active.getAttribute('href') : null;
        if (!href) return;
        const entry = index.find(function (e) {
            return e.href === href;
        });
        if (!entry) return;
        const list = readRecent().filter(function (e) {
            return e.href !== href;
        });
        list.unshift({ href, label: entry.label, group: entry.crumb });
        try {
            localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_MAX)));
        } catch (_e) {}
    };
    recordVisit();

    /**
     * The groups shown for a query: recent pages and actions when it is empty, matching pages and
     * actions otherwise.
     *
     * @param {string} q the lower-cased, trimmed query
     * @returns {{ label: string, items: PaletteItem[] }[]} the non-empty groups in display order
     */
    const groupsFor = function (q) {
        /** @type {{ label: string, items: PaletteItem[] }[]} */
        const groups = [];
        if (!q) {
            /** @type {PaletteItem[]} */
            const recent = [];
            readRecent().forEach(function (e) {
                const known = index.find(function (i) {
                    return i.href === e.href;
                });
                if (known && recent.length < RECENT_MAX) recent.push(known);
            });
            if (recent.length) {
                groups.push({
                    label: window.krtI18nText(data.recent, 'data-recent'),
                    items: recent,
                });
            }
            if (actions.length) {
                groups.push({
                    label: window.krtI18nText(data.actions, 'data-actions'),
                    items: actions,
                });
            }
            return groups;
        }
        const pages = index
            .filter(function (e) {
                return e.label.toLowerCase().includes(q) || e.crumb.toLowerCase().includes(q);
            })
            .map(function (e, i) {
                return { e, rank: e.label.toLowerCase().includes(q) ? 0 : 1, i };
            })
            .sort(function (a, b) {
                return a.rank - b.rank || a.i - b.i;
            })
            .slice(0, PAGE_MAX)
            .map(function (x) {
                return x.e;
            });
        const acts = actions.filter(function (a) {
            return a.label.toLowerCase().includes(q);
        });
        if (pages.length) {
            groups.push({ label: window.krtI18nText(data.pages, 'data-pages'), items: pages });
        }
        if (acts.length) {
            groups.push({ label: window.krtI18nText(data.actions, 'data-actions'), items: acts });
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
        svg.setAttribute('class', 'krt-icon ' + className);
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS(SVG_NS, 'use');
        use.setAttribute('href', '#krt-icon-' + name);
        svg.appendChild(use);
        return svg;
    };

    /**
     * The label with the first occurrence of the query marked.
     *
     * @param {string} label the visible name
     * @param {string} q the lower-cased query
     * @returns {HTMLSpanElement} the label element
     */
    const labelWithHit = function (label, q) {
        const span = document.createElement('span');
        span.className = 'palette-row-label';
        const at = q ? label.toLowerCase().indexOf(q) : -1;
        if (at < 0) {
            span.textContent = label;
            return span;
        }
        span.appendChild(document.createTextNode(label.slice(0, at)));
        const mark = document.createElement('mark');
        mark.textContent = label.slice(at, at + q.length);
        span.appendChild(mark);
        span.appendChild(document.createTextNode(label.slice(at + q.length)));
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
        rows.forEach(function (r, i) {
            const on = i === selected;
            r.row.classList.toggle('is-selected', on);
            r.row.setAttribute('aria-selected', String(on));
        });
        const current = rows[selected].row;
        paletteInput.setAttribute('aria-activedescendant', current.id);
        current.scrollIntoView({ block: 'nearest' });
    };

    /**
     * Opens a page or runs an action, closing the palette first.
     *
     * @param {PaletteItem} item the chosen row
     */
    const choose = function (item) {
        window.krtModal.close(dialog);
        if (item.run) {
            item.run();
        } else if (item.href) {
            window.location.assign(item.href);
        }
    };

    const render = function () {
        const q = paletteInput.value.trim().toLowerCase();
        resultsEl.textContent = '';
        rows = [];
        groupsFor(q).forEach(function (group) {
            const wrap = document.createElement('div');
            wrap.setAttribute('role', 'group');
            wrap.setAttribute('aria-label', group.label);
            const heading = document.createElement('span');
            heading.className = 'palette-group-label';
            heading.setAttribute('aria-hidden', 'true');
            heading.textContent = group.label;
            wrap.appendChild(heading);
            group.items.forEach(function (item) {
                const n = rows.length;
                const row = document.createElement('div');
                row.className = 'palette-row';
                row.id = 'palette-opt-' + n;
                row.setAttribute('role', 'option');
                row.setAttribute('aria-selected', 'false');
                if (item.href) row.setAttribute('data-href', item.href);
                row.appendChild(icon(item.icon, 'palette-row-icon'));
                const text = document.createElement('span');
                text.className = 'palette-row-text';
                text.appendChild(labelWithHit(item.label, q));
                const crumb = document.createElement('span');
                crumb.className = 'palette-row-crumb';
                crumb.textContent = item.crumb;
                text.appendChild(crumb);
                row.appendChild(text);
                const enter = document.createElement('span');
                enter.className = 'palette-row-enter';
                enter.setAttribute('aria-hidden', 'true');
                enter.textContent = '↵';
                row.appendChild(enter);
                row.appendChild(icon('chevron-right', 'palette-row-chevron'));
                row.addEventListener('mousemove', function () {
                    if (selected !== n) select(n);
                });
                row.addEventListener('click', function () {
                    choose(item);
                });
                wrap.appendChild(row);
                rows.push({ row, item });
            });
            resultsEl.appendChild(wrap);
        });
        if (emptyEl instanceof HTMLElement) emptyEl.hidden = rows.length > 0;
        select(0);
    };

    /**
     * Opens the palette.
     *
     * @param {string} [prefill] text to start the search with
     */
    const open = function (prefill) {
        paletteInput.value = prefill || '';
        render();
        window.krtModal.open(dialog, { focus: paletteInput });
        const end = paletteInput.value.length;
        paletteInput.setSelectionRange(end, end);
    };

    window.krtPalette = { open };

    paletteInput.addEventListener('input', render);
    paletteInput.addEventListener('keydown', function (e) {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            select(selected + 1);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            select(selected - 1);
        } else if (e.key === 'Enter') {
            e.preventDefault();
            const current = rows[selected];
            if (current) choose(current.item);
        }
    });

    dialog.addEventListener('click', function (e) {
        const target = e.target instanceof Element ? e.target : null;
        if (target === dialog || (target && target.closest('.palette-cancel'))) {
            window.krtModal.close(dialog);
        }
    });

    dialog.addEventListener('close', function () {
        paletteInput.value = '';
    });

    document.querySelectorAll('[data-palette-open]').forEach(function (trigger) {
        trigger.addEventListener('click', function () {
            if (window.krtNav) window.krtNav.close();
            open('');
        });
    });

    document.addEventListener('keydown', function (e) {
        if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return;
        if (e.key !== 'k' && e.key !== 'K') return;
        const top = window.krtModal.topmost();
        if (top && top !== dialog) return;
        e.preventDefault();
        if (window.krtModal.isOpen(dialog)) {
            paletteInput.focus();
            paletteInput.select();
            return;
        }
        if (window.krtNav) window.krtNav.close();
        open('');
    });

    if (/mac|iphone|ipad/i.test(navigator.platform || navigator.userAgent)) {
        document.querySelectorAll('[data-palette-kbd]').forEach(function (kbd) {
            kbd.textContent = '⌘ K';
        });
    }
});
