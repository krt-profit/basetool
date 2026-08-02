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
 */
(function () {
    'use strict';

    let fileInput = null;
    let filenameEl = null;
    let uploadBtn = null;
    let jobsBody = null;
    let jobsEmptyEl = null;
    let resultsEl = null;
    let applyPanelEl = null;
    let applyFilenameEl = null;
    let seedEl = null;
    let applyConfirmBtn = null;

    let lastJobs = [];
    let applyTargetId = null;
    let pollTimer = null;

    function $(id) {
        return document.getElementById(id);
    }

    function i18n() {
        return window.krtP4kImportI18n || {};
    }

    function jobsUrl() {
        return (window.krtP4kImportEndpoints || {}).jobs || '/admin/p4k-import/jobs';
    }

    // Escape HTML meta-characters before any value is written via innerHTML. Implemented as a
    // self-contained replace chain (not a delegate to window.escapeHtml) so it is an unconditional,
    // statically-recognizable HTML-escape barrier on every path (CodeQL js/xss-through-dom).
    function esc(v) {
        return String(v == null ? '' : v)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // Sources the CSRF token from the shared window.krtCsrf reader (the single
    // source of truth over the meta tags, epic #571) rather than re-reading the
    // meta elements locally. The token/header-name are merged into the given base
    // WITHOUT forcing a Content-Type, so the multipart upload keeps the
    // browser-generated boundary and the body-less apply stays header-only.
    function csrfHeaders(base) {
        const headers = base || {};
        const token = window.krtCsrf ? window.krtCsrf.token() : null;
        const header = window.krtCsrf ? window.krtCsrf.headerName() : null;
        if (token && header) {
            headers[header] = token;
        }
        return headers;
    }

    function toastError() {
        if (window.showFrontendErrorToast)
            window.showFrontendErrorToast(i18n().error || 'Import failed.');
    }

    function toastOk(msg) {
        if (window.showFrontendSuccessToast) window.showFrontendSuccessToast(msg);
    }

    function init() {
        fileInput = $('krt-p4k-file');
        filenameEl = $('krt-p4k-filename');
        uploadBtn = $('krt-p4k-upload-btn');
        jobsBody = $('krt-p4k-jobs');
        jobsEmptyEl = $('krt-p4k-jobs-empty');
        resultsEl = $('krt-p4k-results');
        applyPanelEl = $('krt-p4k-apply-panel');
        applyFilenameEl = $('krt-p4k-apply-filename');
        seedEl = $('krt-p4k-seed');
        applyConfirmBtn = $('krt-p4k-apply-confirm');

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

    function upload() {
        const file = selectedFile();
        if (!file) {
            if (window.showFrontendErrorToast)
                window.showFrontendErrorToast(i18n().pickFirst || 'Please choose a file first.');
            return;
        }
        const fd = new FormData();
        fd.append('file', file);
        if (uploadBtn) uploadBtn.disabled = true;
        fetch(jobsUrl(), {
            method: 'POST',
            credentials: 'same-origin',
            headers: csrfHeaders({ Accept: 'application/json' }),
            body: fd,
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (job) {
                if (!job) {
                    if (uploadBtn) uploadBtn.disabled = false;
                    toastError();
                    return;
                }
                toastOk(i18n().toastUploaded || 'Catalog uploaded.');
                resetFile();
                loadJobs();
            })
            .catch(function () {
                if (uploadBtn) uploadBtn.disabled = false;
                toastError();
            });
    }

    /* -------------------------------------------------------------- job list */

    function loadJobs() {
        fetch(jobsUrl(), {
            method: 'GET',
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (jobs) {
                if (!Array.isArray(jobs)) return;
                lastJobs = jobs;
                renderJobs(jobs);
                pollControl(jobs);
            })
            .catch(function () {
                /* transient; the next poll (or a manual action) retries */
            });
    }

    function isActive(job) {
        return !!job && (job.status === 'PENDING' || job.status === 'RUNNING');
    }

    function pollControl(jobs) {
        const anyActive = jobs.some(isActive);
        if (anyActive && !pollTimer) {
            pollTimer = window.setInterval(loadJobs, 3000);
        } else if (!anyActive && pollTimer) {
            window.clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function renderJobs(jobs) {
        if (!jobsBody) return;
        if (jobsEmptyEl) jobsEmptyEl.hidden = jobs.length > 0;
        let html = '';
        jobs.forEach(function (job) {
            html += renderJobRow(job);
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

    function actionsHtml(job) {
        if (job.status !== 'SUCCEEDED') return '';
        let html =
            '<button type="button" class="btn btn-ghost" data-action="view" data-job-id="' +
            esc(job.id) +
            '">' +
            esc(i18n().actionView || 'Details') +
            '</button>';
        if (job.kind === 'PREVIEW') {
            html +=
                ' <button type="button" class="btn btn--cta" data-action="apply" data-job-id="' +
                esc(job.id) +
                '">' +
                esc(i18n().actionApply || 'Apply') +
                '</button>';
        }
        return html;
    }

    function renderJobRow(job) {
        return (
            '<tr>' +
            '<td>' +
            esc(fmtTime(job.createdAt)) +
            '</td>' +
            '<td>' +
            esc(kindLabel(job)) +
            '</td>' +
            '<td>' +
            esc(statusLabel(job)) +
            '</td>' +
            '<td>' +
            esc(job.sourceFilename || '') +
            '</td>' +
            '<td>' +
            esc(summaryText(job)) +
            '</td>' +
            '<td>' +
            actionsHtml(job) +
            '</td>' +
            '</tr>'
        );
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
        if (body) {
            let html = '';
            rows.forEach(function (pair) {
                html += renderCountRow(pair[0], pair[1]);
            });
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

    function renderCountRow(label, counts) {
        const c = counts || {};
        return (
            '<tr>' +
            '<th scope="row">' +
            esc(label) +
            '</th>' +
            '<td>' +
            num(c.matched) +
            '</td>' +
            '<td>' +
            num(c.uuidBackfilled) +
            '</td>' +
            '<td>' +
            num(c.uuidConflicts) +
            '</td>' +
            '<td>' +
            num(c.enriched) +
            '</td>' +
            '<td>' +
            num(c.created) +
            '</td>' +
            '<td>' +
            num(c.unmatched) +
            '</td>' +
            '</tr>'
        );
    }

    function num(v) {
        return esc(v == null ? 0 : v);
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
        if (applyConfirmBtn) applyConfirmBtn.disabled = true;
        fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: csrfHeaders({ Accept: 'application/json' }),
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (job) {
                if (applyConfirmBtn) applyConfirmBtn.disabled = false;
                if (!job) {
                    toastError();
                    return;
                }
                toastOk(i18n().toastApplyStarted || 'Apply started.');
                if (applyPanelEl) applyPanelEl.hidden = true;
                applyTargetId = null;
                loadJobs();
            })
            .catch(function () {
                if (applyConfirmBtn) applyConfirmBtn.disabled = false;
                toastError();
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
