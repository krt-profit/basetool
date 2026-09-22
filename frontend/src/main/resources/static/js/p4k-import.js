// @ts-check
/*
 * Admin - P4K catalog import flow (asynchronous background jobs).
 *
 * Responsibilities:
 *  - "Datei wählen" + "Hochladen & analysieren" -> POST the picked file (multipart `file`) to the
 *    jobs proxy, which enqueues a background PREVIEW job and returns immediately.
 *  - Poll the job list every few seconds while any job is PENDING/RUNNING; render the table.
 *  - "Details" -> show the per-type count table for a finished job (from the polled result).
 *  - "Anwenden" -> open the apply panel (seed opt-in), then POST .../apply to enqueue a background
 *    APPLY job from the finished preview's stored upload (no re-upload).
 *
 * The page never blocks on the heavy import: it only enqueues and polls. CSP-safe (no inline
 * handlers; wiring via addEventListener + delegation). Strings from window.krtP4kImportI18n;
 * the jobs base URL from window.krtP4kImportEndpoints.
 *
 * Every read of the jobs proxy goes through readJson(), and every write (upload, apply) through
 * krtFetch plus jobFromWrite(); together they are this module's "is this actually job data?" test.
 * `resp.ok` is NOT that test — see readJson for why, and why getting it wrong mattered more here
 * than on any click-driven surface.
 */
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

    // Set once a gate (re-authentication or the Terms-of-Use consent gate) has taken the page over.
    // From then on every handler falls silent: the browser is already navigating away, so an error
    // toast would be both unreadable and wrong about what happened.
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
     * The headers every call to the jobs proxy carries.
     *
     * X-Requested-With is not decoration: the frontend gates answer an XHR and a browser navigation
     * differently. The Terms-of-Use consent gate replies `403` + `X-Terms-Acceptance-Required` to a
     * marked request and a `302` to an unmarked one (REQ-SEC-028), and a lost OAuth2 session behaves
     * the same way with `401` + `X-Reauthenticate` (REQ-SEC-012). Without the marker these calls get
     * the redirect branch, fetch follows it, and the consent page arrives as a `200 text/html` that
     * looks like a successful answer.
     *
     * The two writes (upload, apply) go through krtFetch, which sends the same marker itself.
     *
     * @returns {Record<string, string>} a fresh header object for the job-list poll
     */
    function ajaxHeaders() {
        return { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
    }

    /**
     * Reads a jobs-proxy answer as JSON, or resolves to null when the answer is not job data.
     *
     * `resp.ok` is the wrong test on principle, not just for one gate: fetch follows redirects
     * transparently, so any redirect-to-HTML answer — the consent gate, an expired-session login
     * bounce, an error-handler redirect — arrives as a 200 whose body is a whole document, with
     * `resp.ok` true. For the 3 s poll this was worse than a silent failure: pollControl only runs
     * after a successful parse, so a gated answer left the interval armed forever and the page went
     * on fetching and re-parsing the consent page every tick, indefinitely.
     *
     * So the two gate contracts are honoured first, exactly as every krtFetch-driven surface does —
     * both navigate the browser — and anything redirected or not OK is then rejected outright. An
     * unparseable 200 resolves to null as well rather than rejecting, so the callers' "not job data"
     * path (which disarms the poll) sees it instead of the transient-error catch (which does not).
     *
     * @param {Response} resp the jobs-proxy response
     * @returns {Promise<any> | null} the parsed body, or null when the answer is not job data
     */
    function readJson(resp) {
        if (window.krtReauth && window.krtReauth.check(resp)) return gateTookOver();
        if (window.krtTermsGate && window.krtTermsGate.check(resp)) return gateTookOver();
        if (resp.redirected || !resp.ok) return null;
        return resp.json().catch(function () {
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
            window.showFrontendErrorToast(i18n().error || 'Import failed.');
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

    /* ------------------------------------------------------------------ upload */

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
     * when the answer was not job data. A 2xx whose body is not an object (a followed redirect to
     * an HTML page) counts as not-job-data too — see readJson for why `ok` alone is not the test.
     * When the request failed without reaching the caller's onError / onNetworkError hook, a gate
     * (re-authentication or the Terms-of-Use consent gate) handled it and is navigating the page
     * away, so the page falls silent exactly as readJson's gate branch makes it.
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
                window.showFrontendErrorToast(i18n().pickFirst || 'Please choose a file first.');
            return;
        }
        if (!window.krtFetch) return;
        const fd = new FormData();
        fd.append('file', file);
        let reported = false;
        // krtFetch.submitForm (REQ-FE-002): CSRF header, the bare-403 refresh-and-retry and both
        // gate redirects; Content-Type stays unset so the browser writes the multipart boundary.
        // The upload button is disabled for the in-flight request (double-submit guard).
        window.krtFetch
            .submitForm({
                url: jobsUrl(),
                method: 'POST',
                formData: fd,
                submitter: uploadBtn,
                toast: false,
                onError: function () {
                    reported = true;
                    toastError();
                    return true;
                },
                onNetworkError: function () {
                    reported = true;
                    toastError();
                    return true;
                },
            })
            .then(function (result) {
                const job = jobFromWrite(result, reported);
                if (!job) {
                    if (result.ok && !gated) toastError();
                    return;
                }
                toastOk(i18n().toastUploaded || 'Catalog uploaded.');
                resetFile();
                loadJobs();
            });
    }

    /* -------------------------------------------------------------- job list */

    function loadJobs() {
        fetch(jobsUrl(), {
            method: 'GET',
            credentials: 'same-origin',
            headers: ajaxHeaders(),
        })
            .then(readJson)
            .then(function (jobs) {
                if (!Array.isArray(jobs)) {
                    // The answer was not job data: a gate took the page over, a redirect was
                    // followed, the status was an error, or the body did not parse. Disarm the
                    // timer — pollControl below is reached only on the success path, so this is the
                    // one place a refused poll can stop itself. Leaving it armed is what turned a
                    // single gated answer into an endless 3 s loop against the consent page.
                    stopPolling();
                    return;
                }
                lastJobs = jobs;
                renderJobs(jobs);
                pollControl(jobs);
            })
            .catch(function () {
                // Network-level failure only (a refused or unparseable answer resolves to null
                // above): genuinely transient, so an armed poll keeps retrying on the next tick.
            });
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
     * Disarms the poll timer, idempotently. Every "this answer was not job data" path funnels here,
     * so the timer can never outlive the condition that armed it.
     */
    function stopPolling() {
        if (pollTimer) {
            window.clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function renderJobs(jobs) {
        if (!jobsBody) return;
        if (jobsEmptyEl) jobsEmptyEl.hidden = jobs.length > 0;
        // Accumulated from literals and escapeHtml / escapeAttr calls only, so the innerHTML sink
        // provably sees escaped values (FE-SEC-05).
        let html = '';
        jobs.forEach(function (job) {
            html +=
                '<tr>' +
                '<td>' +
                escapeHtml(fmtTime(job.createdAt)) +
                '</td>' +
                '<td>' +
                escapeHtml(kindLabel(job)) +
                '</td>' +
                '<td>' +
                escapeHtml(statusLabel(job)) +
                '</td>' +
                '<td>' +
                escapeHtml(job.sourceFilename || '') +
                '</td>' +
                '<td>' +
                escapeHtml(summaryText(job)) +
                '</td>' +
                '<td>';
            // Row actions: only a finished job has any; only a finished PREVIEW can be applied.
            if (job.status === 'SUCCEEDED') {
                html +=
                    '<button type="button" class="btn btn-ghost" data-action="view" data-job-id="' +
                    escapeAttr(job.id) +
                    '">' +
                    escapeHtml(i18n().actionView || 'Details') +
                    '</button>';
                if (job.kind === 'PREVIEW') {
                    html +=
                        ' <button type="button" class="btn btn--cta" data-action="apply" data-job-id="' +
                        escapeAttr(job.id) +
                        '">' +
                        escapeHtml(i18n().actionApply || 'Apply') +
                        '</button>';
                }
            }
            html += '</td>' + '</tr>';
        });
        jobsBody.innerHTML = html;
    }

    function kindLabel(job) {
        return job.kind === 'APPLY' ? i18n().kindApply || 'Apply' : i18n().kindPreview || 'Preview';
    }

    function statusLabel(job) {
        switch (job.status) {
            case 'PENDING':
                return i18n().statusPending || 'Queued';
            case 'RUNNING':
                return i18n().statusRunning || 'Running';
            case 'SUCCEEDED':
                return i18n().statusSucceeded || 'Done';
            case 'FAILED':
                return i18n().statusFailed || 'Failed';
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
        blocks.forEach(function (c) {
            if (c && typeof c.created === 'number') sum += c.created;
        });
        return sum;
    }

    function summaryText(job) {
        if (job.status === 'FAILED') return job.errorMessage || i18n().statusFailed || 'Failed';
        if (job.status === 'SUCCEEDED')
            return String(createdTotal(job.result)) + ' ' + (i18n().colCreated || 'Created');
        return i18n().summaryRunning || 'Processing...';
    }

    function findJob(id) {
        return (
            lastJobs.find(function (j) {
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

    /* --------------------------------------------------------------- details */

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
                ? i18n().modeDryRun || 'Preview'
                : i18n().modeApplied || 'Applied';
        const seedingEl = $('krt-p4k-seeding');
        if (seedingEl)
            seedingEl.textContent = result.seedingEnabled
                ? i18n().seedingOn || 'on'
                : i18n().seedingOff || 'off';

        const rows = [
            [i18n().rowManufacturers || 'Manufacturers', result.manufacturers],
            [i18n().rowItems || 'Items', result.items],
            [i18n().rowShips || 'Ships', result.ships],
            [i18n().rowCommodities || 'Commodities', result.commodities],
            [i18n().rowBlueprints || 'Blueprints', result.blueprints],
        ];
        const body = $('krt-p4k-rows');
        // Accumulated from literals and escapeHtml calls only (FE-SEC-05); declared at function
        // level because the lint rule only traces an accumulator in the sink's own function scope.
        let html = '';
        rows.forEach(function (pair) {
            const c = pair[1] || {};
            html += '<tr>' + '<th scope="row">' + escapeHtml(pair[0]) + '</th>';
            [
                c.matched,
                c.uuidBackfilled,
                c.uuidConflicts,
                c.enriched,
                c.created,
                c.unmatched,
            ].forEach(function (v) {
                html += '<td>' + escapeHtml(v == null ? 0 : v) + '</td>';
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

    /* ----------------------------------------------------------------- apply */

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
        const url =
            jobsUrl() +
            '/' +
            encodeURIComponent(applyTargetId) +
            '/apply?seedNew=' +
            (seed ? 'true' : 'false');
        if (!window.krtFetch) return;
        let reported = false;
        // krtFetch.write (REQ-FE-002): a body-less POST with CSRF, the 403 retry and both gate
        // redirects. The confirm button is disabled for the in-flight request.
        window.krtFetch
            .write({
                method: 'POST',
                url: url,
                submitter: applyConfirmBtn,
                toast: false,
                onError: function () {
                    reported = true;
                    toastError();
                    return true;
                },
                onNetworkError: function () {
                    reported = true;
                    toastError();
                    return true;
                },
            })
            .then(function (result) {
                const job = jobFromWrite(result, reported);
                if (!job) {
                    if (result.ok && !gated) toastError();
                    return;
                }
                toastOk(i18n().toastApplyStarted || 'Apply started.');
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
