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
 * Material-detail page module (/materials/{...}), extracted verbatim from the former inline script of
 * material-detail.html (ADR-0069, follow-up to #924).
 *
 * Client-side sort of the terminal price table (numeric columns 1/2 vs. lexical, toggling asc/desc
 * with a header indicator) and the terminal-name filter autocomplete (names read from the sibling
 * <datalist id="terminalNames-data">, deduplicated), plus the live text filter with a no-results row.
 * All wired via window.krtEvents delegation (material-detail-sort / material-detail-filter-terminals).
 *
 * The block carried no Thymeleaf interpolation, so there is no inline bootstrap; krtAutocomplete
 * comes from the autocomplete.js module loaded before this classic script.
 */

/* global krtAutocomplete */

function sortTable(n) {
    const table = document.getElementById('priceTable');
    let rows, switching, i, x, y, shouldSwitch, dir;
    switching = true;

    dir = table.getAttribute('data-sort-dir') === 'asc' ? 'desc' : 'asc';
    if (table.getAttribute('data-sort-col') !== n.toString()) {
        dir = 'asc';
    }
    table.setAttribute('data-sort-dir', dir);
    table.setAttribute('data-sort-col', n);

    while (switching) {
        switching = false;
        rows = table.getElementsByTagName('tbody')[0].getElementsByTagName('tr');

        for (i = 0; i < rows.length - 1; i++) {
            if (rows[i].id === 'noResultsRow' || rows[i].id === 'noDataRow') continue;
            if (rows[i + 1].id === 'noResultsRow' || rows[i + 1].id === 'noDataRow') continue;

            shouldSwitch = false;
            x = rows[i].getElementsByTagName('td')[n];
            y = rows[i + 1].getElementsByTagName('td')[n];

            if (!x || !y) continue;

            const valX = x.textContent || x.innerText;
            const valY = y.textContent || y.innerText;

            if (n === 1 || n === 2) {
                let numX = parseFloat(valX.replace(/[^0-9,-]/g, '').replace(',', '.'));
                let numY = parseFloat(valY.replace(/[^0-9,-]/g, '').replace(',', '.'));
                if (isNaN(numX)) numX = -1;
                if (isNaN(numY)) numY = -1;

                if (dir === 'asc') {
                    if (numX > numY) {
                        shouldSwitch = true;
                        break;
                    }
                } else {
                    if (numX < numY) {
                        shouldSwitch = true;
                        break;
                    }
                }
            } else {
                if (dir === 'asc') {
                    if (valX.toLowerCase() > valY.toLowerCase()) {
                        shouldSwitch = true;
                        break;
                    }
                } else {
                    if (valX.toLowerCase() < valY.toLowerCase()) {
                        shouldSwitch = true;
                        break;
                    }
                }
            }
        }
        if (shouldSwitch) {
            rows[i].parentNode.insertBefore(rows[i + 1], rows[i]);
            switching = true;
        }
    }
    updateSortIndicators(n, dir);
}

function updateSortIndicators(columnIndex, direction) {
    for (let i = 0; i <= 2; i++) {
        const iconElement = document.getElementById('sort-icon-' + i);
        if (iconElement) {
            if (i === columnIndex) {
                iconElement.innerHTML = direction === 'asc' ? '▲' : '▼';
                iconElement.style.color = 'var(--color-primary)';
            } else {
                iconElement.innerHTML = '↕';
                iconElement.style.color = 'var(--color-gray-2)';
            }
        }
    }
}

document.addEventListener('DOMContentLoaded', function () {
    const dataList = document.getElementById('terminalNames-data');
    const terminalNames = dataList
        ? Array.from(dataList.options).map(function (o) {
              return o.value;
          })
        : [];
    const uniqueTerminals = [...new Set(terminalNames)];
    const inp = document.getElementById('terminalFilter');
    if (inp) krtAutocomplete(inp, uniqueTerminals);
});

function filterTerminals() {
    const input = document.getElementById('terminalFilter');
    const filter = input.value.toUpperCase();
    const table = document.getElementById('priceTable');
    const tr = table.getElementsByTagName('tbody')[0].getElementsByTagName('tr');
    let visibleCount = 0;

    for (let i = 0; i < tr.length; i++) {
        if (tr[i].id === 'noResultsRow' || tr[i].id === 'noDataRow') continue;

        const td = tr[i].getElementsByClassName('terminal-name')[0];
        if (td) {
            const txtValue = td.textContent || td.innerText;
            if (txtValue.toUpperCase().indexOf(filter) > -1) {
                tr[i].style.display = '';
                visibleCount++;
            } else {
                tr[i].style.display = 'none';
            }
        }
    }

    const noResults = document.getElementById('noResultsRow');
    if (noResults) {
        // #noResultsRow starts hidden via the krtm-display-none-5790 class (ADR-0093); a
        // `style.display = ''` reveal cannot override the class, so toggle the class instead.
        if (visibleCount === 0 && tr.length > (document.getElementById('noDataRow') ? 1 : 0)) {
            noResults.classList.remove('krtm-display-none-5790');
        } else {
            noResults.classList.add('krtm-display-none-5790');
        }
    }
}

// CSP-safe delegated bindings (replaces onkeyup="filterTerminals()" and
// onclick="sortTable(N)" inline handlers).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('keyup', 'material-detail-filter-terminals', filterTerminals);
    window.krtEvents.on('click', 'material-detail-sort', function (el) {
        sortTable(parseInt(el.getAttribute('data-sort-column'), 10));
    });
}
