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

// @ts-check

/*
 * Cross-order material-demand page module (/orders/material-demand, REQ-ORDERS-034).
 *
 * Two responsibilities, both read-only — the page has no write seam:
 *   1. the per-bucket drill-down toggle, persisted per browser in localStorage;
 *   2. the live-sync receiver on the global `orders` room, which re-fetches the demand tables in
 *      place when a peer creates, completes or re-stocks an order.
 *
 * KRT_DEMAND_* are defined by the inline Thymeleaf bootstrap block of orders-material-demand.html,
 * which executes immediately before this classic script.
 */

/* global KRT_DEMAND_LIVESYNC_UPDATES, KRT_DEMAND_SECTION_REFRESH_ERROR */

// ---- Live multi-user sync — the aggregated demand (REQ-FE-010 / REQ-FE-015) ---------------------
// This page shares the global `orders` room with the order list: both render folds of the same
// queue and are invalidated by the same events. It carries only the `demand` key and the list only
// `queue`, so the two seam maps PARTITION the LiveSyncTopicClass.ORDERS_QUEUE whitelist (asserted by
// LiveSyncSectionMapParityTest — the three-mirror-points rule).
const DEMAND_SECTIONS = {
    demand: { container: '#orders-material-demand-results', fragmentValue: 'results' },
};

// ---- Collapsible per-bucket order drill-down (default collapsed, localStorage-persisted) --------
// Mirrors the order list's material sublist (REQ-ORDERS-027): the set of expanded bucket keys lives
// in localStorage as a JSON array and is re-applied on load AND after every fragment swap, so a
// live-sync refresh never collapses what the user opened. The key is bare (no user id) — the
// app-wide default for non-bank surfaces (REQ-UI-017).
const DEMAND_EXPANDED_KEY = 'orders_demand_expanded';

/**
 * Reads the expanded bucket keys, degrading to "none expanded" when localStorage is unavailable or
 * holds a corrupt value (private mode, quota, hand-edited storage).
 *
 * @returns {string[]} the persisted bucket keys.
 */
function readExpandedBuckets() {
    try {
        const parsed = JSON.parse(localStorage.getItem(DEMAND_EXPANDED_KEY) || '[]');
        return Array.isArray(parsed) ? parsed.filter((v) => typeof v === 'string') : [];
    } catch (_e) {
        return [];
    }
}

/**
 * Persists the expanded bucket keys, silently skipping persistence when storage is denied.
 *
 * @param {string[]} values the bucket keys to store.
 * @returns {void}
 */
function writeExpandedBuckets(values) {
    try {
        localStorage.setItem(DEMAND_EXPANDED_KEY, JSON.stringify(values));
    } catch (_e) {
        /* quota / private mode: skip persistence */
    }
}

/**
 * Escapes a bucket key for use inside an attribute selector. The key concatenates UUIDs with `|`,
 * which CSS would otherwise not accept verbatim.
 *
 * @param {string} value the raw bucket key.
 * @returns {string} the escaped key.
 */
function escapeSelectorValue(value) {
    return window.CSS && typeof CSS.escape === 'function' ? CSS.escape(value) : value;
}

/**
 * Applies one bucket's expanded state to the DOM: shows or hides its drill-down row and syncs the
 * toggle's caret and `aria-expanded`.
 *
 * @param {string} bucketKey the bucket to apply.
 * @param {boolean} expanded whether the drill-down should be visible.
 * @returns {void}
 */
function applyBucketState(bucketKey, expanded) {
    const escaped = escapeSelectorValue(bucketKey);
    const row = document.querySelector('[data-bucket-orders="' + escaped + '"]');
    const btn = document.querySelector(
        '[data-trigger="demand-toggle-orders"][data-bucket-key="' + escaped + '"]',
    );
    if (row) {
        row.classList.toggle('krtm-display-none-5790', !expanded);
    }
    if (btn) {
        btn.setAttribute('aria-expanded', String(expanded));
        const icon = btn.querySelector('.toggle-icon');
        if (icon) icon.textContent = expanded ? '▼' : '▶';
    }
}

/**
 * Toggles one bucket's drill-down and persists the new state.
 *
 * @param {Element} btn the clicked toggle button.
 * @returns {void}
 */
function toggleBucketOrders(btn) {
    const bucketKey = btn.getAttribute('data-bucket-key');
    if (!bucketKey) return;
    const expandedList = readExpandedBuckets();
    const isExpanded = expandedList.indexOf(bucketKey) >= 0;
    applyBucketState(bucketKey, !isExpanded);
    if (isExpanded) {
        writeExpandedBuckets(expandedList.filter((key) => key !== bucketKey));
    } else {
        expandedList.push(bucketKey);
        writeExpandedBuckets(expandedList);
    }
}

/**
 * Re-applies the persisted expanded state to every bucket currently in the DOM. Run on load and
 * after each fragment swap, because a swap replaces the rows with server-rendered collapsed ones.
 *
 * @param {ParentNode} [root] the subtree to restore; defaults to the whole document.
 * @returns {void}
 */
function restoreBucketStates(root) {
    const expandedList = readExpandedBuckets();
    (root || document).querySelectorAll('[data-bucket-orders]').forEach((row) => {
        const bucketKey = row.getAttribute('data-bucket-orders');
        if (bucketKey) {
            applyBucketState(bucketKey, expandedList.indexOf(bucketKey) >= 0);
        }
    });
}

// Document-delegated so the toggles survive the demand tables' fragment swaps.
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'demand-toggle-orders', toggleBucketOrders);
}

document.addEventListener('DOMContentLoaded', function () {
    restoreBucketStates(document);

    if (
        window.krtLiveSync &&
        typeof window.krtLiveSync.createReceiver === 'function' &&
        window.krtFetch &&
        typeof window.krtFetch.swap === 'function' &&
        document.getElementById('orders-material-demand-results')
    ) {
        window.krtLiveSync.createReceiver({
            topic: 'orders',
            sections: DEMAND_SECTIONS,
            // Global room: coalesce like the order queue does, so a burst of order writes does not
            // trigger a refetch herd across every viewer.
            coalesceMs: 1500,
            refresh: function () {
                window.krtFetch.swap({
                    url: window.location.pathname,
                    container: '#orders-material-demand-results',
                    fragmentValue: DEMAND_SECTIONS.demand.fragmentValue,
                    history: false,
                    errorMessage:
                        typeof KRT_DEMAND_SECTION_REFRESH_ERROR !== 'undefined'
                            ? KRT_DEMAND_SECTION_REFRESH_ERROR
                            : undefined,
                });
            },
            pill: {
                label: function () {
                    return typeof KRT_DEMAND_LIVESYNC_UPDATES !== 'undefined'
                        ? KRT_DEMAND_LIVESYNC_UPDATES
                        : undefined;
                },
            },
        });
    }
});

// A swap replaces the server-rendered (collapsed) rows, so the user's expanded buckets are
// re-applied afterwards — without this the drill-downs silently close on every live-sync refresh.
document.addEventListener('krt:swapped', function () {
    restoreBucketStates(document);
});
