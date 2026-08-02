/*
 * krt-client-error.js — the browser half of the client-error beacon (audit finding M12).
 *
 * Before this file the app had NO global JavaScript error handler: the only two
 * addEventListener('error', …) registrations in the whole static/js tree were per-socket and
 * per-EventSource, no template installed one, and the CSP is enforcing with no report-uri. A
 * runtime TypeError inside a delegated click handler therefore produced literally no signal on
 * any surface we own — no fetch, no access-log line, no http.server.requests sample, no counter.
 * "The button did nothing" was, and could only ever be, a user report.
 *
 * Be honest about what this does and does not add: `:frontend:lintJs` and the Playwright suite
 * already catch the "a JS syntax error shipped to prod" half — a file that does not parse fails
 * the gate or blows up every e2e flow. What neither catches is the half this covers: a handler
 * that parses fine and throws at RUNTIME, on one browser, on one data shape, most often against a
 * DOM that a krtFetch fragment swap just replaced (a stale node reference, an element the new
 * fragment no longer renders, a null from a querySelector that used to match). That failure is
 * invisible to the server today and stays invisible to lint tomorrow.
 *
 * Design constraints, all of them load-bearing:
 *  - Loaded FIRST and WITHOUT `defer` (see fragments/head.html). A handler installed after the
 *    scripts it is meant to watch cannot observe their failures, and `defer` would install it
 *    after the entire head has been parsed and executed.
 *  - No new global. The module registers two window listeners and exposes nothing.
 *  - Per-session token bucket. The reporter is self-triggerable — an error inside a rAF/scroll/
 *    input handler repeats at frame rate — so the budget is capped and, crucially, PERSISTED in
 *    sessionStorage: a page that throws during load and a user who hammers F5 must not reset it.
 *  - The payload carries ONLY { message, source, line, column, kind }, each truncated here and
 *    re-truncated + sanitised server-side. Never a stack trace, never document.title, never DOM
 *    content, never form values, never location.search — those are the fields that turn a
 *    diagnostic into a data-exfiltration channel, and the server would refuse them anyway.
 *  - The `kind` values below are mirrored by ClientErrorReportController's server-side allowlist
 *    (a beacon kind outside it contributes no metric series at all); ClientErrorReportControllerTest
 *    pins the two halves against each other so they cannot drift.
 */
(function () {
    'use strict';

    // Mirrored by ClientErrorReportController.PATH.
    const ENDPOINT = '/internal/client-error';

    // Mirrored by MetricNames.CLIENT_ERROR_* / ClientErrorReportController's ALLOWED_KINDS.
    const KIND_SCRIPT_ERROR = 'script_error';
    const KIND_UNHANDLED_REJECTION = 'unhandled_rejection';
    const KIND_RESOURCE_ERROR = 'resource_error';

    // Client-side cap per free-text field. The server truncates again — this one only keeps the
    // request small; the server's is the one that is actually a guarantee.
    const MAX_FIELD_LENGTH = 200;

    // Token bucket: BUDGET_CAPACITY reports up front, then one more per BUDGET_REFILL_MS. Enough
    // to catch a burst of distinct failures on one page view, far too little to be a flood vector.
    const BUDGET_KEY = 'krtClientErrorBudget';
    const BUDGET_CAPACITY = 5;
    const BUDGET_REFILL_MS = 60000;

    // Fallback budget for browsers where sessionStorage is blocked (private mode, policy). It
    // bounds this page view; a reload then legitimately starts over, which is the best a tab
    // without storage can do.
    let memoryBudget = null;

    // Set while a report is in flight, so a storm of identical errors during the round-trip
    // collapses into the one report already on its way instead of queueing N of them.
    let sending = false;

    /**
     * Reads the persisted bucket, preferring sessionStorage over the in-memory fallback so a
     * reload of a page that throws on load cannot refill the budget.
     */
    function readBudget() {
        try {
            const raw = window.sessionStorage.getItem(BUDGET_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed.tokens === 'number' && typeof parsed.at === 'number') {
                    return parsed;
                }
            }
        } catch (_unavailable) {
            /* storage blocked, or holding garbage someone else wrote: use the memory fallback */
        }
        return memoryBudget;
    }

    function writeBudget(budget) {
        memoryBudget = budget;
        try {
            window.sessionStorage.setItem(BUDGET_KEY, JSON.stringify(budget));
        } catch (_unavailable) {
            /* storage blocked: the in-memory budget still bounds this page view */
        }
    }

    /**
     * Consumes one token, refilling first. Returns false when the bucket is empty — the report is
     * then dropped silently, which is the correct outcome: the first few reports of a repeating
     * failure carry all the information the later thousands would.
     *
     * The refill anchor advances by whole refill intervals rather than jumping to `now`, so a
     * sub-interval call cannot reset the clock and stall the refill forever. A backwards clock
     * change yields a clamped elapsed of 0 rather than a negative refill.
     */
    function takeToken() {
        const at = Date.now();
        const budget = readBudget() || { tokens: BUDGET_CAPACITY, at: at };
        const gained = Math.floor(Math.max(0, at - budget.at) / BUDGET_REFILL_MS);
        const tokens = Math.min(BUDGET_CAPACITY, budget.tokens + gained);
        const anchor = tokens >= BUDGET_CAPACITY ? at : budget.at + gained * BUDGET_REFILL_MS;
        if (tokens < 1) {
            writeBudget({ tokens: tokens, at: anchor });
            return false;
        }
        writeBudget({ tokens: tokens - 1, at: anchor });
        return true;
    }

    /** Coerces any value to a length-capped string, or null when there is nothing to report. */
    function field(value) {
        if (value === null || value === undefined || value === '') {
            return null;
        }
        const s = String(value);
        return s.length > MAX_FIELD_LENGTH ? s.slice(0, MAX_FIELD_LENGTH) : s;
    }

    /** Coerces a line/column to a finite integer, or null. Never a string — the server types it. */
    function number(value) {
        return typeof value === 'number' && isFinite(value) ? Math.trunc(value) : null;
    }

    /**
     * Drops the query string and fragment from a script URL BEFORE truncating, so a cache-busting
     * or session-bearing parameter never leaves the browser. The server strips them again — this
     * is the belt, that is the braces.
     */
    function scriptUrl(value) {
        if (value === null || value === undefined || value === '') {
            return null;
        }
        const s = String(value);
        let cut = s.length;
        const query = s.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        const hash = s.indexOf('#');
        if (hash >= 0 && hash < cut) {
            cut = hash;
        }
        return field(s.slice(0, cut));
    }

    /**
     * Reads a meta tag's content. Deliberately duplicated from krt-fetch.js's private helper of
     * the same name instead of calling window.krtCsrf: this module loads BEFORE krt-fetch.js
     * precisely so it can observe a failure in it, and a beacon that depends on the module it is
     * watching is useless in the one case that matters.
     */
    function metaContent(name) {
        const el = document.querySelector('meta[name="' + name + '"]');
        const content = el ? el.getAttribute('content') : null;
        return content && content !== 'undefined' ? content : null;
    }

    /**
     * POSTs one report, best-effort. Every failure mode is swallowed: a beacon that surfaces its
     * own errors would feed itself.
     */
    function report(kind, message, source, line, column) {
        if (sending || !takeToken()) {
            return;
        }
        const token = metaContent('_csrf');
        const header = metaContent('_csrf_header');
        if (!token || !header) {
            // No CSRF token on this render means no usable session to report against; the endpoint
            // would answer with the login redirect anyway.
            return;
        }
        const headers = {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        };
        headers[header] = token;
        const settled = function () {
            sending = false;
        };
        try {
            const init = {
                method: 'POST',
                headers: headers,
                credentials: 'same-origin',
                // A dead or anonymous session answers with a 302 to the OIDC entry point.
                // Following it cross-origin would trip the CSP `default-src 'self'` and turn this
                // diagnostic into a second, louder console error; 'manual' stops at the redirect
                // and reports nothing.
                redirect: 'manual',
                // Survive an unload: the interesting errors are the ones that fire while the user
                // is already navigating away in frustration.
                keepalive: true,
                body: JSON.stringify({
                    kind: kind,
                    message: field(message),
                    source: scriptUrl(source),
                    line: number(line),
                    column: number(column),
                }),
            };
            sending = true;
            window.fetch(ENDPOINT, init).then(settled, settled);
        } catch (_beaconFailed) {
            settled();
        }
    }

    // Capturing, because a subresource that fails to load fires a NON-bubbling `error` event on
    // the element itself and would never reach a bubble-phase window listener.
    window.addEventListener(
        'error',
        function (event) {
            try {
                const target = event ? event.target : null;
                if (target && target !== window && target.tagName) {
                    // A script / stylesheet / image that did not load. Reported as its own kind so
                    // a broken or half-rolled-out asset deploy is distinguishable from application
                    // code that threw — the two need completely different responses. Only the tag
                    // name (a fixed HTML vocabulary) and the asset URL travel; no DOM content.
                    report(
                        KIND_RESOURCE_ERROR,
                        target.tagName,
                        target.src || target.href,
                        null,
                        null,
                    );
                    return;
                }
                report(
                    KIND_SCRIPT_ERROR,
                    event ? event.message : null,
                    event ? event.filename : null,
                    event ? event.lineno : null,
                    event ? event.colno : null,
                );
            } catch (_reportFailed) {
                /* one broken handler must never become two */
            }
        },
        true,
    );

    // The shape a failed promise chain takes — including a krtFetch write or swap whose caller
    // forgot to catch, which is exactly the M11 class of silent failure.
    window.addEventListener('unhandledrejection', function (event) {
        try {
            const reason = event ? event.reason : null;
            let message = null;
            if (reason && typeof reason === 'object' && typeof reason.message === 'string') {
                // The message ONLY. `reason.stack` is deliberately never read: a stack exposes the
                // app's internal structure and can carry inlined values.
                message = reason.message;
            } else if (typeof reason === 'string') {
                message = reason;
            }
            report(KIND_UNHANDLED_REJECTION, message, null, null, null);
        } catch (_reportFailed) {
            /* one broken handler must never become two */
        }
    });
})();
