// @ts-check
/*
 * krt-fetch.js — the single client-side seam for frontend write requests and
 * AJAX fragment swaps (epic #571, spec REQ-FE-001..005).
 *
 * Generalizes the former mission-subresource.js (window.MissionSubresource)
 * into a mission-agnostic toolbox loaded globally from fragments/head.html, so
 * every page shares ONE implementation of:
 *
 *  - CSRF header construction (window.krtCsrf) — reads the freshest
 *    meta[name="_csrf"] / meta[name="_csrf_header"] tags, the single source of
 *    truth. Replaces the ~30 hand-rolled readers in 6 syntactic variants.
 *  - retry-on-403 — a bare 403 means the CSRF token was rejected (stale tab,
 *    post-re-login session-id rotation, maximumSessions eviction). krtFetch
 *    transparently refetches the token from GET /csrf, updates the meta tags,
 *    and retries the write exactly once before surfacing the error.
 *  - write (JSON) / submitForm (multipart FormData) — the two write entry
 *    points share ONE request orchestration (send): CSRF header, bare-403
 *    refresh-and-retry-once, X-Reauthenticate redirect, guest-token replay,
 *    error/conflict handling, syncVersion and success toast. submitForm lets a
 *    page drop its hand-rolled CSRF+retry FormData loop (S10, #916); it omits
 *    Content-Type so the browser sets the multipart boundary itself.
 *  - JSON / application/problem+json parsing and RFC7807 409 branching
 *    (OPTIMISTIC_LOCK / PESSIMISTIC_LOCK -> reload-confirm; domain conflict
 *    codes -> toast only), carried over verbatim from mission-subresource.js.
 *  - syncVersion — the canonical @Version propagator: on success the fresh
 *    version is written to the container AND every descendant [data-version]
 *    so the user's next action does not 409 (see the concurrency rules in
 *    CLAUDE.md).
 *  - swap — server-rendered HTML fragment swaps for lists / filters /
 *    pagination, including delegated interception of in-container pagination
 *    anchors so paging stays in-place (fixes the known full-reload regression).
 *  - sectionWrite — a factory for pages whose aggregate is saved and
 *    re-rendered as independent sections (#924, mission-detail): builds the
 *    page's { write, refresh, notify } trio around write/swap from a
 *    page-supplied config (i18n dict getter, section->container map, page-URL
 *    getter and peer-broadcast closure, each re-evaluated per call).
 *
 * No user-visible string is hardcoded here: callers pass already-localized
 * labels/messages (e.g. mission-detail's page-local krtMissionWrite wrapper
 * sources them from window.MISSION_SUBRES_I18N). The few inline fallbacks only
 * ever surface if a caller forgets to pass a message AND its i18n dictionary is
 * missing — a developer error, not a user-facing path.
 */
(function () {
    'use strict';

    /**
     * Returns value when it is a non-empty string, otherwise the fallback. Used
     * so a missing/blank localized message degrades to a neutral default instead
     * of rendering "undefined".
     */
    function text(value, fallback) {
        return value != null && value !== '' ? value : fallback;
    }

    // ---------------------------------------------------------------- krtCsrf

    function metaContent(name) {
        const el = document.querySelector('meta[name="' + name + '"]');
        const content = el ? el.getAttribute('content') : null;
        return content && content !== 'undefined' ? content : null;
    }

    function setMetaContent(name, value) {
        let el = document.querySelector('meta[name="' + name + '"]');
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
     * Builds the request headers for a JSON write: Accept + Content-Type +
     * X-Requested-With, plus the CSRF header read fresh from the meta tags. Any
     * base headers passed in are merged first (the CSRF header always wins).
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

    // De-duplicates concurrent refreshes: parallel writes that all 403 share one
    // in-flight GET /csrf instead of stampeding the endpoint.
    let refreshInFlight = null;

    /**
     * Refetches the CSRF token from GET /csrf, writes it back into the meta tags
     * (the single source of truth every subsequent krtCsrf.headers() call reads),
     * and resolves to { headerName, token } — or null if the refresh failed (e.g.
     * the session is gone and the endpoint 403s/redirects).
     */
    function refreshCsrf() {
        if (refreshInFlight) {
            return refreshInFlight;
        }
        refreshInFlight = fetch('/csrf', {
            headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        })
            .then(function (res) {
                return res.ok ? res.json() : null;
            })
            .then(function (data) {
                if (data && data.token && data.headerName) {
                    setMetaContent('_csrf', data.token);
                    setMetaContent('_csrf_header', data.headerName);
                    return data;
                }
                return null;
            })
            .catch(function () {
                return null;
            })
            .finally(function () {
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

    // ------------------------------------------------------------- re-auth

    // When the frontend OAuth2 session loses its usable token (Keycloak refresh-token rotation
    // revoked the family, the session idled out, ...) the backend relay fails with
    // client_authorization_required. The server answers HTML navigations with a 302 to the Keycloak
    // login flow (browser follows it automatically) and AJAX callers with a 401 carrying the
    // `X-Reauthenticate: <path>` header. This helper redirects the whole window to that path so the
    // user silently re-authenticates against the still-alive Keycloak SSO session instead of being
    // stranded on a dead session (REQ-SEC-012). A short sessionStorage guard prevents a redirect
    // loop if the fresh session were to be revoked again immediately.
    const REAUTH_GUARD_KEY = 'krtReauthAt';
    const REAUTH_MIN_INTERVAL_MS = 10000;
    const DEFAULT_REAUTH_PATH = '/oauth2/authorization/keycloak';

    function reauthRedirect(url) {
        // Only ever follow a same-origin absolute path — never an attacker-controllable absolute URL
        // (open-redirect guard), even though the value originates from our own server.
        const target = typeof url === 'string' && url.charAt(0) === '/' ? url : DEFAULT_REAUTH_PATH;
        try {
            const last = Number(window.sessionStorage.getItem(REAUTH_GUARD_KEY) || 0);
            const now = Date.now();
            if (now - last < REAUTH_MIN_INTERVAL_MS) {
                return false;
            }
            window.sessionStorage.setItem(REAUTH_GUARD_KEY, String(now));
        } catch (_storageUnavailable) {
            /* sessionStorage may be blocked; proceed without the loop guard */
        }
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

    window.krtReauth = { redirect: reauthRedirect, check: maybeReauthenticate };

    // ----------------------------------------------- guest edit token (M1)

    // Security audit M1 / REQ-SEC-018: an anonymous guest who signs up for a mission receives a
    // per-row capability token in the create response. We persist it in localStorage keyed by the
    // participant id and replay it as the X-Guest-Edit-Token header on later edit/withdraw of THAT
    // row, so the guest can self-manage their own sign-up without a login while a third party (who
    // only saw the public roster) cannot. The token is intentionally lost when the user clears site
    // data — an anonymous caller has no durable server-verifiable identity, so a cleared token falls
    // back to "a mission manager edits it", never to "anyone can edit it".
    const GUEST_TOKEN_PREFIX = 'krtGuestParticipantToken:';
    const GUEST_TOKEN_HEADER = 'X-Guest-Edit-Token';
    // Matches the participant id segment of a frontend participant write URL
    // (/missions/{missionId}/participants/{participantId}/...). The create URL
    // (.../participants/ajax) has no id segment, so no token is attached to it.
    const PARTICIPANT_URL_RE = /\/participants\/([0-9a-fA-F-]{36})(?:\/|$|\?)/;

    function storeGuestToken(participantId, token) {
        if (!participantId || !token) {
            return;
        }
        try {
            window.localStorage.setItem(GUEST_TOKEN_PREFIX + participantId, token);
        } catch (_unavailable) {
            /* localStorage blocked (private mode / policy): self-edit then falls back to staff */
        }
    }

    function readGuestToken(participantId) {
        if (!participantId) {
            return null;
        }
        try {
            return window.localStorage.getItem(GUEST_TOKEN_PREFIX + participantId);
        } catch (_unavailable) {
            return null;
        }
    }

    function guestTokenForUrl(url) {
        if (typeof url !== 'string') {
            return null;
        }
        const m = PARTICIPANT_URL_RE.exec(url);
        return m ? readGuestToken(m[1]) : null;
    }

    /**
     * Assembles the request headers shared by every krtFetch write — the JSON {@link write} and the
     * multipart {@link submitForm} alike: {@code Accept}, {@code X-Requested-With}, the CSRF header
     * read fresh from the meta tags, and (for a per-row participant write) the REQ-SEC-018 guest edit
     * token replayed from local storage. A {@code Content-Type: application/json} is added only when
     * {@code json} is true; it is deliberately omitted for a {@code FormData} body so the browser sets
     * the {@code multipart/form-data} boundary itself. Centralising this here keeps the CSRF + guest
     * token assembly in one place so the two write paths cannot drift.
     *
     * @param url  the target URL, used to look up a matching per-row guest edit token
     * @param json whether the body is JSON (adds Content-Type) rather than FormData (omits it)
     * @return a plain headers object ready to hand to {@code fetch}
     */
    function writeHeaders(url, json) {
        const headers = { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
        if (json) {
            headers['Content-Type'] = 'application/json';
        }
        const token = csrfToken();
        const header = csrfHeaderName();
        if (token && header) {
            headers[header] = token;
        }
        // M1 / REQ-SEC-018: replay the per-row guest edit token (if we hold one for this participant)
        // so an anonymous guest can edit/withdraw their own sign-up. The frontend relays the header to
        // the backend, which verifies it against the stored hash.
        const guestToken = guestTokenForUrl(url);
        if (guestToken) {
            headers[GUEST_TOKEN_HEADER] = guestToken;
        }
        return headers;
    }

    // Captures any guest edit token returned by a write response (the create response of a guest
    // sign-up) into the store. The body is either a single participant object or the slim
    // participant list; only the freshly created guest row carries a non-null guestEditToken.
    function captureGuestTokens(body) {
        const items = Array.isArray(body) ? body : [body];
        items.forEach(function (it) {
            if (it && typeof it === 'object' && it.guestEditToken && it.id) {
                storeGuestToken(it.id, it.guestEditToken);
            }
        });
    }

    window.krtGuestToken = { store: storeGuestToken, read: readGuestToken };

    // --------------------------------------------------------------- helpers

    /**
     * Writes newVersion to the container and to every descendant carrying a
     * [data-version] attribute so the next AJAX action on the same aggregate
     * sends the fresh version. No-op when newVersion is null or the container
     * cannot be resolved.
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
        container.querySelectorAll('[data-version]').forEach(function (el) {
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
     * Developer-facing console diagnostic — never localized, never shown to the user. Several
     * krtFetch paths fail without touching the DOM at all (see swap()'s bail branches), so for the
     * 43 of 49 `.swap({…})` call sites that pass no errorMessage this is the ONLY trace a "the
     * button did nothing / the list did not update" report leaves behind. Guarded on `console`
     * because embedded webviews may not provide one, and a diagnostic must never become the
     * failure it is reporting. `detail` is optional: it is omitted from the call rather than
     * passed as undefined, so the console line stays readable.
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

    // Double-submit guard (app-wide): record the button that triggered the most recent form submit
    // — capture phase, so it runs before the form's own preventDefault handler — so write() /
    // submitForm() can disable it for the in-flight request without every call site threading it
    // through. A microtask clears it right after the synchronous submit handler runs, and
    // write()/submitForm() consume+disable it SYNCHRONOUSLY on first use (see resolveSubmitter), so
    // an unrelated later write never inherits a stale submitter. Raw-fetch writes that do not go
    // through write() guard their submit button explicitly instead.
    let pendingSubmitter = null;
    document.addEventListener(
        'submit',
        function (e) {
            const form = /** @type {HTMLFormElement | null} */ (e.target);
            pendingSubmitter =
                /** @type {SubmitEvent} */ (e).submitter ||
                (form && form.querySelector
                    ? form.querySelector('button[type="submit"], input[type="submit"]')
                    : null);
            Promise.resolve().then(function () {
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

    // Resolve + disable the double-submit button SYNCHRONOUSLY. Called at the very top of write() and
    // submitForm(), while the submit-event dispatch that captured pendingSubmitter is still on the
    // stack — BEFORE runSerialized() defers exec() and BEFORE the capture listener's clearing
    // microtask runs. This synchronous disable is the whole #1133 fix: it is what actually stops the
    // browser from firing a second submit for the now-disabled button. The pre-fix code consumed the
    // submitter inside the deferred send(), which since #970's serialization always ran AFTER the
    // FIFO clearing microtask — so consumePendingSubmitter() there returned null, the button was
    // never disabled, and the app-wide guard its own comments promised was dead code. An explicit
    // opts.submitter (raw-fetch call sites that thread their own button) is honoured as-is; otherwise
    // the auto-captured button is adopted. send() re-enables opts.submitter in its finally on every
    // settle path, so a queued or in-flight write releases the button when it finishes.
    function resolveSubmitter(opts) {
        if (opts.submitter == null) {
            opts.submitter = consumePendingSubmitter();
        }
        if (opts.submitter) {
            opts.submitter.disabled = true;
        }
    }

    // ---------------------------------------------- per-key write serialization
    //
    // Root fix for the tool-wide "self-collision" 409: a user types into an inline field and then
    // immediately clicks +/a dropdown/reorder on the SAME section. The blur-triggered `change` write
    // and the click write used to fire concurrently, each echoing the section version read at call
    // time, so the second lost the optimistic-lock race against the first and 409'd — and "Aktuelle
    // Werte laden" then reloaded, discarding the just-typed row. The user was colliding with their
    // own sequential edits.
    //
    // A write may now declare `opts.serialize` — a lock-scope key. Writes sharing a key run STRICTLY
    // ONE AT A TIME in submission order. Combined with two other properties this removes the stale
    // version entirely:
    //   1. write()/submitForm() resolve `opts.url` / `opts.payload` LAZILY (a value OR a `() =>`
    //      thunk) inside the serialized task, so a queued write reads its version at the moment it is
    //      actually sent — not when it was queued.
    //   2. send() awaits a thenable `opts.onSuccess` (typically the caller's fragment refresh, which
    //      rewrites the `data-*-version` holder), so the NEXT queued write re-reads the FRESH, bumped
    //      version.
    // Distinct keys keep running concurrently, so disjoint sections never block each other — the
    // REQ-ORG-018 fine-grained-lock invariant is preserved (a Ziele edit still cannot stall an
    // Ablauf / core / schedule edit).
    const serialChains = new Map();
    function noop() {}
    function runSerialized(key, task) {
        if (key == null || key === '') {
            // No lock scope: keep the historical fire-when-called behaviour (still async).
            return Promise.resolve().then(task);
        }
        const prev = serialChains.get(key) || Promise.resolve();
        // Run `task` once `prev` SETTLES (fulfilled OR rejected) — a failed write must never stall
        // the writes queued behind it. The caller still receives task's own result/rejection.
        const result = prev.then(task, task);
        const tail = result.then(noop, noop);
        serialChains.set(key, tail);
        // Drop the map entry once the chain drains so one-shot section keys do not leak settled
        // promises. A later write that chained onto this tail overwrites the entry first, so the
        // guard only deletes when this is still the current tail.
        tail.then(function () {
            if (serialChains.get(key) === tail) {
                serialChains.delete(key);
            }
        });
        return result;
    }

    /**
     * Renders the KRT-styled feedback for a non-ok response.
     *
     * On 409 the RFC 7807 `code` extension decides the UX:
     *  - OPTIMISTIC_LOCK / PESSIMISTIC_LOCK -> the user's view is stale; show the
     *    error toast and offer a reload via showKrtConfirm.
     *  - any other code (DUPLICATE_ENTITY, BUSINESS_CONFLICT, ...) -> a domain
     *    rule refused the operation; the input is fine, so just show why (no
     *    reload prompt).
     *
     * opts carries the already-localized strings: conflictSectionLabel (error
     * prefix), errorMessage (generic fallback), and conflict.{title,reloadLabel,
     * dismissLabel,reloadQuestion,reloadDetailFallback}.
     */
    async function handleProblem(response, problem, opts) {
        const options = opts || {};
        const prefix = options.conflictSectionLabel ? options.conflictSectionLabel + ': ' : '';
        const genericError = text(options.errorMessage, 'Speichern fehlgeschlagen.');
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
                        : text(conflict.reloadDetailFallback, 'Bitte Seite neu laden.');
                errorToast(prefix + detail);
                if (typeof window.showKrtConfirm === 'function') {
                    const ok = await window.showKrtConfirm(
                        text(conflict.title, 'Konflikt'),
                        text(conflict.reloadQuestion, 'Aktuelle Werte laden?'),
                        text(conflict.reloadLabel, 'Aktuelle Werte laden'),
                        text(conflict.dismissLabel, 'Schliessen'),
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
        const generic = problem && problem.detail ? problem.detail : genericError;
        errorToast(prefix + generic);
    }

    async function parseBody(response) {
        const contentType = response.headers.get('Content-Type') || '';
        try {
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
     * Shared request orchestration behind both {@link write} (JSON) and {@link submitForm}
     * (multipart FormData): the double-submit guard, the bare-403 CSRF-refresh-and-retry-once,
     * network-error toast, RFC7807 parse, X-Reauthenticate redirect, error/conflict handling,
     * guest-token capture, syncVersion, success toast and onSuccess callback. The only thing that
     * differs between a JSON write and a form submit is how the request init (headers + body) is
     * built, so each caller passes its own {@code buildInit} thunk and the target {@code url}; every
     * response-side behaviour is identical and defined here exactly once.
     *
     * opts (shared by write / submitForm):
     *  - containerSelector    DOM container/selector for syncVersion on success
     *  - sectionLabel         already-localized success-toast prefix (optional)
     *  - successMessage       already-localized success text (default "Gespeichert.")
     *  - toast                set false to suppress the success toast
     *  - conflictSectionLabel already-localized error/conflict prefix (optional)
     *  - errorMessage         already-localized generic error text
     *  - conflict             localized conflict strings (see handleProblem)
     *  - onSuccess            callback(body) run after a 2xx; if it returns a thenable it is AWAITED
     *                         before the write resolves, so a serialized chain waits for the caller's
     *                         fragment refresh (which rewrites the version holder) to finish
     *  - onError              optional callback(status, body, response) run on a non-ok, non-reauth
     *                         response BEFORE the default handleProblem; return a truthy value to
     *                         signal "handled" (e.g. rendering 422 field-validation errors) and skip
     *                         the default toast/conflict handling
     *  - onNetworkError       optional callback(networkError) run when the request fails at the
     *                         transport layer (no response ever arrived, so onError never fires);
     *                         return truthy to signal it surfaced its own error UI and suppress the
     *                         default network-error toast
     *  - submitter            optional submit button disabled for the in-flight request and
     *                         re-enabled when it settles (double-submit guard)
     *
     * Returns { ok, status, body }. On a bare 403 the CSRF token is refreshed from GET /csrf and the
     * request retried exactly once before failing.
     */
    async function send(opts, buildInit, url) {
        // In-flight double-submit guard: opts.submitter was resolved + disabled SYNCHRONOUSLY by
        // resolveSubmitter() in write()/submitForm() (the #1133 fix); here we only keep it disabled
        // for the whole round-trip and re-enable it in the finally below on every
        // success/error/network path, so a double-click cannot fire a second (duplicate-create /
        // stale-version) write. Consuming it here instead — as the code did before #1133 — always
        // lost the microtask race to the capture listener's clear, leaving the button enabled.
        const submitter = opts.submitter || null;
        if (submitter) {
            submitter.disabled = true;
        }
        try {
            let response;
            try {
                response = await fetch(url, buildInit());
                // A bare 403 is the CSRF filter rejecting a stale token (it answers
                // before GlobalExceptionHandler, so the body is not problem+json).
                // Refresh the token once and retry; a genuine authorization failure
                // either redirects (302) or 403s again after the refetch and surfaces.
                if (response.status === 403) {
                    const refreshed = await refreshCsrf();
                    if (refreshed) {
                        response = await fetch(url, buildInit());
                    }
                }
            } catch (networkError) {
                // Transport-layer failure (offline, DNS/TLS, aborted): the fetch promise rejected
                // before any response arrived. Keep the raw browser diagnostic in the console for
                // debugging, but never leak the untranslated message into the user-facing toast
                // (i18n). The optional onNetworkError hook lets a caller restore optimistic UI (e.g.
                // re-enable a store button) that the response-side onError never sees on this path;
                // returning truthy signals it already surfaced its own error UI and suppresses the
                // default network-error toast.
                devWarn('krtFetch network error', networkError);
                let handled = false;
                if (typeof opts.onNetworkError === 'function') {
                    try {
                        handled = opts.onNetworkError(networkError);
                    } catch (_callbackError) {
                        /* a network-error callback must never break the UX */
                    }
                }
                if (!handled) {
                    errorToast(text(opts.errorMessage, 'Speichern fehlgeschlagen.'));
                }
                return { ok: false, status: 0, body: null };
            }

            const body = await parseBody(response);

            // A 401 with X-Reauthenticate means the session lost its OAuth2 token: redirect the
            // window to re-login instead of toasting an error the user cannot act on.
            if (maybeReauthenticate(response)) {
                return { ok: false, status: response.status, body: body };
            }

            if (!response.ok) {
                // Optional caller hook (e.g. 422 field-validation rendering): if it handles the
                // response it returns truthy and we skip the default toast/conflict handling.
                if (typeof opts.onError === 'function') {
                    let handled = false;
                    try {
                        handled = opts.onError(response.status, body, response);
                    } catch (_callbackError) {
                        /* an error callback must never break the UX */
                    }
                    if (handled) {
                        return { ok: false, status: response.status, body: body };
                    }
                }
                await handleProblem(response, body, opts);
                return { ok: false, status: response.status, body: body };
            }

            // M1 / REQ-SEC-018: capture a per-row guest edit token returned by a guest sign-up
            // create response so a later edit/withdraw of that row authenticates as the creator.
            captureGuestTokens(body);

            if (opts.containerSelector && body && body.version != null) {
                syncVersion(opts.containerSelector, body.version);
            }
            if (opts.toast !== false) {
                const label = opts.sectionLabel ? opts.sectionLabel + ': ' : '';
                successToast(label + text(opts.successMessage, 'Gespeichert.'));
            }
            if (typeof opts.onSuccess === 'function') {
                try {
                    // Await a thenable onSuccess so a serialized write does not resolve — and the
                    // next queued same-key write does not start — until the caller's fragment
                    // refresh has rewritten the data-*-version holder the next write will re-read
                    // (see runSerialized). A synchronous onSuccess is unaffected.
                    const outcome = opts.onSuccess(body);
                    if (outcome && typeof outcome.then === 'function') {
                        await outcome;
                    }
                } catch (_callbackError) {
                    /* a success callback must never break the UX */
                }
            }
            return { ok: true, status: response.status, body: body };
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
     *  - serialize            optional lock-scope key; writes sharing it run one at a time in order
     *                         (see runSerialized). Pair it with thunk url/payload so a queued write
     *                         re-reads its optimistic-lock version AFTER the preceding same-key write
     *                         refreshed the version holder — this is the fix for the self-collision
     *                         409 where a user's own back-to-back edits shipped a stale version.
     *
     * Returns { ok, status, body }.
     */
    async function write(opts) {
        const method = opts.method || 'PATCH';

        // Disable the double-submit button NOW, synchronously — before runSerialized defers exec()
        // and before the capture listener's clearing microtask runs (resolveSubmitter / #1133).
        resolveSubmitter(opts);

        // Resolve url + payload lazily inside the serialized task so a queued write reads them — and
        // any version they embed — at the moment it is actually sent, not when it was queued.
        function exec() {
            const url = typeof opts.url === 'function' ? opts.url() : opts.url;
            const payload = typeof opts.payload === 'function' ? opts.payload() : opts.payload;
            function buildInit() {
                const headers = writeHeaders(url, true);
                const init = { method: method, headers: headers };
                if (payload !== undefined && method !== 'GET' && method !== 'DELETE') {
                    init.body = JSON.stringify(payload);
                }
                return init;
            }
            return send(opts, buildInit, url);
        }

        return runSerialized(opts.serialize, exec);
    }

    /**
     * Submits a multipart/form-data (or urlencoded) form write and handles the response via {@link
     * send} — the FormData twin of {@link write} that lets a page drop its hand-rolled CSRF-header +
     * retry-on-403 loop (S10, #916). It inherits every response-side behaviour from {@link send},
     * including the bare-403 CSRF refresh-and-retry, the X-Reauthenticate redirect (REQ-SEC-012) and
     * the per-row guest edit token replay (REQ-SEC-018), so a migrated site is a net security
     * improvement over its bespoke loop.
     *
     * <p><b>Content-Type is deliberately NOT set.</b> When the body is a {@code FormData} the browser
     * must set {@code multipart/form-data} together with the boundary parameter itself; a manual
     * Content-Type would omit the boundary and corrupt the parse. So it asks {@link writeHeaders} for
     * the shared CSRF + guest-token header set with {@code json=false}, which omits the
     * {@code Content-Type} that {@link write} forces to {@code application/json}. The CSRF token rides
     * in the header, never in the form body.
     *
     * <p><b>No-JS fallback.</b> submitForm never runs unless {@code window.krtFetch} loaded, so a
     * script-disabled browser keeps the form's native {@code th:action}/{@code method=post} submit —
     * the migrated call site must still guard its listener with {@code if (!window.krtFetch) return;}
     * (before {@code preventDefault}) so the native redirect handler stays the fallback.
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

        // Disable the double-submit button NOW, synchronously — before runSerialized defers exec()
        // and before the capture listener's clearing microtask runs (resolveSubmitter / #1133).
        resolveSubmitter(opts);

        // Resolve url + snapshot the FormData inside the serialized task so a queued form submit
        // captures the form's hidden version input AFTER the preceding same-key write refreshed it.
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
                const headers = writeHeaders(url, false);
                const body =
                    opts.formData !== undefined
                        ? opts.formData
                        : form
                          ? new FormData(form)
                          : undefined;
                return { method: method, headers: headers, body: body };
            }

            return send(opts, buildInit, url);
        }

        return runSerialized(opts.serialize, exec);
    }

    // ------------------------------------------------------------ fragment swap

    /**
     * Ensures the fragment query parameter (default fragment=results) is present
     * on url so the controller returns the results fragment rather than the full
     * page. Resolves relative URLs against the current origin and returns a
     * same-origin path+query string.
     */
    function withFragmentParam(url, paramName, paramValue) {
        const resolved = new URL(url, window.location.origin);
        resolved.searchParams.set(paramName, paramValue);
        return resolved.pathname + '?' + resolved.searchParams.toString();
    }

    /**
     * Strips the internal fragment query parameter and returns the user-facing
     * same-origin path+query — the URL shown in the address bar after a swap, so a
     * refresh or a copied deep-link re-renders the same filtered / paged state
     * server-side.
     */
    function withoutFragmentParam(url, paramName) {
        const resolved = new URL(url, window.location.origin);
        resolved.searchParams.delete(paramName);
        const query = resolved.searchParams.toString();
        return resolved.pathname + (query ? '?' + query : '');
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
     *  - history         when true, the address-bar URL is kept in sync via
     *                    history.replaceState (minus the internal fragment param)
     *                    so a refresh / deep-link re-renders the same state. We use
     *                    replaceState, not pushState, so a debounced filter does not
     *                    flood the back-stack with intermediate keystrokes.
     *  - preserveScroll  unless false, the window scroll position is restored after
     *                    the swap so paging/filtering does not jump the page.
     *  - errorMessage    optional, already-localized string shown as an error toast when
     *                    the swap bails because the response was redirected or not OK (a
     *                    whole-document body the swap must not inject); omit it to fail
     *                    silently. The stale container is left untouched on this path.
     *
     * The swap injects the body only on a non-redirected 2xx response; a redirected or
     * non-OK response (expired-session login bounce, error-handler redirect, 5xx) is
     * treated as a whole-page body and skipped, so a fragment swap can never paint a
     * login form or a nested page into the small results container.
     *
     * After the swap, a single delegated click listener is installed on the
     * container so in-container pagination/sort anchors (a.page-btn[href] and
     * any opted-in a[data-swap][href]) re-swap in place instead of navigating —
     * fixing the regression where pagination inside an AJAX results container
     * triggered a full page reload.
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

        // Per-container in-flight sequencing (#1151, REQ-FE-013): several independent triggers overlap swaps on
        // ONE container — the local write's onSuccess refresh, a peer's coalesced live-sync refresh,
        // the reconnect resync burst, a debounced filter, a create/delete reload. Under the load-
        // induced latency variance that caused the outage (150 ms vs multi-second), an OLDER request
        // can resolve LAST and overwrite a newer render with a staler DB snapshot — regressing the
        // rows' data-version attributes and re-arming the "stale version -> 409 on next click"
        // landmine, and (with history:true) leaving the address bar on whichever response landed
        // last. Each swap claims the next sequence number for its container; when it resolves it only
        // touches the DOM / history / krt:swapped / indicator if it is STILL the latest swap. The
        // superseded in-flight request is aborted so a slow older read stops wasting a backend
        // round-trip. The write-side runSerialized() orders writes, never these read-side swaps.
        const seq = (container._krtSwapSeq = (container._krtSwapSeq || 0) + 1);
        if (container._krtSwapAbort) {
            container._krtSwapAbort.abort();
        }
        const aborter = typeof AbortController === 'function' ? new AbortController() : null;
        container._krtSwapAbort = aborter;
        function isCurrent() {
            return container._krtSwapSeq === seq;
        }
        // Only the latest swap owns the indicator: a superseded response must not hide it while a
        // newer swap is still in flight, so hide it strictly when we are still current.
        function hideIndicatorIfCurrent() {
            if (indicator && isCurrent()) {
                indicator.style.display = 'none';
            }
        }
        // Release our aborter slot once we settle, unless a newer swap has already claimed it.
        function releaseAborter() {
            if (container._krtSwapAbort === aborter) {
                container._krtSwapAbort = null;
            }
        }

        if (indicator) {
            indicator.style.display = 'block';
        }
        return fetch(url, {
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
            signal: aborter ? aborter.signal : undefined,
        })
            .then(function (res) {
                // Session lost its OAuth2 token: redirect to re-login rather than painting an
                // error/empty fragment (REQ-SEC-012).
                if (maybeReauthenticate(res)) {
                    return null;
                }
                // A fragment swap must only ever paint section-sized HTML into a small
                // container. If the request was redirected (an expired session bounced to
                // the login page, or a controller error handler answered with a redirect)
                // or the status is not OK, the body is a whole document, not a fragment —
                // injecting it would dump a login form or a nested page into the results
                // container. Bail without touching the DOM (#574 review must-fix).
                if (res.redirected || !res.ok) {
                    // M11: bailing here is correct, but it used to be completely invisible — no
                    // toast (unless the caller passed errorMessage), no DOM change, no console
                    // line. The section simply stopped updating. Record WHY, so a support session
                    // can tell an expired-session login bounce (redirected) from a 5xx fragment
                    // render (status) without reproducing the failure.
                    devWarn('krtFetch.swap bailed: response is not a fragment', {
                        url: url,
                        status: res.status,
                        redirected: res.redirected,
                    });
                    return null;
                }
                return res.text();
            })
            .then(function (html) {
                // A newer swap superseded this one while it was in flight: drop the response
                // wholesale (no innerHTML, no history, no krt:swapped, no indicator toggle) so the
                // latest render wins and the older snapshot never clobbers it (#1151).
                if (!isCurrent()) {
                    return false;
                }
                hideIndicatorIfCurrent();
                releaseAborter();
                if (html === null) {
                    // Optional caller-supplied (already-localized) toast; krt-fetch.js never
                    // hardcodes user-visible strings. The stale container is left as-is.
                    if (opts.errorMessage) {
                        errorToast(opts.errorMessage);
                    }
                    // M11: this is the branch the USER experiences — the swap finished and the
                    // container still shows the pre-swap render. Only 6 of the 49 `.swap({…})`
                    // call sites pass an errorMessage, so for the other 43 nothing at all is said.
                    // `toasted` records which of the two it was, so the console line distinguishes
                    // "the user was told and ignored it" from "the UI lied by omission".
                    devWarn('krtFetch.swap did not update the container', {
                        url: url,
                        container: opts.container,
                        toasted: !!opts.errorMessage,
                    });
                    return false;
                }
                container.innerHTML = html;
                bindSwapAnchorInterception(container, opts);
                // Let page/global enhancers re-process the freshly swapped subtree
                // (e.g. the .utc-time localiser in sidebar.html). A one-shot
                // DOMContentLoaded enhancer would otherwise miss swapped-in content.
                document.dispatchEvent(
                    new CustomEvent('krt:swapped', { detail: { container: container } }),
                );
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
            .catch(function (error) {
                // Aborted-by-supersession (a newer swap called abort()) or a genuine transport
                // error: never paint anything, and only clear the indicator if a newer swap has not
                // already taken ownership of it.
                hideIndicatorIfCurrent();
                releaseAborter();
                // M11: the comment above already named the two cases; the code then treated them
                // identically and said nothing about either. A supersession is INTENTIONAL and
                // happens on every debounced keystroke (#1151 aborts the older read), so warning
                // on it would make the console useless. A genuine transport failure — offline,
                // DNS/TLS, the frontend gone — is the opposite: the section silently froze on a
                // stale render and there is no other signal anywhere that it did.
                const superseded = (error && error.name === 'AbortError') || !isCurrent();
                if (!superseded) {
                    devWarn('krtFetch.swap transport failure', { url: url, error: error });
                }
                return false;
            });
    }

    function bindSwapAnchorInterception(container, opts) {
        if (container._krtSwapBound) {
            return;
        }
        container._krtSwapBound = true;
        container.addEventListener('click', function (event) {
            const anchor = event.target.closest('a.page-btn[href], a[data-swap][href]');
            if (!anchor || !container.contains(anchor)) {
                return;
            }
            // A disabled page-btn renders without an href, but guard anyway so a
            // CSS-only ".disabled" never triggers a wasted swap.
            if (anchor.classList.contains('disabled')) {
                event.preventDefault();
                return;
            }
            event.preventDefault();
            // Nested swap containers (e.g. bank-account-detail's #bank-bookings-results pager sits
            // inside the #bank-account-results accountBody region, both bound here): the click
            // bubbles innermost-first, so the CLOSEST swap container handles its own pagination
            // anchor and stops the event — otherwise an enclosing container would ALSO swap, firing
            // a redundant full-section re-render on top of the intended sub-table page change.
            event.stopPropagation();
            swap(Object.assign({}, opts, { url: anchor.getAttribute('href') }));
        });
    }

    /**
     * Binds the in-container pagination/sort anchor interception WITHOUT performing an
     * initial fetch — for pagination-only lists (no filter) where nothing else calls
     * swap() on load. Clicking a contained a.page-btn[href] / a[data-swap][href] then
     * re-swaps the container in place, reusing the given opts (history, indicator, …).
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

    // ------------------------------------------------------------ section-write seam

    /**
     * Builds a page's section-write seam — the { write, refresh, notify } trio for pages whose
     * aggregate is saved and re-rendered as independent sections (#924; canonical consumer:
     * mission-detail.js, which re-publishes the trio as the window.krtMissionWrite /
     * window.krtRefreshMissionSection / window.krtNotifyMissionChanged aliases so its ~60 call
     * sites stay one-liners).
     *
     * Every page-specific lookup is LATE-BOUND — the dict getter, the pageUrl getter and the
     * broadcast closure are re-evaluated on every call, never captured: the i18n dictionary is
     * assigned by an inline template bootstrap and the presence client (mission) only exists after
     * a later conditional bootstrap's DOMContentLoaded.
     *
     * config:
     *  - dict()               getter for the page's already-localized i18n dictionary
     *  - keys                 dictionary keys + fallbacks: saveSectionPrefix, conflictSectionPrefix,
     *                         successKey/successFallback, errorKey/errorFallback,
     *                         conflictTitleKey/conflictTitleFallback,
     *                         reloadLabelKey/reloadLabelFallback,
     *                         dismissLabelKey/dismissLabelFallback,
     *                         reloadQuestionKey/reloadQuestionFallback,
     *                         reloadDetailKey/reloadDetailFallback, refreshErrorKey
     *  - sections             sectionKey -> { container, fragmentValue } map for refresh()
     *  - pageUrl()            getter for the page's base URL; null while the entity has no id —
     *                         refresh() then resolves false for that section without fetching
     *  - broadcast(keys)      optional peer-notification closure (REQ-FE-010); called by refresh()
     *                         unless opts.broadcast === false (i.e. the refresh itself applies a
     *                         peer's inbound signal — broadcasting again would echo into a loop)
     *                         and unconditionally by notify()
     *
     * Returns:
     *  - write(opts)          {@link write} with the section's localized sectionLabel /
     *                         conflictSectionLabel / successMessage / errorMessage / conflict
     *                         strings derived from opts.sectionKey via the dict (with fallbacks)
     *  - refresh(sectionKeys, opts)  re-renders one or more sections in place via {@link swap}
     *                         (history:false, preserveScroll:true); accepts a single key or an
     *                         array; returns a Promise resolving when all swaps complete so
     *                         callers can close a modal afterwards
     *  - notify(sectionKeys)  broadcast-only sibling of refresh() for handlers that already
     *                         patched their own DOM surgically and need no self re-render
     */
    function sectionWrite(config) {
        return {
            write: function (opts) {
                const dict = config.dict() || {};
                function t(key, fallback) {
                    return dict[key] != null && dict[key] !== '' ? dict[key] : fallback;
                }
                const key = opts.sectionKey || '';
                const k = config.keys;
                return write(
                    Object.assign({}, opts, {
                        // Every section write serializes against its own section by default, so a
                        // user's back-to-back edits of one section run in order and never 409 each
                        // other; distinct sections keep distinct keys and stay concurrent. A caller
                        // can override with an explicit opts.serialize (e.g. a per-row scope).
                        serialize: opts.serialize || (key ? 'section:' + key : undefined),
                        sectionLabel: t(k.saveSectionPrefix + key, key),
                        conflictSectionLabel: t(k.conflictSectionPrefix + key, key),
                        successMessage: t(k.successKey, k.successFallback),
                        errorMessage: t(k.errorKey, k.errorFallback),
                        conflict: {
                            title: t(k.conflictTitleKey, k.conflictTitleFallback),
                            reloadLabel: t(k.reloadLabelKey, k.reloadLabelFallback),
                            dismissLabel: t(k.dismissLabelKey, k.dismissLabelFallback),
                            reloadQuestion: t(k.reloadQuestionKey, k.reloadQuestionFallback),
                            reloadDetailFallback: t(k.reloadDetailKey, k.reloadDetailFallback),
                        },
                    }),
                );
            },
            refresh: function (sectionKeys, opts) {
                const list = Array.isArray(sectionKeys) ? sectionKeys : [sectionKeys];
                if ((!opts || opts.broadcast !== false) && typeof config.broadcast === 'function') {
                    config.broadcast(list);
                }
                return Promise.all(
                    list.map(function (sectionKey) {
                        const cfg = config.sections[sectionKey];
                        const url = cfg ? config.pageUrl() : null;
                        if (!cfg || !url || !document.querySelector(cfg.container)) {
                            return Promise.resolve(false);
                        }
                        return swap({
                            url: url,
                            container: cfg.container,
                            fragmentValue: cfg.fragmentValue,
                            history: false,
                            preserveScroll: true,
                            // Surfaced as a toast when a swap bails on a redirect/non-OK response
                            // (e.g. an expired session bounced to the login page): swap() then leaves
                            // the stale section untouched rather than painting a full page into the
                            // container.
                            errorMessage: (config.dict() || {})[config.keys.refreshErrorKey] || '',
                        });
                    }),
                );
            },
            notify: function (sectionKeys) {
                const list = Array.isArray(sectionKeys) ? sectionKeys : [sectionKeys];
                if (typeof config.broadcast === 'function') {
                    config.broadcast(list);
                }
            },
        };
    }

    window.krtFetch = {
        write: write,
        submitForm: submitForm,
        swap: swap,
        bindSwap: bindSwap,
        syncVersion: syncVersion,
        handleProblem: handleProblem,
        maybeReauthenticate: maybeReauthenticate,
        reauthRedirect: reauthRedirect,
        sectionWrite: sectionWrite,
        // Exposed so a raw-fetch write (one not routed through write/submitForm) can share the same
        // per-key serialization: krtFetch.serialize('scope:id', () => doTheWrite()) runs its task
        // after the previous same-key task settles. Wrap the WHOLE write — including where it reads
        // its optimistic-lock version from the DOM — so the version is re-read fresh once the prior
        // write synced it back, killing the self-collision 409 for raw-fetch call sites too.
        serialize: runSerialized,
        csrf: window.krtCsrf,
    };

    // The former window.MissionSubresource alias was retired in #574; since #924 the page-local
    // krtMissionWrite wrapper lives in mission-detail.js and is produced by the generic
    // sectionWrite factory above, so this shared module still carries no mission-specific code,
    // keys or strings — the mission dictionary, section map and presence broadcast are all
    // supplied by the page config.
})();
