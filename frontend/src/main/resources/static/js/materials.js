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

/*
 * Materials overview page module (/materials), extracted verbatim from the former inline script of
 * materials.html (ADR-0069, follow-up to #924).
 *
 * Wires the material-name filter autocomplete (names read from the sibling
 * <datalist id="materialNames-data"> to sidestep the Thymeleaf 3.1 JS-inline list-truncation bug),
 * the category-grouped vs flat view toggle with per-browser localStorage persistence, per-kind
 * accordion expand/collapse, and the live text filter over whichever view is active. All wired via
 * window.krtEvents delegation (materials-filter / materials-toggle-kind / materials-toggle-grouping).
 *
 * The block carried no Thymeleaf interpolation, so there is no inline bootstrap; krtAutocomplete
 * comes from the autocomplete.js module loaded before this classic script.
 */

/* global krtAutocomplete */

document.addEventListener('DOMContentLoaded', function () {
    // Names come from the <datalist id="materialNames-data"> rendered above. The historical
    // Thymeleaf JS inline comment-fallback pattern would truncate the entire <script> block
    // once a Java List was serialised into it (see the parser-level comment next to the
    // datalist for the full diagnosis).
    const dataEl = document.getElementById('materialNames-data');
    const materialNames = dataEl
        ? Array.from(dataEl.options).map(function (opt) {
              return opt.value;
          })
        : [];
    const inp = document.getElementById('materialFilter');
    if (inp) krtAutocomplete(inp, materialNames);

    // Restore the saved grouping preference and show the matching view.
    initGroupingView();
});

// Per-browser persistence of the grouping preference (guarded so privacy modes that
// throw on storage access degrade to the default grouped view instead of breaking).
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
    } catch (_e) {
        /* storage unavailable */
    }
}

function isGroupedMode() {
    const box = document.getElementById('groupByCategory');
    return !box || box.checked;
}

// Show #materialsGrouped (accordion) or #materialsFlat (flat alphabetical grid) to match the
// toggle, then re-apply the current text filter to whichever view is now visible.
function applyGroupingView() {
    const grouped = isGroupedMode();
    const groupedEl = document.getElementById('materialsGrouped');
    const flatEl = document.getElementById('materialsFlat');
    if (groupedEl) groupedEl.style.display = grouped ? '' : 'none';
    if (flatEl) flatEl.style.display = grouped ? 'none' : 'grid';
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

function toggleKindGroup(element) {
    const content = element.nextElementSibling;
    const icon = element.querySelector('.toggle-icon');
    if (
        window.getComputedStyle(content).display === 'none' ||
        window.getComputedStyle(content).display === ''
    ) {
        content.style.display = 'grid'; // or block depending on css, grid-auto-cards needs grid
        icon.textContent = '−';
    } else {
        content.style.display = 'none';
        icon.textContent = '+';
    }
}

// Applies the text filter to whichever view is active and toggles the no-results message.
function filterMaterials() {
    const input = document.getElementById('materialFilter');
    const filter = (input ? input.value : '').toUpperCase();
    const totalVisibleCount = isGroupedMode() ? filterGroupedView(filter) : filterFlatView(filter);

    const noResults = document.getElementById('noResultsMsg');
    if (noResults) {
        noResults.style.display = totalVisibleCount === 0 ? 'block' : 'none';
    }
}

// Category-grouped view: filter cards within each .kind-group, hide empty groups, and
// auto-expand the surviving groups while a filter is active. Returns the visible-card count.
function filterGroupedView(filter) {
    const groups = document.getElementsByClassName('kind-group');
    let totalVisibleCount = 0;

    for (let j = 0; j < groups.length; j++) {
        const group = groups[j];
        const cards = group.getElementsByClassName('material-card');
        let visibleInGroup = 0;

        for (let i = 0; i < cards.length; i++) {
            const title = cards[i].getElementsByClassName('material-title')[0];
            if (title) {
                const txtValue = title.textContent || title.innerText;
                if (txtValue.toUpperCase().indexOf(filter) > -1) {
                    cards[i].style.display = '';
                    visibleInGroup++;
                } else {
                    cards[i].style.display = 'none';
                }
            }
        }

        if (visibleInGroup === 0) {
            group.style.display = 'none';
        } else {
            group.style.display = '';
            totalVisibleCount += visibleInGroup;

            // If filtering is active, auto-expand groups
            const content = group.querySelector('.materialsGrid');
            const icon = group.querySelector('.toggle-icon');
            if (filter.length > 0) {
                content.style.display = 'grid';
                icon.textContent = '−';
            }
        }
    }
    return totalVisibleCount;
}

// Flat view: filter the single #materialsFlat grid of all cards. Returns the visible-card count.
function filterFlatView(filter) {
    const container = document.getElementById('materialsFlat');
    if (!container) {
        return 0;
    }
    const cards = container.getElementsByClassName('material-card');
    let visibleCount = 0;
    for (let i = 0; i < cards.length; i++) {
        const title = cards[i].getElementsByClassName('material-title')[0];
        const txtValue = title ? title.textContent || title.innerText : '';
        if (txtValue.toUpperCase().indexOf(filter) > -1) {
            cards[i].style.display = '';
            visibleCount++;
        } else {
            cards[i].style.display = 'none';
        }
    }
    return visibleCount;
}

// CSP-safe delegated bindings (replace the historical onkeyup="filterMaterials()" and
// onclick="toggleKindGroup(this)" inline handlers).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('keyup', 'materials-filter', filterMaterials);
    window.krtEvents.on('click', 'materials-toggle-kind', function (el) {
        toggleKindGroup(el);
    });
    window.krtEvents.on('change', 'materials-toggle-grouping', onToggleGrouping);
}
