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
 * Promotion evaluation matrix page module (/promotion/manage), extracted verbatim from the
 * former inline script of promotion-manage.html (#924 Part 2).
 *
 * - Serialized, key-deduplicated save queue for the per-cell level-select PUTs; a 409 conflict
 *   drops the pending queue and re-renders the matrix in place via window.krtFetch.swap
 *   (fragment matrixBody) instead of a full-page reload.
 * - Per-member eligibility-cell swaps once the queue drains, topic collapse/expand with
 *   sessionStorage persistence, search/checkbox filters, a five-mode sort cycle, row selection
 *   with bulk apply through window.showKrtConfirm, client-side CSV export (UTF-8 BOM so Excel
 *   detects umlauts) and local formatting of the last-evaluated timestamps.
 * - Wiring runs on DOMContentLoaded through window.krtEvents delegation; the krt:swapped
 *   listener restores collapse/sort/filter/selection view state after a matrixBody container
 *   swap and deliberately ignores the id-less per-cell eligibility swaps.
 *
 * The localized MSG_* strings, SORT_LABELS and the sessionStorage key names are defined by the
 * inline Thymeleaf bootstrap block of promotion-manage.html, which executes immediately before
 * this classic script; both tags share th:unless="${isAllSquadronsMode}", so neither runs in
 * all-squadrons mode.
 */

/* global MSG_SAVED, MSG_ERROR, MSG_CONFLICT, MSG_LAST_EVAL, MSG_BULK_CONFIRM_TITLE, MSG_BULK_CONFIRM_MSG, MSG_BULK_NEED_CAT, MSG_BULK_NEED_LEVEL, MSG_CSV_NAME, MSG_CSV_HEADER_MEMBER, MSG_CSV_HEADER_RANK, MSG_CSV_HEADER_ELIG, MSG_CSV_HEADER_LAST, SORT_LABELS, STORAGE_KEY_COLLAPSED, STORAGE_KEY_SORT, STORAGE_KEY_FILTERS, MSG_REFRESH_FAILED */

// CSRF readers delegate to the shared krtCsrf module (epic #571); the meta-tag
// fallback keeps the page working if krt-fetch.js failed to load.
function getCsrfToken() {
    return (
        (window.krtCsrf && window.krtCsrf.token && window.krtCsrf.token()) ||
        document.querySelector('meta[name="_csrf"]')?.content ||
        ''
    );
}
function getCsrfHeader() {
    return (
        (window.krtCsrf && window.krtCsrf.headerName && window.krtCsrf.headerName()) ||
        document.querySelector('meta[name="_csrf_header"]')?.content ||
        'X-CSRF-TOKEN'
    );
}

function pmCsrfHeaders() {
    if (window.krtCsrf && typeof window.krtCsrf.headers === 'function') {
        return window.krtCsrf.headers();
    }
    const h = {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
    };
    h[getCsrfHeader()] = getCsrfToken();
    return h;
}

// PUT an evaluation through the shared CSRF reader, retrying once on a bare 403
// (stale token) before surfacing the response to the caller.
function pmPutEvaluation(url, payload) {
    function run() {
        return fetch(url, {
            method: 'PUT',
            headers: pmCsrfHeaders(),
            body: JSON.stringify(payload),
        });
    }
    return run().then(function (r) {
        if (r.status === 403 && window.krtCsrf && typeof window.krtCsrf.refresh === 'function') {
            return window.krtCsrf.refresh().then(function (ok) {
                return ok ? run() : r;
            });
        }
        return r;
    });
}

// Members whose stored grades changed during the current save run. Their
// eligibility chips are recomputed once the queue drains (one lightweight
// per-member fetch each) instead of reloading the whole matrix (#580).
const pmEligibilityDirty = new Set();

// Full in-place re-render of the matrix used to recover from an optimistic-lock
// conflict: the server rebuilds every row with fresh @Version / level /
// eligibility, replacing the old full-page reload. View state (collapse, sort,
// filter, formatting) is restored by the krt:swapped listener.
function pmRefreshMatrix() {
    if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
        window.location.reload();
        return Promise.resolve(false);
    }
    return window.krtFetch.swap({
        url: '/promotion/manage',
        container: '#pm-matrix-results',
        fragmentValue: 'matrixBody',
        errorMessage: MSG_REFRESH_FAILED,
    });
}

// Recompute one member's eligibility chips in place after a successful save by
// swapping just that row's eligibility cell, then derive the row-level
// data-pm-eligible flag the "Nur Beförderbare" filter reads.
function pmRefreshEligibility(userId) {
    const row = document.querySelector('tr[data-pm-user-id="' + userId + '"]');
    if (!row) return Promise.resolve(false);
    const cell = row.querySelector('.pm-eligibility-cell');
    if (!cell || !window.krtFetch || typeof window.krtFetch.swap !== 'function') {
        return Promise.resolve(false);
    }
    return window.krtFetch
        .swap({
            url: '/promotion/manage?userId=' + encodeURIComponent(userId),
            container: cell,
            fragmentValue: 'eligibilityCell',
        })
        .then(function (ok) {
            if (!ok) return false;
            const eligible = !!cell.querySelector('.eligibility-chip[data-pm-eligible="true"]');
            row.setAttribute('data-pm-eligible', eligible ? 'true' : 'false');
            pmApplyFilters();
            return true;
        });
}

function pmFlushEligibility() {
    if (pmEligibilityDirty.size === 0) return;
    const ids = Array.from(pmEligibilityDirty);
    pmEligibilityDirty.clear();
    ids.forEach(function (uid) {
        pmRefreshEligibility(uid);
    });
}

// ----------------------------------------------------------------------
// Save queue
// ----------------------------------------------------------------------
// Each item in the queue is `{ key, select }`. `key` is `userId_categoryId`,
// which is also the optimistic-lock identity in the backend. We dedupe by
// key so rapid changes to the same cell collapse into a single PUT (the
// last one wins) — important for two reasons:
//   - prevents needless network roundtrips when the user clicks through a
//     dropdown to settle on a value;
//   - prevents a queued earlier value from overwriting a later one with a
//     stale @Version, which would silently 409 and drop user input.
// Saves run sequentially: a row's `data-version` must be refreshed from
// the response before the next save for that row fires. Conflicts (409)
// pause the queue and reload the page so the user sees authoritative state.
let pmSaveQueue = [];
let pmInFlightKey = null;

function pmUpdateQueueIndicator() {
    const indicator = document.getElementById('pm-queue-indicator');
    const counter = document.getElementById('pm-queue-count');
    if (!indicator || !counter) return;
    const total = pmSaveQueue.length + (pmInFlightKey ? 1 : 0);
    counter.textContent = String(total);
    indicator.hidden = total === 0;
}

function pmEnqueueSave(select) {
    const userId = select.getAttribute('data-user-id');
    const catId = select.getAttribute('data-category-id');
    const key = userId + '_' + catId;
    // Replace any queued entry for the same cell — only the latest value
    // is interesting. The currently in-flight save is *not* cancelled;
    // its response will simply be followed by the next one for this cell.
    pmSaveQueue = pmSaveQueue.filter(function (job) {
        return job.key !== key;
    });
    pmSaveQueue.push({ key: key, select: select });
    select.classList.add('pm-pending');
    pmUpdateQueueIndicator();
    if (!pmInFlightKey) pmProcessNextSave();
}

function pmProcessNextSave() {
    if (pmSaveQueue.length === 0) {
        pmInFlightKey = null;
        pmUpdateQueueIndicator();
        // Queue drained: recompute eligibility chips for every member touched
        // during this run, in place, instead of reloading the page.
        pmFlushEligibility();
        return;
    }
    const job = pmSaveQueue.shift();
    pmInFlightKey = job.key;
    pmUpdateQueueIndicator();

    const select = job.select;
    const userId = select.getAttribute('data-user-id');
    const categoryId = select.getAttribute('data-category-id');
    const versionStr = select.getAttribute('data-version');
    const version = versionStr ? parseInt(versionStr, 10) : null;
    const assignedLevel = select.value || null;
    const cell = select.closest('td');

    return pmPutEvaluation(
        '/api/proxy/promotion/evaluations/user/' +
            encodeURIComponent(userId) +
            '/category/' +
            encodeURIComponent(categoryId),
        { version: version, assignedLevel: assignedLevel },
    )
        .then(function (response) {
            if (response.status === 409) {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(MSG_CONFLICT);
                }
                // Drop pending jobs and re-render the matrix in place from the
                // server's authoritative state (fresh @Version on every cell) —
                // the in-place equivalent of the old full-page reload.
                pmSaveQueue = [];
                pmEligibilityDirty.clear();
                pmRefreshMatrix();
                return null;
            }
            if (!response.ok) {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(MSG_ERROR);
                }
                return null;
            }
            return response.json();
        })
        .then(function (data) {
            if (data) {
                select.setAttribute('data-version', data.version);
                select.setAttribute('data-level', assignedLevel || '');
                // Mark this member for an eligibility recompute once the queue drains.
                pmEligibilityDirty.add(userId);
                if (cell) {
                    // Restart the animation by removing+re-adding the class on
                    // the next animation frame. Without the rAF the browser
                    // batches both mutations and the animation never replays.
                    cell.classList.remove('pm-cell-saved');
                    void cell.offsetWidth; /* trigger reflow */
                    cell.classList.add('pm-cell-saved');
                    setTimeout(function () {
                        cell.classList.remove('pm-cell-saved');
                    }, 850);
                }
                if (typeof window.showFrontendSuccessToast === 'function') {
                    window.showFrontendSuccessToast(MSG_SAVED);
                }
            }
        })
        .catch(function () {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(MSG_ERROR);
            }
        })
        .finally(function () {
            select.classList.remove('pm-pending');
            pmInFlightKey = null;
            pmProcessNextSave();
        });
}

// ----------------------------------------------------------------------
// Topic collapse / expand
// ----------------------------------------------------------------------
function pmLoadCollapsedTopics() {
    try {
        const raw = sessionStorage.getItem(STORAGE_KEY_COLLAPSED);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
        return [];
    }
}
function pmSaveCollapsedTopics(ids) {
    try {
        sessionStorage.setItem(STORAGE_KEY_COLLAPSED, JSON.stringify(ids));
    } catch (e) {
        /* ignore */
    }
}

function pmSetTopicCollapsed(topicId, collapsed) {
    const topicHeader = document.querySelector(
        'th.pm-topic-cell[data-pm-topic-id="' + topicId + '"]',
    );
    if (!topicHeader) return;
    topicHeader.setAttribute('data-pm-collapsed', collapsed ? 'true' : 'false');
    const toggle = topicHeader.querySelector('.pm-topic-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');

    const cells = document.querySelectorAll(
        '.pm-matrix [data-pm-topic-id="' + topicId + '"]:not(.pm-topic-cell)',
    );
    cells.forEach(function (cell) {
        cell.setAttribute('data-pm-topic-collapsed', collapsed ? 'true' : 'false');
    });
    // The topic header keeps its original colspan. Reducing it to 1
    // while the cat/body rows below lose all N category slots leaves
    // the topic row one slot shorter than the cat row, and the
    // browser slides the next topic's cells leftward to fill the
    // gap — which is the "I can't find the + trigger anymore" bug.
    // The visual collapse is purely CSS-driven now: the topic header
    // narrows to 3rem (data-pm-collapsed) and the cat/body cells
    // shrink to zero width (data-pm-topic-collapsed). See the CSS
    // block of the same name for details.
}

function pmApplyCollapsedState(collapsedIds) {
    const topicHeaders = document.querySelectorAll('th.pm-topic-cell[data-pm-topic-id]');
    topicHeaders.forEach(function (th) {
        const id = th.getAttribute('data-pm-topic-id');
        pmSetTopicCollapsed(id, collapsedIds.indexOf(id) !== -1);
    });
}

function pmToggleTopic(button) {
    const topicId = button.getAttribute('data-pm-topic-id');
    if (!topicId) return;
    const collapsedIds = pmLoadCollapsedTopics();
    const idx = collapsedIds.indexOf(topicId);
    if (idx === -1) {
        collapsedIds.push(topicId);
        pmSetTopicCollapsed(topicId, true);
    } else {
        collapsedIds.splice(idx, 1);
        pmSetTopicCollapsed(topicId, false);
    }
    pmSaveCollapsedTopics(collapsedIds);
}

function pmExpandAllTopics() {
    pmSaveCollapsedTopics([]);
    document.querySelectorAll('th.pm-topic-cell[data-pm-topic-id]').forEach(function (th) {
        pmSetTopicCollapsed(th.getAttribute('data-pm-topic-id'), false);
    });
}
function pmCollapseAllTopics() {
    const ids = [];
    document.querySelectorAll('th.pm-topic-cell[data-pm-topic-id]').forEach(function (th) {
        const id = th.getAttribute('data-pm-topic-id');
        ids.push(id);
        pmSetTopicCollapsed(id, true);
    });
    pmSaveCollapsedTopics(ids);
}

// ----------------------------------------------------------------------
// Filter (search + checkboxes)
// ----------------------------------------------------------------------
function pmGetFilterState() {
    const search = document.getElementById('pm-member-search');
    const elig = document.getElementById('pm-filter-eligible');
    const noEval = document.getElementById('pm-filter-no-eval');
    return {
        search: ((search && search.value) || '').trim().toLowerCase(),
        eligibleOnly: !!(elig && elig.checked),
        noEvalOnly: !!(noEval && noEval.checked),
    };
}
function pmSaveFilters() {
    try {
        const f = pmGetFilterState();
        sessionStorage.setItem(
            STORAGE_KEY_FILTERS,
            JSON.stringify({
                eligibleOnly: f.eligibleOnly,
                noEvalOnly: f.noEvalOnly,
            }),
        );
    } catch (e) {
        /* ignore */
    }
}
function pmRestoreFilters() {
    try {
        const raw = sessionStorage.getItem(STORAGE_KEY_FILTERS);
        if (!raw) return;
        const f = JSON.parse(raw);
        const elig = document.getElementById('pm-filter-eligible');
        const noEval = document.getElementById('pm-filter-no-eval');
        if (elig && typeof f.eligibleOnly === 'boolean') elig.checked = f.eligibleOnly;
        if (noEval && typeof f.noEvalOnly === 'boolean') noEval.checked = f.noEvalOnly;
    } catch (e) {
        /* ignore */
    }
}

function pmApplyFilters() {
    const state = pmGetFilterState();
    const rows = document.querySelectorAll('.pm-matrix tbody tr[data-pm-username]');
    let visible = 0;
    rows.forEach(function (row) {
        const name = row.getAttribute('data-pm-member-name') || '';
        const eligible = row.getAttribute('data-pm-eligible') === 'true';
        const hasEvals = row.getAttribute('data-pm-has-evaluations') === 'true';
        const match =
            (state.search === '' || name.indexOf(state.search) !== -1) &&
            (!state.eligibleOnly || eligible) &&
            (!state.noEvalOnly || !hasEvals);
        row.classList.toggle('pm-row-hidden', !match);
        if (match) visible++;
    });
    const empty = document.getElementById('pm-empty-state');
    if (empty) empty.hidden = visible !== 0;
}

// ----------------------------------------------------------------------
// Sort cycle on the Mitglied header
// ----------------------------------------------------------------------
// Five-state cycle: 0=original | 1=name asc | 2=name desc | 3=rank asc | 4=rank desc
// Sort runs on a snapshot of the current tbody children — that keeps the
// semantics of "filtered rows stay filtered" intact, because we never
// toggle .pm-row-hidden during sort.
function pmGetSortMode() {
    const btn = document.querySelector('.pm-sort-toggle');
    return btn ? parseInt(btn.getAttribute('data-pm-sort-mode') || '0', 10) : 0;
}
function pmSetSortMode(mode) {
    const btn = document.querySelector('.pm-sort-toggle');
    if (btn) btn.setAttribute('data-pm-sort-mode', String(mode));
    const indicator = btn ? btn.querySelector('.pm-sort-indicator') : null;
    if (indicator) indicator.textContent = SORT_LABELS[mode] || SORT_LABELS[0];
    try {
        sessionStorage.setItem(STORAGE_KEY_SORT, String(mode));
    } catch (e) {
        /* ignore */
    }
}
function pmRestoreSortMode() {
    try {
        const raw = sessionStorage.getItem(STORAGE_KEY_SORT);
        if (!raw) return 0;
        const m = parseInt(raw, 10);
        return m >= 0 && m <= 4 ? m : 0;
    } catch (e) {
        return 0;
    }
}
function pmApplySort(mode) {
    const tbody = document.querySelector('.pm-matrix tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr[data-pm-username]'));
    rows.sort(function (a, b) {
        const origA = parseInt(a.getAttribute('data-pm-original-order') || '0', 10);
        const origB = parseInt(b.getAttribute('data-pm-original-order') || '0', 10);
        if (mode === 0) return origA - origB;
        if (mode === 1 || mode === 2) {
            const na = (a.getAttribute('data-pm-username') || '').toLowerCase();
            const nb = (b.getAttribute('data-pm-username') || '').toLowerCase();
            let cmp = na.localeCompare(nb);
            if (cmp === 0) cmp = origA - origB;
            return mode === 1 ? cmp : -cmp;
        }
        // rank sort: missing ranks go to the bottom regardless of direction
        const ra = a.getAttribute('data-pm-rank');
        const rb = b.getAttribute('data-pm-rank');
        const rai = ra ? parseInt(ra, 10) : NaN;
        const rbi = rb ? parseInt(rb, 10) : NaN;
        const amissing = isNaN(rai);
        const bmissing = isNaN(rbi);
        if (amissing && bmissing) return origA - origB;
        if (amissing) return 1;
        if (bmissing) return -1;
        let rcmp = rai - rbi;
        if (rcmp === 0) rcmp = origA - origB;
        return mode === 3 ? rcmp : -rcmp;
    });
    rows.forEach(function (r) {
        tbody.appendChild(r);
    });
}
function pmCycleSort() {
    const next = (pmGetSortMode() + 1) % SORT_LABELS.length;
    pmSetSortMode(next);
    pmApplySort(next);
}

// ----------------------------------------------------------------------
// Selection & bulk edit
// ----------------------------------------------------------------------
function pmGetSelectedRows() {
    return document.querySelectorAll('.pm-matrix tbody tr.pm-row-selected:not(.pm-row-hidden)');
}
function pmRefreshBulkPanel() {
    const selected = pmGetSelectedRows();
    const count = selected.length;
    const panel = document.getElementById('pm-bulk-panel');
    const countEl = document.getElementById('pm-bulk-count-value');
    if (countEl) countEl.textContent = String(count);
    if (panel) {
        panel.hidden = count === 0;
        panel.classList.toggle('visible', count > 0);
    }
    // Sync select-all checkbox to current state of visible rows.
    const selectAll = document.getElementById('pm-select-all');
    if (selectAll) {
        const visible = document.querySelectorAll(
            '.pm-matrix tbody tr[data-pm-username]:not(.pm-row-hidden)',
        );
        const allChecked =
            visible.length > 0 &&
            Array.from(visible).every(function (r) {
                return r.classList.contains('pm-row-selected');
            });
        selectAll.checked = allChecked;
        selectAll.indeterminate = !allChecked && count > 0;
    }
}
function pmOnRowCheck(checkbox) {
    const row = checkbox.closest('tr');
    if (!row) return;
    row.classList.toggle('pm-row-selected', checkbox.checked);
    pmRefreshBulkPanel();
}
function pmOnSelectAll(checkbox) {
    const visible = document.querySelectorAll(
        '.pm-matrix tbody tr[data-pm-username]:not(.pm-row-hidden)',
    );
    visible.forEach(function (row) {
        row.classList.toggle('pm-row-selected', checkbox.checked);
        const cb = row.querySelector('.pm-row-checkbox');
        if (cb) cb.checked = checkbox.checked;
    });
    pmRefreshBulkPanel();
}
function pmClearSelection() {
    document.querySelectorAll('.pm-matrix tbody tr.pm-row-selected').forEach(function (r) {
        r.classList.remove('pm-row-selected');
        const cb = r.querySelector('.pm-row-checkbox');
        if (cb) cb.checked = false;
    });
    const selectAll = document.getElementById('pm-select-all');
    if (selectAll) {
        selectAll.checked = false;
        selectAll.indeterminate = false;
    }
    pmRefreshBulkPanel();
}

function pmBulkApply() {
    const catSel = document.getElementById('pm-bulk-category');
    const lvlSel = document.getElementById('pm-bulk-level');
    const categoryId = catSel ? catSel.value : '';
    const level = lvlSel ? lvlSel.value : '';
    if (!categoryId) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(MSG_BULK_NEED_CAT);
        }
        return;
    }
    if (!level) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(MSG_BULK_NEED_LEVEL);
        }
        return;
    }
    const rows = pmGetSelectedRows();
    if (rows.length === 0) return;

    // Render a confirmation modal that uses the level's *displayed* text
    // (not LEVEL_A / __NONE__) so the officer reviews the change in the
    // same language the dropdown uses.
    const levelLabel = lvlSel.options[lvlSel.selectedIndex].textContent.trim();
    const catLabel = catSel.options[catSel.selectedIndex].textContent.trim();
    const msg = MSG_BULK_CONFIRM_MSG.replace('{level}', levelLabel)
        .replace('{count}', String(rows.length))
        .replace('{category}', catLabel);

    const confirmFn =
        window.showKrtConfirm ||
        function () {
            return Promise.resolve(true);
        };
    confirmFn(MSG_BULK_CONFIRM_TITLE, msg).then(function (ok) {
        if (!ok) return;
        // Enqueue one save per selected row. The queue serialises saves,
        // so all of these will eventually be PUT in order without
        // overwhelming the backend or stepping on each other's @Version.
        const apiLevel = level === '__NONE__' ? '' : level;
        rows.forEach(function (row) {
            const userId = row.getAttribute('data-pm-user-id');
            const select = document.querySelector(
                '.level-select[data-user-id="' +
                    userId +
                    '"][data-category-id="' +
                    categoryId +
                    '"]',
            );
            if (!select) return;
            select.value = apiLevel;
            pmEnqueueSave(select);
        });
        pmClearSelection();
    });
}

// ----------------------------------------------------------------------
// Last-evaluated formatting (server sends ISO Instant; we format locally)
// ----------------------------------------------------------------------
function pmFormatLastEvaluated() {
    const nodes = document.querySelectorAll('.pm-member-last-eval[data-pm-last-evaluated]');
    nodes.forEach(function (node) {
        const iso = node.getAttribute('data-pm-last-evaluated');
        if (!iso) return;
        const d = new Date(iso);
        if (isNaN(d.getTime())) return;
        const formatted = d.toLocaleDateString();
        node.textContent = MSG_LAST_EVAL + ': ' + formatted;
        node.setAttribute('title', MSG_LAST_EVAL + ': ' + d.toLocaleString());
    });
}
function pmRefreshLastEvaluatedFor(userId) {
    // After a save we just bump the row's last-evaluated to "now". The
    // server's authoritative timestamp lands on the next full page load.
    const row = document.querySelector('tr[data-pm-user-id="' + userId + '"]');
    if (!row) return;
    const node = row.querySelector('.pm-member-last-eval');
    if (!node) return;
    const now = new Date();
    node.setAttribute('data-pm-empty', 'false');
    node.setAttribute('data-pm-last-evaluated', now.toISOString());
    node.textContent = MSG_LAST_EVAL + ': ' + now.toLocaleDateString();
    node.setAttribute('title', MSG_LAST_EVAL + ': ' + now.toLocaleString());
    row.setAttribute('data-pm-has-evaluations', 'true');
}

// ----------------------------------------------------------------------
// CSV export (respects current filter + sort, never reads from server)
// ----------------------------------------------------------------------
function pmCsvEscape(value) {
    if (value === null || value === undefined) return '';
    let s = String(value);
    if (
        s.indexOf('"') !== -1 ||
        s.indexOf(',') !== -1 ||
        s.indexOf('\n') !== -1 ||
        s.indexOf('\r') !== -1
    ) {
        s = '"' + s.replace(/"/g, '""') + '"';
    }
    return s;
}
function pmLevelLabel(value) {
    if (!value) return '';
    if (value === 'LEVEL_A') return 'A';
    if (value === 'LEVEL_B') return 'B';
    if (value === 'LEVEL_C') return 'C';
    return value;
}
function pmExportCsv() {
    // Column order from the second header row so the CSV matches the
    // matrix exactly (including any future column re-ordering done by
    // the backend). Each header carries the topic and category name.
    const catHeaders = document.querySelectorAll(
        '.pm-matrix thead tr.pm-row-category th[data-pm-category-id]',
    );
    const headers = [
        MSG_CSV_HEADER_MEMBER,
        MSG_CSV_HEADER_RANK,
        MSG_CSV_HEADER_ELIG,
        MSG_CSV_HEADER_LAST,
    ];
    const catIds = [];
    catHeaders.forEach(function (th) {
        const topicName = th.getAttribute('data-pm-topic-name') || '';
        const catName = th.getAttribute('data-pm-category-name') || th.textContent.trim();
        headers.push(topicName + ' / ' + catName);
        catIds.push(th.getAttribute('data-pm-category-id'));
    });

    const rows = document.querySelectorAll(
        '.pm-matrix tbody tr[data-pm-username]:not(.pm-row-hidden)',
    );
    const lines = [headers.map(pmCsvEscape).join(',')];

    rows.forEach(function (row) {
        const username = row.getAttribute('data-pm-username') || '';
        const rank = row.getAttribute('data-pm-rank') || '';
        const iso = row.getAttribute('data-pm-last-evaluated') || '';
        let lastEval = '';
        if (iso) {
            const d = new Date(iso);
            if (!isNaN(d.getTime())) lastEval = d.toLocaleDateString();
        }
        const chips = row.querySelectorAll('.eligibility-chip[data-pm-eligible="true"]');
        const eligText = Array.from(chips)
            .map(function (c) {
                return (
                    c.getAttribute('data-pm-from-rank') + '->' + c.getAttribute('data-pm-to-rank')
                );
            })
            .join(', ');

        const cells = [username, rank, eligText, lastEval];
        catIds.forEach(function (catId) {
            const select = row.querySelector('.level-select[data-category-id="' + catId + '"]');
            cells.push(select ? pmLevelLabel(select.value) : '');
        });
        lines.push(cells.map(pmCsvEscape).join(','));
    });

    const csv = lines.join('\r\n');
    // BOM so Excel auto-detects UTF-8 for umlauts in member names.
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const iso = new Date().toISOString().slice(0, 10);
    const a = document.createElement('a');
    a.href = url;
    a.download = MSG_CSV_NAME + '-' + iso + '.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () {
        URL.revokeObjectURL(url);
    }, 0);
}

// ----------------------------------------------------------------------
// Wiring
// ----------------------------------------------------------------------
document.addEventListener('DOMContentLoaded', function () {
    pmApplyCollapsedState(pmLoadCollapsedTopics());
    pmFormatLastEvaluated();
    pmRestoreFilters();
    const initialSort = pmRestoreSortMode();
    pmSetSortMode(initialSort);
    pmApplySort(initialSort);
    pmApplyFilters();
    pmRefreshBulkPanel();

    const search = document.getElementById('pm-member-search');
    if (search) search.addEventListener('input', pmApplyFilters);

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('change', 'pm-save-evaluation', function (el) {
            pmEnqueueSave(el);
            pmRefreshLastEvaluatedFor(el.getAttribute('data-user-id'));
        });
        window.krtEvents.on('click', 'pm-toggle-topic', pmToggleTopic);
        window.krtEvents.on('click', 'pm-expand-all', pmExpandAllTopics);
        window.krtEvents.on('click', 'pm-collapse-all', pmCollapseAllTopics);
        window.krtEvents.on('click', 'pm-export-csv', pmExportCsv);
        window.krtEvents.on('click', 'pm-cycle-sort', pmCycleSort);
        window.krtEvents.on('change', 'pm-filter-change', function () {
            pmSaveFilters();
            pmApplyFilters();
            pmRefreshBulkPanel();
        });
        window.krtEvents.on('change', 'pm-row-check', pmOnRowCheck);
        window.krtEvents.on('change', 'pm-select-all', pmOnSelectAll);
        window.krtEvents.on('click', 'pm-bulk-apply', pmBulkApply);
        window.krtEvents.on('click', 'pm-bulk-clear', pmClearSelection);
    }

    // After a full matrix re-render (409 recovery) the rows are rebuilt from
    // server state, so re-apply every client-side view layer: topic collapse,
    // last-evaluated formatting, the persisted sort mode, the active filter
    // and the bulk-selection panel. The delegated change/click handlers above
    // survive the swap (they are bound on document), so only view state needs
    // restoring. The lighter eligibility-cell swaps target a <td> (no id), so
    // they are intentionally ignored here.
    document.addEventListener('krt:swapped', function (e) {
        const c = e.detail && e.detail.container;
        if (!c || c.id !== 'pm-matrix-results') return;
        pmApplyCollapsedState(pmLoadCollapsedTopics());
        pmFormatLastEvaluated();
        const mode = pmRestoreSortMode();
        pmSetSortMode(mode);
        pmApplySort(mode);
        pmApplyFilters();
        pmRefreshBulkPanel();
    });
});
