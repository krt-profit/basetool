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
     * One terminal column of the grid.
     *
     * @typedef {object} MatrixColumn
     * @property {string} name the terminal name, also the key of a row's price map
     * @property {string | null} [nickname] the short name shown in the head
     * @property {string | null} [starSystemName] the star system
     * @property {string | null} [planetName] the effective planet
     * @property {string | null} [planetCssClass] the planet tint class
     */

    /**
     * One price cell; either side may be absent.
     *
     * @typedef {object} MatrixCell
     * @property {number | null} [priceBuy] the purchase price per SCU
     * @property {number | null} [priceSell] the sale price per SCU
     */

    /**
     * One material row.
     *
     * @typedef {object} MatrixRow
     * @property {string} materialName the material
     * @property {boolean} [isIllegal] whether the material is illegal
     * @property {boolean} [isVolatileQt] whether it decays in quantum travel
     * @property {boolean} [isVolatileTime] whether it decays over time
     * @property {Record<string, MatrixCell>} prices the cells by terminal name
     */

    /**
     * One category with its rows.
     *
     * @typedef {object} MatrixGroup
     * @property {string} kind the category name
     * @property {MatrixRow[]} rows the material rows
     */

    /**
     * One rendered line: a category head or a material row with its best prices.
     *
     * @typedef {object} FlatItem
     * @property {'kind' | 'row'} type the line kind
     * @property {string | null} kind the category
     * @property {MatrixRow | null} row the material row, for a row line
     * @property {number | null} bestSell the row's highest sale price
     * @property {number | null} bestBuy the row's lowest purchase price
     */

    const config = document.getElementById('matrixConfig');
    const card = document.getElementById('tableContainer');
    const scroller = document.getElementById('matrixScroll');
    const colgroup = document.getElementById('matrixColgroup');
    const head = document.getElementById('matrixHead');
    const body = document.getElementById('matrixBody');
    const loading = document.getElementById('matrixLoading');
    const errorBox = document.getElementById('matrixError');
    const emptyBox = document.getElementById('matrixEmpty');
    const chipBox = document.getElementById('mtxActiveChips');
    if (!config || !card || !scroller || !colgroup || !head || !body) {
        return;
    }
    const tableCard = card;
    const scroll = scroller;
    const colgroupEl = colgroup;
    const headEl = head;
    const bodyEl = body;

    const DATA_URL = config.getAttribute('data-data-url') || '';
    const I18N = {
        material: window.krtI18nText(
            config.getAttribute('data-label-material'),
            'data-label-material',
        ),
        unsorted: window.krtI18nText(
            config.getAttribute('data-label-unsorted'),
            'data-label-unsorted',
        ),
        unsortedSentinel: 'Unsortiert',
        illegal: config.getAttribute('data-label-illegal') || '',
        volatileQt: config.getAttribute('data-label-volatile-qt') || '',
        volatileTime: config.getAttribute('data-label-volatile-time') || '',
        spread: config.getAttribute('data-label-spread') || '',
        all: config.getAttribute('data-label-all') || '',
        selectionOf: config.getAttribute('data-label-selection-of') || '',
        remove: config.getAttribute('data-label-remove') || '',
    };

    const NUM = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 2 });
    const MINUS = '−';
    const DASH = '–';

    const BUFFER = 8;
    /** @type {Record<string, boolean>} */
    const collapsed = {};
    const GROUP_PREF_KEY = 'materials_matrix_group_by_category';
    const FILTER_PREF_KEY = 'materials_matrix_filters';
    const BOOL_FILTERS = ['filterLoadingDock', 'filterAutoLoad'];
    let grouped = true;

    let rowHeight = 0;
    let calibrated = false;
    /** @type {{ terminals: MatrixColumn[], groups: MatrixGroup[] } | null} */
    let grid = null;
    /** @type {MatrixColumn[]} */
    let cols = [];
    /** @type {FlatItem[]} */
    let flat = [];
    let renderedStart = -1;
    let renderedEnd = -1;
    let colsSig = '';
    let scrollPending = false;
    /** @type {number | null} */
    let filterTimer = null;
    let fetchToken = 0;

    /** Restores the saved filters, wires the controls and loads the first grid. */
    function init() {
        restoreFilters();
        bindFilters();
        renderChips();
        fetchGrid();
    }

    /**
     * Shows or hides an optional element.
     *
     * @param {HTMLElement | null} el the element
     * @param {boolean} visible whether it is shown
     */
    function show(el, visible) {
        if (el) {
            el.hidden = !visible;
        }
    }

    /** Fetches the grid for the current filter selection; a newer request wins. */
    function fetchGrid() {
        const token = ++fetchToken;
        window.krtFetch
            .getJson(DATA_URL + buildFilterQuery())
            .then((data) => {
                if (token !== fetchToken) {
                    return;
                }
                grid = {
                    terminals: (data && data.terminals) || [],
                    groups: (data && data.groups) || [],
                };
                show(loading, false);
                show(errorBox, false);
                render();
            })
            .catch(() => {
                if (token !== fetchToken) {
                    return;
                }
                grid = null;
                show(loading, false);
                show(tableCard, false);
                show(emptyBox, false);
                show(errorBox, true);
            });
    }

    /**
     * The query string of the current filter selection (REQ-UI-014).
     *
     * @returns {string} the query including `?`, or an empty string without filters
     */
    function buildFilterQuery() {
        /** @type {string[]} */
        const parts = [];
        const materials = selectedValues('matCheck');
        const systems = selectedValues('sysCheck');
        if (materials) {
            materials.forEach((v) => {
                parts.push(`materials=${encodeURIComponent(v)}`);
            });
        }
        if (systems) {
            systems.forEach((v) => {
                parts.push(`systems=${encodeURIComponent(v)}`);
            });
        }
        if (isChecked('filterLoadingDock')) {
            parts.push('loadingDock=true');
        }
        if (isChecked('filterAutoLoad')) {
            parts.push('autoLoad=true');
        }
        return parts.length ? `?${parts.join('&')}` : '';
    }

    /**
     * The checkboxes of one multi-select dimension.
     *
     * @param {string} className the dimension's checkbox class
     * @returns {HTMLInputElement[]} the checkboxes in document order
     */
    function checksOf(className) {
        return /** @type {HTMLInputElement[]} */ (
            Array.prototype.slice.call(document.getElementsByClassName(className))
        );
    }

    /**
     * The selected values of one dimension; all or none selected means no filter.
     *
     * @param {string} className the dimension's checkbox class
     * @returns {string[] | null} the selected values, or `null` for no filter
     */
    function selectedValues(className) {
        const checks = checksOf(className);
        const picked = checks
            .filter((c) => {
                return c.checked;
            })
            .map((c) => {
                return c.value;
            });
        if (picked.length === 0 || picked.length === checks.length) {
            return null;
        }
        return picked;
    }

    /**
     * Whether a checkbox is checked.
     *
     * @param {string} id the checkbox id
     * @returns {boolean} true when it exists and is checked
     */
    function isChecked(id) {
        const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
        return !!(el && el.checked);
    }

    /**
     * The saved filter selection (REQ-UI-016).
     *
     * @returns {any} the parsed object, or `null` when none is stored or storage is denied
     */
    function readFilterPref() {
        try {
            const raw = localStorage.getItem(FILTER_PREF_KEY);
            return raw === null ? null : JSON.parse(raw);
        } catch (_e) {
            return null;
        }
    }

    /**
     * Saves the filter selection.
     *
     * @param {object} value the selection
     */
    function writeFilterPref(value) {
        try {
            localStorage.setItem(FILTER_PREF_KEY, JSON.stringify(value));
        } catch (_e) {}
    }

    /** Saves the current selection, a dimension left at "all" as `null`. */
    function persistFilters() {
        writeFilterPref({
            materials: selectedValues('matCheck'),
            systems: selectedValues('sysCheck'),
            loadingDock: isChecked('filterLoadingDock'),
            autoLoad: isChecked('filterAutoLoad'),
        });
    }

    /** Applies the saved selection to the widgets before the first fetch. */
    function restoreFilters() {
        const saved = readFilterPref();
        if (saved && typeof saved === 'object') {
            applySavedSelection('matCheck', 'matAll', saved.materials);
            applySavedSelection('sysCheck', 'sysAll', saved.systems);
            setCheckedById('filterLoadingDock', saved.loadingDock);
            setCheckedById('filterAutoLoad', saved.autoLoad);
        }
        updateSelectedText('matCheck', 'materialHeader');
        updateSelectedText('sysCheck', 'systemHeader');
    }

    /**
     * Applies one saved dimension; stale values are dropped and an entirely stale subset falls
     * back to "all".
     *
     * @param {string} className the dimension's checkbox class
     * @param {string} allId the id of its select-all checkbox
     * @param {unknown} saved the saved values
     */
    function applySavedSelection(className, allId, saved) {
        if (!Array.isArray(saved) || saved.length === 0) {
            return;
        }
        const checks = checksOf(className);
        let anyChecked = false;
        let allChecked = true;
        checks.forEach((c) => {
            const on = saved.indexOf(c.value) >= 0;
            c.checked = on;
            if (on) {
                anyChecked = true;
            } else {
                allChecked = false;
            }
        });
        if (!anyChecked) {
            checks.forEach((c) => {
                c.checked = true;
            });
            allChecked = true;
        }
        const allBox = /** @type {HTMLInputElement | null} */ (document.getElementById(allId));
        if (allBox) {
            allBox.checked = allChecked;
        }
    }

    /**
     * Sets a checkbox from a saved boolean.
     *
     * @param {string} id the checkbox id
     * @param {unknown} value the saved value; anything but a boolean is ignored
     */
    function setCheckedById(id, value) {
        const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
        if (el && typeof value === 'boolean') {
            el.checked = value;
        }
    }

    /**
     * The saved grouping choice (REQ-UI-010).
     *
     * @returns {string | null} `'1'`, `'0'`, or `null` when none is stored
     */
    function readGroupPref() {
        try {
            return localStorage.getItem(GROUP_PREF_KEY);
        } catch (_e) {
            return null;
        }
    }

    /**
     * Saves the grouping choice.
     *
     * @param {string} value `'1'` for grouped, `'0'` for flat
     */
    function writeGroupPref(value) {
        try {
            localStorage.setItem(GROUP_PREF_KEY, value);
        } catch (_e) {}
    }

    /** Re-renders head and body from the loaded grid. */
    function render() {
        if (!grid) {
            return;
        }
        cols = grid.terminals;
        buildFlat(grid.groups);
        const empty = flat.length === 0 || cols.length === 0;
        show(emptyBox, empty);
        show(tableCard, !empty);
        if (empty) {
            bodyEl.innerHTML = '';
            return;
        }
        renderHead();
        renderedStart = -1;
        renderedEnd = -1;
        scroll.scrollTop = 0;
        renderBody();
    }

    /**
     * A row line with the row's best sale and purchase across the shown terminals.
     *
     * @param {MatrixRow} row the material row
     * @param {string | null} kind its category
     * @returns {FlatItem} the line
     */
    function rowItem(row, kind) {
        /** @type {number | null} */
        let bestSell = null;
        /** @type {number | null} */
        let bestBuy = null;
        for (let i = 0; i < cols.length; i++) {
            const cell = row.prices ? row.prices[cols[i].name] : undefined;
            if (!cell) {
                continue;
            }
            const sell = positive(cell.priceSell);
            const buy = positive(cell.priceBuy);
            if (sell !== null && (bestSell === null || sell > bestSell)) {
                bestSell = sell;
            }
            if (buy !== null && (bestBuy === null || buy < bestBuy)) {
                bestBuy = buy;
            }
        }
        return { type: 'row', kind, row, bestSell, bestBuy };
    }

    /**
     * A price as a number when it is a positive amount.
     *
     * @param {unknown} value the raw price
     * @returns {number | null} the price, or `null` for absent, zero or negative
     */
    function positive(value) {
        const n = typeof value === 'number' ? value : value == null ? NaN : Number(value);
        return isFinite(n) && n > 0 ? n : null;
    }

    /**
     * Flattens the groups into render lines, grouped or name-sorted.
     *
     * @param {MatrixGroup[]} groups the categories
     */
    function buildFlat(groups) {
        flat = [];
        if (!grouped) {
            /** @type {MatrixRow[]} */
            const rows = [];
            groups.forEach((g) => {
                for (let i = 0; i < g.rows.length; i++) {
                    rows.push(g.rows[i]);
                }
            });
            rows.sort((a, b) => {
                return String(a.materialName).localeCompare(String(b.materialName), undefined, {
                    sensitivity: 'base',
                });
            });
            rows.forEach((r) => {
                flat.push(rowItem(r, null));
            });
            return;
        }
        groups.forEach((g) => {
            flat.push({ type: 'kind', kind: g.kind, row: null, bestSell: null, bestBuy: null });
            if (!collapsed[g.kind]) {
                g.rows.forEach((r) => {
                    flat.push(rowItem(r, g.kind));
                });
            }
        });
    }

    /** Renders the column head when the terminal set changed. */
    function renderHead() {
        const sig = `${String(cols.length)}|${cols
            .map((c) => {
                return c.name;
            })
            .join('\u0001')}`;
        if (sig === colsSig) {
            return;
        }
        colsSig = sig;

        let cgHtml = '<col class="mtx-col-first" />';
        let sysHtml = '<th class="mtx-corner"></th>';
        let termHtml = `<th class="mtx-corner">${escapeHtml(I18N.material)}</th>`;

        systemGroups(cols).forEach((sg) => {
            cgHtml += `<col class="mtx-col-term" span="${escapeAttr(sg.count)}" />`;
            sysHtml += `<th colspan="${escapeAttr(sg.count)}" class="col-system">${escapeHtml(
                sg.name ? sg.name : DASH,
            )}</th>`;
        });

        cols.forEach((c) => {
            const label = c.nickname ? c.nickname : c.name;
            const title = c.planetName ? `${label} — ${c.planetName}` : label;
            const cls = `col-terminal${c.planetCssClass ? ` ${c.planetCssClass}` : ''}`;
            termHtml += `<th class="${escapeAttr(cls)}" title="${escapeAttr(
                title,
            )}"><span class="mtx-term__name">${escapeHtml(
                label,
            )}</span><span class="mtx-term__planet">${escapeHtml(c.planetName || '')}</span></th>`;
        });

        colgroupEl.innerHTML = cgHtml;
        headEl.innerHTML =
            `<tr class="row-system">${sysHtml}</tr>` + `<tr class="row-terminal">${termHtml}</tr>`;
    }

    /**
     * Groups consecutive columns of the same star system for the system row.
     *
     * @param {MatrixColumn[]} columns the ordered columns
     * @returns {{ name: string, count: number }[]} one entry per run of columns
     */
    function systemGroups(columns) {
        /** @type {{ name: string, count: number }[]} */
        const out = [];
        /** @type {string | null} */
        let current = null;
        let count = 0;
        for (let i = 0; i < columns.length; i++) {
            const sys = columns[i].starSystemName || '';
            if (current === null) {
                current = sys;
                count = 1;
            } else if (current === sys) {
                count++;
            } else {
                out.push({ name: current, count });
                current = sys;
                count = 1;
            }
        }
        if (current !== null) {
            out.push({ name: current, count });
        }
        return out;
    }

    /**
     * Formats a price for a cell.
     *
     * @param {number} value the price
     * @returns {string} the formatted price
     */
    function fmt(value) {
        return NUM.format(value);
    }

    /** Renders the visible window of lines, with spacer rows for the rest. */
    function renderBody() {
        let bodyHtml = '';
        const span = cols.length + 1;

        /** @param {number} heightPx the spacer height */
        function appendSpacer(heightPx) {
            bodyHtml += `<tr class="row-spacer" aria-hidden="true"><td colspan="${escapeAttr(
                span,
            )}" data-krtm-height="${escapeAttr(heightPx)}"></td></tr>`;
        }

        /** @param {FlatItem} item the line */
        function appendKind(item) {
            const kind = item.kind || '';
            const label = kind === I18N.unsortedSentinel ? I18N.unsorted : kind;
            const open = !collapsed[kind];
            bodyHtml += `<tr class="row-kind" data-kind="${escapeAttr(
                kind,
            )}"><td colspan="${escapeAttr(
                span,
            )}" class="mtx-kind-cell"><button type="button" class="mtx-kind-toggle" aria-expanded="${
                open ? 'true' : 'false'
            }"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-${
                open ? 'chevron-down' : 'chevron-right'
            }"/></svg><span>${escapeHtml(label)}</span></button></td></tr>`;
        }

        /** @param {MatrixRow} r the material row */
        function appendWarnings(r) {
            /** @type {[boolean | undefined, string, string][]} */
            const flags = [
                [r.isIllegal, 'mtx-warn--danger', I18N.illegal],
                [r.isVolatileQt, 'mtx-warn--warning', I18N.volatileQt],
                [r.isVolatileTime, 'mtx-warn--warning', I18N.volatileTime],
            ];
            flags.forEach((flag) => {
                if (!flag[0]) {
                    return;
                }
                bodyHtml += `<span class="mtx-warn ${flag[1]}" role="img" title="${escapeAttr(
                    flag[2],
                )}" aria-label="${escapeAttr(
                    flag[2],
                )}"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-warning"/></svg></span>`;
            });
        }

        /** @param {FlatItem} item the line */
        function appendRow(item) {
            const r = /** @type {MatrixRow} */ (item.row);
            const spreadText =
                item.bestSell !== null && item.bestBuy !== null
                    ? fmt(item.bestSell - item.bestBuy)
                    : DASH;
            bodyHtml +=
                '<tr class="row-material"><td class="mtx-name-cell"><span class="mtx-name">';
            appendWarnings(r);
            bodyHtml += `<span class="mtx-name__text">${escapeHtml(
                r.materialName,
            )}</span></span><span class="mtx-spread">${escapeHtml(
                `${I18N.spread} ${spreadText}`,
            )}</span></td>`;
            for (let i = 0; i < cols.length; i++) {
                const c = cols[i];
                const cell = r.prices ? r.prices[c.name] : undefined;
                const sell = cell ? positive(cell.priceSell) : null;
                const buy = cell ? positive(cell.priceBuy) : null;
                let cls = `col-terminal${c.planetCssClass ? ` ${c.planetCssClass}` : ''}`;
                if (sell !== null && sell === item.bestSell) {
                    cls += ' is-best-sell';
                }
                if (buy !== null && buy === item.bestBuy) {
                    cls += ' is-best-buy';
                }
                bodyHtml += `<td class="${escapeAttr(cls)}">`;
                if (sell === null && buy === null) {
                    bodyHtml += `<span class="mtx-empty">${DASH}</span>`;
                }
                if (sell !== null) {
                    bodyHtml += `<span class="price-sell">+${escapeHtml(fmt(sell))}</span>`;
                }
                if (buy !== null) {
                    bodyHtml += `<span class="price-buy">${MINUS}${escapeHtml(fmt(buy))}</span>`;
                }
                bodyHtml += '</td>';
            }
            bodyHtml += '</tr>';
        }

        const rh = rowHeight || 52;
        const viewport = scroll.clientHeight || 600;
        const firstVisible = Math.floor(scroll.scrollTop / rh);
        const lastVisible = Math.ceil((scroll.scrollTop + viewport) / rh);
        const start = Math.max(0, firstVisible - BUFFER);
        const end = Math.min(flat.length, lastVisible + BUFFER);

        if (start > 0) {
            appendSpacer(start * rh);
        }
        for (let i = start; i < end; i++) {
            if (flat[i].type === 'kind') {
                appendKind(flat[i]);
            } else {
                appendRow(flat[i]);
            }
        }
        if (end < flat.length) {
            appendSpacer((flat.length - end) * rh);
        }
        window.krtFetch.setTrustedHtml(bodyEl, bodyHtml);
        applySpacerHeights();
        renderedStart = start;
        renderedEnd = end;

        if (!calibrated) {
            calibrate();
        }
    }

    /** Sizes the spacer rows through the CSSOM, which the CSP allows. */
    function applySpacerHeights() {
        const spacers = bodyEl.querySelectorAll('td[data-krtm-height]');
        for (let i = 0; i < spacers.length; i++) {
            const td = /** @type {HTMLElement} */ (spacers[i]);
            const h = parseFloat(td.getAttribute('data-krtm-height') || '0');
            td.style.height = `${isFinite(h) ? h : 0}px`;
        }
    }

    /** Measures one rendered row once and re-renders when the assumed height was off. */
    function calibrate() {
        const sample =
            bodyEl.querySelector('tr.row-material') || bodyEl.querySelector('tr.row-kind');
        calibrated = true;
        if (!sample) {
            return;
        }
        const h = Math.round(sample.getBoundingClientRect().height);
        if (h > 0 && Math.abs(h - rowHeight) > 1) {
            rowHeight = h;
            renderedStart = -1;
            renderBody();
        }
    }

    /** Re-renders on the next frame when scrolling leaves the rendered window. */
    function onScroll() {
        if (scrollPending) {
            return;
        }
        scrollPending = true;
        window.requestAnimationFrame(() => {
            scrollPending = false;
            if (!flat.length) {
                return;
            }
            const rh = rowHeight || 52;
            const viewport = scroll.clientHeight || 600;
            const firstVisible = Math.floor(scroll.scrollTop / rh);
            const lastVisible = Math.ceil((scroll.scrollTop + viewport) / rh);
            if (firstVisible - BUFFER < renderedStart || lastVisible + BUFFER > renderedEnd) {
                renderBody();
            }
        });
    }

    /** Saves the selection at once and re-fetches debounced. */
    function scheduleRefetch() {
        persistFilters();
        renderChips();
        if (filterTimer !== null) {
            clearTimeout(filterTimer);
        }
        filterTimer = window.setTimeout(() => {
            filterTimer = null;
            fetchGrid();
        }, 200);
    }

    /**
     * Writes a dimension's summary into its dropdown button: "Alle", the one selected name, or
     * "n von N".
     *
     * @param {string} checkClass the dimension's checkbox class
     * @param {string} headerId the dropdown button id
     */
    function updateSelectedText(checkClass, headerId) {
        const header = document.getElementById(headerId);
        const textEl = header ? header.querySelector('.selected-text') : null;
        if (!textEl) {
            return;
        }
        const checks = checksOf(checkClass);
        const picked = checks.filter((c) => {
            return c.checked;
        });
        if (picked.length === checks.length) {
            textEl.textContent = I18N.all;
        } else if (picked.length === 1) {
            const name = picked[0].nextElementSibling;
            textEl.textContent = name ? (name.textContent || '').trim() : picked[0].value;
        } else {
            textEl.textContent = I18N.selectionOf
                .replace('{0}', String(picked.length))
                .replace('{1}', String(checks.length));
        }
    }

    /** Renders one removable chip per active boolean filter. */
    function renderChips() {
        if (!chipBox) {
            return;
        }
        chipBox.textContent = '';
        BOOL_FILTERS.forEach((id) => {
            const box = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
            if (!box || !box.checked) {
                return;
            }
            const label = box.nextElementSibling
                ? (box.nextElementSibling.textContent || '').trim()
                : id;
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'chip chip--primary filter-chip';
            chip.setAttribute('data-testid', `filter-chip-${id}`);
            chip.setAttribute('data-clear', id);
            chip.setAttribute('aria-label', I18N.remove ? `${I18N.remove}: ${label}` : label);
            const text = document.createElement('span');
            text.textContent = label;
            chip.appendChild(text);
            const svgNs = 'http://www.w3.org/2000/svg';
            const icon = document.createElementNS(svgNs, 'svg');
            icon.setAttribute('class', 'krt-icon');
            icon.setAttribute('aria-hidden', 'true');
            const use = document.createElementNS(svgNs, 'use');
            use.setAttribute('href', '#krt-icon-close');
            icon.appendChild(use);
            chip.appendChild(icon);
            chipBox.appendChild(chip);
        });
        chipBox.hidden = chipBox.childElementCount === 0;
    }

    /** Wires the filter controls, the grouping switch, the category heads and the scroll. */
    function bindFilters() {
        document.querySelectorAll('.mtx-select-all').forEach((el) => {
            const box = /** @type {HTMLInputElement} */ (el);
            box.addEventListener('change', () => {
                const checkClass = box.getAttribute('data-check-class') || '';
                checksOf(checkClass).forEach((c) => {
                    c.checked = box.checked;
                });
                updateSelectedText(checkClass, box.getAttribute('data-header-id') || '');
                scheduleRefetch();
            });
        });

        document.querySelectorAll('.mtx-check').forEach((el) => {
            const chk = /** @type {HTMLInputElement} */ (el);
            chk.addEventListener('change', () => {
                const checkClass = chk.getAttribute('data-check-class') || '';
                const allChecked = checksOf(checkClass).every((c) => {
                    return c.checked;
                });
                const allBox = /** @type {HTMLInputElement | null} */ (
                    document.getElementById(chk.getAttribute('data-all-id') || '')
                );
                if (allBox) {
                    allBox.checked = allChecked;
                }
                updateSelectedText(checkClass, chk.getAttribute('data-header-id') || '');
                scheduleRefetch();
            });
        });

        document.querySelectorAll('.mtx-bool-filter').forEach((b) => {
            b.addEventListener('change', scheduleRefetch);
        });

        if (chipBox) {
            chipBox.addEventListener('click', (ev) => {
                const target = /** @type {Element | null} */ (ev.target);
                const chip = target ? target.closest('[data-clear]') : null;
                if (!chip) {
                    return;
                }
                const box = /** @type {HTMLInputElement | null} */ (
                    document.getElementById(chip.getAttribute('data-clear') || '')
                );
                if (!box) {
                    return;
                }
                box.checked = false;
                box.dispatchEvent(new Event('change', { bubbles: true }));
                const next = chipBox.querySelector('.filter-chip');
                const toggle = document.querySelector('[aria-controls="materials-filter-panel"]');
                const focusTarget = /** @type {HTMLElement | null} */ (next || toggle);
                if (focusTarget) {
                    focusTarget.focus();
                }
            });
        }

        const groupBox = /** @type {HTMLInputElement | null} */ (
            document.getElementById('filterGroupByCategory')
        );
        if (groupBox) {
            const pref = readGroupPref();
            if (pref !== null) {
                groupBox.checked = pref === '1';
            }
            grouped = groupBox.checked;
            groupBox.addEventListener('change', () => {
                grouped = groupBox.checked;
                writeGroupPref(grouped ? '1' : '0');
                render();
            });
        }

        bodyEl.addEventListener('click', (ev) => {
            const target = /** @type {Element | null} */ (ev.target);
            const kindRow = target ? target.closest('tr.row-kind') : null;
            if (!kindRow) {
                return;
            }
            const kind = kindRow.getAttribute('data-kind') || '';
            collapsed[kind] = !collapsed[kind];
            const keepScroll = scroll.scrollTop;
            buildFlat(grid ? grid.groups : []);
            renderedStart = -1;
            renderBody();
            scroll.scrollTop = keepScroll;
            const again = bodyEl.querySelector(
                `tr.row-kind[data-kind="${CSS.escape(kind)}"] .mtx-kind-toggle`,
            );
            if (again) {
                /** @type {HTMLElement} */ (again).focus();
            }
        });

        scroll.addEventListener('scroll', onScroll);
    }

    init();
})();
