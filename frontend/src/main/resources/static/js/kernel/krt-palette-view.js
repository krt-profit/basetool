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
 * The rows of the quick access on screen: the groups for the typed query, the highlighted row and
 * its keyboard movement (REQ-UI-033).
 */

import { isFavourite } from '/js/kernel/krt-palette-personalise.js';
import { krtSearchFind, krtSearchFold, krtSearchParse } from '/js/kernel/krt-search.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * A sprite icon element.
 *
 * @param {string} name the symbol without its `krt-icon-` prefix
 * @param {string} className the class to give the svg
 * @returns {SVGSVGElement} the icon
 */
function icon(name, className) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', `krt-icon ${className}`);
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS(SVG_NS, 'use');
    use.setAttribute('href', `#krt-icon-${name}`);
    svg.appendChild(use);
    return svg;
}

/**
 * The label with the first occurrence of the query marked.
 *
 * @param {string} label the visible name
 * @param {string} q the folded query
 * @returns {HTMLSpanElement} the label element
 */
function labelWithHit(label, q) {
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
}

/**
 * The identity of a row across renders.
 *
 * @param {PaletteItem} item the row's item
 * @returns {string} its entry key or target, or its label for an action
 */
function rowKey(item) {
    return item.key || item.href || `${item.crumb}|${item.label}`;
}

/**
 * The result list of one palette.
 *
 * @param {object} parts what the view shows and asks
 * @param {HTMLInputElement} parts.input the query input, which carries the active descendant
 * @param {HTMLElement} parts.results the element the groups are rendered into
 * @param {Element | null} parts.empty the "nothing found" notice
 * @param {(parsed: { kind: string | null, term: string, help: boolean }) => { label: string, items: PaletteItem[] }[]} parts.groupsFor
 *     the groups for a parsed query
 * @param {() => boolean} parts.searching whether the backend search is still pending or running
 * @param {(item: PaletteItem) => void} parts.onChoose called when a row is clicked
 * @returns {{
 *     render: (keepSelection?: boolean) => void,
 *     select: (next: number) => void,
 *     selectedIndex: () => number,
 *     current: () => PaletteItem | null,
 * }} the rendering of the rows for the current query, the movement of the highlight, the index of
 *     the highlighted row and the highlighted row's item
 */
export function createView({ input, results, empty, groupsFor, searching, onChoose }) {
    /** @type {{ row: HTMLElement, item: PaletteItem }[]} */
    let rows = [];
    let selected = 0;

    /**
     * Moves the selection, clamped to the rows shown.
     *
     * @param {number} next the row index to select
     */
    function select(next) {
        if (!rows.length) {
            selected = 0;
            input.removeAttribute('aria-activedescendant');
            return;
        }
        selected = Math.max(0, Math.min(rows.length - 1, next));
        rows.forEach((r, i) => {
            const on = i === selected;
            r.row.classList.toggle('is-selected', on);
            r.row.setAttribute('aria-selected', String(on));
        });
        const current = rows[selected].row;
        input.setAttribute('aria-activedescendant', current.id);
        current.scrollIntoView({ block: 'nearest' });
    }

    /**
     * One row of the list.
     *
     * @param {PaletteItem} item the row's item
     * @param {number} n the row's position in the list
     * @param {string} q the folded query
     * @returns {HTMLElement} the row
     */
    function buildRow(item, n, q) {
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
            onChoose(item);
        });
        return row;
    }

    /**
     * Shows the rows for the current query.
     *
     * @param {boolean} [keepSelection] whether the highlighted row stays highlighted when it is
     *     still shown, wherever it moved; otherwise the first row is highlighted
     */
    function render(keepSelection) {
        const previous = keepSelection && rows[selected] ? rowKey(rows[selected].item) : null;
        const parsed = krtSearchParse(input.value);
        const q = krtSearchFold(parsed.term);
        results.textContent = '';
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
                const row = buildRow(item, rows.length, q);
                wrap.appendChild(row);
                rows.push({ row, item });
            });
            results.appendChild(wrap);
        });
        if (empty instanceof HTMLElement) {
            empty.hidden = rows.length > 0 || searching();
        }
        const kept =
            previous === null
                ? -1
                : rows.findIndex((r) => {
                      return rowKey(r.item) === previous;
                  });
        select(Math.max(kept, 0));
    }

    return {
        render,
        select,
        selectedIndex: () => selected,
        current: () => (rows[selected] ? rows[selected].item : null),
    };
}
