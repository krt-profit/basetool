import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { refreshDueBanner } from '/js/catalogue/p4k-due.js';
import { bindPager, renderPager } from '/js/catalogue/catalogue-pager.js';
import {
    byId as $,
    dictionaryLabels,
    readJson as readJsonOr,
} from '/js/catalogue/catalogue-link-admin.js';

/** @typedef {ApiDto<'P4kImportRunDto'>} P4kRun */
/** @typedef {ApiDto<'P4kImportChangeDto'>} P4kChange */
/** @typedef {ApiDto<'P4kReviewCaseDto'>} P4kCase */
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
let casesPage = 0;

/** Reads one label of the page's dictionary. */
const t = dictionaryLabels(() => window.krtP4kImportI18n, 'krtP4kImportI18n');

/**
 * The page's room in the admin catalogue live sync (REQ-FE-015): a peer admin's review resolution
 * announces `review`, which reloads the options and the cases, and an APPLY announces `runs` as
 * well, which reloads the run history and the import-due banner.
 */
krtPageSections.create({
    topicClass: 'CATALOGUE_ADMIN',
    topic: 'catalogue-admin',
    sections: { runs: { container: '#krt-p4k-runs' }, review: { container: '#krt-p4k-cases' } },
    receiver: {
        coalesceMs: 1500,
        refresh(/** @type {string[]} */ keys) {
            if (keys.includes('runs')) {
                refreshDueBanner();
                loadRuns(runsPage);
            }
            if (keys.includes('review')) loadCasesWithOptions(casesPage);
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
 * Reads a JSON answer for one render target, or null when it is no data or a later read of the
 * target superseded it.
 *
 * @param {string} url the proxy URL
 * @param {string} target the render target
 * @returns {Promise<any>} the parsed body, or null
 */
function readJson(url, target) {
    return readJsonOr(url, t('error'), target);
}

/** @returns {{ previous: string, next: string }} the pager button names */
function pagerLabels() {
    return { previous: t('pagePrevious'), next: t('pageNext') };
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
    readJson(pageUrl(`/runs?page=${page}`), 'p4k-runs').then(
        (/** @type {P4kRunsView | null} */ view) => {
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
            renderPager($('krt-p4k-runs-pager'), view.runs, pagerLabels());
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
            reportPage = page;
            renderReport(result.content || []);
            renderPager($('krt-p4k-report-pager'), result, pagerLabels());
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

/**
 * Loads one page of the open review cases.
 *
 * @param {number} page the zero-based page
 */
function loadCases(page) {
    readJson(pageUrl(`/review-cases?page=${page}`), 'p4k-cases').then(
        (/** @type {ApiPage<'P4kReviewCaseDto'> | null} */ result) => {
            if (!result) return;
            casesPage = page;
            renderCases(result.content || []);
            renderPager($('krt-p4k-cases-pager'), result, pagerLabels());
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
                }</td><td>${fmtTime(c.createdAt)}</td><td class="cell-actions">${caseActions(c)}</td></tr>`,
        ),
    );
}

/** @type {{ id: string, name: string }[]} */
let resourceGroups = [];

/**
 * Loads the resource groups a missing-group case can assign (REQ-P4K-025).
 *
 * @returns {Promise<void>} settles once the groups are known
 */
function loadResourceGroups() {
    return readJson(pageUrl('/resource-groups'), 'p4k-resource-groups').then(
        (/** @type {{ id: string, name: string }[] | null} */ result) => {
            if (Array.isArray(result)) resourceGroups = result;
        },
    );
}

/** @type {{ id: string, name: string }[]} */
let materials = [];

/**
 * Loads the materials a resource-join case can assign (REQ-P4K-024).
 *
 * @returns {Promise<void>} settles once the materials are known
 */
function loadMaterials() {
    return readJson(pageUrl('/materials'), 'p4k-materials').then(
        (/** @type {{ id: string, name: string }[] | null} */ result) => {
            if (Array.isArray(result)) materials = result;
        },
    );
}

/**
 * Reloads the resource groups and materials the cases can assign, then one page of the cases, so a
 * case an APPLY opened offers the groups and materials it created (REQ-P4K-024, REQ-P4K-025).
 *
 * @param {number} page the zero-based page
 */
function loadCasesWithOptions(page) {
    Promise.all([loadResourceGroups(), loadMaterials()]).then(() => loadCases(page));
}

/** The reasons of a resource that joins no single commodity, resolved by assigning a material. */
const RESOURCE_JOIN_REASONS = ['RESOURCE_JOIN_NONE', 'RESOURCE_JOIN_SEVERAL'];

/**
 * The action cell of a case: a group picker for a missing resource group, a material picker and
 * keep for a resource without a single commodity, keep and hide otherwise.
 *
 * @param {P4kCase} c the case
 * @returns {ReturnType<typeof krtHtml>} the cell content
 */
function caseActions(c) {
    if (RESOURCE_JOIN_REASONS.includes(c.reason || '')) {
        return krtHtml`<select class="form-input" data-material-pick aria-label="${t(
            'pickMaterial',
        )}"><option value="">${t('pickMaterial')}</option>${materials.map(
            (m) => krtHtml`<option value="${m.id}">${m.name}</option>`,
        )}</select> <button type="button" class="btn btn-ghost btn-xs" data-resolution="ASSIGN">${t(
            'actionAssign',
        )}</button> <button type="button" class="btn btn-ghost btn-xs" data-resolution="KEEP">${t(
            'actionKeep',
        )}</button>`;
    }
    if (c.reason === 'NO_RESOURCE_GROUP') {
        return krtHtml`<select class="form-input" data-group-pick aria-label="${t('pickGroup')}"><option value="">${t(
            'pickGroup',
        )}</option>${resourceGroups.map(
            (g) => krtHtml`<option value="${g.id}">${g.name}</option>`,
        )}</select> <button type="button" class="btn btn-ghost btn-xs" data-resolution="ASSIGN">${t(
            'actionAssign',
        )}</button>`;
    }
    return krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-resolution="KEEP">${t(
        'actionKeep',
    )}</button> <button type="button" class="btn btn-quiet-danger btn-xs" data-resolution="HIDE">${t(
        'actionHide',
    )}</button>`;
}

/**
 * The toast of a resolution.
 *
 * @param {string} resolution the resolution sent
 * @returns {string} the message
 */
function resolvedToast(resolution) {
    if (resolution === 'HIDE') return t('toastHidden');
    if (resolution === 'ASSIGN') return t('toastAssigned');
    return t('toastKept');
}

/**
 * Resolves the case of a clicked keep, hide or assign button and removes its row in place.
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
    const pick = row.querySelector('select[data-group-pick]');
    const categoryId = pick instanceof HTMLSelectElement ? pick.value : '';
    const materialPick = row.querySelector('select[data-material-pick]');
    const materialId = materialPick instanceof HTMLSelectElement ? materialPick.value : '';
    if (resolution === 'ASSIGN' && !categoryId && !materialId) {
        const focus = pick || materialPick;
        if (focus instanceof HTMLSelectElement) focus.focus();
        return;
    }
    krtFetch
        .write({
            method: 'POST',
            url: pageUrl(`/review-cases/${encodeURIComponent(caseId)}/resolve`),
            payload: {
                resolution,
                categoryId: categoryId || null,
                materialId: materialId || null,
                version: Number(row.dataset.caseVersion),
            },
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
            showFrontendSuccessToast(resolvedToast(resolution));
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
    bindPager($('krt-p4k-runs-pager'), loadRuns);
    bindPager($('krt-p4k-report-pager'), loadReport);
    bindPager($('krt-p4k-cases-pager'), loadCases);
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
    loadCasesWithOptions(0);
    refreshDueBanner();
}

/**
 * Reloads the run list, the review list and the due banner after an APPLY finished.
 */
export function refreshAfterApply() {
    refreshDueBanner();
    loadRuns(runsPage);
    loadCasesWithOptions(casesPage);
    if (reportTarget) loadReport(reportPage);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
