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
 * enhancers re-run on krt:swapped so fragment-swapped rows are wired too. The status + onlyMine
 * selection persists per browser in localStorage (REQ-UI-017) and is restored on load.
 *
 * The block carried no Thymeleaf interpolation, so there is no inline bootstrap: the whole
 * script moved here unchanged.
 */

// ---- Live multi-user sync — the refinery queue (REQ-FE-010 / REQ-FE-015, ADR-0094, #1235) ------
// When anyone creates, edits, stores ("Einlagern") or cancels a refinery order, every other viewer's
// list re-fetches its OWN status/onlyMine filter and page in place over the shared /ws/sync
// `refinery` room. Only the opaque `queue` key crosses the wire; each viewer re-pulls its own
// authorization-checked results fragment, so the acting user's `onlyMine` narrowing can never leak
// into a peer's view. REFINERY_SECTIONS mirrors the server LiveSyncTopicClass.REFINERY whitelist
// (the REQ-FE-010 three-mirror-points rule, build-enforced by LiveSyncSectionMapParityTest).
//
// The BROADCAST side is server-side (RefineryOrderWriteController.liveSyncLocalBus): every refinery
// mutation navigates away, so a client publish would race the socket teardown, and the no-JS
// form-POST fallback would emit nothing at all. This module is therefore receive-only.
const REFINERY_SECTIONS = {
    queue: { container: '#refinery-orders-results', fragmentValue: 'results' },
};

// Re-renders the results fragment for the CURRENT filter selection without touching history — the
// peer-driven counterpart of the user-driven applyFilter() below (a peer's change must not push a
// history entry). Kept top-level so the receiver can be wired before DOMContentLoaded.
function refreshRefineryResults() {
    const filterForm = document.getElementById('refinery-filter-form');
    const resultsContainer = document.getElementById('refinery-orders-results');
    if (!filterForm || !resultsContainer || !window.krtFetch) return;
    const params = new URLSearchParams();
    for (const [key, value] of new FormData(filterForm).entries()) {
        if (value !== '') params.append(key, value);
    }
    const query = params.toString();
    window.krtFetch.swap({
        url: '/refinery-orders' + (query ? '?' + query : ''),
        container: resultsContainer,
        history: false,
        preserveScroll: true,
    });
}

if (window.krtLiveSync && typeof window.krtLiveSync.createReceiver === 'function') {
    window.krtLiveSync.createReceiver({
        topic: 'refinery',
        sections: REFINERY_SECTIONS,
        // Global room: the longer coalesce window (#1125) flattens the re-fetch herd when many
        // viewers receive the same signal at once.
        coalesceMs: 1500,
        refresh: refreshRefineryResults,
    });
}

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

// ---- Per-browser filter persistence (REQ-UI-017) ------------------------------------------------
// Mirrors the orders-queue status-filter idiom (orders-index.js, REQ-ORDERS-027): one JSON object
// {statuses: [...]|null, onlyMine: bool} under a single localStorage key, persisted on every filter
// change and replayed through the existing krtFetch.swap. Absence of the key — and a null statuses
// entry — means "no saved preference" (keep the server default OPEN+IN_PROGRESS). An explicit
// status/onlyMine query param (deep link / back-nav; the swaps run history:true) wins over the
// stored state and is re-persisted; only a bare URL restores from storage.
const REFINERY_FILTER_PREF_KEY = 'refinery_orders_filter';

function refineryStatusBoxes() {
    return Array.prototype.slice.call(
        document.querySelectorAll('#refinery-filter-form input[name="status"]'),
    );
}

function refineryOnlyMineBox() {
    return document.querySelector('#refinery-filter-form input[name="onlyMine"]');
}

function readRefineryFilterPref() {
    try {
        const raw = localStorage.getItem(REFINERY_FILTER_PREF_KEY);
        return raw === null ? null : JSON.parse(raw);
    } catch (_e) {
        return null;
    }
}

function writeRefineryFilterPref(value) {
    try {
        localStorage.setItem(REFINERY_FILTER_PREF_KEY, JSON.stringify(value));
    } catch (_e) {
        /* quota / private mode: skip persistence */
    }
}

function persistRefineryFilter() {
    const checked = refineryStatusBoxes()
        .filter((b) => b.checked)
        .map((b) => b.value);
    const onlyMine = refineryOnlyMineBox();
    writeRefineryFilterPref({
        // An empty selection is stored as null ("no preference"): the GET form would submit no
        // status param either, and the server then applies its OPEN+IN_PROGRESS default.
        statuses: checked.length > 0 ? checked : null,
        onlyMine: !!(onlyMine && onlyMine.checked),
    });
}

// Applies the persisted selection on load and reports whether it differs from what the server
// rendered, so the caller can trigger a single results re-fetch. A null or entirely-stale
// statuses entry leaves the server-rendered default untouched.
function restoreRefineryFilter() {
    const saved = readRefineryFilterPref();
    if (!saved || typeof saved !== 'object') return false;
    let differs = false;
    const boxes = refineryStatusBoxes();
    if (Array.isArray(saved.statuses) && boxes.length > 0) {
        const known = boxes.filter((b) => saved.statuses.indexOf(b.value) >= 0);
        if (known.length > 0) {
            boxes.forEach((b) => {
                const on = saved.statuses.indexOf(b.value) >= 0;
                if (b.checked !== on) differs = true;
                b.checked = on;
            });
        }
    }
    const onlyMine = refineryOnlyMineBox();
    if (onlyMine && typeof saved.onlyMine === 'boolean' && onlyMine.checked !== saved.onlyMine) {
        onlyMine.checked = saved.onlyMine;
        differs = true;
    }
    return differs;
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
            el.addEventListener('change', () => {
                persistRefineryFilter();
                applyFilter();
            });
        });
        // Restore the persisted filter (REQ-UI-017): an explicit status/onlyMine query param is
        // authoritative and re-persisted; a bare URL applies the saved selection and re-fetches
        // the results fragment once when it differs from the server-rendered default.
        if (/[?&](status|onlyMine)=/.test(window.location.search)) {
            persistRefineryFilter();
        } else if (restoreRefineryFilter()) {
            applyFilter();
        }
    }
});

// Re-run the row enhancers after a fragment swap so swapped-in rows get their local-time
// display + overdue colouring (they are otherwise only wired on the initial page load).
document.addEventListener('krt:swapped', () => {
    formatLocalDates();
    updateRefineryOrderColors();
});
