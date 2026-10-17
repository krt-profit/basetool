(function () {
    'use strict';

    const ENDPOINT = '/internal/client-error';

    const KIND_SCRIPT_ERROR = 'script_error';
    const KIND_UNHANDLED_REJECTION = 'unhandled_rejection';
    const KIND_RESOURCE_ERROR = 'resource_error';
    const KIND_CSP_VIOLATION = 'csp_violation';
    const KIND_I18N_MISSING = 'i18n_missing';

    const MAX_FIELD_LENGTH = 200;
    const MAX_POLICY_NAME_LENGTH = 40;

    const CODE_ORIGIN_SELF = 'self';
    const CODE_ORIGIN_EXTENSION = 'extension';
    const CODE_ORIGIN_INLINE = 'inline';
    const CODE_ORIGIN_OTHER = 'other';
    const EXTENSION_SCHEMES = [
        'chrome-extension:',
        'moz-extension:',
        'safari-web-extension:',
        'webkit-masked-url:',
    ];

    const BUDGET_KEY = 'krtClientErrorBudget';
    const BUDGET_CAPACITY = 5;
    const BUDGET_REFILL_MS = 60000;

    /**
     * @typedef {object} ClientErrorPayload
     * @property {string} kind the report kind
     * @property {string | null} message the capped message
     * @property {string | null} source the script URL or blocked origin
     * @property {number | null} line the line number
     * @property {number | null} column the column number
     * @property {string | null} codeOrigin where a CSP violation's code came from, or null
     */

    /**
     * @typedef {object} Budget
     * @property {number} tokens the remaining tokens
     * @property {number} at the timestamp the refill interval is measured from
     */

    /** @type {Budget | null} */
    let memoryBudget = null;

    let sending = false;

    const PENDING_CAPACITY = 5;
    /** @type {ClientErrorPayload[]} */
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

    /**
     * Persists the bucket to the in-memory fallback and, when available, sessionStorage.
     *
     * @param {Budget} budget the bucket to store
     */
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

    /**
     * Coerces any value to a length-capped string, or null when there is nothing to report.
     *
     * @param {unknown} value the value to coerce
     * @returns {string | null} the capped string, or null
     */
    function field(value) {
        if (value === null || value === undefined || value === '') {
            return null;
        }
        const s = String(value);
        return s.length > MAX_FIELD_LENGTH ? s.slice(0, MAX_FIELD_LENGTH) : s;
    }

    /**
     * Coerces a line/column to a finite integer, or null. Never a string — the server types it.
     *
     * @param {unknown} value the line or column
     * @returns {number | null} the integer, or null
     */
    function number(value) {
        return typeof value === 'number' && isFinite(value) ? Math.trunc(value) : null;
    }

    /**
     * Strips the query string and fragment from a script URL, then caps its length.
     *
     * @param {unknown} value the script URL
     * @returns {string | null} the stripped, capped URL, or null
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
     *
     * @param {unknown} value the blockedURI
     * @returns {string | null} the origin, scheme or keyword, or null
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
     *
     * @param {string} name the meta tag name
     * @returns {string | null} the content, or null
     */
    function metaContent(name) {
        const el = document.querySelector(`meta[name="${name}"]`);
        const content = el ? el.getAttribute('content') : null;
        return content && content !== 'undefined' ? content : null;
    }

    /**
     * POSTs one payload best-effort, swallowing every failure. Returns false without sending when
     * the CSRF meta tags are not readable, true otherwise.
     *
     * @param {ClientErrorPayload} payload the report to send
     * @returns {boolean} whether the report was handed to fetch
     */
    function deliver(payload) {
        const token = metaContent('_csrf');
        const header = metaContent('_csrf_header');
        if (!token || !header) {
            return false;
        }
        /** @type {Record<string, string>} */
        const headers = {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        };
        headers[header] = token;
        const settled = function () {
            sending = false;
        };
        try {
            /** @type {RequestInit} */
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

    /**
     * Rate-limits, builds the payload, and either delivers or briefly queues it.
     *
     * @param {string} kind the report kind
     * @param {unknown} message the message to cap
     * @param {unknown} source the script URL or blocked origin
     * @param {unknown} line the line number
     * @param {unknown} column the column number
     * @param {string | null} [codeOrigin] one of the `CODE_ORIGIN_*` values for a CSP violation
     */
    function report(kind, message, source, line, column, codeOrigin = null) {
        if (sending || !takeToken()) {
            return;
        }
        const payload = {
            kind,
            message: field(message),
            source: scriptUrl(source),
            line: number(line),
            column: number(column),
            codeOrigin,
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
        (event) => {
            try {
                const target =
                    /** @type {{ tagName?: string, src?: string, href?: string } | null} */ (
                        event ? event.target : null
                    );
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

    /**
     * Reduces a Trusted Types policy name to its first 40 characters, each outside letters, digits
     * and `_ . # = @ -` replaced by `?`.
     *
     * @param {string} value the attempted policy name
     * @returns {string} the reduced name, empty when there was none
     */
    function policyName(value) {
        return value
            .trim()
            .slice(0, MAX_POLICY_NAME_LENGTH)
            .replace(/[^A-Za-z0-9_.#=@-]/g, '?');
    }

    /**
     * The violated directive; for a Trusted Types sink violation also the sink the browser names in
     * front of the sample's `|`, for a policy violation the attempted policy name, never markup.
     *
     * @param {SecurityPolicyViolationEvent} event the violation event
     * @returns {string} the directive, with the sink or policy name appended where applicable
     */
    function violationMessage(event) {
        const directive = event.effectiveDirective || event.violatedDirective;
        if (typeof event.sample !== 'string') {
            return directive;
        }
        if (directive === 'require-trusted-types-for') {
            const sink = event.sample.split('|')[0].trim();
            return sink ? `${directive} ${sink}` : directive;
        }
        if (directive === 'trusted-types') {
            const name = policyName(event.sample);
            return name ? `${directive} ${name}` : directive;
        }
        return directive;
    }

    /**
     * Classifies where a violation's code came from by its `sourceFile`: the page's own origin, a
     * browser extension, no source file at all, or anything else. Never the URL itself.
     *
     * @param {unknown} value the event's sourceFile
     * @returns {string} one of the `CODE_ORIGIN_*` values
     */
    function codeOriginOf(value) {
        if (value === null || value === undefined || value === '') {
            return CODE_ORIGIN_INLINE;
        }
        const s = String(value).toLowerCase();
        if (EXTENSION_SCHEMES.some((scheme) => s.startsWith(scheme))) {
            return CODE_ORIGIN_EXTENSION;
        }
        try {
            return new URL(s).origin === window.location.origin
                ? CODE_ORIGIN_SELF
                : CODE_ORIGIN_OTHER;
        } catch (_notAUrl) {
            return CODE_ORIGIN_OTHER;
        }
    }

    document.addEventListener('securitypolicyviolation', (event) => {
        try {
            report(
                KIND_CSP_VIOLATION,
                event ? violationMessage(event) : null,
                event ? blockedOrigin(event.blockedURI) : null,
                null,
                null,
                event ? codeOriginOf(event.sourceFile) : null,
            );
        } catch (_reportFailed) {}
    });

    window.addEventListener('unhandledrejection', (event) => {
        try {
            const reason = event ? event.reason : null;
            /** @type {string | null} */
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
