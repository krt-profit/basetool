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

/* global krtAutocomplete */

document.addEventListener('DOMContentLoaded', function () {
    const dataEl = document.getElementById('materialNames-data');
    const materialNames = dataEl
        ? Array.from(dataEl.options).map(function (opt) {
              return opt.value;
          })
        : [];
    const inp = document.getElementById('materialFilter');
    if (inp) krtAutocomplete(inp, materialNames);

    initGroupingView();
});

const GROUP_PREF_KEY = 'materials_group_by_category';
function readGroupPref() {
    try {
        return localStorage.getItem(GROUP_PREF_KEY);
    } catch (_e) {
        return null;
    }
}
function writeGroupPref(value) {
    try {
        localStorage.setItem(GROUP_PREF_KEY, value);
    } catch (_e) {}
}

function isGroupedMode() {
    const box = document.getElementById('groupByCategory');
    return !box || box.checked;
}

function applyGroupingView() {
    const grouped = isGroupedMode();
    const groupedEl = document.getElementById('materialsGrouped');
    const flatEl = document.getElementById('materialsFlat');
    if (groupedEl) groupedEl.hidden = !grouped;
    if (flatEl) flatEl.hidden = grouped;
    filterMaterials();
}

function initGroupingView() {
    const box = document.getElementById('groupByCategory');
    if (box) {
        const pref = readGroupPref();
        if (pref !== null) {
            box.checked = pref === '1';
        }
    }
    applyGroupingView();
}

function onToggleGrouping() {
    writeGroupPref(isGroupedMode() ? '1' : '0');
    applyGroupingView();
}

/**
 * Opens or closes one category: the toggle's aria-expanded and the hidden state of the card grid
 * it controls.
 *
 * @param {Element} toggle the category's [data-trigger="materials-toggle-kind"] button
 * @param {boolean} expanded whether the category is open afterwards
 */
function setKindExpanded(toggle, expanded) {
    const content = document.getElementById(toggle.getAttribute('aria-controls') || '');
    toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    if (content) content.hidden = !expanded;
}

function toggleKindGroup(toggle) {
    setKindExpanded(toggle, toggle.getAttribute('aria-expanded') !== 'true');
}

/**
 * Shows the cards whose title contains the filter and hides the others.
 *
 * @param {ParentNode} scope the element holding the cards
 * @param {string} filter the upper-cased filter text
 * @returns {number} the number of visible cards
 */
function filterCards(scope, filter) {
    let visibleCount = 0;
    scope.querySelectorAll('.material-card').forEach(function (card) {
        const title = card.querySelector('.material-title');
        const text = title ? title.textContent || '' : '';
        const visible = text.toUpperCase().indexOf(filter) > -1;
        card.hidden = !visible;
        if (visible) visibleCount++;
    });
    return visibleCount;
}

function filterMaterials() {
    const input = document.getElementById('materialFilter');
    const filter = (input ? input.value : '').toUpperCase();
    const totalVisibleCount = isGroupedMode() ? filterGroupedView(filter) : filterFlatView(filter);

    const noResults = document.getElementById('noResultsMsg');
    if (noResults) {
        noResults.hidden = totalVisibleCount !== 0;
    }
}

function filterGroupedView(filter) {
    let totalVisibleCount = 0;
    document.querySelectorAll('#materialsGrouped .kind-group').forEach(function (group) {
        const visibleInGroup = filterCards(group, filter);
        group.hidden = visibleInGroup === 0;
        totalVisibleCount += visibleInGroup;
        const toggle = group.querySelector('[data-trigger="materials-toggle-kind"]');
        if (visibleInGroup > 0 && filter.length > 0 && toggle) {
            setKindExpanded(toggle, true);
        }
    });
    return totalVisibleCount;
}

function filterFlatView(filter) {
    const container = document.getElementById('materialsFlat');
    return container ? filterCards(container, filter) : 0;
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('input', 'materials-filter', filterMaterials);
    window.krtEvents.on('change', 'materials-filter', filterMaterials);
    window.krtEvents.on('click', 'materials-toggle-kind', function (el) {
        toggleKindGroup(el);
    });
    window.krtEvents.on('change', 'materials-toggle-grouping', onToggleGrouping);
}
