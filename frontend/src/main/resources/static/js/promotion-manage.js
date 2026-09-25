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

function pmPutEvaluation(url, payload) {
    if (!window.krtFetch) {
        return Promise.resolve(null);
    }
    function toastError(message) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message);
        }
    }
    return window.krtFetch
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
        .then(function (result) {
            return result.ok ? result.body : null;
        });
}

const pmEligibilityDirty = new Set();

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
    pmSaveQueue = pmSaveQueue.filter(function (job) {
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
        { version, assignedLevel },
    )
        .then(function (data) {
            if (data) {
                select.setAttribute('data-version', data.version);
                select.setAttribute('data-level', assignedLevel || '');
                pmEligibilityDirty.add(userId);
                if (cell) {
                    cell.classList.remove('pm-cell-saved');
                    void cell.offsetWidth;
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
function pmSaveCollapsedTopics(ids) {
    try {
        localStorage.setItem(STORAGE_KEY_COLLAPSED, JSON.stringify(ids));
    } catch {}
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
        const elig = document.getElementById('pm-filter-eligible');
        const noEval = document.getElementById('pm-filter-no-eval');
        if (elig && typeof f.eligibleOnly === 'boolean') elig.checked = f.eligibleOnly;
        if (noEval && typeof f.noEvalOnly === 'boolean') noEval.checked = f.noEvalOnly;
    } catch {}
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
