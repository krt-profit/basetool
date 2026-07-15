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

/* global KRT_ORDERS_AGE_YELLOW, KRT_ORDERS_AGE_RED, KRT_ORDERS_LIVESYNC_UPDATES, KRT_ORDERS_SECTION_REFRESH_ERROR */

// ---- Live multi-user sync — the staff queue (REQ-FE-010 / REQ-FE-015, ADR-0094) -------------
// When anyone creates / reorders / completes an order, every other viewer's queue re-fetches its
// OWN filter/page in place over the shared /ws/sync `orders` room. Only the opaque `queue` key
// crosses the wire; each viewer re-pulls its own authorization-checked list fragment (a non-profit
// requester is denied the room entirely — canViewJobOrders). ORDERS_SECTIONS mirrors the server
// LiveSyncTopicClass.ORDERS_QUEUE whitelist (the three-mirror-points rule).
const ORDERS_SECTIONS = {
    queue: { container: '#orders-results', fragmentValue: 'results' },
};

(function () {
    if (!window.krtFetch || typeof window.krtFetch.sectionWrite !== 'function') {
        return; // no-JS / no-foundation: the classic GET filter form runs.
    }
    const ordersQueueSeam = window.krtFetch.sectionWrite({
        dict: function () {
            return {
                'orders.section.refresh.error':
                    typeof KRT_ORDERS_SECTION_REFRESH_ERROR !== 'undefined'
                        ? KRT_ORDERS_SECTION_REFRESH_ERROR
                        : '',
            };
        },
        keys: { refreshErrorKey: 'orders.section.refresh.error' },
        sections: ORDERS_SECTIONS,
        // Each peer re-fetches ITS OWN current filter + page, not the actor's.
        pageUrl: function () {
            return window.location.pathname + window.location.search;
        },
        broadcast: function (keys) {
            if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                window.krtLiveSync.sendChanged('orders', keys);
            }
        },
    });
    // Exposed so the LOGISTICIAN-only reorder module can re-render + broadcast the queue in one call.
    window.krtRefreshOrdersQueue = ordersQueueSeam.refresh;

    if (window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: 'orders',
            sections: ORDERS_SECTIONS,
            // Global room: coalesce longer (#1125) to flatten the refetch herd when many viewers get
            // the same signal at once.
            coalesceMs: 1500,
            refresh: function (keys) {
                if (window.krtRefreshOrdersQueue) {
                    window.krtRefreshOrdersQueue(keys, { broadcast: false });
                }
            },
            // Never yank the queue out from under an in-flight drag-reorder.
            busyTest: function () {
                return window.__ordersDragging === true;
            },
            pill: {
                label: function () {
                    return typeof KRT_ORDERS_LIVESYNC_UPDATES !== 'undefined'
                        ? KRT_ORDERS_LIVESYNC_UPDATES
                        : undefined;
                },
            },
        });
    }
})();

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

// ---- Collapsible per-order material sublist (default collapsed, localStorage-persisted) ---------
// Each order row's material summary starts collapsed; the set of expanded order ids is stored in
// localStorage (JSON array) and re-applied on load AND after every fragment swap — the same idiom as
// the Lager tree (REQ-INV-002, inventory-my.js). Order ids are globally unique, so a single
// per-browser key is enough. State survives the queue's krtFetch swaps.
const ORDERS_MATERIALS_EXPANDED_KEY = 'orders_materials_expanded';

function readExpandedOrderMaterials() {
    try {
        return JSON.parse(localStorage.getItem(ORDERS_MATERIALS_EXPANDED_KEY) || '[]');
    } catch (_e) {
        return []; // corrupt value / private mode: treat as none expanded
    }
}

function writeExpandedOrderMaterials(values) {
    try {
        localStorage.setItem(ORDERS_MATERIALS_EXPANDED_KEY, JSON.stringify(values));
    } catch (_e) {
        /* quota / private mode: skip persistence */
    }
}

function applyOrderMaterialsState(orderId, expanded) {
    const body = document.querySelector(
        '[data-order-materials="' +
            (window.CSS && CSS.escape ? CSS.escape(orderId) : orderId) +
            '"]',
    );
    const btn = document.querySelector(
        '[data-trigger="ord-toggle-materials"][data-order-id="' +
            (window.CSS && CSS.escape ? CSS.escape(orderId) : orderId) +
            '"]',
    );
    if (body) {
        body.classList.toggle('krtm-display-none-5790', !expanded);
    }
    if (btn) {
        btn.setAttribute('aria-expanded', String(expanded));
        const icon = btn.querySelector('.toggle-icon');
        if (icon) icon.textContent = expanded ? '▼' : '▶';
    }
}

function toggleOrderMaterials(btn) {
    const orderId = btn.getAttribute('data-order-id');
    if (!orderId) return;
    const expandedList = readExpandedOrderMaterials();
    const isExpanded = expandedList.indexOf(orderId) >= 0;
    applyOrderMaterialsState(orderId, !isExpanded);
    if (isExpanded) {
        writeExpandedOrderMaterials(expandedList.filter((id) => id !== orderId));
    } else {
        expandedList.push(orderId);
        writeExpandedOrderMaterials(expandedList);
    }
}

function restoreOrderMaterials(root) {
    const expandedList = readExpandedOrderMaterials();
    (root || document).querySelectorAll('[data-order-materials]').forEach((body) => {
        const orderId = body.getAttribute('data-order-materials');
        applyOrderMaterialsState(orderId, expandedList.indexOf(orderId) >= 0);
    });
}

// ---- Multi-select squadron filter (default all, localStorage-persisted, REQ-ORDERS-027) ---------
// A checkbox dropdown of the active squadrons (mirrors the Lager .multi-select-container). The
// selection is stored per-user in localStorage; the list is filtered SERVER-side (the queue is
// paginated), so a change re-fetches the results fragment. "All checked" == no filter (show every
// scoped order, incl. SK-only); a subset sends its ids; "none checked" sends a nil-uuid sentinel
// (empty result). Absence of the key means "no saved preference" (leave the server default = all).
const ORDERS_SQUADRON_FILTER_KEY = 'orders_squadron_filter';
const ORDERS_SQUADRON_NONE_SENTINEL = '00000000-0000-0000-0000-000000000000';

function readSquadronFilter() {
    try {
        const raw = localStorage.getItem(ORDERS_SQUADRON_FILTER_KEY);
        return raw === null ? null : JSON.parse(raw);
    } catch (_e) {
        return null;
    }
}

function writeSquadronFilter(ids) {
    try {
        localStorage.setItem(ORDERS_SQUADRON_FILTER_KEY, JSON.stringify(ids));
    } catch (_e) {
        /* quota / private mode: skip persistence */
    }
}

function squadronBoxes() {
    return Array.prototype.slice.call(document.querySelectorAll('input.sqCheck'));
}

function collectCheckedSquadrons() {
    return squadronBoxes()
        .filter((b) => b.checked)
        .map((b) => b.value);
}

function persistSquadronFilter() {
    writeSquadronFilter(collectCheckedSquadrons());
}

function updateSquadronHeaderText() {
    const header = document.getElementById('squadronHeader');
    const textEl = document.getElementById('squadronSelectedText');
    const boxes = squadronBoxes();
    if (!header || !textEl || boxes.length === 0) return;
    const checked = boxes.filter((b) => b.checked);
    const dataAll = header.getAttribute('data-all') || 'All';
    const dataSelected = header.getAttribute('data-selected') || 'selected';
    if (checked.length === boxes.length) {
        textEl.textContent = dataAll;
    } else if (checked.length === 1) {
        const option = checked[0].closest('.multi-select-option');
        const label = option ? option.querySelector('span') : null;
        textEl.textContent = label ? label.textContent.trim() : checked.length + ' ' + dataSelected;
    } else {
        textEl.textContent = checked.length + ' ' + dataSelected;
    }
}

function toggleSquadronMulti() {
    const el = document.getElementById('squadronOptions');
    if (!el) return;
    const isOpen = el.classList.contains('open');
    document.querySelectorAll('.multi-select-options').forEach((o) => o.classList.remove('open'));
    if (!isOpen) el.classList.add('open');
}

function toggleSquadronAll() {
    const allBox = document.getElementById('sqAll');
    if (!allBox) return;
    squadronBoxes().forEach((b) => {
        b.checked = allBox.checked;
    });
    updateSquadronHeaderText();
    persistSquadronFilter();
    applyOrdersFilter();
}

function updateSquadronState() {
    const allBox = document.getElementById('sqAll');
    const boxes = squadronBoxes();
    if (allBox) allBox.checked = boxes.length > 0 && boxes.every((b) => b.checked);
    updateSquadronHeaderText();
    persistSquadronFilter();
    applyOrdersFilter();
}

// Re-render the results fragment for the current status + squadron selection (server-side filter +
// pagination). Top-level so both the status inputs and the delegated squadron handlers share it.
function applyOrdersFilter() {
    const filterForm = document.getElementById('orders-filter-form');
    const resultsContainer = document.getElementById('orders-results');
    if (!filterForm || !resultsContainer || !window.krtFetch) return;
    const data = new FormData(filterForm);
    const params = new URLSearchParams();
    for (const [key, value] of data.entries()) {
        if (key === 'squadronId') continue; // rebuilt below with the all/subset/none semantics
        if (value !== '') params.append(key, value);
    }
    const boxes = squadronBoxes();
    if (boxes.length > 0) {
        const checked = boxes.filter((b) => b.checked);
        if (checked.length === 0) {
            params.append('squadronId', ORDERS_SQUADRON_NONE_SENTINEL);
        } else if (checked.length < boxes.length) {
            checked.forEach((b) => params.append('squadronId', b.value));
        }
        // all checked -> omit squadronId entirely (no narrowing, shows SK-only orders too)
    }
    const query = params.toString();
    window.krtFetch.swap({
        url: '/orders' + (query ? '?' + query : ''),
        container: resultsContainer,
        history: true,
    });
}

// Apply the persisted squadron selection on load. Only re-fetches when the server did not already
// render this exact selection, so a pagination reload (which already carries the ids) is not
// clobbered back to page 1.
function restoreSquadronFilter() {
    const saved = readSquadronFilter();
    if (saved === null) {
        updateSquadronHeaderText();
        return;
    }
    const boxes = squadronBoxes();
    if (boxes.length === 0) return;
    const serverChecked = boxes
        .filter((b) => b.checked)
        .map((b) => b.value)
        .sort();
    const savedSel = saved.slice().sort();
    const savedSet = {};
    saved.forEach((id) => {
        savedSet[id] = true;
    });
    boxes.forEach((b) => {
        b.checked = savedSet[b.value] === true;
    });
    const allBox = document.getElementById('sqAll');
    if (allBox) allBox.checked = boxes.every((b) => b.checked);
    updateSquadronHeaderText();
    const differs =
        serverChecked.length !== savedSel.length || serverChecked.some((v, i) => v !== savedSel[i]);
    if (differs) applyOrdersFilter();
}

// Document-delegated so the toggles survive the queue's fragment swaps.
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'ord-toggle-materials', toggleOrderMaterials);
    window.krtEvents.on('click', 'ord-toggle-squadron-multi', toggleSquadronMulti);
    window.krtEvents.on('change', 'ord-toggle-squadron-all', toggleSquadronAll);
    window.krtEvents.on('change', 'ord-update-squadron-state', updateSquadronState);
}

// Close the squadron dropdown on an outside click.
document.addEventListener('click', (e) => {
    if (!e.target.closest('.multi-select-container')) {
        document
            .querySelectorAll('.multi-select-options.open')
            .forEach((o) => o.classList.remove('open'));
    }
});

document.addEventListener('DOMContentLoaded', () => {
    colorOrderAges(document);
    restoreOrderMaterials(document);
    restoreSquadronFilter();

    // In-place status + squadron filter (epic #571 / #573). The form id was renamed off the generic
    // "filter-form" so the sidebar's generic change->submit auto-reload no longer fires here. The
    // squadron checkboxes are handled by the delegated handlers above (they also persist + re-fetch);
    // only the status inputs bind directly here.
    const filterForm = document.getElementById('orders-filter-form');
    if (filterForm && window.krtFetch) {
        filterForm.addEventListener('submit', (event) => {
            event.preventDefault();
            applyOrdersFilter();
        });
        filterForm.querySelectorAll('input[name="status"]').forEach((el) => {
            el.addEventListener('change', applyOrdersFilter);
        });
    }
});

document.addEventListener('krt:swapped', (e) => {
    const container = e && e.detail ? e.detail.container : document;
    colorOrderAges(container);
    restoreOrderMaterials(container);
});
