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
 * Everything here is read-only presentation over already-rendered rows — the page has no write
 * seam:
 *   1. the per-bucket drill-down toggle, persisted per browser;
 *   2. the collapsible filter panel (material / quality / hide-covered), mirroring the Lager;
 *   3. client-side sorting of every group table by one shared column selection;
 *   4. the live-sync receiver, which re-fetches the tables when a peer changes an order.
 *
 * Because a live-sync swap replaces the server-rendered (unfiltered, unsorted, collapsed) rows,
 * every view state above is re-applied on `krt:swapped`.
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

// ---- Persisted view state (REQ-UI-017 / ADR-0120) ----------------------------------------------
// One JSON object per page under one bare key (no user id — the app-wide default outside bank
// surfaces). The drill-down expansion keeps its own pre-existing key so a stored expansion set
// survives this change.
const DEMAND_EXPANDED_KEY = 'orders_demand_expanded';
const DEMAND_FILTERS_KEY = 'orders_demand_filters';

/**
 * @typedef {object} DemandViewState
 * @property {string[]|null} excludedMaterials material ids to hide, or null when none are excluded
 * @property {string[]|null} excludedQualities quality tokens to hide, or null when none are
 * @property {boolean} hideCovered whether rows whose outstanding amount is 0 are hidden
 * @property {string|null} sortKey the sorted column, or null for the server order
 * @property {string} sortDir 'asc' or 'desc'
 * @property {boolean|undefined} panelCollapsed explicit panel choice; undefined = never chosen
 */

/**
 * The live view state. Materials and qualities are stored as EXCLUSIONS rather than selections on
 * purpose: the option list is rendered from the data present at page load, so a material that only
 * appears after a live-sync swap has no checkbox. Keyed on exclusions it is simply not excluded and
 * stays visible, where a selection list would silently hide it.
 *
 * @type {DemandViewState}
 */
let demandState = {
    excludedMaterials: null,
    excludedQualities: null,
    hideCovered: false,
    sortKey: null,
    sortDir: 'asc',
    panelCollapsed: undefined,
};

/**
 * Reads the persisted view state, degrading to defaults when storage is unavailable or holds a
 * corrupt value (private mode, quota, hand-edited storage).
 *
 * @returns {void}
 */
function readDemandState() {
    try {
        const raw = localStorage.getItem(DEMAND_FILTERS_KEY);
        if (!raw) return;
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object') return;
        demandState = {
            excludedMaterials: Array.isArray(parsed.excludedMaterials)
                ? parsed.excludedMaterials.filter((v) => typeof v === 'string')
                : null,
            excludedQualities: Array.isArray(parsed.excludedQualities)
                ? parsed.excludedQualities.filter((v) => typeof v === 'string')
                : null,
            hideCovered: parsed.hideCovered === true,
            sortKey: typeof parsed.sortKey === 'string' ? parsed.sortKey : null,
            sortDir: parsed.sortDir === 'desc' ? 'desc' : 'asc',
            panelCollapsed:
                typeof parsed.panelCollapsed === 'boolean' ? parsed.panelCollapsed : undefined,
        };
    } catch (_e) {
        /* corrupt value / private mode: keep the defaults */
    }
}

/**
 * Persists the view state, silently skipping persistence when storage is denied. Empty exclusion
 * lists collapse to null so "nothing filtered" is stored as the absence of a filter.
 *
 * @returns {void}
 */
function writeDemandState() {
    try {
        localStorage.setItem(
            DEMAND_FILTERS_KEY,
            JSON.stringify({
                excludedMaterials:
                    demandState.excludedMaterials && demandState.excludedMaterials.length
                        ? demandState.excludedMaterials
                        : null,
                excludedQualities:
                    demandState.excludedQualities && demandState.excludedQualities.length
                        ? demandState.excludedQualities
                        : null,
                hideCovered: demandState.hideCovered,
                sortKey: demandState.sortKey,
                sortDir: demandState.sortDir,
                panelCollapsed: demandState.panelCollapsed,
            }),
        );
    } catch (_e) {
        /* quota / private mode: skip persistence */
    }
}

/**
 * How many filter dimensions currently narrow the table. Sorting is not a filter and never counts.
 *
 * @returns {number} the number of active filter dimensions.
 */
function countActiveDemandFilters() {
    let active = 0;
    if (demandState.excludedMaterials && demandState.excludedMaterials.length) active++;
    if (demandState.excludedQualities && demandState.excludedQualities.length) active++;
    if (demandState.hideCovered) active++;
    return active;
}

// ---- Filter panel ------------------------------------------------------------------------------

/**
 * Syncs the active-filter chip on the toggle, including its screen-reader twin, so a shortened
 * table is never left unexplained.
 *
 * @returns {void}
 */
function updateDemandFilterCount() {
    const badge = document.getElementById('demandFilterCount');
    const value = document.getElementById('demandFilterCountValue');
    const label = document.getElementById('demandFilterCountLabel');
    if (!badge || !value || !label) return;
    const active = countActiveDemandFilters();
    if (active === 0) {
        badge.setAttribute('hidden', '');
    } else {
        badge.removeAttribute('hidden');
    }
    value.textContent = String(active);
    const template = badge.getAttribute('data-label') || '';
    label.textContent = template.replace('{0}', String(active));
}

/**
 * Shows or hides the filter panel and keeps the toggle's `aria-expanded` in step.
 *
 * @param {boolean} collapsed whether the panel should be hidden.
 * @returns {void}
 */
function setDemandPanelCollapsed(collapsed) {
    const panel = document.getElementById('demandFilterPanel');
    const toggle = document.getElementById('demandFilterToggle');
    if (!panel || !toggle) return;
    if (collapsed) {
        panel.setAttribute('hidden', '');
    } else {
        panel.removeAttribute('hidden');
    }
    toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
}

/**
 * Toggles the panel and remembers the explicit choice, so it wins over the "collapsed when nothing
 * is filtered" default from then on.
 *
 * @returns {void}
 */
function toggleDemandPanel() {
    const toggle = document.getElementById('demandFilterToggle');
    const collapsed = toggle ? toggle.getAttribute('aria-expanded') === 'true' : false;
    demandState.panelCollapsed = collapsed;
    setDemandPanelCollapsed(collapsed);
    writeDemandState();
}

// ---- Material multi-select ---------------------------------------------------------------------

/**
 * Opens the given options list, closing any other open multi-select first.
 *
 * @param {Element} header the clicked multi-select header.
 * @returns {void}
 */
function toggleDemandMulti(header) {
    const targetId = header.getAttribute('data-multi-target');
    if (!targetId) return;
    const target = document.getElementById(targetId);
    document.querySelectorAll('.multi-select-options.open').forEach((list) => {
        if (list !== target) list.classList.remove('open');
    });
    if (target) target.classList.toggle('open');
}

/**
 * The material option checkboxes currently in the DOM.
 *
 * @returns {HTMLInputElement[]} the per-material checkboxes.
 */
function demandMaterialCheckboxes() {
    return /** @type {HTMLInputElement[]} */ (
        Array.from(document.querySelectorAll('#demandMaterialOptions .demandMatCheck'))
    );
}

/**
 * Rewrites the multi-select header text: "Alle" when everything (or nothing) is checked, the single
 * option's own label when exactly one is, otherwise "N ausgewählt" — the shared idiom of the Lager
 * and orders-index multi-selects.
 *
 * @returns {void}
 */
function updateDemandMaterialHeaderText() {
    const header = document.getElementById('demandMaterialHeader');
    const text = document.getElementById('demandMaterialSelectedText');
    if (!header || !text) return;
    const boxes = demandMaterialCheckboxes();
    const checked = boxes.filter((box) => box.checked);
    const allLabel = header.getAttribute('data-all') || '';
    const selectedLabel = header.getAttribute('data-selected') || '';
    if (checked.length === boxes.length || checked.length === 0) {
        text.textContent = allLabel;
        return;
    }
    if (checked.length === 1) {
        const option = checked[0].closest('.multi-select-option');
        const span = option ? option.querySelector('span') : null;
        text.textContent = span && span.textContent ? span.textContent : selectedLabel;
        return;
    }
    text.textContent = checked.length + ' ' + selectedLabel;
}

/**
 * Rebuilds the material exclusion list from the checkboxes and re-renders.
 *
 * @returns {void}
 */
function syncDemandMaterialSelection() {
    const boxes = demandMaterialCheckboxes();
    const excluded = boxes.filter((box) => !box.checked).map((box) => box.value);
    demandState.excludedMaterials = excluded.length ? excluded : null;
    const all = /** @type {HTMLInputElement|null} */ (document.getElementById('demandMatAll'));
    if (all) all.checked = excluded.length === 0;
    updateDemandMaterialHeaderText();
    writeDemandState();
    applyDemandView();
}

/**
 * "Alle" drives every material checkbox.
 *
 * @param {Element} input the clicked select-all checkbox.
 * @returns {void}
 */
function toggleDemandMaterialAll(input) {
    const box = /** @type {HTMLInputElement} */ (input);
    const checked = box.checked;
    demandMaterialCheckboxes().forEach((box) => {
        box.checked = checked;
    });
    syncDemandMaterialSelection();
}

/**
 * Narrows the visible material options to those whose name contains the search term. Purely visual:
 * a hidden option keeps its checked state, so searching never changes what the table shows.
 *
 * @param {Element} input the search field.
 * @returns {void}
 */
function filterDemandMaterialOptions(input) {
    const field = /** @type {HTMLInputElement} */ (input);
    const term = (field.value || '').trim().toLowerCase();
    document
        .querySelectorAll('#demandMaterialOptions .demand-material-option')
        .forEach((option) => {
            const name = (option.getAttribute('data-material-name') || '').toLowerCase();
            if (!term || name.indexOf(term) >= 0) {
                option.removeAttribute('hidden');
            } else {
                option.setAttribute('hidden', '');
            }
        });
}

// ---- Quality + hide-covered --------------------------------------------------------------------

/**
 * Rebuilds the quality exclusion list from its checkboxes and re-renders.
 *
 * @returns {void}
 */
function syncDemandQualitySelection() {
    const boxes = /** @type {HTMLInputElement[]} */ (
        Array.from(document.querySelectorAll('.demandQualCheck'))
    );
    const excluded = boxes.filter((box) => !box.checked).map((box) => box.value);
    demandState.excludedQualities = excluded.length ? excluded : null;
    writeDemandState();
    applyDemandView();
}

/**
 * Reads the hide-covered toggle and re-renders.
 *
 * @param {Element} input the toggle.
 * @returns {void}
 */
function toggleDemandHideCovered(input) {
    const toggle = /** @type {HTMLInputElement} */ (input);
    demandState.hideCovered = toggle.checked;
    writeDemandState();
    applyDemandView();
}

/**
 * Clears every filter (but not the sort) and restores the controls to "show everything".
 *
 * @returns {void}
 */
function resetDemandFilters() {
    demandState.excludedMaterials = null;
    demandState.excludedQualities = null;
    demandState.hideCovered = false;
    writeDemandState();
    restoreDemandControls();
    applyDemandView();
}

// ---- Sorting -----------------------------------------------------------------------------------

/**
 * Cycles the clicked column: ascending on first click, descending on the second, back to the
 * server's own order on the third — so there is always a way back to the default.
 *
 * @param {Element} button the clicked column-header button.
 * @returns {void}
 */
function cycleDemandSort(button) {
    const key = button.getAttribute('data-sort-key');
    if (!key) return;
    if (demandState.sortKey !== key) {
        demandState.sortKey = key;
        demandState.sortDir = 'asc';
    } else if (demandState.sortDir === 'asc') {
        demandState.sortDir = 'desc';
    } else {
        demandState.sortKey = null;
        demandState.sortDir = 'asc';
    }
    writeDemandState();
    applyDemandView();
}

/**
 * The sort value of one row for a column: the material name for the name column, the raw numeric
 * data attribute otherwise. Never the rendered cell text, which is localised ("1000,000 SCU") and
 * would sort as a string.
 *
 * @param {Element} row the bucket row.
 * @param {string} key the sort column key.
 * @returns {string|number} the comparable value.
 */
function demandSortValue(row, key) {
    if (key === 'material') {
        return row.getAttribute('data-material-name') || '';
    }
    const raw = parseFloat(row.getAttribute('data-' + key) || '');
    return isNaN(raw) ? 0 : raw;
}

/**
 * Reorders every group table by the active column. Each bucket is TWO rows — the figures and its
 * drill-down — so they are moved as a pair, otherwise a sort would separate a drill-down from the
 * material it belongs to. Ties keep the server's order via the stamped original index.
 *
 * @returns {void}
 */
function applyDemandSort() {
    const key = demandState.sortKey;
    const factor = demandState.sortDir === 'desc' ? -1 : 1;

    document.querySelectorAll('.demand-table').forEach((table) => {
        const body = table.querySelector('tbody');
        if (!body) return;
        const rows = Array.from(body.querySelectorAll('tr[data-testid="demand-row"]'));
        rows.forEach((row, index) => {
            if (!row.hasAttribute('data-demand-order')) {
                row.setAttribute('data-demand-order', String(index));
            }
        });

        const ordered = rows.slice().sort((a, b) => {
            if (key) {
                const left = demandSortValue(a, key);
                const right = demandSortValue(b, key);
                let cmp;
                if (typeof left === 'string' && typeof right === 'string') {
                    cmp = left.localeCompare(right, undefined, { sensitivity: 'base' });
                } else {
                    cmp = Number(left) - Number(right);
                }
                if (cmp !== 0) return cmp * factor;
            }
            const ao = parseInt(a.getAttribute('data-demand-order') || '0', 10);
            const bo = parseInt(b.getAttribute('data-demand-order') || '0', 10);
            return ao - bo;
        });

        ordered.forEach((row) => {
            const bucketKey = row.getAttribute('data-bucket-key');
            body.appendChild(row);
            if (bucketKey) {
                const drill = body.querySelector(
                    '[data-bucket-orders="' + escapeSelectorValue(bucketKey) + '"]',
                );
                if (drill) body.appendChild(drill);
            }
        });
    });

    document.querySelectorAll('.demand-sortable').forEach((header) => {
        const col = header.getAttribute('data-sort-col');
        const indicator = header.querySelector('.demand-sort-indicator');
        const active = key !== null && col === key;
        header.setAttribute(
            'aria-sort',
            active ? (demandState.sortDir === 'desc' ? 'descending' : 'ascending') : 'none',
        );
        if (indicator) {
            indicator.textContent = active ? (demandState.sortDir === 'desc' ? '▼' : '▲') : '↕';
        }
    });
}

// ---- Filtering + rendering ---------------------------------------------------------------------

/**
 * Applies the filters to every bucket row, hides a group left with no visible row, and surfaces the
 * empty-result notice. Then re-applies the sort and the drill-down expansion, so one call fully
 * reconciles the DOM with the view state.
 *
 * @returns {void}
 */
function applyDemandView() {
    const excludedMaterials = demandState.excludedMaterials || [];
    const excludedQualities = demandState.excludedQualities || [];
    let visibleTotal = 0;

    document.querySelectorAll('section[data-testid="demand-group"]').forEach((group) => {
        let visibleInGroup = 0;
        group.querySelectorAll('tr[data-testid="demand-row"]').forEach((row) => {
            const materialId = row.getAttribute('data-material-id') || '';
            const quality = row.getAttribute('data-quality') || '';
            const outstanding = parseFloat(row.getAttribute('data-outstanding') || '0');
            const hidden =
                excludedMaterials.indexOf(materialId) >= 0 ||
                excludedQualities.indexOf(quality) >= 0 ||
                (demandState.hideCovered && !(outstanding > 0));

            const bucketKey = row.getAttribute('data-bucket-key');
            const drill = bucketKey
                ? group.querySelector(
                      '[data-bucket-orders="' + escapeSelectorValue(bucketKey) + '"]',
                  )
                : null;
            if (hidden) {
                row.setAttribute('hidden', '');
                if (drill) drill.setAttribute('hidden', '');
            } else {
                row.removeAttribute('hidden');
                if (drill) drill.removeAttribute('hidden');
                visibleInGroup++;
            }
        });
        if (visibleInGroup === 0) {
            group.setAttribute('hidden', '');
        } else {
            group.removeAttribute('hidden');
        }
        visibleTotal += visibleInGroup;
    });

    const noMatch = document.getElementById('demandNoMatch');
    if (noMatch) {
        const anyGroup = document.querySelector('section[data-testid="demand-group"]');
        if (anyGroup && visibleTotal === 0) {
            noMatch.removeAttribute('hidden');
        } else {
            noMatch.setAttribute('hidden', '');
        }
    }

    applyDemandSort();
    restoreBucketStates(document);
    updateDemandFilterCount();
}

/**
 * Pushes the persisted state back into the controls. Runs on load and after a reset; the panel's
 * own collapsed state is handled separately because it also has a data-dependent default.
 *
 * @returns {void}
 */
function restoreDemandControls() {
    const excludedMaterials = demandState.excludedMaterials || [];
    demandMaterialCheckboxes().forEach((box) => {
        box.checked = excludedMaterials.indexOf(box.value) < 0;
    });
    const all = /** @type {HTMLInputElement|null} */ (document.getElementById('demandMatAll'));
    if (all) all.checked = excludedMaterials.length === 0;
    updateDemandMaterialHeaderText();

    const excludedQualities = demandState.excludedQualities || [];
    document.querySelectorAll('.demandQualCheck').forEach((element) => {
        const box = /** @type {HTMLInputElement} */ (element);
        box.checked = excludedQualities.indexOf(box.value) < 0;
    });

    const hideCovered = /** @type {HTMLInputElement|null} */ (
        document.getElementById('demandHideCovered')
    );
    if (hideCovered) hideCovered.checked = demandState.hideCovered;
}

// ---- Collapsible per-bucket order drill-down ----------------------------------------------------
// The set of expanded bucket keys lives in localStorage as a JSON array and is re-applied on load
// AND after every fragment swap, so a live-sync refresh never collapses what the user opened.

/**
 * Reads the expanded bucket keys, degrading to "none expanded" when localStorage is unavailable or
 * holds a corrupt value.
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
 * Escapes a value for use inside an attribute selector. Bucket keys concatenate UUIDs with `|`,
 * which CSS would otherwise not accept verbatim.
 *
 * @param {string} value the raw value.
 * @returns {string} the escaped value.
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

// ---- Wiring ------------------------------------------------------------------------------------

// Document-delegated so every control survives the results fragment's swaps.
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'demand-toggle-orders', toggleBucketOrders);
    window.krtEvents.on('click', 'demand-toggle-filters', toggleDemandPanel);
    window.krtEvents.on('click', 'demand-toggle-multi', toggleDemandMulti);
    window.krtEvents.on('change', 'demand-toggle-material-all', toggleDemandMaterialAll);
    window.krtEvents.on('change', 'demand-update-material-state', syncDemandMaterialSelection);
    window.krtEvents.on('change', 'demand-update-quality', syncDemandQualitySelection);
    window.krtEvents.on('change', 'demand-toggle-hide-covered', toggleDemandHideCovered);
    window.krtEvents.on('click', 'demand-reset-filters', resetDemandFilters);
    window.krtEvents.on('input', 'demand-material-search', filterDemandMaterialOptions);
    window.krtEvents.on('click', 'demand-sort', cycleDemandSort);
}

// Close the material dropdown on an outside click.
document.addEventListener('click', (e) => {
    const target = /** @type {Element|null} */ (e.target);
    if (target && !target.closest('.multi-select-container')) {
        document
            .querySelectorAll('.multi-select-options.open')
            .forEach((list) => list.classList.remove('open'));
    }
});

document.addEventListener('DOMContentLoaded', function () {
    readDemandState();
    restoreDemandControls();
    // Panel default: an explicit choice always wins; otherwise it starts collapsed only when
    // nothing is filtered, so an active filter is never hidden behind a closed panel.
    setDemandPanelCollapsed(
        typeof demandState.panelCollapsed === 'boolean'
            ? demandState.panelCollapsed
            : countActiveDemandFilters() === 0,
    );
    applyDemandView();

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

// A swap replaces the server-rendered rows — unfiltered, unsorted and collapsed — so the whole view
// state is re-applied afterwards. Without this the user's filter and sort silently reset on every
// live-sync refresh.
document.addEventListener('krt:swapped', function () {
    applyDemandView();
});
