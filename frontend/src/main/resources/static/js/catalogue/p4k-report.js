import { krtHtml } from '/js/kernel/krt-html.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { refreshDueBanner } from '/js/catalogue/p4k-due.js';
import {
    bindPager,
    pagerLabels,
    reloadedBeyondEnd,
    renderPager,
} from '/js/catalogue/catalogue-pager.js';
import { byId as $, dictionaryLabels, jsonReader } from '/js/catalogue/catalogue-link-admin.js';
import { formatTimestamp } from '/js/catalogue/catalogue-time.js';
import { createReviewWorkspace } from '/js/catalogue/p4k-review.js';

/** @typedef {ApiDto<'P4kImportRunDto'>} P4kRun */
/** @typedef {ApiDto<'P4kImportChangeDto'>} P4kChange */
/** @typedef {{ runs: ApiPage<'P4kImportRunDto'>, adminNames: Record<string, string> }} P4kRunsView */
/** @typedef {{ source: 'runs' | 'jobs', id: string, title: string }} P4kReportTarget */

/** The kinds a report filters by, in display order. */
const KINDS = [
    'MANUFACTURER',
    'ITEM',
    'SHIP',
    'COMMODITY',
    'BLUEPRINT',
    'STARMAP_OBJECT',
    'SHOP',
    'RESOURCE_GROUP',
    'BLUEPRINT_CATEGORY',
];

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
    'LOADOUT_ISSUES',
];

/** @type {P4kReportTarget | null} */
let reportTarget = null;
let reportPage = 0;
let runsPage = 0;

/** Reads one label of the page's dictionary. */
const t = dictionaryLabels(() => window.krtP4kImportI18n, 'krtP4kImportI18n');

/**
 * The page's room in the admin catalogue live sync (REQ-FE-015): a peer admin's review resolution
 * announces `review`, which reloads the cases, and an APPLY announces `runs` as well, which reloads
 * the run history, the import-due banner and the options the cases offer, since an APPLY can create
 * resource groups and materials.
 */
const sections = krtPageSections.create({
    topicClass: 'CATALOGUE_ADMIN',
    topic: 'catalogue-admin',
    sections: { runs: { container: '#krt-p4k-runs' }, review: { container: '#krt-p4k-cases' } },
    receiver: {
        coalesceMs: 1500,
        refresh(/** @type {string[]} */ keys) {
            if (keys.includes('runs')) {
                refreshDueBanner();
                loadRuns(runsPage);
                reviewWorkspace.refresh(true);
            } else if (keys.includes('review')) {
                reviewWorkspace.refresh();
            }
        },
    },
});

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

/** Reads a JSON answer for one render target, or null after the error toast or a newer read. */
const readJson = jsonReader(() => t('error'));

const reviewWorkspace = createReviewWorkspace({
    pageUrl,
    readJson,
    t,
    sections,
    kinds: KINDS,
    reasons: Object.keys(window.krtP4kImportI18n || {})
        .filter((key) => key.startsWith('reason.'))
        .map((key) => key.slice('reason.'.length)),
});

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
    readJson(pageUrl(`/runs?page=${page}`), 'p4k-runs').then(
        (/** @type {P4kRunsView | null} */ view) => {
            if (!view || !view.runs) return;
            if (reloadedBeyondEnd(page, view.runs, loadRuns)) return;
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
                            krtHtml`<tr data-testid="p4k-run-row"><td><span class="cell-title">${formatTimestamp(
                                run.appliedAt,
                            )}</span><span class="cell-sub">${
                                (run.createdBy && view.adminNames[run.createdBy]) ||
                                t('adminUnknown')
                            }</span></td><td class="data-value--mono">${run.buildBranch} · ${
                                run.changeNumber
                            }</td><td>${runCounts(run)}</td><td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs" data-run-id="${
                                run.id
                            }" data-run-title="${run.buildVersion}">${t('actionReport')}</button></td></tr>`,
                    ),
                );
            }
            renderPager($('krt-p4k-runs-pager'), view.runs, pagerLabels(t));
        },
    );
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
    readJson(url, 'p4k-report').then(
        (/** @type {ApiPage<'P4kImportChangeDto'> | null} */ result) => {
            if (!result) return;
            if (reloadedBeyondEnd(page, result, loadReport)) return;
            reportPage = page;
            renderReport(result.content || []);
            renderPager($('krt-p4k-report-pager'), result, pagerLabels(t));
        },
    );
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
 * The value column of a report row: old → new, the counts of a skipped sweep, or the unresolved
 * ports and issue codes of an incomplete loadout.
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
    if (row.changeType === 'LOADOUT_ISSUES') {
        return t('loadoutIssues')
            .replace('{0}', row.newValue || '0')
            .replace('{1}', row.oldValue || '–');
    }
    if (row.oldValue == null && row.newValue == null) return '';
    return `${row.oldValue == null ? '∅' : row.oldValue} → ${row.newValue == null ? '∅' : row.newValue}`;
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
    bindPager($('krt-p4k-runs-pager'), loadRuns);
    bindPager($('krt-p4k-report-pager'), loadReport);
    const close = $('krt-p4k-report-close');
    if (close) {
        close.addEventListener('click', () => {
            const section = $('krt-p4k-report');
            if (section) section.hidden = true;
            reportTarget = null;
        });
    }
    loadRuns(0);
    reviewWorkspace.init();
    refreshDueBanner();
}

/**
 * Reloads the run list, the review list and the due banner after an APPLY finished.
 */
export function refreshAfterApply() {
    refreshDueBanner();
    loadRuns(runsPage);
    reviewWorkspace.refresh(true);
    if (reportTarget) loadReport(reportPage);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
