// @ts-check
(function () {
    'use strict';

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
    let seedEl = null;
    /** @type {HTMLButtonElement | null} */
    let applyConfirmBtn = null;

    let lastJobs = [];
    /** @type {string | null} */
    let applyTargetId = null;
    /** @type {number | null} */
    let pollTimer = null;

    let gated = false;

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
        if (window.showFrontendErrorToast)
            window.showFrontendErrorToast(
                window.krtI18nText(i18n().error, 'krtP4kImportI18n.error'),
            );
    }

    function toastOk(msg) {
        if (window.showFrontendSuccessToast) window.showFrontendSuccessToast(msg);
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
        seedEl = /** @type {HTMLInputElement | null} */ ($('krt-p4k-seed'));
        applyConfirmBtn = /** @type {HTMLButtonElement | null} */ ($('krt-p4k-apply-confirm'));

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
     * @returns {any} the job DTO, or null when the answer is not job data
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
            if (window.showFrontendErrorToast)
                window.showFrontendErrorToast(
                    window.krtI18nText(i18n().pickFirst, 'krtP4kImportI18n.pickFirst'),
                );
            return;
        }
        if (!window.krtFetch) return;
        const fd = new FormData();
        fd.append('file', file);
        let reported = false;
        window.krtFetch
            .submitForm({
                url: jobsUrl(),
                method: 'POST',
                formData: fd,
                submitter: uploadBtn,
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
                toastOk(window.krtI18nText(i18n().toastUploaded, 'krtP4kImportI18n.toastUploaded'));
                resetFile();
                loadJobs();
            });
    }

    function loadJobs() {
        window.krtFetch
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

    function isActive(job) {
        return !!job && (job.status === 'PENDING' || job.status === 'RUNNING');
    }

    function pollControl(jobs) {
        const anyActive = jobs.some(isActive);
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
     * @param {any} job the job as the proxy returns it
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

    function renderJobs(jobs) {
        if (!jobsBody) return;
        if (jobsEmptyEl) jobsEmptyEl.hidden = jobs.length > 0;
        const tableWrap = jobsBody.closest('[data-p4k-jobs-table]');
        if (tableWrap instanceof HTMLElement) tableWrap.hidden = jobs.length === 0;
        const kindHeader = window.krtI18nText(i18n().colKind, 'krtP4kImportI18n.colKind');
        const summaryHeader = window.krtI18nText(i18n().colSummary, 'krtP4kImportI18n.colSummary');
        let html = '';
        jobs.forEach((job) => {
            html +=
                `<tr data-testid="p4k-job-row">` +
                `<td><span class="cell-title">${escapeHtml(
                    job.sourceFilename || '',
                )}</span><span class="cell-sub">${escapeHtml(fmtTime(job.createdAt))}</span></td>` +
                `<td data-label="${escapeAttr(kindHeader)}">${escapeHtml(kindLabel(job))}</td>` +
                `<td class="cell-status"><span class="chip ${escapeAttr(
                    statusChip(job),
                )}">${escapeHtml(statusLabel(job))}</span></td>` +
                `<td data-label="${escapeAttr(summaryHeader)}">${escapeHtml(
                    summaryText(job),
                )}</td>` +
                `<td class="cell-actions">`;
            if (job.status === 'SUCCEEDED') {
                html += `<button type="button" class="btn btn-ghost btn-xs" data-action="view" data-job-id="${escapeAttr(
                    job.id,
                )}">${escapeHtml(
                    window.krtI18nText(i18n().actionView, 'krtP4kImportI18n.actionView'),
                )}</button>`;
                if (job.kind === 'PREVIEW') {
                    html += ` <button type="button" class="btn btn-success btn-xs" data-action="apply" data-job-id="${escapeAttr(
                        job.id,
                    )}">${escapeHtml(
                        window.krtI18nText(i18n().actionApply, 'krtP4kImportI18n.actionApply'),
                    )}</button>`;
                }
            }
            html += '</td>' + '</tr>';
        });
        jobsBody.innerHTML = html;
    }

    function kindLabel(job) {
        return job.kind === 'APPLY'
            ? window.krtI18nText(i18n().kindApply, 'krtP4kImportI18n.kindApply')
            : window.krtI18nText(i18n().kindPreview, 'krtP4kImportI18n.kindPreview');
    }

    function statusLabel(job) {
        switch (job.status) {
            case 'PENDING':
                return window.krtI18nText(i18n().statusPending, 'krtP4kImportI18n.statusPending');
            case 'RUNNING':
                return window.krtI18nText(i18n().statusRunning, 'krtP4kImportI18n.statusRunning');
            case 'SUCCEEDED':
                return window.krtI18nText(
                    i18n().statusSucceeded,
                    'krtP4kImportI18n.statusSucceeded',
                );
            case 'FAILED':
                return window.krtI18nText(i18n().statusFailed, 'krtP4kImportI18n.statusFailed');
            default:
                return job.status || '';
        }
    }

    function fmtTime(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
    }

    function createdTotal(result) {
        if (!result) return 0;
        const blocks = [
            result.manufacturers,
            result.items,
            result.ships,
            result.commodities,
            result.blueprints,
        ];
        let sum = 0;
        blocks.forEach((c) => {
            if (c && typeof c.created === 'number') sum += c.created;
        });
        return sum;
    }

    function summaryText(job) {
        if (job.status === 'FAILED')
            return (
                job.errorMessage ||
                window.krtI18nText(i18n().statusFailed, 'krtP4kImportI18n.statusFailed')
            );
        if (job.status === 'SUCCEEDED')
            return `${String(createdTotal(job.result))} ${window.krtI18nText(
                i18n().colCreated,
                'krtP4kImportI18n.colCreated',
            )}`;
        return window.krtI18nText(i18n().summaryRunning, 'krtP4kImportI18n.summaryRunning');
    }

    function findJob(id) {
        return (
            lastJobs.find((j) => {
                return j && j.id === id;
            }) || null
        );
    }

    function onJobsClick(e) {
        if (!e.target || !e.target.closest) return;
        const btn = e.target.closest('[data-action]');
        if (!btn) return;
        const id = btn.getAttribute('data-job-id');
        const action = btn.getAttribute('data-action');
        if (!id) return;
        if (action === 'view') viewDetails(id);
        else if (action === 'apply') openApply(id);
    }

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

    function renderResult(result) {
        const modeEl = $('krt-p4k-mode');
        if (modeEl)
            modeEl.textContent = result.dryRun
                ? window.krtI18nText(i18n().modeDryRun, 'krtP4kImportI18n.modeDryRun')
                : window.krtI18nText(i18n().modeApplied, 'krtP4kImportI18n.modeApplied');
        const seedingEl = $('krt-p4k-seeding');
        if (seedingEl)
            seedingEl.textContent = result.seedingEnabled
                ? window.krtI18nText(i18n().seedingOn, 'krtP4kImportI18n.seedingOn')
                : window.krtI18nText(i18n().seedingOff, 'krtP4kImportI18n.seedingOff');

        const rows = [
            [
                window.krtI18nText(i18n().rowManufacturers, 'krtP4kImportI18n.rowManufacturers'),
                result.manufacturers,
            ],
            [window.krtI18nText(i18n().rowItems, 'krtP4kImportI18n.rowItems'), result.items],
            [window.krtI18nText(i18n().rowShips, 'krtP4kImportI18n.rowShips'), result.ships],
            [
                window.krtI18nText(i18n().rowCommodities, 'krtP4kImportI18n.rowCommodities'),
                result.commodities,
            ],
            [
                window.krtI18nText(i18n().rowBlueprints, 'krtP4kImportI18n.rowBlueprints'),
                result.blueprints,
            ],
        ];
        const body = $('krt-p4k-rows');
        let html = '';
        rows.forEach((pair) => {
            const c = pair[1] || {};
            html += `<tr>` + `<th scope="row">${escapeHtml(pair[0])}</th>`;
            [
                c.matched,
                c.uuidBackfilled,
                c.uuidConflicts,
                c.enriched,
                c.created,
                c.unmatched,
            ].forEach((v) => {
                html += `<td>${escapeHtml(v == null ? 0 : v)}</td>`;
            });
            html += '</tr>';
        });
        if (body) {
            body.innerHTML = html;
        }

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

    function openApply(id) {
        const job = findJob(id);
        if (!job) return;
        applyTargetId = id;
        if (applyFilenameEl) applyFilenameEl.textContent = job.sourceFilename || '';
        if (seedEl) seedEl.checked = false;
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
        const seed = !!(seedEl && seedEl.checked);
        const url = `${jobsUrl()}/${encodeURIComponent(applyTargetId)}/apply?seedNew=${
            seed ? 'true' : 'false'
        }`;
        if (!window.krtFetch) return;
        let reported = false;
        window.krtFetch
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
                toastOk(
                    window.krtI18nText(
                        i18n().toastApplyStarted,
                        'krtP4kImportI18n.toastApplyStarted',
                    ),
                );
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
})();
