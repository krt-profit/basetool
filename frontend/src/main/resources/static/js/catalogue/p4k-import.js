import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { openReport, refreshAfterApply } from '/js/catalogue/p4k-report.js';

/** @typedef {ApiDto<'P4kImportJobDto'>} P4kJob */
/** @typedef {ApiDto<'P4kImportResultDto'>} P4kResult */
/** @typedef {ApiDto<'Build'>} P4kBuild */
/** @typedef {ApiDto<'Sweep'>} P4kSweep */

/** @type {HTMLInputElement | null} */
let fileInput = null;
/** @type {HTMLElement | null} */
let filenameEl = null;
/** @type {HTMLButtonElement | null} */
let uploadBtn = null;
/** @type {HTMLElement | null} */
let jobsBody = null;
/** @type {HTMLElement | null} */
let jobsEmptyEl = null;
/** @type {HTMLElement | null} */
let resultsEl = null;
/** @type {HTMLElement | null} */
let applyPanelEl = null;
/** @type {HTMLElement | null} */
let applyFilenameEl = null;
/** @type {HTMLInputElement | null} */
let createNewEl = null;
/** @type {HTMLElement | null} */
let wouldCreateEl = null;
/** @type {HTMLButtonElement | null} */
let applyConfirmBtn = null;
/** @type {HTMLInputElement | null} */
let markAnywayEl = null;

/** @type {P4kJob[]} */
let lastJobs = [];
/** @type {string | null} */
let applyTargetId = null;
/** @type {number | null} */
let pollTimer = null;

let gated = false;
let wasActive = false;

/**
 * Looks up an element by id.
 *
 * @param {string} id the element id
 * @returns {HTMLElement | null} the element, or null
 */
function $(id) {
    return document.getElementById(id);
}

function i18n() {
    return window.krtP4kImportI18n || {};
}

function jobsUrl() {
    return (window.krtP4kImportEndpoints || {}).jobs || '/admin/p4k-import/jobs';
}

/**
 * Reads a jobs-proxy answer as JSON, or resolves to null when the answer is not job data.
 *
 * A null response means `krtFetch.get` handed the answer to the re-auth or consent gate or
 * refused it as redirected; that and a non-OK or unparseable answer yield null, which makes the
 * callers disarm the poll.
 *
 * @param {Response | null} resp the jobs-proxy response
 * @returns {Promise<any> | null} the parsed body, or null when the answer is not job data
 */
function readJson(resp) {
    if (!resp) return gateTookOver();
    if (!resp.ok) return null;
    return resp.json().catch(() => {
        return null;
    });
}

/**
 * Records that a gate is navigating the page away and disarms the poll, so the departing page
 * cannot keep re-requesting the resource that just refused it.
 *
 * @returns {null} always null, so readJson's callers take their "not job data" path
 */
function gateTookOver() {
    gated = true;
    stopPolling();
    return null;
}

function toastError() {
    showFrontendErrorToast(krtI18nText(i18n().error, 'krtP4kImportI18n.error'));
}

/**
 * Shows the upload gate's refusal: the problem's localized `detail`, else the generic text.
 *
 * @param {unknown} body the parsed problem body of the 400 answer
 */
function toastRejected(body) {
    const detail =
        body && typeof body === 'object' && 'detail' in body && typeof body.detail === 'string'
            ? body.detail
            : '';
    showFrontendErrorToast(detail || krtI18nText(i18n().rejected, 'krtP4kImportI18n.rejected'));
}

/**
 * Shows a success toast.
 *
 * @param {string} msg the message
 */
function toastOk(msg) {
    showFrontendSuccessToast(msg);
}

function init() {
    fileInput = /** @type {HTMLInputElement | null} */ ($('krt-p4k-file'));
    filenameEl = $('krt-p4k-filename');
    uploadBtn = /** @type {HTMLButtonElement | null} */ ($('krt-p4k-upload-btn'));
    jobsBody = $('krt-p4k-jobs');
    jobsEmptyEl = $('krt-p4k-jobs-empty');
    resultsEl = $('krt-p4k-results');
    applyPanelEl = $('krt-p4k-apply-panel');
    applyFilenameEl = $('krt-p4k-apply-filename');
    createNewEl = /** @type {HTMLInputElement | null} */ ($('krt-p4k-create-new'));
    wouldCreateEl = $('krt-p4k-would-create');
    applyConfirmBtn = /** @type {HTMLButtonElement | null} */ ($('krt-p4k-apply-confirm'));
    markAnywayEl = /** @type {HTMLInputElement | null} */ ($('krt-p4k-mark-anyway'));

    const pickBtn = $('krt-p4k-pick');
    const detailsCloseBtn = $('krt-p4k-details-close');
    const applyCancelBtn = $('krt-p4k-apply-cancel');
    if (pickBtn) pickBtn.addEventListener('click', pickFile);
    if (fileInput) fileInput.addEventListener('change', onFileChosen);
    if (uploadBtn) uploadBtn.addEventListener('click', upload);
    if (detailsCloseBtn) detailsCloseBtn.addEventListener('click', closeDetails);
    if (applyConfirmBtn) applyConfirmBtn.addEventListener('click', confirmApply);
    if (applyCancelBtn) applyCancelBtn.addEventListener('click', cancelApply);
    if (jobsBody) jobsBody.addEventListener('click', onJobsClick);

    loadJobs();
}

function pickFile() {
    if (fileInput) fileInput.click();
}

function selectedFile() {
    if (!fileInput || !fileInput.files || fileInput.files.length === 0) return null;
    return fileInput.files[0];
}

function onFileChosen() {
    const file = selectedFile();
    if (filenameEl) filenameEl.textContent = file ? file.name : '';
    if (uploadBtn) uploadBtn.disabled = !file;
}

function resetFile() {
    if (fileInput) fileInput.value = '';
    if (filenameEl) filenameEl.textContent = '';
    if (uploadBtn) uploadBtn.disabled = true;
}

/**
 * Interprets a krtFetch write outcome against the jobs proxy: the job DTO on success, or null
 * when the answer was not job data (including a 2xx whose body is not an object).
 *
 * An unreported failure means a gate is navigating the page away, so the poll is disarmed.
 *
 * @param {KrtWriteResult} result the krtFetch outcome
 * @param {boolean} reported whether an onError / onNetworkError hook already surfaced the failure
 * @returns {P4kJob | null} the job DTO, or null when the answer is not job data
 */
function jobFromWrite(result, reported) {
    if (result.ok && result.body && typeof result.body === 'object') {
        return result.body;
    }
    if (!result.ok && !reported) {
        gateTookOver();
    }
    return null;
}

function upload() {
    const file = selectedFile();
    if (!file) {
        showFrontendErrorToast(krtI18nText(i18n().pickFirst, 'krtP4kImportI18n.pickFirst'));
        return;
    }

    const fd = new FormData();
    fd.append('file', file);
    let reported = false;
    krtFetch
        .submitForm({
            url: jobsUrl(),
            method: 'POST',
            formData: fd,
            submitter: uploadBtn,
            toast: false,
            onError(status, body) {
                reported = true;
                if (status === 400) {
                    toastRejected(body);
                } else {
                    toastError();
                }
                return true;
            },
            onNetworkError() {
                reported = true;
                toastError();
                return true;
            },
        })
        .then((result) => {
            const job = jobFromWrite(result, reported);
            if (!job) {
                if (result.ok && !gated) toastError();
                return;
            }
            toastOk(krtI18nText(i18n().toastUploaded, 'krtP4kImportI18n.toastUploaded'));
            resetFile();
            loadJobs();
        });
}

function loadJobs() {
    krtFetch
        .get(jobsUrl(), { accept: 'application/json' })
        .then(readJson)
        .then((jobs) => {
            if (!Array.isArray(jobs)) {
                stopPolling();
                return;
            }
            lastJobs = jobs;
            renderJobs(jobs);
            pollControl(jobs);
        })
        .catch(() => {});
}

/**
 * Tells whether a job is still queued or running.
 *
 * @param {P4kJob | null | undefined} job the job
 * @returns {boolean} true while the job is pending or running
 */
function isActive(job) {
    return !!job && (job.status === 'PENDING' || job.status === 'RUNNING');
}

/**
 * Arms the poll while any job is active and disarms it otherwise.
 *
 * @param {P4kJob[]} jobs the current jobs
 */
function pollControl(jobs) {
    const anyActive = jobs.some(isActive);
    if (wasActive && !anyActive) refreshAfterApply();
    wasActive = anyActive;
    if (anyActive && !pollTimer) {
        pollTimer = window.setInterval(loadJobs, 3000);
    } else if (!anyActive) {
        stopPolling();
    }
}

/**
 * Disarms the poll timer; idempotent.
 */
function stopPolling() {
    if (pollTimer) {
        window.clearInterval(pollTimer);
        pollTimer = null;
    }
}

/**
 * Maps a job status onto the chip modifier that colours it.
 *
 * @param {P4kJob} job the job as the proxy returns it
 * @returns {string} the chip modifier class
 */
function statusChip(job) {
    switch (job.status) {
        case 'RUNNING':
            return 'chip--info';
        case 'SUCCEEDED':
            return 'chip--success';
        case 'FAILED':
            return 'chip--danger';
        default:
            return 'chip--muted';
    }
}

/**
 * Renders the job table rows.
 *
 * @param {P4kJob[]} jobs the jobs to show
 */
function renderJobs(jobs) {
    if (!jobsBody) return;
    if (jobsEmptyEl) jobsEmptyEl.hidden = jobs.length > 0;
    const tableWrap = jobsBody.closest('[data-p4k-jobs-table]');
    if (tableWrap instanceof HTMLElement) tableWrap.hidden = jobs.length === 0;
    const kindHeader = krtI18nText(i18n().colKind, 'krtP4kImportI18n.colKind');
    const summaryHeader = krtI18nText(i18n().colSummary, 'krtP4kImportI18n.colSummary');
    /** @type {KrtHtml[]} */
    const html = [];
    jobs.forEach((job) => {
        html.push(
            krtHtml`<tr data-testid="p4k-job-row"><td><span class="cell-title">${
                job.sourceFilename || ''
            }</span><span class="cell-sub">${fmtTime(job.createdAt)}</span></td><td data-label="${
                kindHeader
            }">${kindLabel(job)}</td><td class="cell-status"><span class="chip ${statusChip(
                job,
            )}">${statusLabel(job)}</span></td><td data-label="${summaryHeader}">${summaryText(
                job,
            )}</td><td class="cell-actions">`,
        );
        if (job.status === 'SUCCEEDED') {
            html.push(
                krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-action="view" data-job-id="${
                    job.id
                }">${krtI18nText(i18n().actionView, 'krtP4kImportI18n.actionView')}</button>`,
            );
            if (job.kind === 'PREVIEW') {
                html.push(
                    krtHtml` <button type="button" class="btn btn-ghost btn-xs" data-action="report" data-job-id="${
                        job.id
                    }">${krtI18nText(i18n().actionReport, 'krtP4kImportI18n.actionReport')}</button>`,
                );
                html.push(
                    krtHtml` <button type="button" class="btn btn-success btn-xs" data-action="apply" data-job-id="${
                        job.id
                    }">${krtI18nText(i18n().actionApply, 'krtP4kImportI18n.actionApply')}</button>`,
                );
            }
        }
        html.push(krtHtml`</td></tr>`);
    });
    krtHtml.set(jobsBody, html);
}

/**
 * Localised label of the job kind.
 *
 * @param {P4kJob} job the job
 * @returns {string} the label
 */
function kindLabel(job) {
    return job.kind === 'APPLY'
        ? krtI18nText(i18n().kindApply, 'krtP4kImportI18n.kindApply')
        : krtI18nText(i18n().kindPreview, 'krtP4kImportI18n.kindPreview');
}

/**
 * Localised label of the job status.
 *
 * @param {P4kJob} job the job
 * @returns {string} the label
 */
function statusLabel(job) {
    switch (job.status) {
        case 'PENDING':
            return krtI18nText(i18n().statusPending, 'krtP4kImportI18n.statusPending');
        case 'RUNNING':
            return krtI18nText(i18n().statusRunning, 'krtP4kImportI18n.statusRunning');
        case 'SUCCEEDED':
            return krtI18nText(i18n().statusSucceeded, 'krtP4kImportI18n.statusSucceeded');
        case 'FAILED':
            return krtI18nText(i18n().statusFailed, 'krtP4kImportI18n.statusFailed');
        default:
            return job.status || '';
    }
}

/**
 * Formats an ISO timestamp in the browser locale.
 *
 * @param {string | undefined} iso the timestamp
 * @returns {string} the formatted time, the input when unparseable, or an empty string
 */
function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * Sums the created counts of all result blocks.
 *
 * @param {P4kResult | undefined} result the import result
 * @returns {number} the total number of created rows
 */
function createdTotal(result) {
    if (!result) return 0;
    const blocks = [
        result.manufacturers,
        result.items,
        result.ships,
        result.commodities,
        result.blueprints,
        result.starmapObjects,
        result.shops,
        result.resourceGroups,
        result.blueprintCategories,
    ];
    let sum = 0;
    blocks.forEach((c) => {
        if (c && typeof c.created === 'number') sum += c.created;
    });
    return sum;
}

/**
 * The items and ships a preview would create with the "create new items and ships" box ticked.
 *
 * @param {P4kResult | undefined} result the preview result
 * @returns {number} the would-be count of new items and ships
 */
function wouldCreate(result) {
    if (!result) return 0;
    const items = result.items ? result.items.created || 0 : 0;
    const ships = result.ships ? result.ships.created || 0 : 0;
    return items + ships;
}

/**
 * Localised label of a kind's completeness.
 *
 * @param {string | null | undefined} completeness `COMPLETE`, `PARTIAL` or null
 * @returns {string} the label, empty when the catalogue did not state it
 */
function completenessLabel(completeness) {
    if (completeness === 'COMPLETE') {
        return krtI18nText(i18n().completeComplete, 'krtP4kImportI18n.completeComplete');
    }
    if (completeness === 'PARTIAL') {
        return krtI18nText(i18n().completePartial, 'krtP4kImportI18n.completePartial');
    }
    return '';
}

/**
 * The display text of a run's patch version: a public patch as is, a branch lower bound marked
 * as the branch, an unknown version as "unknown".
 *
 * @param {P4kBuild} build the Build of the run
 * @returns {string} the patch-version text
 */
function patchText(build) {
    if (build.patchVersion && build.patchVersionOrigin === 'PUBLIC_PATCH') {
        return build.patchVersion;
    }
    if (build.patchVersion && build.patchVersionOrigin === 'BRANCH_LOWER_BOUND') {
        return krtI18nText(i18n().patchLowerBound, 'krtP4kImportI18n.patchLowerBound')
            .replace('{0}', build.patchVersion)
            .replace('{1}', build.branch || '');
    }
    return krtI18nText(i18n().patchUnknown, 'krtP4kImportI18n.patchUnknown');
}

/**
 * Fills the Build line of the result panel, hiding it when the result names no Build.
 *
 * @param {P4kBuild | null | undefined} build the Build of the run
 */
function renderBuild(build) {
    const line = $('krt-p4k-build-line');
    if (!line) return;
    if (!build) {
        line.hidden = true;
        return;
    }
    /** @type {[string, string][]} */
    const fields = [
        ['krt-p4k-build-channel', build.channel || ''],
        ['krt-p4k-build-branch', build.branch || ''],
        ['krt-p4k-build-version', build.version || ''],
        ['krt-p4k-build-change', build.changeNumber || ''],
        ['krt-p4k-build-patch', patchText(build)],
    ];
    fields.forEach((pair) => {
        const el = $(pair[0]);
        if (el) el.textContent = pair[1];
    });
    line.hidden = false;
}

/**
 * Summary column text of a job.
 *
 * @param {P4kJob} job the job
 * @returns {string} the summary
 */
function summaryText(job) {
    if (job.status === 'FAILED')
        return (
            job.errorMessage || krtI18nText(i18n().statusFailed, 'krtP4kImportI18n.statusFailed')
        );
    if (job.status === 'SUCCEEDED')
        return `${String(createdTotal(job.result))} ${krtI18nText(
            i18n().colCreated,
            'krtP4kImportI18n.colCreated',
        )}`;
    return krtI18nText(i18n().summaryRunning, 'krtP4kImportI18n.summaryRunning');
}

/**
 * Finds a loaded job by id.
 *
 * @param {string} id the job id
 * @returns {P4kJob | null} the job, or null
 */
function findJob(id) {
    return (
        lastJobs.find((j) => {
            return j && j.id === id;
        }) || null
    );
}

/**
 * Handles a click on a job row action button.
 *
 * @param {MouseEvent} e the click
 */
function onJobsClick(e) {
    const target = /** @type {Element | null} */ (e.target);
    if (!target || !target.closest) return;
    const btn = target.closest('[data-action]');
    if (!btn) return;
    const id = btn.getAttribute('data-job-id');
    const action = btn.getAttribute('data-action');
    if (!id) return;
    if (action === 'view') viewDetails(id);
    else if (action === 'apply') openApply(id);
    else if (action === 'report') {
        const job = findJob(id);
        openReport('jobs', id, job && job.sourceFilename ? job.sourceFilename : id);
    }
}

/**
 * Shows the result panel of a job.
 *
 * @param {string} id the job id
 */
function viewDetails(id) {
    const job = findJob(id);
    if (!job || !job.result) return;
    renderResult(job.result);
    if (resultsEl) {
        resultsEl.hidden = false;
        resultsEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
}

function closeDetails() {
    if (resultsEl) resultsEl.hidden = true;
}

/**
 * Renders an import result into the result panel.
 *
 * @param {P4kResult} result the import result
 */
function renderResult(result) {
    const modeEl = $('krt-p4k-mode');
    if (modeEl)
        modeEl.textContent = result.dryRun
            ? krtI18nText(i18n().modeDryRun, 'krtP4kImportI18n.modeDryRun')
            : krtI18nText(i18n().modeApplied, 'krtP4kImportI18n.modeApplied');
    const createNewModeEl = $('krt-p4k-create-new-mode');
    if (createNewModeEl)
        createNewModeEl.textContent = result.createItemsAndShips
            ? krtI18nText(i18n().createNewOn, 'krtP4kImportI18n.createNewOn')
            : krtI18nText(i18n().createNewOff, 'krtP4kImportI18n.createNewOff');
    renderBuild(result.build);

    /** @type {[string, ApiDto<'Counts'> | undefined][]} */
    const rows = [
        [
            krtI18nText(i18n().rowManufacturers, 'krtP4kImportI18n.rowManufacturers'),
            result.manufacturers,
        ],
        [krtI18nText(i18n().rowItems, 'krtP4kImportI18n.rowItems'), result.items],
        [krtI18nText(i18n().rowShips, 'krtP4kImportI18n.rowShips'), result.ships],
        [krtI18nText(i18n().rowCommodities, 'krtP4kImportI18n.rowCommodities'), result.commodities],
        [krtI18nText(i18n().rowBlueprints, 'krtP4kImportI18n.rowBlueprints'), result.blueprints],
        [
            krtI18nText(i18n().rowStarmapObjects, 'krtP4kImportI18n.rowStarmapObjects'),
            result.starmapObjects || undefined,
        ],
        [krtI18nText(i18n().rowShops, 'krtP4kImportI18n.rowShops'), result.shops || undefined],
        [
            krtI18nText(i18n().rowResourceGroups, 'krtP4kImportI18n.rowResourceGroups'),
            result.resourceGroups || undefined,
        ],
        [
            krtI18nText(i18n().rowBlueprintCategories, 'krtP4kImportI18n.rowBlueprintCategories'),
            result.blueprintCategories || undefined,
        ],
    ];
    const body = $('krt-p4k-rows');
    const html = rows.map((pair) => {
        const c = pair[1] || {};
        const cells = [
            c.matched,
            c.enriched,
            c.identityMismatches,
            c.created,
            c.unmatched,
            c.review,
            c.unknownValues,
        ].map((v) => krtHtml`<td>${v == null ? 0 : v}</td>`);
        return krtHtml`<tr><th scope="row">${pair[0]}</th><td>${completenessLabel(
            c.completeness,
        )}</td>${cells}</tr>`;
    });
    if (body) {
        krtHtml.set(body, html);
    }

    renderSweeps(result.sweeps || []);

    const ingredientsEl = $('krt-p4k-ingredients');
    if (ingredientsEl) ingredientsEl.textContent = String(result.ingredientsResolved || 0);

    const runIdLine = $('krt-p4k-runid-line');
    const runIdEl = $('krt-p4k-runid');
    if (runIdLine && runIdEl) {
        if (result.runId) {
            runIdEl.textContent = result.runId;
            runIdLine.hidden = false;
        } else {
            runIdEl.textContent = '';
            runIdLine.hidden = true;
        }
    }
}

/**
 * Localised label of a kind.
 *
 * @param {string | undefined} kind the kind
 * @returns {string} the label
 */
function kindText(kind) {
    return krtI18nText(i18n()[`kind.${kind}`], `krtP4kImportI18n.kind.${kind}`);
}

/**
 * Localised label of a sweep outcome.
 *
 * @param {string | null | undefined} outcome the outcome
 * @returns {string} the label, empty when no sweep was decided
 */
function sweepText(outcome) {
    return outcome
        ? krtI18nText(i18n()[`sweep.${outcome}`], `krtP4kImportI18n.sweep.${outcome}`)
        : '';
}

/**
 * Renders what the sweep did, or would do, per kind (REQ-P4K-011, REQ-P4K-012).
 *
 * @param {P4kSweep[]} sweeps the sweep results
 */
function renderSweeps(sweeps) {
    const body = $('krt-p4k-sweeps');
    if (!body) return;
    krtHtml.set(
        body,
        sweeps.map(
            (s) =>
                krtHtml`<tr><th scope="row">${kindText(s.kind)}</th><td>${sweepText(
                    s.outcome,
                )}</td><td>${s.known}</td><td>${s.toStamp}</td><td>${s.vanished}</td><td>${
                    s.reappeared
                }</td></tr>`,
        ),
    );
}

/**
 * Lists the kinds whose sweep the mass guard would hold back, with the rows it would stamp.
 *
 * @param {P4kResult | undefined} result the preview result
 */
function renderMassGuard(result) {
    const list = $('krt-p4k-mass-guard');
    const wrap = $('krt-p4k-mass-guard-wrap');
    const held = ((result && result.sweeps) || []).filter(
        (s) => s.outcome === 'SKIPPED_MASS_GUARD',
    );
    if (wrap) wrap.hidden = held.length === 0;
    const hint = $('krt-p4k-mass-guard-hint');
    if (hint) {
        const percent = result && result.massGuardPercent != null ? result.massGuardPercent : '';
        hint.textContent = (hint.dataset.template || '').replace('{0}', String(percent));
    }
    if (!list) return;
    krtHtml.set(
        list,
        held.map(
            (s) =>
                krtHtml`<li>${kindText(s.kind)}: <span class="data-value">${s.toStamp}</span> / ${s.known}</li>`,
        ),
    );
}

/**
 * Opens the apply confirmation panel for a preview job.
 *
 * @param {string} id the job id
 */
function openApply(id) {
    const job = findJob(id);
    if (!job) return;
    applyTargetId = id;
    if (applyFilenameEl) applyFilenameEl.textContent = job.sourceFilename || '';
    if (createNewEl) createNewEl.checked = false;
    if (markAnywayEl) markAnywayEl.checked = false;
    renderMassGuard(job.result);
    if (wouldCreateEl) wouldCreateEl.textContent = String(wouldCreate(job.result));
    if (applyPanelEl) {
        applyPanelEl.hidden = false;
        applyPanelEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
}

function cancelApply() {
    applyTargetId = null;
    if (applyPanelEl) applyPanelEl.hidden = true;
}

function confirmApply() {
    if (!applyTargetId) return;
    const createNew = !!(createNewEl && createNewEl.checked);
    const markAnyway = !!(markAnywayEl && markAnywayEl.checked);
    const url = `${jobsUrl()}/${encodeURIComponent(
        applyTargetId,
    )}/apply?createItemsAndShips=${createNew ? 'true' : 'false'}&overrideMassGuard=${
        markAnyway ? 'true' : 'false'
    }`;

    let reported = false;
    krtFetch
        .write({
            method: 'POST',
            url,
            submitter: applyConfirmBtn,
            toast: false,
            onError() {
                reported = true;
                toastError();
                return true;
            },
            onNetworkError() {
                reported = true;
                toastError();
                return true;
            },
        })
        .then((result) => {
            const job = jobFromWrite(result, reported);
            if (!job) {
                if (result.ok && !gated) toastError();
                return;
            }
            toastOk(krtI18nText(i18n().toastApplyStarted, 'krtP4kImportI18n.toastApplyStarted'));
            if (applyPanelEl) applyPanelEl.hidden = true;
            applyTargetId = null;
            loadJobs();
        });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
