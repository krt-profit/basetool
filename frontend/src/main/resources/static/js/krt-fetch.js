// @ts-check
(function () {
    'use strict';

    /**
     * Returns the caller-supplied localized string, or the page-wide default from
     * `window.krtFetchI18n`; a missing default renders as its key name via `krtI18nText`.
     *
     * @param {unknown} value the caller's already-localized string, if any
     * @param {string} fallbackValue the page-wide default the caller did not override
     * @param {string} key the default's name, `krtFetchI18n.<property>`
     * @returns {string} the string to show
     */
    function text(value, fallbackValue, key) {
        return typeof value === 'string' && value !== ''
            ? value
            : window.krtI18nText(fallbackValue, key);
    }

    /**
     * The page-wide krtFetch defaults, never null.
     *
     * @returns {KrtI18nDict} the dictionary fragments/head.html declares
     */
    function defaults() {
        return window.krtFetchI18n || {};
    }

    function metaContent(name) {
        const el = document.querySelector(`meta[name="${name}"]`);
        const content = el ? el.getAttribute('content') : null;
        return content && content !== 'undefined' ? content : null;
    }

    function setMetaContent(name, value) {
        let el = document.querySelector(`meta[name="${name}"]`);
        if (!el) {
            el = document.createElement('meta');
            el.setAttribute('name', name);
            document.head.appendChild(el);
        }
        el.setAttribute('content', value);
    }

    function csrfToken() {
        return metaContent('_csrf');
    }

    function csrfHeaderName() {
        return metaContent('_csrf_header');
    }

    /**
     * Builds the JSON-write headers (Accept, Content-Type, X-Requested-With) plus the CSRF header
     * read fresh from the meta tags; `base` is merged first, so the CSRF header always wins.
     */
    function csrfHeaders(base) {
        const headers = Object.assign(
            {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                'X-Requested-With': 'XMLHttpRequest',
            },
            base || {},
        );
        const token = csrfToken();
        const header = csrfHeaderName();
        if (token && header) {
            headers[header] = token;
        }
        return headers;
    }

    /** @type {Promise<any> | null} */
    let refreshInFlight = null;

    /**
     * Refetches the CSRF token from GET /csrf and writes it into the meta tags. Concurrent calls
     * share one request; resolves to { headerName, token }, or null when the refresh failed.
     */
    function refreshCsrf() {
        if (refreshInFlight) {
            return refreshInFlight;
        }
        refreshInFlight = fetch('/csrf', {
            headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        })
            .then((res) => {
                return res.ok ? res.json() : null;
            })
            .then((data) => {
                if (data && data.token && data.headerName) {
                    setMetaContent('_csrf', data.token);
                    setMetaContent('_csrf_header', data.headerName);
                    return data;
                }
                return null;
            })
            .catch(() => {
                return null;
            })
            .finally(() => {
                refreshInFlight = null;
            });
        return refreshInFlight;
    }

    window.krtCsrf = {
        token: csrfToken,
        headerName: csrfHeaderName,
        headers: csrfHeaders,
        refresh: refreshCsrf,
    };

    const REAUTH_GUARD_KEY = 'krtReauthAt';
    const REAUTH_MIN_INTERVAL_MS = 10000;
    const DEFAULT_REAUTH_PATH = '/oauth2/authorization/keycloak';

    function reauthRedirect(url) {
        const target = typeof url === 'string' && url.charAt(0) === '/' ? url : DEFAULT_REAUTH_PATH;
        try {
            const last = Number(window.sessionStorage.getItem(REAUTH_GUARD_KEY) || 0);
            const now = Date.now();
            if (now - last < REAUTH_MIN_INTERVAL_MS) {
                return false;
            }
            window.sessionStorage.setItem(REAUTH_GUARD_KEY, String(now));
        } catch (_storageUnavailable) {}
        window.location.assign(target);
        return true;
    }

    /**
     * If response is a 401 carrying the X-Reauthenticate header, redirects the browser to the
     * Keycloak login flow and returns true; otherwise returns false. Safe to call with any Response.
     */
    function maybeReauthenticate(response) {
        if (!response || response.status !== 401 || !response.headers) {
            return false;
        }
        const header =
            typeof response.headers.get === 'function'
                ? response.headers.get('X-Reauthenticate')
                : null;
        return header ? reauthRedirect(header) : false;
    }

    /**
     * If response carries the X-Terms-Acceptance-Required header, navigates to the consent page it
     * names and returns true; otherwise returns false. Safe to call with any Response (REQ-SEC-028).
     */
    function maybeTermsGate(response) {
        if (!response || !response.headers) {
            return false;
        }
        const target =
            typeof response.headers.get === 'function'
                ? response.headers.get('X-Terms-Acceptance-Required')
                : null;
        return target ? termsGateRedirect(target) : false;
    }

    /**
     * Navigates the window to the consent page, refusing anything that is not a same-origin absolute
     * path. Also used by the SSE `terms-gate` event and the /ws/sync 4003 close, which carry a bare
     * URL.
     *
     * @param {string | null | undefined} url the consent-page path the server named
     * @returns {boolean} true when the browser was sent to the consent page
     */
    function termsGateRedirect(url) {
        if (typeof url !== 'string' || url.charAt(0) !== '/') {
            return false;
        }
        window.location.assign(url);
        return true;
    }

    window.krtReauth = { redirect: reauthRedirect, check: maybeReauthenticate };
    window.krtTermsGate = { check: maybeTermsGate, redirect: termsGateRedirect };

    /**
     * Assembles the headers shared by {@link write} and {@link submitForm}: Accept,
     * X-Requested-With and the CSRF header read fresh from the meta tags. Content-Type is set only
     * for JSON, so a FormData body gets its multipart boundary from the browser.
     *
     * @param json whether the body is JSON (adds Content-Type) rather than FormData (omits it)
     * @param accept the Accept header value; defaults to application/json
     * @return a plain headers object for `fetch`
     */
    function writeHeaders(json, accept) {
        const headers = {
            Accept: accept || 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        };
        if (json) {
            headers['Content-Type'] = 'application/json';
        }
        const token = csrfToken();
        const header = csrfHeaderName();
        if (token && header) {
            headers[header] = token;
        }
        return headers;
    }

    /**
     * Writes newVersion to the container and every descendant carrying [data-version]. No-op when
     * newVersion is null or the container cannot be resolved.
     */
    function syncVersion(containerSelector, newVersion) {
        if (newVersion == null) {
            return;
        }
        const container =
            typeof containerSelector === 'string'
                ? document.querySelector(containerSelector)
                : containerSelector;
        if (!container) {
            return;
        }
        container.setAttribute('data-version', String(newVersion));
        container.querySelectorAll('[data-version]').forEach((el) => {
            el.setAttribute('data-version', String(newVersion));
        });
    }

    function errorToast(message) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message);
        }
    }

    function successToast(message) {
        if (typeof window.showFrontendSuccessToast === 'function') {
            window.showFrontendSuccessToast(message);
        }
    }

    /**
     * Logs a developer-facing, unlocalized console warning; a no-op when no console exists.
     * `detail` is passed only when defined.
     */
    function devWarn(message, detail) {
        if (typeof console === 'undefined' || typeof console.warn !== 'function') {
            return;
        }
        if (detail === undefined) {
            console.warn(message);
        } else {
            console.warn(message, detail);
        }
    }

    /** @type {Map<string, AbortController>} */
    const readAborters = new Map();

    /**
     * Combines the caller's signal with a supersede controller for `key`, aborting the read still
     * in flight under the same key.
     *
     * @param {KrtReadOpts} options the read options
     * @returns {{ signal: AbortSignal | undefined, controller: AbortController | null }} the signal
     *     to pass to fetch and the supersede controller to release afterwards
     */
    function readSignal(options) {
        /** @type {AbortSignal[]} */
        const signals = [];
        if (options.signal) {
            signals.push(options.signal);
        }
        /** @type {AbortController | null} */
        let controller = null;
        if (options.key) {
            const previous = readAborters.get(options.key);
            if (previous) {
                previous.abort();
            }
            controller = new AbortController();
            readAborters.set(options.key, controller);
            signals.push(controller.signal);
        }
        if (signals.length === 0) {
            return { signal: undefined, controller };
        }
        return { signal: signals.length === 1 ? signals[0] : AbortSignal.any(signals), controller };
    }

    /**
     * Sends a same-origin GET marked as background traffic and runs the answer past the
     * re-authentication and terms gates (REQ-FE-031).
     *
     * @param {string} url the same-origin URL to read
     * @param {KrtReadOpts} [opts] accept, extra headers, signal and supersede key
     * @returns {Promise<Response | null>} the response, ok or not; null when a gate navigated away
     *     or the answer came through a redirect, which for a background read is a login or consent
     *     page rather than the resource
     */
    async function get(url, opts) {
        const options = opts || {};
        /** @type {Record<string, string>} */
        const headers = Object.assign({}, options.headers);
        if (options.accept) {
            headers.Accept = options.accept;
        }
        headers['X-Requested-With'] = 'XMLHttpRequest';
        const { signal, controller } = readSignal(options);
        let response;
        try {
            response = await fetch(url, {
                method: 'GET',
                headers,
                credentials: 'same-origin',
                signal,
            });
        } finally {
            if (controller && options.key && readAborters.get(options.key) === controller) {
                readAborters.delete(options.key);
            }
        }
        if (maybeReauthenticate(response) || maybeTermsGate(response)) {
            return null;
        }
        if (response.redirected) {
            devWarn('krtFetch.get refused a redirected answer', { url, status: response.status });
            return null;
        }
        return response;
    }

    /**
     * Whether the response declares a JSON body (`application/json` or `application/problem+json`).
     *
     * @param {Response} response the response to inspect
     * @returns {boolean} true for a JSON content type
     */
    function isJsonResponse(response) {
        const contentType = (response.headers.get('Content-Type') || '').toLowerCase();
        return (
            contentType.includes('application/json') ||
            contentType.includes('application/problem+json')
        );
    }

    /**
     * Builds the rejection of {@link getJson}.
     *
     * @param {string} url the URL that was read
     * @param {number} status the HTTP status, 0 when there was no usable answer
     * @param {'refused' | 'status' | 'not-json'} reason why the answer was refused
     * @param {any} problem the parsed error body, or null
     * @returns {KrtReadError} the error to reject with
     */
    function readError(url, status, reason, problem) {
        const error = /** @type {KrtReadError} */ (
            new Error(`krtFetch.getJson ${reason} (${status}) for ${url}`)
        );
        error.name = 'KrtReadError';
        error.status = status;
        error.reason = reason;
        error.problem = problem;
        return error;
    }

    /**
     * Reads JSON through {@link get}.
     *
     * @param {string} url the same-origin URL to read
     * @param {KrtReadOpts} [opts] extra headers, signal and supersede key; Accept is always JSON
     * @returns {Promise<any>} the parsed body of a 2xx JSON answer, or null for a 204; rejects with
     *     a KrtReadError for a gated, redirected, non-2xx or non-JSON answer, and with the browser's
     *     own error on a transport failure or an abort
     */
    async function getJson(url, opts) {
        const response = await get(url, Object.assign({}, opts, { accept: 'application/json' }));
        if (!response) {
            throw readError(url, 0, 'refused', null);
        }
        if (!response.ok) {
            let problem = null;
            if (isJsonResponse(response)) {
                try {
                    problem = await response.json();
                } catch (_unparsable) {}
            }
            throw readError(url, response.status, 'status', problem);
        }
        if (response.status === 204) {
            return null;
        }
        if (!isJsonResponse(response)) {
            throw readError(url, response.status, 'not-json', null);
        }
        return response.json();
    }

    /** @type {Element | null} */
    let pendingSubmitter = null;
    document.addEventListener(
        'submit',
        (e) => {
            const form = /** @type {HTMLFormElement | null} */ (e.target);
            pendingSubmitter =
                /** @type {SubmitEvent} */ (e).submitter ||
                (form && form.querySelector
                    ? form.querySelector('button[type="submit"], input[type="submit"]')
                    : null);
            Promise.resolve().then(() => {
                pendingSubmitter = null;
            });
        },
        true,
    );

    function consumePendingSubmitter() {
        const s = pendingSubmitter;
        pendingSubmitter = null;
        return s;
    }

    function resolveSubmitter(opts) {
        if (opts.submitter == null) {
            opts.submitter = consumePendingSubmitter();
        }
        if (opts.submitter) {
            opts.submitter.disabled = true;
        }
    }

    const serialChains = new Map();
    function noop() {}
    function runSerialized(key, task) {
        if (key == null || key === '') {
            return Promise.resolve().then(task);
        }
        const prev = serialChains.get(key) || Promise.resolve();
        const result = prev.then(task, task);
        const tail = result.then(noop, noop);
        serialChains.set(key, tail);
        tail.then(() => {
            if (serialChains.get(key) === tail) {
                serialChains.delete(key);
            }
        });
        return result;
    }

    /**
     * Shows the error feedback for a non-ok response. A 409 with code OPTIMISTIC_LOCK or
     * PESSIMISTIC_LOCK also offers a reload; any other 409 only shows the problem detail.
     *
     * opts carries the localized strings: conflictSectionLabel (prefix), errorMessage (fallback)
     * and conflict.{title,reloadLabel,dismissLabel,reloadQuestion,reloadDetailFallback}.
     */
    async function handleProblem(response, problem, opts) {
        const options = opts || {};
        const prefix = options.conflictSectionLabel ? `${options.conflictSectionLabel}: ` : '';
        const genericError = text(
            options.errorMessage,
            defaults().saveFailed,
            'krtFetchI18n.saveFailed',
        );
        if (response.status === 409) {
            const code =
                problem && typeof problem === 'object' && problem.code
                    ? String(problem.code)
                    : null;
            const stale = code === 'OPTIMISTIC_LOCK' || code === 'PESSIMISTIC_LOCK';
            if (stale) {
                const conflict = options.conflict || {};
                const detail =
                    problem && problem.detail
                        ? problem.detail
                        : text(
                              conflict.reloadDetailFallback,
                              defaults().conflictDetail,
                              'krtFetchI18n.conflictDetail',
                          );
                errorToast(prefix + detail);
                if (typeof window.showKrtConfirm === 'function') {
                    const ok = await window.showKrtConfirm(
                        text(
                            conflict.title,
                            defaults().conflictTitle,
                            'krtFetchI18n.conflictTitle',
                        ),
                        text(
                            conflict.reloadQuestion,
                            defaults().conflictQuestion,
                            'krtFetchI18n.conflictQuestion',
                        ),
                        text(
                            conflict.reloadLabel,
                            defaults().conflictReload,
                            'krtFetchI18n.conflictReload',
                        ),
                        text(
                            conflict.dismissLabel,
                            defaults().conflictDismiss,
                            'krtFetchI18n.conflictDismiss',
                        ),
                    );
                    if (ok) {
                        window.location.reload();
                    }
                }
                return;
            }
            const domainDetail = problem && problem.detail ? problem.detail : genericError;
            errorToast(prefix + domainDetail);
            return;
        }
        const ownerRequired = ownerOrgUnitRequiredMessage(problem);
        if (ownerRequired) {
            errorToast(prefix + ownerRequired);
            return;
        }
        const generic = problem && problem.detail ? problem.detail : genericError;
        errorToast(prefix + generic);
    }

    /**
     * Returns the localized message for an OWNER_ORG_UNIT_REQUIRED problem from
     * `window.krtOwnerPickerI18n`, or null for any other problem (REQ-ORG-023).
     *
     * @param {any} problem the parsed RFC 7807 body, or null
     * @returns {string | null} the localized message, or null when this is not that failure
     */
    function ownerOrgUnitRequiredMessage(problem) {
        if (!problem || typeof problem !== 'object' || problem.code !== 'OWNER_ORG_UNIT_REQUIRED') {
            return null;
        }
        const i18n = window.krtOwnerPickerI18n;
        return window.krtI18nText(i18n && i18n.required, 'krtOwnerPickerI18n.required');
    }

    /**
     * Parses a write response: JSON / problem+json as an object, anything else as text. With
     * `responseType === 'blob'` a 2xx body is returned as a Blob instead (a generated PDF, …); an
     * error body is still parsed as above so the problem handling keeps working.
     *
     * @param {Response} response the fetch response
     * @param {string} [responseType] 'blob' to read a successful body as a Blob
     * @returns {Promise<any>} the parsed body, or null when it could not be read
     */
    async function parseBody(response, responseType) {
        const contentType = response.headers.get('Content-Type') || '';
        try {
            if (responseType === 'blob' && response.ok) {
                return await response.blob();
            }
            if (
                contentType.indexOf('application/json') >= 0 ||
                contentType.indexOf('application/problem+json') >= 0
            ) {
                return await response.json();
            }
            return await response.text();
        } catch (_ignored) {
            return null;
        }
    }

    /**
     * Sends a request built by `buildInit` to `url` and handles the response for {@link write} and
     * {@link submitForm}: submit guard, one CSRF refresh-and-retry on 403, reauth and terms gates,
     * error handling, version sync, success toast and onSuccess.
     *
     * opts (shared by write / submitForm):
     *  - containerSelector    container/selector for syncVersion on success
     *  - sectionLabel         localized success-toast prefix (optional)
     *  - successMessage       localized success text
     *  - toast                false suppresses the success toast
     *  - conflictSectionLabel localized error/conflict prefix (optional)
     *  - errorMessage         localized generic error text
     *  - conflict             localized conflict strings (see handleProblem)
     *  - onSuccess            callback(body) after a 2xx; a returned thenable is awaited
     *  - onError              callback(status, body, response) on a non-ok response before
     *                         handleProblem; truthy return skips the default handling
     *  - onNetworkError       callback(networkError) on a transport failure; truthy return
     *                         suppresses the default toast
     *  - submitter            button disabled while the request is in flight
     *  - accept               Accept header value (default application/json)
     *  - responseType         'blob' delivers a 2xx body to onSuccess as a Blob
     *
     * Returns { ok, status, body } (plus `redirected` on a 2xx).
     */
    async function send(opts, buildInit, url) {
        const submitter = opts.submitter || null;
        if (submitter) {
            submitter.disabled = true;
        }
        try {
            let response;
            try {
                response = await fetch(url, buildInit());
                if (response.status === 403) {
                    const refreshed = await refreshCsrf();
                    if (refreshed) {
                        response = await fetch(url, buildInit());
                    }
                }
            } catch (networkError) {
                devWarn('krtFetch network error', networkError);
                let handled = false;
                if (typeof opts.onNetworkError === 'function') {
                    try {
                        handled = opts.onNetworkError(networkError);
                    } catch (_callbackError) {}
                }
                if (!handled) {
                    errorToast(
                        text(opts.errorMessage, defaults().saveFailed, 'krtFetchI18n.saveFailed'),
                    );
                }
                return { ok: false, status: 0, body: null };
            }

            const body = await parseBody(response, opts.responseType);

            if (maybeReauthenticate(response)) {
                return { ok: false, status: response.status, body };
            }

            if (maybeTermsGate(response)) {
                return { ok: false, status: response.status, body };
            }

            if (!response.ok) {
                if (typeof opts.onError === 'function') {
                    let handled = false;
                    try {
                        handled = opts.onError(response.status, body, response);
                    } catch (_callbackError) {}
                    if (handled) {
                        return { ok: false, status: response.status, body };
                    }
                }
                await handleProblem(response, body, opts);
                return { ok: false, status: response.status, body };
            }

            if (opts.containerSelector && body && body.version != null) {
                syncVersion(opts.containerSelector, body.version);
            }
            if (opts.toast !== false) {
                const label = opts.sectionLabel ? `${opts.sectionLabel}: ` : '';
                successToast(
                    label + text(opts.successMessage, defaults().saved, 'krtFetchI18n.saved'),
                );
            }
            if (typeof opts.onSuccess === 'function') {
                try {
                    const outcome = opts.onSuccess(body);
                    if (outcome && typeof outcome.then === 'function') {
                        await outcome;
                    }
                } catch (_callbackError) {}
            }
            return {
                ok: true,
                status: response.status,
                body,
                redirected: !!response.redirected,
            };
        } finally {
            if (submitter) {
                submitter.disabled = false;
            }
        }
    }

    /**
     * Sends a write (PATCH/POST/PUT/DELETE) as JSON and handles the response via {@link send}.
     *
     * opts (in addition to the shared {@link send} opts):
     *  - method               HTTP method (default PATCH)
     *  - url                  target URL, OR a `() => url` thunk resolved at send time
     *  - payload              JSON payload (omitted for GET/DELETE), OR a `() => payload` thunk
     *  - bodyOnDelete         true also sends the payload with a DELETE (default false)
     *  - serialize            optional lock-scope key; writes sharing it run one at a time in order.
     *                         Pair it with thunk url/payload so a queued write reads its version
     *                         after the preceding write has settled.
     *
     * Returns { ok, status, body }.
     */
    async function write(opts) {
        const method = opts.method || 'PATCH';

        resolveSubmitter(opts);

        function exec() {
            const url = typeof opts.url === 'function' ? opts.url() : opts.url;
            const payload = typeof opts.payload === 'function' ? opts.payload() : opts.payload;
            function buildInit() {
                const headers = writeHeaders(true, opts.accept);
                const init = { method, headers };
                const sendsBody =
                    method !== 'GET' && (method !== 'DELETE' || opts.bodyOnDelete === true);
                if (payload !== undefined && sendsBody) {
                    init.body = JSON.stringify(payload);
                }
                return init;
            }
            return send(opts, buildInit, url);
        }

        return runSerialized(opts.serialize, exec);
    }

    /**
     * Submits a form body (FormData) and handles the response via {@link send}; the FormData twin of
     * {@link write}. No Content-Type is set, so the browser supplies the multipart boundary; the CSRF
     * token travels in a header.
     *
     * opts (in addition to the shared {@link send} opts):
     *  - form                 the <form> element or a selector; its action/method/FormData are used
     *  - url                  target URL (default: form's action attribute)
     *  - method               HTTP method (default: form's method attribute, else POST)
     *  - formData             explicit FormData (default: new FormData(form))
     *
     * Returns { ok, status, body }.
     */
    async function submitForm(opts) {
        const form = typeof opts.form === 'string' ? document.querySelector(opts.form) : opts.form;

        resolveSubmitter(opts);

        function exec() {
            const url =
                (typeof opts.url === 'function' ? opts.url() : opts.url) ||
                (form ? form.getAttribute('action') : null);
            const method = (
                opts.method ||
                (form ? form.getAttribute('method') : null) ||
                'POST'
            ).toUpperCase();

            function buildInit() {
                const headers = writeHeaders(false, opts.accept);
                const body =
                    opts.formData !== undefined
                        ? opts.formData
                        : form
                          ? new FormData(form)
                          : undefined;
                return { method, headers, body };
            }

            return send(opts, buildInit, url);
        }

        return runSerialized(opts.serialize, exec);
    }

    /**
     * Replaces the content of `el` with a server-rendered HTML fragment; the only sanctioned
     * innerHTML sink for unescaped markup. Pass only the text of a same-origin Thymeleaf fragment
     * response, never a string assembled from user or API data.
     *
     * @param {Element | null | undefined} el the container whose content is replaced; no-op when
     *     absent
     * @param {string | null | undefined} html the fragment markup; null / undefined clears `el`
     */
    function setTrustedHtml(el, html) {
        if (!el) {
            return;
        }
        // eslint-disable-next-line no-unsanitized/property
        el.innerHTML = html == null ? '' : String(html);
    }

    /**
     * Replaces `el` itself with a server-rendered fragment, under the same trust contract as
     * {@link setTrustedHtml}. The markup is parsed into an inert `<template>`, so no script runs.
     *
     * @param {Element | null | undefined} el the element to replace; no-op when absent or detached
     * @param {string | null | undefined} html the fragment markup; null / undefined removes `el`
     */
    function replaceWithTrustedHtml(el, html) {
        if (!el || !el.parentNode) {
            return;
        }
        const tpl = document.createElement('template');
        setTrustedHtml(tpl, html);
        el.replaceWith(tpl.content);
    }

    /**
     * Returns the same-origin path+query of url with the fragment query parameter set, so the
     * controller renders only the fragment.
     */
    function withFragmentParam(url, paramName, paramValue) {
        const resolved = new URL(url, window.location.origin);
        resolved.searchParams.set(paramName, paramValue);
        return `${resolved.pathname}?${resolved.searchParams.toString()}`;
    }

    /**
     * Returns the same-origin path+query of url without the fragment query parameter, as shown in
     * the address bar after a swap.
     */
    function withoutFragmentParam(url, paramName) {
        const resolved = new URL(url, window.location.origin);
        resolved.searchParams.delete(paramName);
        const query = resolved.searchParams.toString();
        return resolved.pathname + (query ? `?${query}` : '');
    }

    /**
     * Loads a server-rendered HTML fragment and swaps it into a container.
     *
     * opts:
     *  - url             source URL (fragment param is added if missing)
     *  - container       target element or selector
     *  - indicator       optional loading element/selector toggled during the fetch
     *  - fragmentParam   query param name (default "fragment")
     *  - fragmentValue   query param value (default "results")
     *  - history         when true, the address-bar URL (minus the fragment param) is updated via
     *                    history.replaceState
     *  - preserveScroll  unless false, the window scroll position is restored after the swap
     *  - errorMessage    optional localized error toast when the response is redirected or not OK;
     *                    the container is then left untouched
     *
     * Only a non-redirected 2xx body is injected. Afterwards, contained a.page-btn[href] and
     * a[data-swap][href] anchors re-swap in place. Resolves to true when the container was updated.
     */
    function swap(opts) {
        const container =
            typeof opts.container === 'string'
                ? document.querySelector(opts.container)
                : opts.container;
        if (!container) {
            return Promise.resolve(false);
        }
        const indicator =
            typeof opts.indicator === 'string'
                ? document.querySelector(opts.indicator)
                : opts.indicator;
        const paramName = opts.fragmentParam || 'fragment';
        const paramValue = opts.fragmentValue || 'results';
        const url = withFragmentParam(opts.url, paramName, paramValue);
        const scrollY = window.scrollY;

        const seq = (container._krtSwapSeq = (container._krtSwapSeq || 0) + 1);
        if (container._krtSwapAbort) {
            container._krtSwapAbort.abort();
        }
        const aborter = typeof AbortController === 'function' ? new AbortController() : null;
        container._krtSwapAbort = aborter;
        function isCurrent() {
            return container._krtSwapSeq === seq;
        }
        function hideIndicatorIfCurrent() {
            if (indicator && isCurrent()) {
                indicator.style.display = 'none';
            }
        }
        function releaseAborter() {
            if (container._krtSwapAbort === aborter) {
                container._krtSwapAbort = null;
            }
        }

        if (indicator) {
            indicator.style.display = 'block';
        }
        return get(url, { signal: aborter ? aborter.signal : undefined })
            .then((res) => {
                if (!res) {
                    return null;
                }
                if (!res.ok) {
                    devWarn('krtFetch.swap bailed: response is not a fragment', {
                        url,
                        status: res.status,
                        redirected: res.redirected,
                    });
                    return null;
                }
                return res.text();
            })
            .then((html) => {
                if (!isCurrent()) {
                    return false;
                }
                hideIndicatorIfCurrent();
                releaseAborter();
                if (html === null) {
                    if (opts.errorMessage) {
                        errorToast(opts.errorMessage);
                    }
                    devWarn('krtFetch.swap did not update the container', {
                        url,
                        container: opts.container,
                        toasted: !!opts.errorMessage,
                    });
                    return false;
                }
                setTrustedHtml(container, html);
                bindSwapAnchorInterception(container, opts);
                document.dispatchEvent(new CustomEvent('krt:swapped', { detail: { container } }));
                if (opts.history) {
                    window.history.replaceState(
                        window.history.state,
                        '',
                        withoutFragmentParam(opts.url, paramName),
                    );
                }
                if (opts.preserveScroll !== false) {
                    window.scrollTo(0, scrollY);
                }
                return true;
            })
            .catch((error) => {
                hideIndicatorIfCurrent();
                releaseAborter();
                const superseded = (error && error.name === 'AbortError') || !isCurrent();
                if (!superseded) {
                    devWarn('krtFetch.swap transport failure', { url, error });
                }
                return false;
            });
    }

    function bindSwapAnchorInterception(container, opts) {
        if (container._krtSwapBound) {
            return;
        }
        container._krtSwapBound = true;
        container.addEventListener('click', (event) => {
            const anchor = event.target.closest('a.page-btn[href], a[data-swap][href]');
            if (!anchor || !container.contains(anchor)) {
                return;
            }
            if (anchor.classList.contains('disabled')) {
                event.preventDefault();
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            swap(Object.assign({}, opts, { url: anchor.getAttribute('href') }));
        });
    }

    /**
     * Binds the in-container pagination/sort anchor interception without an initial fetch; a click
     * on a contained a.page-btn[href] / a[data-swap][href] re-swaps with the given opts.
     */
    function bindSwap(opts) {
        const container =
            typeof opts.container === 'string'
                ? document.querySelector(opts.container)
                : opts.container;
        if (!container) {
            return;
        }
        bindSwapAnchorInterception(container, opts);
    }

    /**
     * Builds a page's section-write seam: the { write, refresh, notify } trio for pages whose
     * aggregate is saved and re-rendered as independent sections. The dict, pageUrl and broadcast
     * callbacks are re-evaluated on every call.
     *
     * config:
     *  - dict()               getter for the page's localized i18n dictionary
     *  - dictName             the dictionary's global name, used in the key a missing string is
     *                         reported as (`NAME[key]`)
     *  - keys                 dictionary keys: saveSectionPrefix, conflictSectionPrefix, successKey,
     *                         errorKey, conflictTitleKey, reloadLabelKey, dismissLabelKey,
     *                         reloadQuestionKey, reloadDetailKey, refreshErrorKey; an omitted key
     *                         leaves write()'s krtFetchI18n default in place
     *  - sections             sectionKey -> { container, fragmentValue } map for refresh()
     *  - pageUrl()            getter for the page's base URL; null makes refresh() resolve false
     *  - broadcast(keys)      optional peer notification (REQ-FE-010); called by refresh() unless
     *                         opts.broadcast === false, and always by notify()
     *
     * Returns:
     *  - write(opts)          {@link write} with the localized strings derived from opts.sectionKey
     *  - refresh(sectionKeys, opts)  re-renders one or more sections via {@link swap}; resolves when
     *                         all swaps complete
     *  - notify(sectionKeys)  broadcasts without re-rendering
     */
    function sectionWrite(config) {
        return {
            write(opts) {
                const dict = config.dict() || {};
                /**
                 * The page dictionary's string for `key`, visibly reported when missing; undefined
                 * for a key the config does not name, so write() applies its own default.
                 *
                 * @param {string | undefined} key the dictionary key
                 * @returns {string | undefined} the localized string
                 */
                function t(key) {
                    if (!key) return undefined;
                    return window.krtI18nText(dict[key], `${config.dictName || 'dict'}[${key}]`);
                }
                const key = opts.sectionKey || '';
                const k = config.keys;
                return write(
                    Object.assign({}, opts, {
                        serialize: opts.serialize || (key ? `section:${key}` : undefined),
                        sectionLabel: k.saveSectionPrefix
                            ? t(k.saveSectionPrefix + key)
                            : undefined,
                        conflictSectionLabel: k.conflictSectionPrefix
                            ? t(k.conflictSectionPrefix + key)
                            : undefined,
                        successMessage: t(k.successKey),
                        errorMessage: t(k.errorKey),
                        conflict: {
                            title: t(k.conflictTitleKey),
                            reloadLabel: t(k.reloadLabelKey),
                            dismissLabel: t(k.dismissLabelKey),
                            reloadQuestion: t(k.reloadQuestionKey),
                            reloadDetailFallback: t(k.reloadDetailKey),
                        },
                    }),
                );
            },
            refresh(sectionKeys, opts) {
                const list = Array.isArray(sectionKeys) ? sectionKeys : [sectionKeys];
                if ((!opts || opts.broadcast !== false) && typeof config.broadcast === 'function') {
                    config.broadcast(list);
                }
                return Promise.all(
                    list.map((sectionKey) => {
                        const cfg = config.sections[sectionKey];
                        const url = cfg ? config.pageUrl() : null;
                        if (!cfg || !url || !document.querySelector(cfg.container)) {
                            return Promise.resolve(false);
                        }
                        return swap({
                            url,
                            container: cfg.container,
                            fragmentValue: cfg.fragmentValue,
                            history: false,
                            preserveScroll: true,
                            errorMessage: (config.dict() || {})[config.keys.refreshErrorKey] || '',
                        });
                    }),
                );
            },
            notify(sectionKeys) {
                const list = Array.isArray(sectionKeys) ? sectionKeys : [sectionKeys];
                if (typeof config.broadcast === 'function') {
                    config.broadcast(list);
                }
            },
        };
    }

    window.krtFetch = {
        get,
        getJson,
        write,
        submitForm,
        swap,
        bindSwap,
        setTrustedHtml,
        replaceWithTrustedHtml,
        syncVersion,
        handleProblem,
        ownerOrgUnitRequiredMessage,
        maybeReauthenticate,
        reauthRedirect,
        sectionWrite,
        serialize: runSerialized,
        csrf: window.krtCsrf,
    };
})();
