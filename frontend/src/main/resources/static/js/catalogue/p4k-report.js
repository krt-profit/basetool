import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

/** @typedef {ApiDto<'P4kImportRunDto'>} P4kRun */
/** @typedef {ApiDto<'P4kImportChangeDto'>} P4kChange */
/** @typedef {ApiDto<'P4kReviewCaseDto'>} P4kCase */
/** @typedef {{ runs: ApiPage<'P4kImportRunDto'>, adminNames: Record<string, string> }} P4kRunsView */
/** @typedef {{ source: 'runs' | 'jobs', id: string, title: string }} P4kReportTarget */

/** The kinds a report filters by, in display order. */
const KINDS = ['MANUFACTURER', 'ITEM', 'SHIP', 'COMMODITY', 'BLUEPRINT', 'RESOURCE_GROUP'];

/** The change types a report filters by, in display order. */
const TYPES = [
    'ADDED',
    'CHANGED',
    'VANISHED',
    'REAPPEARED',
    'UNMATCHED',
    'REVIEW',
    'IDENTITY_MISMATCH',
    'UNKNOWN_VALUE',
    'SWEEP_SKIPPED',
];

/** @type {P4kReportTarget | null} */
let reportTarget = null;
let reportPage = 0;
let runsPage = 0;
let casesPage = 0;

/**
 * Looks up an element by id.
 *
 * @param {string} id the element id
 * @returns {HTMLElement | null} the element, or null
 */
function $(id) {
    return document.getElementById(id);
}

/**
 * Reads one label of the page's dictionary.
 *
 * @param {string} key the dictionary key
 * @returns {string} the label
 */
function t(key) {
    const dict = window.krtP4kImportI18n || {};
    return krtI18nText(dict[key], `krtP4kImportI18n.${key}`);
}

/**
 * Builds a proxy URL of the page.
 *
 * @param {string} path the path below the page, starting with a slash
 * @returns {string} the URL
 */
function pageUrl(path) {
    const base = (window.krtP4kImportEndpoints || {}).page || '/admin/p4k-import';
    return base + path;
}

/**
 * Formats an ISO timestamp in the browser locale.
 *
 * @param {string | undefined} iso the timestamp
 * @returns {string} the formatted time, or an empty string
 */
function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * Reads a JSON list answer, or null when it is no data.
 *
 * @param {string} url the proxy URL
 * @returns {Promise<any>} the parsed body, or null
 */
function readJson(url) {
    return krtFetch.getJson(url).catch(() => {
        showFrontendErrorToast(t('error'));
        return null;
    });
}

/**
 * Renders a previous/next pager below a list, or hides it on a single page.
 *
 * @param {HTMLElement | null} nav the pager element
 * @param {{ page?: number, totalPages?: number } | null} page the page the list shows
 */
function renderPager(nav, page) {
    if (!nav) return;
    const total = page && page.totalPages ? page.totalPages : 0;
    const current = page && page.page ? page.page : 0;
    nav.hidden = total <= 1;
    krtHtml.set(
        nav,
        krtHtml`<button type="button" class="page-btn" data-page="${current - 1}" aria-label="${t(
            'pagePrevious',
        )}">‹</button><span class="page-status"><b>${current + 1}</b> / ${Math.max(
            total,
            1,
        )}</span><button type="button" class="page-btn" data-page="${current + 1}" aria-label="${t(
            'pageNext',
        )}">›</button>`,
    );
    nav.querySelectorAll('button[data-page]').forEach((btn) => {
        if (btn instanceof HTMLButtonElement) {
            const target = Number(btn.dataset.page);
            btn.disabled = target < 0 || target >= total;
        }
    });
}

/**
 * Makes a pager load the page its buttons ask for.
 *
 * @param {string} id the pager's element id
 * @param {(page: number) => void} load loads a page of its list
 */
function bindPager(id, load) {
    const nav = $(id);
    if (!nav) return;
    nav.addEventListener('click', (e) => {
        const page = pagerTarget(e);
        if (page != null) load(page);
    });
}

/**
 * Reads the page a pager button asks for.
 *
 * @param {Event} e the click
 * @returns {number | null} the page, or null when the click hit no enabled button
 */
function pagerTarget(e) {
    const target = /** @type {Element | null} */ (e.target);
    const btn = target && target.closest ? target.closest('button[data-page]') : null;
    if (!(btn instanceof HTMLButtonElement) || btn.disabled) return null;
    return Number(btn.dataset.page);
}

/**
 * Summarises one run's counts across the kinds.
 *
 * @param {P4kRun} run the run
 * @returns {string} matched, added, changed, vanished and reappeared totals
 */
function runCounts(run) {
    const sum = { matched: 0, added: 0, changed: 0, vanished: 0, reappeared: 0 };
    (run.kinds || []).forEach((k) => {
        sum.matched += k.matched || 0;
        sum.added += k.added || 0;
        sum.changed += k.changed || 0;
        sum.vanished += k.vanished || 0;
        sum.reappeared += k.reappeared || 0;
    });
    return [
        `${t('countMatched')} ${sum.matched}`,
        `${t('countAdded')} ${sum.added}`,
        `${t('countChanged')} ${sum.changed}`,
        `${t('countVanished')} ${sum.vanished}`,
        `${t('countReappeared')} ${sum.reappeared}`,
    ].join(' · ');
}

/**
 * Loads one page of the applied runs into the run table.
 *
 * @param {number} page the zero-based page
 */
function loadRuns(page) {
    readJson(pageUrl(`/runs?page=${page}`)).then((/** @type {P4kRunsView | null} */ view) => {
        if (!view || !view.runs) return;
        runsPage = page;
        const body = $('krt-p4k-runs');
        const empty = $('krt-p4k-runs-empty');
        const rows = view.runs.content || [];
        if (empty) empty.hidden = rows.length > 0;
        if (body) {
            krtHtml.set(
                body,
                rows.map(
                    (run) =>
                        krtHtml`<tr data-testid="p4k-run-row"><td><span class="cell-title">${fmtTime(
                            run.appliedAt,
                        )}</span><span class="cell-sub">${
                            (run.createdBy && view.adminNames[run.createdBy]) || t('adminUnknown')
                        }</span></td><td class="data-value--mono">${run.buildBranch} · ${
                            run.changeNumber
                        }</td><td>${runCounts(run)}</td><td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs" data-run-id="${
                            run.id
                        }" data-run-title="${run.buildVersion}">${t('actionReport')}</button></td></tr>`,
                ),
            );
        }
        renderPager($('krt-p4k-runs-pager'), view.runs);
    });
}

/**
 * Opens the report of an applied run or of a preview job.
 *
 * @param {'runs' | 'jobs'} source whether the id names a run or a preview job
 * @param {string} id the run or job id
 * @param {string} title the heading suffix, e.g. the Build version or file name
 */
export function openReport(source, id, title) {
    reportTarget = { source, id, title };
    const section = $('krt-p4k-report');
    const heading = $('krt-p4k-report-subject');
    if (heading)
        heading.textContent = `${source === 'jobs' ? t('reportPreview') : t('reportRun')}: ${title}`;
    if (section) {
        section.hidden = false;
        section.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    loadReport(0);
}

/**
 * Loads one page of the open report with the current filters.
 *
 * @param {number} page the zero-based page
 */
function loadReport(page) {
    if (!reportTarget) return;
    const kind = /** @type {HTMLSelectElement | null} */ ($('krt-p4k-report-kind'));
    const type = /** @type {HTMLSelectElement | null} */ ($('krt-p4k-report-type'));
    const params = new URLSearchParams({ page: String(page) });
    if (kind && kind.value) params.set('kind', kind.value);
    if (type && type.value) params.set('type', type.value);
    const url = pageUrl(
        `/${reportTarget.source}/${encodeURIComponent(reportTarget.id)}/changes?${params}`,
    );
    readJson(url).then((/** @type {ApiPage<'P4kImportChangeDto'> | null} */ result) => {
        if (!result) return;
        reportPage = page;
        renderReport(result.content || []);
        renderPager($('krt-p4k-report-pager'), result);
    });
}

/**
 * Renders report rows.
 *
 * @param {P4kChange[]} rows the rows of the page
 */
function renderReport(rows) {
    const body = $('krt-p4k-report-rows');
    const empty = $('krt-p4k-report-empty');
    if (empty) empty.hidden = rows.length > 0;
    if (!body) return;
    krtHtml.set(
        body,
        rows.map(
            (row) =>
                krtHtml`<tr data-testid="p4k-change-row"><td>${t(`kind.${row.kind}`)}</td><td><span class="chip chip--muted">${t(
                    `type.${row.changeType}`,
                )}</span>${
                    row.stillLiveElsewhere
                        ? krtHtml` <span class="chip chip--warning">${t('stillLive')}</span>`
                        : ''
                }</td><td><span class="cell-title">${row.nameEn || row.className || ''}</span><span class="cell-sub data-value--mono">${
                    row.guid || ''
                }</span></td><td>${fieldLabel(row)}</td><td class="data-value--mono">${valueChange(row)}</td></tr>`,
        ),
    );
}

/**
 * The field column of a report row: the column, the review reason or the sweep outcome.
 *
 * @param {P4kChange} row the row
 * @returns {string} the label
 */
function fieldLabel(row) {
    if (!row.fieldName) return '';
    if (row.changeType === 'REVIEW') return t(`reason.${row.fieldName}`);
    if (row.changeType === 'SWEEP_SKIPPED') return t(`sweep.${row.fieldName}`);
    return row.fieldName;
}

/**
 * The value column of a report row: old → new, or the counts of a skipped sweep.
 *
 * @param {P4kChange} row the row
 * @returns {string} the text
 */
function valueChange(row) {
    if (row.changeType === 'SWEEP_SKIPPED') {
        return t('sweepCounts')
            .replace('{0}', row.newValue || '0')
            .replace('{1}', row.oldValue || '0');
    }
    if (row.oldValue == null && row.newValue == null) return '';
    return `${row.oldValue == null ? '∅' : row.oldValue} → ${row.newValue == null ? '∅' : row.newValue}`;
}

/**
 * Loads one page of the open review cases.
 *
 * @param {number} page the zero-based page
 */
function loadCases(page) {
    readJson(pageUrl(`/review-cases?page=${page}`)).then(
        (/** @type {ApiPage<'P4kReviewCaseDto'> | null} */ result) => {
            if (!result) return;
            casesPage = page;
            renderCases(result.content || []);
            renderPager($('krt-p4k-cases-pager'), result);
        },
    );
}

/**
 * Renders the open review cases.
 *
 * @param {P4kCase[]} cases the cases of the page
 */
function renderCases(cases) {
    const body = $('krt-p4k-cases');
    const empty = $('krt-p4k-cases-empty');
    if (empty) empty.hidden = cases.length > 0;
    if (!body) return;
    krtHtml.set(
        body,
        cases.map(
            (c) =>
                krtHtml`<tr data-testid="p4k-case-row" data-case-id="${c.id}" data-case-version="${String(
                    c.version,
                )}"><td>${t(`kind.${c.kind}`)}</td><td>${t(`reason.${c.reason}`)}</td><td><span class="cell-title">${
                    c.nameEn || c.className || c.guid
                }</span><span class="cell-sub data-value--mono">${c.guid}</span></td><td>${
                    c.rowName || t('noRow')
                }</td><td>${fmtTime(c.createdAt)}</td><td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs" data-resolution="KEEP">${t(
                    'actionKeep',
                )}</button> <button type="button" class="btn btn-quiet-danger btn-xs" data-resolution="HIDE">${t(
                    'actionHide',
                )}</button></td></tr>`,
        ),
    );
}

/**
 * Resolves the case of a clicked keep or hide button and removes its row in place.
 *
 * @param {MouseEvent} e the click
 */
function onCasesClick(e) {
    const target = /** @type {Element | null} */ (e.target);
    const btn = target && target.closest ? target.closest('button[data-resolution]') : null;
    const row = btn ? btn.closest('tr[data-case-id]') : null;
    if (!(btn instanceof HTMLButtonElement) || !(row instanceof HTMLElement)) return;
    const caseId = row.dataset.caseId || '';
    const resolution = btn.dataset.resolution || '';
    krtFetch
        .write({
            method: 'POST',
            url: pageUrl(`/review-cases/${encodeURIComponent(caseId)}/resolve`),
            payload: { resolution, version: Number(row.dataset.caseVersion) },
            submitter: btn,
            toast: false,
            onConflict() {
                showFrontendErrorToast(t('conflict'));
                loadCases(casesPage);
                return true;
            },
        })
        .then((result) => {
            if (!result.ok) return;
            showFrontendSuccessToast(resolution === 'HIDE' ? t('toastHidden') : t('toastKept'));
            loadCases(casesPage);
        });
}

function init() {
    const kind = $('krt-p4k-report-kind');
    const type = $('krt-p4k-report-type');
    if (kind instanceof HTMLSelectElement) {
        KINDS.forEach((k) => kind.add(new Option(t(`kind.${k}`), k)));
        kind.addEventListener('change', () => loadReport(0));
    }
    if (type instanceof HTMLSelectElement) {
        TYPES.forEach((k) => type.add(new Option(t(`type.${k}`), k)));
        type.addEventListener('change', () => loadReport(0));
    }
    const runs = $('krt-p4k-runs');
    if (runs) {
        runs.addEventListener('click', (e) => {
            const target = /** @type {Element | null} */ (e.target);
            const btn = target && target.closest ? target.closest('button[data-run-id]') : null;
            if (btn instanceof HTMLButtonElement) {
                openReport('runs', btn.dataset.runId || '', btn.dataset.runTitle || '');
            }
        });
    }
    bindPager('krt-p4k-runs-pager', loadRuns);
    bindPager('krt-p4k-report-pager', loadReport);
    bindPager('krt-p4k-cases-pager', loadCases);
    const close = $('krt-p4k-report-close');
    if (close) {
        close.addEventListener('click', () => {
            const section = $('krt-p4k-report');
            if (section) section.hidden = true;
            reportTarget = null;
        });
    }
    const cases = $('krt-p4k-cases');
    if (cases) cases.addEventListener('click', onCasesClick);
    loadRuns(0);
    loadCases(0);
}

/**
 * Reloads the run list and the review list after an APPLY finished.
 */
export function refreshAfterApply() {
    loadRuns(runsPage);
    loadCases(casesPage);
    if (reportTarget) loadReport(reportPage);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
