(function () {
    'use strict';

    const ENDPOINT = '/internal/client-error';

    const KIND_SCRIPT_ERROR = 'script_error';
    const KIND_UNHANDLED_REJECTION = 'unhandled_rejection';
    const KIND_RESOURCE_ERROR = 'resource_error';
    const KIND_CSP_VIOLATION = 'csp_violation';
    const KIND_I18N_MISSING = 'i18n_missing';

    const MAX_FIELD_LENGTH = 200;

    const BUDGET_KEY = 'krtClientErrorBudget';
    const BUDGET_CAPACITY = 5;
    const BUDGET_REFILL_MS = 60000;

    let memoryBudget = null;

    let sending = false;

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
        } catch (_unavailable) {}
        return memoryBudget;
    }

    function writeBudget(budget) {
        memoryBudget = budget;
        try {
            window.sessionStorage.setItem(BUDGET_KEY, JSON.stringify(budget));
        } catch (_unavailable) {}
    }

    /**
     * Refills the bucket by whole elapsed intervals and consumes one token; returns false, dropping
     * the report, when the bucket is empty.
     */
    function takeToken() {
        const at = Date.now();
        const budget = readBudget() || { tokens: BUDGET_CAPACITY, at };
        const gained = Math.floor(Math.max(0, at - budget.at) / BUDGET_REFILL_MS);
        const tokens = Math.min(BUDGET_CAPACITY, budget.tokens + gained);
        const anchor = tokens >= BUDGET_CAPACITY ? at : budget.at + gained * BUDGET_REFILL_MS;
        if (tokens < 1) {
            writeBudget({ tokens, at: anchor });
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
     * Strips the query string and fragment from a script URL, then caps its length.
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
     * Reduces a CSP violation's blockedURI to its origin; a data: or blob: URL becomes its scheme
     * and a non-URL keyword such as 'inline' or 'eval' passes through bare.
     */
    function blockedOrigin(value) {
        if (value === null || value === undefined || value === '') {
            return null;
        }
        const s = String(value);
        try {
            const url = new URL(s);
            if (url.origin && url.origin !== 'null') {
                return field(url.origin);
            }
            return field(url.protocol.replace(/:$/, ''));
        } catch (_notAUrl) {
            return field(s.split(/[:/?#\s]/)[0]);
        }
    }

    /**
     * Reads a meta tag's content, or null when it is absent or the string 'undefined'.
     */
    function metaContent(name) {
        const el = document.querySelector('meta[name="' + name + '"]');
        const content = el ? el.getAttribute('content') : null;
        return content && content !== 'undefined' ? content : null;
    }

    /**
     * POSTs one payload best-effort, swallowing every failure. Returns false without sending when
     * the CSRF meta tags are not readable, true otherwise.
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
                headers,
                credentials: 'same-origin',
                redirect: 'manual',
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
     * Retries the queued reports once after parsing, clearing the queue first and stopping at the
     * first payload that is still undeliverable.
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
            kind,
            message: field(message),
            source: scriptUrl(source),
            line: number(line),
            column: number(column),
        };
        if (deliver(payload)) {
            return;
        }
        if (document.readyState !== 'loading' || pending.length >= PENDING_CAPACITY) {
            return;
        }
        pending.push(payload);
        if (!flushArmed) {
            flushArmed = true;
            document.addEventListener('DOMContentLoaded', flushPending, { once: true });
        }
    }

    window.addEventListener(
        'error',
        function (event) {
            try {
                const target = event ? event.target : null;
                if (target && target !== window && target.tagName) {
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
            } catch (_reportFailed) {}
        },
        true,
    );

    document.addEventListener('securitypolicyviolation', function (event) {
        try {
            report(
                KIND_CSP_VIOLATION,
                event ? event.effectiveDirective || event.violatedDirective : null,
                event ? blockedOrigin(event.blockedURI) : null,
                null,
                null,
            );
        } catch (_reportFailed) {}
    });

    window.addEventListener('unhandledrejection', function (event) {
        try {
            const reason = event ? event.reason : null;
            let message = null;
            if (reason && typeof reason === 'object' && typeof reason.message === 'string') {
                message = reason.message;
            } else if (typeof reason === 'string') {
                message = reason;
            }
            report(KIND_UNHANDLED_REJECTION, message, null, null, null);
        } catch (_reportFailed) {}
    });

    const reportedI18nKeys = new Set();

    /**
     * Returns a non-empty localized string as is; otherwise reports the key once per page view as an
     * `i18n_missing` client error and returns the key name.
     *
     * @param {unknown} value the localized string the page provided, if any
     * @param {string} key the string's identifier: `DICTIONARY.property` or `data-attribute`
     * @returns {string} the localized string, or the key name when it is missing
     */
    function i18nText(value, key) {
        if (typeof value === 'string' && value !== '') {
            return value;
        }
        const name = String(key);
        if (!reportedI18nKeys.has(name)) {
            reportedI18nKeys.add(name);
            try {
                report(KIND_I18N_MISSING, name, null, null, null);
            } catch (_reportFailed) {}
        }
        return name;
    }

    window.krtI18nText = i18nText;
})();
