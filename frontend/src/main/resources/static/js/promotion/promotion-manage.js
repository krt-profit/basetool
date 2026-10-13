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

/* global MSG_SAVED, MSG_ERROR, MSG_CONFLICT, MSG_LAST_EVAL, MSG_BULK_CONFIRM_TITLE, MSG_BULK_CONFIRM_MSG, MSG_BULK_NEED_CAT, MSG_BULK_NEED_LEVEL, MSG_CSV_NAME, MSG_CSV_HEADER_MEMBER, MSG_CSV_HEADER_RANK, MSG_CSV_HEADER_ELIG, MSG_CSV_HEADER_LAST, SORT_LABELS, STORAGE_KEY_COLLAPSED, STORAGE_KEY_SORT, STORAGE_KEY_FILTERS, MSG_REFRESH_FAILED */

import {
    showFrontendErrorToast,
    showFrontendSuccessToast,
    showKrtConfirm,
} from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtFilterChips } from '/js/kernel/krt-filter-chips.js';
import { krtFilterPanel } from '/js/kernel/krt-filter-panel.js';

/**
 * @typedef {object} PmSaveJob
 * @property {string} key the user and category the save belongs to
 * @property {HTMLSelectElement} select the level select to save
 */

/**
 * Writes one evaluation; a conflict clears the queue and re-pulls the matrix.
 *
 * @param {string} url the proxy endpoint
 * @param {unknown} payload the JSON payload
 * @returns {Promise<any>} the response body, null when the write failed
 */
function pmPutEvaluation(url, payload) {
    /** @param {string} message the toast text */
    function toastError(message) {
        showFrontendErrorToast(message);
    }
    return krtFetch
        .write({
            method: 'PUT',
            url,
            payload,
            toast: false,
            onError(status) {
                if (status === 409) {
                    toastError(MSG_CONFLICT);
                    pmSaveQueue = [];
                    pmEligibilityDirty.clear();
                    pmRefreshMatrix();
                } else {
                    toastError(MSG_ERROR);
                }
                return true;
            },
            onNetworkError() {
                toastError(MSG_ERROR);
                return true;
            },
        })
        .then((result) => {
            return result.ok ? result.body : null;
        });
}

/** @type {Set<string>} */
const pmEligibilityDirty = new Set();

function pmRefreshMatrix() {
    return krtFetch.swap({
        url: '/promotion/manage',
        container: '#pm-matrix-results',
        fragmentValue: 'matrixBody',
        errorMessage: MSG_REFRESH_FAILED,
    });
}

/**
 * Re-renders the eligibility cell of one member row.
 *
 * @param {string} userId the member's user id
 * @returns {Promise<boolean>} true when the cell was swapped
 */
function pmRefreshEligibility(userId) {
    const row = document.querySelector(`tr[data-pm-user-id="${userId}"]`);
    if (!row) return Promise.resolve(false);
    const cell = row.querySelector('.pm-eligibility-cell');
    if (!cell) {
        return Promise.resolve(false);
    }
    return krtFetch
        .swap({
            url: `/promotion/manage?userId=${encodeURIComponent(userId)}`,
            container: cell,
            fragmentValue: 'eligibilityCell',
        })
        .then((ok) => {
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
    ids.forEach((uid) => {
        pmRefreshEligibility(uid);
    });
}

/** @type {PmSaveJob[]} */
let pmSaveQueue = [];
/** @type {string | null} */
let pmInFlightKey = null;

function pmUpdateQueueIndicator() {
    const indicator = document.getElementById('pm-queue-indicator');
    const counter = document.getElementById('pm-queue-count');
    if (!indicator || !counter) return;
    const total = pmSaveQueue.length + (pmInFlightKey ? 1 : 0);
    counter.textContent = String(total);
    indicator.hidden = total === 0;
}

/**
 * Queues a level save, replacing a queued one for the same user and category.
 *
 * @param {HTMLSelectElement} select the level select
 */
function pmEnqueueSave(select) {
    const userId = select.getAttribute('data-user-id');
    const catId = select.getAttribute('data-category-id');
    const key = `${userId}_${catId}`;
    pmSaveQueue = pmSaveQueue.filter((job) => {
        return job.key !== key;
    });
    pmSaveQueue.push({ key, select });
    select.classList.add('pm-pending');
    pmUpdateQueueIndicator();
    if (!pmInFlightKey) pmProcessNextSave();
}

function pmProcessNextSave() {
    if (pmSaveQueue.length === 0) {
        pmInFlightKey = null;
        pmUpdateQueueIndicator();
        pmFlushEligibility();
        return;
    }
    const job = /** @type {PmSaveJob} */ (pmSaveQueue.shift());
    pmInFlightKey = job.key;
    pmUpdateQueueIndicator();

    const select = job.select;
    const userId = /** @type {string} */ (select.getAttribute('data-user-id'));
    const categoryId = /** @type {string} */ (select.getAttribute('data-category-id'));
    const versionStr = select.getAttribute('data-version');
    const version = versionStr ? parseInt(versionStr, 10) : null;
    const assignedLevel = select.value || null;
    const cell = select.closest('td');

    return pmPutEvaluation(
        `/api/proxy/promotion/evaluations/user/${encodeURIComponent(
            userId,
        )}/category/${encodeURIComponent(categoryId)}`,
        { version, assignedLevel },
    )
        .then((data) => {
            if (data) {
                select.setAttribute('data-version', data.version);
                select.setAttribute('data-level', assignedLevel || '');
                pmEligibilityDirty.add(userId);
                if (cell) {
                    cell.classList.remove('pm-cell-saved');
                    void cell.offsetWidth;
                    cell.classList.add('pm-cell-saved');
                    setTimeout(() => {
                        cell.classList.remove('pm-cell-saved');
                    }, 850);
                }
                showFrontendSuccessToast(MSG_SAVED);
            }
        })
        .catch(() => {
            showFrontendErrorToast(MSG_ERROR);
        })
        .finally(() => {
            select.classList.remove('pm-pending');
            pmInFlightKey = null;
            pmProcessNextSave();
        });
}

/**
 * Reads the collapsed topic ids from local storage.
 *
 * @returns {string[]} the ids; empty when none are stored
 */
function pmLoadCollapsedTopics() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY_COLLAPSED);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}
/**
 * Stores the collapsed topic ids in local storage; a storage failure is ignored.
 *
 * @param {Array<string | null>} ids the collapsed topic ids
 */
function pmSaveCollapsedTopics(ids) {
    try {
        localStorage.setItem(STORAGE_KEY_COLLAPSED, JSON.stringify(ids));
    } catch {}
}

/**
 * Collapses or expands the columns of one topic.
 *
 * @param {string | null} topicId the topic id
 * @param {boolean} collapsed true to collapse
 */
function pmSetTopicCollapsed(topicId, collapsed) {
    const topicHeader = document.querySelector(`th.pm-topic-cell[data-pm-topic-id="${topicId}"]`);
    if (!topicHeader) return;
    topicHeader.setAttribute('data-pm-collapsed', collapsed ? 'true' : 'false');
    const toggle = topicHeader.querySelector('.pm-topic-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');

    const cells = document.querySelectorAll(
        `.pm-matrix [data-pm-topic-id="${topicId}"]:not(.pm-topic-cell)`,
    );
    cells.forEach((cell) => {
        cell.setAttribute('data-pm-topic-collapsed', collapsed ? 'true' : 'false');
    });
}

/**
 * Applies the collapsed state to every topic header.
 *
 * @param {Array<string | null>} collapsedIds the collapsed topic ids
 */
function pmApplyCollapsedState(collapsedIds) {
    const topicHeaders = document.querySelectorAll('th.pm-topic-cell[data-pm-topic-id]');
    topicHeaders.forEach((th) => {
        const id = th.getAttribute('data-pm-topic-id');
        pmSetTopicCollapsed(id, collapsedIds.indexOf(id) !== -1);
    });
}

/**
 * Toggles one topic between collapsed and expanded and stores the state.
 *
 * @param {HTMLElement} button the topic toggle button
 */
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
    document.querySelectorAll('th.pm-topic-cell[data-pm-topic-id]').forEach((th) => {
        pmSetTopicCollapsed(th.getAttribute('data-pm-topic-id'), false);
    });
}
function pmCollapseAllTopics() {
    /** @type {Array<string | null>} */
    const ids = [];
    document.querySelectorAll('th.pm-topic-cell[data-pm-topic-id]').forEach((th) => {
        const id = th.getAttribute('data-pm-topic-id');
        ids.push(id);
        pmSetTopicCollapsed(id, true);
    });
    pmSaveCollapsedTopics(ids);
}

function pmGetFilterState() {
    const search = /** @type {HTMLInputElement | null} */ (
        document.getElementById('pm-member-search')
    );
    const elig = /** @type {HTMLInputElement | null} */ (
        document.getElementById('pm-filter-eligible')
    );
    const noEval = /** @type {HTMLInputElement | null} */ (
        document.getElementById('pm-filter-no-eval')
    );
    return {
        search: ((search && search.value) || '').trim().toLowerCase(),
        eligibleOnly: !!(elig && elig.checked),
        noEvalOnly: !!(noEval && noEval.checked),
    };
}
function pmSaveFilters() {
    try {
        const f = pmGetFilterState();
        localStorage.setItem(
            STORAGE_KEY_FILTERS,
            JSON.stringify({
                eligibleOnly: f.eligibleOnly,
                noEvalOnly: f.noEvalOnly,
            }),
        );
    } catch {}
}
function pmRestoreFilters() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY_FILTERS);
        if (!raw) return;
        const f = JSON.parse(raw);
        const elig = /** @type {HTMLInputElement | null} */ (
            document.getElementById('pm-filter-eligible')
        );
        const noEval = /** @type {HTMLInputElement | null} */ (
            document.getElementById('pm-filter-no-eval')
        );
        if (elig && typeof f.eligibleOnly === 'boolean') elig.checked = f.eligibleOnly;
        if (noEval && typeof f.noEvalOnly === 'boolean') noEval.checked = f.noEvalOnly;
    } catch {}
}

function pmApplyFilters() {
    const state = pmGetFilterState();
    const rows = document.querySelectorAll('.pm-matrix tbody tr[data-pm-username]');
    let visible = 0;
    rows.forEach((row) => {
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

function pmGetSortMode() {
    const btn = document.querySelector('.pm-sort-toggle');
    return btn ? parseInt(btn.getAttribute('data-pm-sort-mode') || '0', 10) : 0;
}
/**
 * Shows and stores the sort mode.
 *
 * @param {number} mode the sort mode, an index into the sort labels
 */
function pmSetSortMode(mode) {
    const btn = document.querySelector('.pm-sort-toggle');
    if (btn) btn.setAttribute('data-pm-sort-mode', String(mode));
    const indicator = btn ? btn.querySelector('.pm-sort-indicator') : null;
    if (indicator) indicator.textContent = SORT_LABELS[mode] || SORT_LABELS[0];
    try {
        localStorage.setItem(STORAGE_KEY_SORT, String(mode));
    } catch {}
}
function pmRestoreSortMode() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY_SORT);
        if (!raw) return 0;
        const m = parseInt(raw, 10);
        return m >= 0 && m <= 4 ? m : 0;
    } catch {
        return 0;
    }
}
/**
 * Reorders the member rows for a sort mode.
 *
 * @param {number} mode the sort mode
 */
function pmApplySort(mode) {
    const tbody = document.querySelector('.pm-matrix tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr[data-pm-username]'));
    rows.sort((a, b) => {
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
    rows.forEach((r) => {
        tbody.appendChild(r);
    });
}
function pmCycleSort() {
    const next = (pmGetSortMode() + 1) % SORT_LABELS.length;
    pmSetSortMode(next);
    pmApplySort(next);
}

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
    const selectAll = /** @type {HTMLInputElement | null} */ (
        document.getElementById('pm-select-all')
    );
    if (selectAll) {
        const visible = document.querySelectorAll(
            '.pm-matrix tbody tr[data-pm-username]:not(.pm-row-hidden)',
        );
        const allChecked =
            visible.length > 0 &&
            Array.from(visible).every((r) => {
                return r.classList.contains('pm-row-selected');
            });
        selectAll.checked = allChecked;
        selectAll.indeterminate = !allChecked && count > 0;
    }
}
/**
 * Mirrors a row checkbox onto its row's selected state.
 *
 * @param {HTMLElement} checkbox the row checkbox
 */
function pmOnRowCheck(checkbox) {
    const row = checkbox.closest('tr');
    if (!row) return;
    row.classList.toggle('pm-row-selected', /** @type {HTMLInputElement} */ (checkbox).checked);
    pmRefreshBulkPanel();
}
/**
 * Selects or deselects every visible member row.
 *
 * @param {HTMLElement} checkbox the select-all checkbox
 */
function pmOnSelectAll(checkbox) {
    const visible = document.querySelectorAll(
        '.pm-matrix tbody tr[data-pm-username]:not(.pm-row-hidden)',
    );
    visible.forEach((row) => {
        row.classList.toggle('pm-row-selected', /** @type {HTMLInputElement} */ (checkbox).checked);
        const cb = /** @type {HTMLInputElement | null} */ (row.querySelector('.pm-row-checkbox'));
        if (cb) cb.checked = /** @type {HTMLInputElement} */ (checkbox).checked;
    });
    pmRefreshBulkPanel();
}
function pmClearSelection() {
    document.querySelectorAll('.pm-matrix tbody tr.pm-row-selected').forEach((r) => {
        r.classList.remove('pm-row-selected');
        const cb = /** @type {HTMLInputElement | null} */ (r.querySelector('.pm-row-checkbox'));
        if (cb) cb.checked = false;
    });
    const selectAll = /** @type {HTMLInputElement | null} */ (
        document.getElementById('pm-select-all')
    );
    if (selectAll) {
        selectAll.checked = false;
        selectAll.indeterminate = false;
    }
    pmRefreshBulkPanel();
}

function pmBulkApply() {
    const catSel = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('pm-bulk-category')
    );
    const lvlSel = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('pm-bulk-level')
    );
    const categoryId = catSel ? catSel.value : '';
    const level = lvlSel ? lvlSel.value : '';
    if (!categoryId) {
        showFrontendErrorToast(MSG_BULK_NEED_CAT);
        return;
    }
    if (!level) {
        showFrontendErrorToast(MSG_BULK_NEED_LEVEL);
        return;
    }
    const rows = pmGetSelectedRows();
    if (rows.length === 0) return;

    if (!catSel || !lvlSel) return;
    const levelLabel = lvlSel.options[lvlSel.selectedIndex].textContent.trim();
    const catLabel = catSel.options[catSel.selectedIndex].textContent.trim();
    const msg = MSG_BULK_CONFIRM_MSG.replace('{level}', levelLabel)
        .replace('{count}', String(rows.length))
        .replace('{category}', catLabel);

    const confirmFn = showKrtConfirm;
    confirmFn(MSG_BULK_CONFIRM_TITLE, msg).then((ok) => {
        if (!ok) return;
        const apiLevel = level === '__NONE__' ? '' : level;
        rows.forEach((row) => {
            const userId = row.getAttribute('data-pm-user-id');
            const select = /** @type {HTMLSelectElement | null} */ (
                document.querySelector(
                    `.level-select[data-user-id="${userId}"][data-category-id="${categoryId}"]`,
                )
            );
            if (!select) return;
            select.value = apiLevel;
            pmEnqueueSave(select);
        });
        pmClearSelection();
    });
}

function pmFormatLastEvaluated() {
    const nodes = document.querySelectorAll('.pm-member-last-eval[data-pm-last-evaluated]');
    nodes.forEach((node) => {
        const iso = node.getAttribute('data-pm-last-evaluated');
        if (!iso) return;
        const d = new Date(iso);
        if (isNaN(d.getTime())) return;
        const formatted = d.toLocaleDateString();
        node.textContent = `${MSG_LAST_EVAL}: ${formatted}`;
        node.setAttribute('title', `${MSG_LAST_EVAL}: ${d.toLocaleString()}`);
    });
}
/**
 * Stamps a member row as evaluated just now.
 *
 * @param {string | null} userId the member's user id
 */
function pmRefreshLastEvaluatedFor(userId) {
    const row = document.querySelector(`tr[data-pm-user-id="${userId}"]`);
    if (!row) return;
    const node = row.querySelector('.pm-member-last-eval');
    if (!node) return;
    const now = new Date();
    node.setAttribute('data-pm-empty', 'false');
    node.setAttribute('data-pm-last-evaluated', now.toISOString());
    node.textContent = `${MSG_LAST_EVAL}: ${now.toLocaleDateString()}`;
    node.setAttribute('title', `${MSG_LAST_EVAL}: ${now.toLocaleString()}`);
    row.setAttribute('data-pm-has-evaluations', 'true');
}

/**
 * Quotes and neutralises a value for a CSV cell.
 *
 * @param {unknown} value the cell value
 * @returns {string} the escaped cell
 */
function pmCsvEscape(value) {
    if (value === null || value === undefined) return '';
    let s = String(value);
    if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
    if (
        s.indexOf('"') !== -1 ||
        s.indexOf(',') !== -1 ||
        s.indexOf('\n') !== -1 ||
        s.indexOf('\r') !== -1
    ) {
        s = `"${s.replace(/"/g, '""')}"`;
    }
    return s;
}
/**
 * Maps a level constant to its short label.
 *
 * @param {string} value the level constant
 * @returns {string} the label; empty for no level
 */
function pmLevelLabel(value) {
    if (!value) return '';
    if (value === 'LEVEL_A') return 'A';
    if (value === 'LEVEL_B') return 'B';
    if (value === 'LEVEL_C') return 'C';
    return value;
}
function pmExportCsv() {
    const catHeaders = document.querySelectorAll(
        '.pm-matrix thead tr.pm-row-category th[data-pm-category-id]',
    );
    const headers = [
        MSG_CSV_HEADER_MEMBER,
        MSG_CSV_HEADER_RANK,
        MSG_CSV_HEADER_ELIG,
        MSG_CSV_HEADER_LAST,
    ];
    /** @type {Array<string | null>} */
    const catIds = [];
    catHeaders.forEach((th) => {
        const topicName = th.getAttribute('data-pm-topic-name') || '';
        const catName = th.getAttribute('data-pm-category-name') || th.textContent.trim();
        headers.push(`${topicName} / ${catName}`);
        catIds.push(th.getAttribute('data-pm-category-id'));
    });

    const rows = document.querySelectorAll(
        '.pm-matrix tbody tr[data-pm-username]:not(.pm-row-hidden)',
    );
    const lines = [headers.map(pmCsvEscape).join(',')];

    rows.forEach((row) => {
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
            .map((c) => {
                return `${c.getAttribute('data-pm-from-rank')}->${c.getAttribute('data-pm-to-rank')}`;
            })
            .join(', ');

        const cells = [username, rank, eligText, lastEval];
        catIds.forEach((catId) => {
            const select = /** @type {HTMLSelectElement | null} */ (
                row.querySelector(`.level-select[data-category-id="${catId}"]`)
            );
            cells.push(select ? pmLevelLabel(select.value) : '');
        });
        lines.push(cells.map(pmCsvEscape).join(','));
    });

    const csv = lines.join('\r\n');
    const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const iso = new Date().toISOString().slice(0, 10);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${MSG_CSV_NAME}-${iso}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => {
        URL.revokeObjectURL(url);
    }, 0);
}

document.addEventListener('DOMContentLoaded', () => {
    pmApplyCollapsedState(pmLoadCollapsedTopics());
    pmFormatLastEvaluated();
    pmRestoreFilters();
    krtFilterPanel.refresh('pm-filter-panel');
    krtFilterChips.refresh();
    const initialSort = pmRestoreSortMode();
    pmSetSortMode(initialSort);
    pmApplySort(initialSort);
    pmApplyFilters();
    pmRefreshBulkPanel();

    const search = document.getElementById('pm-member-search');
    if (search) search.addEventListener('input', pmApplyFilters);

    krtEvents.on('change', 'pm-save-evaluation', (el) => {
        pmEnqueueSave(/** @type {HTMLSelectElement} */ (el));
        pmRefreshLastEvaluatedFor(el.getAttribute('data-user-id'));
    });
    krtEvents.on('click', 'pm-toggle-topic', pmToggleTopic);
    krtEvents.on('click', 'pm-expand-all', pmExpandAllTopics);
    krtEvents.on('click', 'pm-collapse-all', pmCollapseAllTopics);
    krtEvents.on('click', 'pm-export-csv', pmExportCsv);
    krtEvents.on('click', 'pm-cycle-sort', pmCycleSort);
    krtEvents.on('change', 'pm-filter-change', () => {
        pmSaveFilters();
        pmApplyFilters();
        pmRefreshBulkPanel();
    });
    krtEvents.on('change', 'pm-row-check', pmOnRowCheck);
    krtEvents.on('change', 'pm-select-all', pmOnSelectAll);
    krtEvents.on('click', 'pm-bulk-apply', pmBulkApply);
    krtEvents.on('click', 'pm-bulk-clear', pmClearSelection);

    document.addEventListener('krt:swapped', (e) => {
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
