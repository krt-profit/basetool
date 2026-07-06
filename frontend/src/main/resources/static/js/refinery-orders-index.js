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
 * Refinery-orders list page module (/refinery-orders), extracted verbatim from the former
 * inline script of refinery-orders-index.html (ADR-0069, follow-up to #924).
 *
 * Formats the UTC data-utc timestamps into local dd.MM. HH:mm strings, marks finished orders
 * (now past their ends-at) with a bold success colour on a 10s interval, and drives the in-place
 * status filter through window.krtFetch.swap (the GET form stays the no-JS fallback). Both
 * enhancers re-run on krt:swapped so fragment-swapped rows are wired too.
 *
 * The block carried no Thymeleaf interpolation, so there is no inline bootstrap: the whole
 * script moved here unchanged.
 */

function updateRefineryOrderColors() {
    const nowMs = new Date().getTime();
    document.querySelectorAll('.refinery-order-id-display').forEach((el) => {
        const utcMsStr = el.getAttribute('data-utc');

        if (utcMsStr && utcMsStr !== 'null') {
            const endsAtMs = parseInt(utcMsStr, 10);
            if (!isNaN(endsAtMs) && nowMs > endsAtMs) {
                el.classList.add('text-success');
                el.style.fontWeight = 'bold';
            }
        }
    });
}

function formatLocalDates() {
    document.querySelectorAll('.local-datetime-display').forEach((el) => {
        const utcMsStr = el.getAttribute('data-utc');
        if (utcMsStr && utcMsStr !== 'null') {
            const date = new Date(parseInt(utcMsStr, 10));
            if (!isNaN(date)) {
                const day = String(date.getDate()).padStart(2, '0');
                const month = String(date.getMonth() + 1).padStart(2, '0');
                const hours = String(date.getHours()).padStart(2, '0');
                const minutes = String(date.getMinutes()).padStart(2, '0');
                el.innerText = `${day}.${month}. ${hours}:${minutes}`;
            }
        } else {
            el.innerText = '-';
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    formatLocalDates();
    updateRefineryOrderColors();
    setInterval(updateRefineryOrderColors, 10000);

    // In-place status filter (epic #571 / #573). The GET form stays the no-JS fallback;
    // the form id was renamed away from the generic "filter-form" so the sidebar's generic
    // change->submit auto-reload no longer fires here.
    const filterForm = document.getElementById('refinery-filter-form');
    const resultsContainer = document.getElementById('refinery-orders-results');
    if (filterForm && resultsContainer && window.krtFetch) {
        const applyFilter = () => {
            const data = new FormData(filterForm);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') params.append(key, value);
            }
            const query = params.toString();
            window.krtFetch.swap({
                url: '/refinery-orders' + (query ? '?' + query : ''),
                container: resultsContainer,
                history: true,
            });
        };
        filterForm.addEventListener('submit', (event) => {
            event.preventDefault();
            applyFilter();
        });
        filterForm.querySelectorAll('input').forEach((el) => {
            el.addEventListener('change', applyFilter);
        });
    }
});

// Re-run the row enhancers after a fragment swap so swapped-in rows get their local-time
// display + overdue colouring (they are otherwise only wired on the initial page load).
document.addEventListener('krt:swapped', () => {
    formatLocalDates();
    updateRefineryOrderColors();
});
