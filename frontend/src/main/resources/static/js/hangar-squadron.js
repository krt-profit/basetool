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

/*
 * Squadron-hangar page module, extracted verbatim from the former inline script of
 * hangar-squadron.html (ADR-0069, follow-up to #924).
 *
 * Binds the per-<details> expand toggles that drive the companion detail row (idempotent via
 * a _sqBound guard, re-run on krt:swapped so swapped-in rows bind too) and the in-place ship
 * search + page-size + pagination that swaps the #squadron-results fragment (epic #571 / #573);
 * the GET form and page links stay the no-JS fallback.
 */

// Details toggle drives the companion row via a class; a tr:has(details[open]) sibling
// selector would force a table-wide style recalculation on every toggle and visibly jank on
// large tables. Bound per-<details> (idempotent via the _sqBound guard), and re-run after a
// fragment swap so swapped-in rows get the binding too (epic #571 / #573).
function bindSquadronDetailsToggles(root) {
    (root || document).querySelectorAll('tr.sq-row details').forEach(function (details) {
        if (details._sqBound) {
            return;
        }
        details._sqBound = true;
        details.addEventListener('toggle', function () {
            const row = details.closest('tr');
            const detailsRow = row ? row.nextElementSibling : null;
            if (detailsRow && detailsRow.classList.contains('details-row')) {
                detailsRow.classList.toggle('sq-expanded', details.open);
            }
        });
    });
}

document.addEventListener('DOMContentLoaded', function () {
    bindSquadronDetailsToggles(document);

    // In-place search + page-size + pagination (the page-size/pagination links carry the
    // .page-btn class, so krtFetch.swap intercepts them). The GET form is the no-JS fallback.
    const filterForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('squadron-filter-form')
    );
    const resultsContainer = document.getElementById('squadron-results');
    const clearLink = document.getElementById('squadron-filter-clear');
    const searchInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('squadron-ship-filter')
    );
    let squadronFilterTimer = null;

    function applySquadronFilter(url) {
        if (!resultsContainer || !window.krtFetch) {
            if (url) {
                window.location.assign(url);
            } else if (filterForm) {
                filterForm.submit();
            }
            return;
        }
        let target = url;
        if (!target && filterForm) {
            const data = new FormData(filterForm);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') {
                    params.append(key, String(value));
                }
            }
            const query = params.toString();
            target = '/hangar/squadron' + (query ? '?' + query : '');
        }
        window.krtFetch.swap({ url: target, container: resultsContainer, history: true });
    }

    if (filterForm) {
        filterForm.addEventListener('submit', function (event) {
            event.preventDefault();
            clearTimeout(squadronFilterTimer);
            applySquadronFilter();
        });
    }
    if (searchInput) {
        searchInput.addEventListener('input', function () {
            clearTimeout(squadronFilterTimer);
            squadronFilterTimer = setTimeout(function () {
                applySquadronFilter();
            }, 300);
        });
    }
    if (clearLink) {
        clearLink.addEventListener('click', function (event) {
            event.preventDefault();
            if (searchInput) {
                searchInput.value = '';
            }
            applySquadronFilter(clearLink.getAttribute('href'));
        });
    }
});

document.addEventListener('krt:swapped', function (e) {
    bindSquadronDetailsToggles(e && e.detail ? e.detail.container : document);
});
