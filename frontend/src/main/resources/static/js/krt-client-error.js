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
 *
 * COVERAGE HOLES. Both are consequences of being a synchronous, CSRF-gated beacon inside the
 * document it watches. A reader must not mistake "no client-error report" for "nothing broke":
 *
 *  1. IT CANNOT SEE A FAILURE OF ANYTHING THE HEAD LOADS ABOVE IT. In fragments/head.html the
 *     favicon, the Lato-Regular preload and — the one that actually matters — the styles.css
 *     <link> are emitted BEFORE this script. A pending stylesheet blocks execution of the next
 *     synchronous script until its load settles, so if styles.css 404s, its non-bubbling `error`
 *     event has already fired and been discarded by the time this file installs its capturing
 *     listener. The same holds for any per-page `additionalLinks` further down: those parse after
 *     this script, so they ARE covered, but the head's own three assets are not. This is a
 *     deliberate trade, not an oversight — hoisting the script above the stylesheet would delay
 *     CSS discovery and first paint for every page view, to gain a signal for a failure mode that
 *     is already loud (an unstyled app is self-evident to the user, and a broken asset deploy is
 *     what the blackbox probes and the asset pipeline exist for). The failure this beacon exists
 *     for — a handler that parses and then throws at runtime — cannot occur that early.
 *
 *  2. A REPORT STILL DIES WHEN THE RENDER CARRIES NO CSRF META TAGS — but no longer blindly. The
 *     tags are `th:if="${_csrf != null}"`, and the POST endpoint is session + CSRF gated, so
 *     without them there is nothing to send that would be accepted. Two cases used to be
 *     conflated by a single unconditional `return`: (a) the tags do not exist on this render at
 *     all (an anonymous error page) — genuinely undeliverable, still dropped; and (b) the tags
 *     exist but the parser has not reached them yet, which an early resource error can outrun if
 *     the head is ever reordered so the beacon precedes them. Case (b) is now mitigated cheaply:
 *     up to PENDING_CAPACITY such reports are held and retried once at DOMContentLoaded, when the
 *     document is fully parsed and the answer is final. The queue is bounded twice over (by that
 *     cap and by the token bucket, which a queued report has already paid), only ever fills while
 *     `document.readyState === 'loading'`, and is never re-armed — it cannot become a memory or
 *     flood vector. What is NOT mitigated, and cannot be from here, is case (a): a report from a
 *     page that has no session is dropped in the browser rather than sent and rejected.
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

    // Reports that could not be delivered because the `_csrf` meta tags were not parsed yet (see
    // coverage hole 2 in the header). Held only while the document is still parsing and retried
    // once at DOMContentLoaded. Bounded by PENDING_CAPACITY on top of the token bucket, which a
    // queued report has already paid — the queue can therefore never outgrow one page load.
    const PENDING_CAPACITY = 5;
    let pending = [];
    let flushArmed = false;

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
     * POSTs one already-built payload, best-effort. Every failure mode is swallowed: a beacon that
     * surfaces its own errors would feed itself.
     *
     * Returns false, having sent nothing and consumed nothing further, when the CSRF meta tags are
     * not readable — the caller decides between queueing that payload and dropping it. Any other
     * outcome (including a rejected fetch) counts as delivered: the report left the browser.
     */
    function deliver(payload) {
        const token = metaContent('_csrf');
        const header = metaContent('_csrf_header');
        if (!token || !header) {
            return false;
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
                body: JSON.stringify(payload),
            };
            sending = true;
            window.fetch(ENDPOINT, init).then(settled, settled);
        } catch (_beaconFailed) {
            settled();
        }
        return true;
    }

    /**
     * Retries the reports that outran the `_csrf` meta tags, once, after the document has finished
     * parsing. The queue is cleared first so a failure here cannot leave it re-armed, and the loop
     * stops at the first payload that is still undeliverable: the tags are absent from the whole
     * parsed document, which means this render has no session to report against and every later
     * payload would fail identically.
     */
    function flushPending() {
        const queued = pending;
        pending = [];
        for (let i = 0; i < queued.length; i++) {
            if (!deliver(queued[i])) {
                return;
            }
        }
    }

    /** Rate-limits, builds the payload, and either delivers or briefly queues it. */
    function report(kind, message, source, line, column) {
        if (sending || !takeToken()) {
            return;
        }
        const payload = {
            kind: kind,
            message: field(message),
            source: scriptUrl(source),
            line: number(line),
            column: number(column),
        };
        if (deliver(payload)) {
            return;
        }
        // No usable CSRF token. While the document is still parsing that may only mean the meta
        // tags are further down, so hold a bounded number of reports for one retry at
        // DOMContentLoaded. Once parsing is done the answer is final — this render carries no
        // token, the endpoint would answer with the login redirect, so drop.
        if (document.readyState !== 'loading' || pending.length >= PENDING_CAPACITY) {
            return;
        }
        pending.push(payload);
        if (!flushArmed) {
            flushArmed = true;
            document.addEventListener('DOMContentLoaded', flushPending, { once: true });
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
