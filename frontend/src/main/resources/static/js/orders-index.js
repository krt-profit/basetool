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
 * Job-order list page module (/orders), extracted verbatim from the first inline script of
 * orders-index.html (ADR-0069, follow-up to #924).
 *
 * Age-colours the order-id cells (yellow past KRT_ORDERS_AGE_YELLOW days, red past
 * KRT_ORDERS_AGE_RED) on load and again on krt:swapped, and drives the in-place status/scope filter
 * through window.krtFetch.swap (the GET form stays the no-JS fallback).
 *
 * KRT_ORDERS_AGE_YELLOW / KRT_ORDERS_AGE_RED are defined by the inline Thymeleaf bootstrap block of
 * orders-index.html, which executes immediately before this classic script.
 */

/* global KRT_ORDERS_AGE_YELLOW, KRT_ORDERS_AGE_RED */

// Age-colour the order ids. Extracted so it can re-run on `krt:swapped` — the row colours are
// otherwise only applied on the initial load and would be lost on a fragment swap (#573).
function colorOrderAges(root) {
    const now = new Date();
    (root || document).querySelectorAll('.order-id-display').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (utcDateStr) {
            const date = new Date(utcDateStr);
            if (!isNaN(date)) {
                const diffDays = Math.floor(
                    (now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24),
                );
                if (diffDays >= KRT_ORDERS_AGE_RED) {
                    el.classList.add('text-danger');
                    el.style.fontWeight = 'bold';
                } else if (diffDays >= KRT_ORDERS_AGE_YELLOW) {
                    el.classList.add('text-warning');
                    el.style.fontWeight = 'bold';
                }
            }
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    colorOrderAges(document);

    // In-place status/scope filter (epic #571 / #573). The form id was renamed off the generic
    // "filter-form" so the sidebar's generic change->submit auto-reload no longer fires here.
    const filterForm = document.getElementById('orders-filter-form');
    const resultsContainer = document.getElementById('orders-results');
    if (filterForm && resultsContainer && window.krtFetch) {
        const applyFilter = () => {
            const data = new FormData(filterForm);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') params.append(key, value);
            }
            const query = params.toString();
            window.krtFetch.swap({
                url: '/orders' + (query ? '?' + query : ''),
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

document.addEventListener('krt:swapped', (e) => {
    colorOrderAges(e && e.detail ? e.detail.container : document);
});
