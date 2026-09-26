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
        content.style.display = 'grid';
        icon.textContent = '−';
    } else {
        content.style.display = 'none';
        icon.textContent = '+';
    }
}

function filterMaterials() {
    const input = document.getElementById('materialFilter');
    const filter = (input ? input.value : '').toUpperCase();
    const totalVisibleCount = isGroupedMode() ? filterGroupedView(filter) : filterFlatView(filter);

    const noResults = document.getElementById('noResultsMsg');
    if (noResults) {
        noResults.style.display = totalVisibleCount === 0 ? 'block' : 'none';
    }
}

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

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('keyup', 'materials-filter', filterMaterials);
    window.krtEvents.on('click', 'materials-toggle-kind', function (el) {
        toggleKindGroup(el);
    });
    window.krtEvents.on('change', 'materials-toggle-grouping', onToggleGrouping);
}
