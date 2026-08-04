// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Notification bell + always-on unread badge (epic #622, REQ-NOTIF-005/006).
 *
 * - Lazily loads the recent notifications into the bell dropdown on open.
 * - Marks read / deletes individual notifications and the bulk actions with no page reload via
 *   window.krtFetch (CSRF + 403-retry handled centrally). Deleting a single notification is
 *   low-stakes and fires immediately (no confirmation); only the bulk clear-read confirms through
 *   the non-native window.showKrtConfirm dialog.
 * - Keeps the unread badge fresh from the server (the single source of truth) after every mutation
 *   and on a background poll, paused while the tab is hidden. The poll backs off to a slow keepalive
 *   while the SSE push stream is connected and speeds back up the moment it drops; the slow cadence
 *   is kept frequent enough to remain the REQ-SEC-012 re-auth safety net (the poll path — not the
 *   refresh-incapable SSE relay — is what drives token refresh / 401 re-login detection). A liveness
 *   watchdog catches a half-open stream (one that stays "connected" but stops delivering, so it never
 *   fires `error`): if no SSE traffic arrives within the liveness window the poll falls back to the
 *   fast cadence even without an `error`. Per-item DOM is patched in place so the same handlers drive
 *   both the dropdown and the full /notifications page.
 *
 * i18n strings are read from data-* attributes injected by Thymeleaf so this static file carries no
 * hardcoded user-facing text.
 */
(function () {
    // Fast cadence: SSE is down, so the poll is the primary "is there anything new?" mechanism.
    const POLL_INTERVAL_FAST_MS = 60000;
    // Slow cadence: SSE is connected and pushes updates in real time, so the poll is only a backstop
    // — but stays frequent enough to remain the REQ-SEC-012 re-auth keepalive (the poll path is what
    // refreshes the token / detects a poisoned session; the SSE relay is deliberately refresh-incapable).
    const POLL_INTERVAL_SLOW_MS = 300000;
    // Liveness window: if no SSE traffic (named `heartbeat` or `notification`) arrives within this
    // time while we believe the stream is healthy, treat it as half-open (TCP up, stream dead — such
    // a connection never fires `error`) and fall back to the fast poll. ~3x the backend heartbeat
    // (PT20S default) so a single dropped beat doesn't trip it. Keep in step with that interval.
    const SSE_LIVENESS_TIMEOUT_MS = 60000;
    const bell = document.getElementById('notification-bell');
    const i18n = readMessages();
    let pollTimer = null;
    let sseHealthy = false;
    let sseWatchdogTimer = null;

    // Read the localized strings the templates expose so this static JS stays text-free.
    function readMessages() {
        const holder = document.getElementById('notification-i18n');
        const data = holder ? holder.dataset : {};
        return {
            loading: data.loading || 'Loading...',
            empty: data.empty || 'No notifications',
            markRead: data.markRead || 'Mark read',
            deleteLabel: data.delete || 'Delete',
            deleted: data.deleted || 'Notification deleted',
            allRead: data.allRead || 'All marked read',
            cleared: data.cleared || 'Read notifications cleared',
            confirmClearTitle: data.confirmClearTitle || 'Clear read notifications',
            confirmClearBody: data.confirmClearBody || 'Delete all read notifications?',
            confirmOk: data.confirmOk || 'Delete',
            confirmCancel: data.confirmCancel || 'Cancel',
            error: data.error || 'Action failed',
        };
    }

    function csrfRequestInit() {
        return {
            headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        };
    }

    /**
     * Reads the JSON payload of one of this module's three hand-rolled GETs, or resolves to
     * `fallback` when the answer is not that payload. The writes go through krtFetch, which makes
     * these checks itself; these reads are the ones that have to make them here.
     *
     * Two gates can go up mid-session, and both must navigate rather than fail quietly. A poisoned
     * or expired session answers 401 + X-Reauthenticate (REQ-SEC-012). A newly deployed Terms-of-Use
     * wording answers 403 + X-Terms-Acceptance-Required (REQ-SEC-028) — precisely for the tab that
     * was already open when it deployed, which is when the whole feature first does anything. The
     * consent check used to be missing here, and the symptom was a badge frozen at its last value
     * and a dropdown that opened empty, with nothing on screen saying why. It lives in one place now
     * so the three reads cannot drift back apart one at a time.
     *
     * `res.ok` is not the test either. fetch follows redirects transparently, so any
     * redirect-to-HTML answer arrives as a 200 whose body is a whole document — rejecting
     * `res.redirected` keeps that out of the JSON parse rather than letting a login page or a
     * consent page be read as a payload.
     *
     * @param {Response} res the response to read
     * @param {any} fallback the value to resolve to when the answer is not the payload
     * @returns {any} the parsed body, or the fallback
     */
    function readJson(res, fallback) {
        if (window.krtReauth && window.krtReauth.check(res)) {
            return fallback;
        }
        if (window.krtTermsGate && window.krtTermsGate.check(res)) {
            // The consent page is already loading over this one: stop the badge poll so the
            // departing page cannot keep asking the endpoint that just refused it.
            stopPolling();
            return fallback;
        }
        if (res.redirected || !res.ok) {
            return fallback;
        }
        return res.json();
    }

    // ---- unread badge -------------------------------------------------------

    function setBadge(count) {
        const badge = document.getElementById('notification-badge');
        if (!badge) {
            return;
        }
        const n = typeof count === 'number' ? count : 0;
        badge.textContent = n > 99 ? '99+' : String(n);
        if (n > 0) {
            badge.classList.remove('notification-badge-hidden');
        } else {
            badge.classList.add('notification-badge-hidden');
        }
    }

    function refreshUnreadCount() {
        return fetch('/notifications/unread-count', csrfRequestInit())
            .then(function (res) {
                // Both gates navigate rather than let the badge silently report its last value
                // forever (REQ-SEC-012 / REQ-SEC-028) — see readJson.
                return readJson(res, null);
            })
            .then(function (data) {
                if (data && data.count != null) {
                    setBadge(Number(data.count));
                }
            })
            .catch(function () {
                /* a transient count refresh failure must never break the page */
            });
    }

    // ---- dropdown -----------------------------------------------------------

    function buildItem(item) {
        const li = document.createElement('li');
        li.className = 'notification-item' + (item.read ? ' is-read' : '');
        li.setAttribute('data-notif-id', item.id);
        li.setAttribute('data-notif-read', item.read ? 'true' : 'false');

        const body = document.createElement('div');
        body.className = 'notification-item-body';
        const text = document.createElement('p');
        text.className = 'notification-item-text';
        text.textContent = item.text != null ? item.text : '';
        const time = document.createElement('span');
        time.className = 'notification-item-time';
        time.textContent = item.createdAtDisplay != null ? item.createdAtDisplay : '';
        body.appendChild(text);
        body.appendChild(time);

        const actions = document.createElement('div');
        actions.className = 'notification-item-actions';
        if (!item.read) {
            actions.appendChild(
                actionButton('notif-mark-read', i18n.markRead, 'krt-icon-check', 'btn btn-icon'),
            );
        }
        actions.appendChild(
            actionButton(
                'notif-delete',
                i18n.deleteLabel,
                'krt-icon-trash',
                'btn btn-quiet-danger btn-icon',
            ),
        );

        li.appendChild(body);
        li.appendChild(actions);
        return li;
    }

    function actionButton(attr, label, icon, className) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = className;
        btn.setAttribute('data-' + attr, '');
        btn.setAttribute('aria-label', label);
        btn.title = label;
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'krt-icon');
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', '#' + icon);
        svg.appendChild(use);
        btn.appendChild(svg);
        return btn;
    }

    function loadDropdown() {
        const list = document.getElementById('notification-dropdown-list');
        const empty = document.getElementById('notification-dropdown-empty');
        if (!list) {
            return;
        }
        list.innerHTML = '';
        if (empty) {
            empty.classList.add('notification-badge-hidden');
        }
        fetch('/notifications/recent', csrfRequestInit())
            .then(function (res) {
                return readJson(res, []);
            })
            .then(function (items) {
                if (!Array.isArray(items) || items.length === 0) {
                    if (empty) {
                        empty.classList.remove('notification-badge-hidden');
                    }
                    return;
                }
                items.forEach(function (item) {
                    list.appendChild(buildItem(item));
                });
            })
            .catch(function () {
                if (empty) {
                    empty.classList.remove('notification-badge-hidden');
                }
            });
    }

    function openDropdown() {
        const dropdown = document.getElementById('notification-dropdown');
        const toggle = document.getElementById('notification-toggle');
        if (!dropdown) {
            return;
        }
        dropdown.classList.remove('notification-dropdown-hidden');
        if (toggle) {
            toggle.setAttribute('aria-expanded', 'true');
        }
        loadDropdown();
    }

    function closeDropdown() {
        const dropdown = document.getElementById('notification-dropdown');
        const toggle = document.getElementById('notification-toggle');
        if (!dropdown) {
            return;
        }
        dropdown.classList.add('notification-dropdown-hidden');
        if (toggle) {
            toggle.setAttribute('aria-expanded', 'false');
        }
    }

    function toggleDropdown() {
        const dropdown = document.getElementById('notification-dropdown');
        if (!dropdown) {
            return;
        }
        if (dropdown.classList.contains('notification-dropdown-hidden')) {
            openDropdown();
        } else {
            closeDropdown();
        }
    }

    // ---- mutations (no reload) ---------------------------------------------

    function eachItem(id, fn) {
        const nodes = document.querySelectorAll('[data-notif-id="' + cssEscape(id) + '"]');
        Array.prototype.forEach.call(nodes, fn);
    }

    function cssEscape(value) {
        if (window.CSS && typeof window.CSS.escape === 'function') {
            return window.CSS.escape(value);
        }
        return String(value).replace(/["\\]/g, '\\$&');
    }

    function markReadInPlace(li) {
        li.classList.add('is-read');
        li.setAttribute('data-notif-read', 'true');
        const btn = li.querySelector('[data-notif-mark-read]');
        if (btn) {
            btn.remove();
        }
    }

    function removeItem(li) {
        const list = li.parentElement;
        li.remove();
        refreshEmptyState(list);
    }

    function refreshEmptyState(list) {
        if (!list) {
            return;
        }
        const hasItems = list.querySelector('.notification-item');
        const empty = list.parentElement
            ? list.parentElement.querySelector('[data-notif-empty]')
            : null;
        if (empty) {
            if (hasItems) {
                empty.classList.add('notification-badge-hidden');
            } else {
                empty.classList.remove('notification-badge-hidden');
            }
        }
    }

    function doMarkRead(id, submitter) {
        if (!window.krtFetch) {
            return;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: '/notifications/' + encodeURIComponent(id) + '/read',
                toast: false,
                errorMessage: i18n.error,
                submitter: submitter,
                onSuccess: function () {
                    eachItem(id, markReadInPlace);
                },
            })
            .finally(refreshUnreadCount);
    }

    // Deleting a single notification is low-stakes and reversible-in-spirit (the message is just
    // gone from the inbox), so it fires immediately with no confirmation hurdle — unlike the bulk
    // clear-read below, which still confirms. The success toast is the only feedback.
    function doDelete(id, submitter) {
        if (!window.krtFetch) {
            return;
        }
        window.krtFetch
            .write({
                method: 'DELETE',
                url: '/notifications/' + encodeURIComponent(id),
                successMessage: i18n.deleted,
                errorMessage: i18n.error,
                submitter: submitter,
                onSuccess: function () {
                    eachItem(id, removeItem);
                },
            })
            .finally(refreshUnreadCount);
    }

    function doMarkAll(submitter) {
        if (!window.krtFetch) {
            return;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: '/notifications/read-all',
                successMessage: i18n.allRead,
                errorMessage: i18n.error,
                submitter: submitter,
                onSuccess: function () {
                    const nodes = document.querySelectorAll('.notification-item');
                    Array.prototype.forEach.call(nodes, markReadInPlace);
                },
            })
            .finally(refreshUnreadCount);
    }

    function doClearRead(submitter) {
        if (!window.krtFetch) {
            return;
        }
        confirmThen(i18n.confirmClearTitle, i18n.confirmClearBody, function () {
            window.krtFetch
                .write({
                    method: 'DELETE',
                    url: '/notifications/read',
                    successMessage: i18n.cleared,
                    errorMessage: i18n.error,
                    submitter: submitter,
                    onSuccess: function () {
                        const nodes = document.querySelectorAll('.notification-item.is-read');
                        Array.prototype.forEach.call(nodes, removeItem);
                    },
                })
                .finally(refreshUnreadCount);
        });
    }

    // ---- inbox page load-more (REQ-NOTIF-019) --------------------------------

    // The /notifications page renders only the newest 50; the load-more button appends the next
    // server page in place so the older tail stays reachable instead of being silently cut off.
    // Items arrive as the same localized view DTOs the dropdown uses, so buildItem() renders them
    // identically to the server-rendered rows and the delegated mark-read/delete handlers apply.
    function loadMorePage(btn) {
        const list = document.getElementById('notification-page-list');
        if (!list || btn.disabled) {
            return;
        }
        const page = parseInt(btn.getAttribute('data-notif-next-page'), 10) || 1;
        btn.disabled = true;
        fetch('/notifications/page-items?page=' + page, csrfRequestInit())
            .then(function (res) {
                return readJson(res, null);
            })
            .then(function (data) {
                if (!data || !Array.isArray(data.items)) {
                    return;
                }
                data.items.forEach(function (item) {
                    // A notification arriving since page 0 shifts rows down, so an offset fetch can
                    // re-return a row already shown — skip those to avoid duplicates. (The reverse,
                    // a delete shifting an unseen row up past the offset, is the inherent limit of
                    // offset pagination and is out of scope for REQ-NOTIF-019; a manual reload
                    // recovers it, as it does for every offset-paginated list in the app.)
                    if (!list.querySelector('[data-notif-id="' + cssEscape(item.id) + '"]')) {
                        list.appendChild(buildItem(item));
                    }
                });
                btn.setAttribute('data-notif-next-page', String(page + 1));
                updatePageHint(list, data.totalElements);
                if (!data.hasMore) {
                    const wrap = btn.closest('.notification-page-more');
                    if (wrap) {
                        wrap.remove();
                    } else {
                        btn.remove();
                    }
                }
            })
            .catch(function () {
                /* leave the button usable so the user can retry a transient failure */
            })
            .finally(function () {
                btn.disabled = false;
            });
    }

    // Keep the "showing X of Y" hint truthful after each appended page.
    function updatePageHint(list, total) {
        const hint = document.querySelector('[data-notif-hint]');
        if (!hint) {
            return;
        }
        const template = hint.getAttribute('data-notif-hint-template') || '';
        const shown = list.querySelectorAll('.notification-item').length;
        hint.textContent = template
            .replace('{shown}', String(shown))
            .replace('{total}', String(total));
    }

    function confirmThen(title, body, action) {
        if (typeof window.showKrtConfirm === 'function') {
            window
                .showKrtConfirm(title, body, i18n.confirmOk, i18n.confirmCancel)
                .then(function (ok) {
                    if (ok) {
                        action();
                    }
                });
        } else {
            action();
        }
    }

    // ---- wiring -------------------------------------------------------------

    function onDocumentClick(event) {
        const markReadBtn = event.target.closest('[data-notif-mark-read]');
        if (markReadBtn) {
            const readLi = markReadBtn.closest('[data-notif-id]');
            if (readLi) {
                doMarkRead(readLi.getAttribute('data-notif-id'), markReadBtn);
            }
            return;
        }
        const deleteBtn = event.target.closest('[data-notif-delete]');
        if (deleteBtn) {
            const delLi = deleteBtn.closest('[data-notif-id]');
            if (delLi) {
                doDelete(delLi.getAttribute('data-notif-id'), deleteBtn);
            }
            return;
        }
        const markAllBtn = event.target.closest('[data-notif-mark-all]');
        if (markAllBtn) {
            doMarkAll(markAllBtn);
            return;
        }
        const clearReadBtn = event.target.closest('[data-notif-clear-read]');
        if (clearReadBtn) {
            doClearRead(clearReadBtn);
            return;
        }
        const loadMoreBtn = event.target.closest('[data-notif-load-more]');
        if (loadMoreBtn) {
            loadMorePage(loadMoreBtn);
            return;
        }
        if (event.target.closest('#notification-toggle')) {
            toggleDropdown();
            return;
        }
        // Click outside the bell closes the dropdown.
        if (bell && !event.target.closest('#notification-bell')) {
            closeDropdown();
        }
    }

    function currentPollIntervalMs() {
        return sseHealthy ? POLL_INTERVAL_SLOW_MS : POLL_INTERVAL_FAST_MS;
    }

    function startPolling() {
        if (pollTimer || !document.getElementById('notification-badge')) {
            return;
        }
        pollTimer = window.setInterval(refreshUnreadCount, currentPollIntervalMs());
    }

    function stopPolling() {
        if (pollTimer) {
            window.clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    // Re-arm the poll timer at the cadence implied by the current SSE health. No-op while the tab is
    // hidden (the timer is stopped; onVisibilityChange restarts it at the right cadence on return).
    function restartPolling() {
        if (!pollTimer) {
            return;
        }
        stopPolling();
        startPolling();
    }

    // (Re)start the SSE liveness window. Called on every proof of life (open / heartbeat /
    // notification); if it ever elapses, the stream is half-open and we demote to the fast poll.
    function bumpSseWatchdog() {
        if (sseWatchdogTimer) {
            window.clearTimeout(sseWatchdogTimer);
        }
        sseWatchdogTimer = window.setTimeout(onSseWatchdogTimeout, SSE_LIVENESS_TIMEOUT_MS);
    }

    function clearSseWatchdog() {
        if (sseWatchdogTimer) {
            window.clearTimeout(sseWatchdogTimer);
            sseWatchdogTimer = null;
        }
    }

    // The liveness window elapsed with no SSE traffic: the stream is half-open (still "connected"
    // but dead, so it never fired `error`). Demote to the fast poll so re-auth detection and unread
    // updates fall back to the poll path (REQ-SEC-012). A later proof-of-life event re-promotes.
    function onSseWatchdogTimeout() {
        sseWatchdogTimer = null;
        markSseUnhealthy();
    }

    // Proof of life (connection open or any received event): re-arm the watchdog, and on the
    // false→true flip back the poll off to the slow keepalive. Re-promotes after a half-open demote.
    function markSseHealthy() {
        bumpSseWatchdog();
        if (!sseHealthy) {
            sseHealthy = true;
            restartPolling();
        }
    }

    // The stream is (or went) unhealthy: stop the watchdog and, on the true→false flip, fall back to
    // the fast poll plus a one-shot catch-up. The catch-up is skipped while the tab is hidden, where
    // polling is paused and onVisibilityChange refreshes on return. Guarded so the repeated `error`
    // events of a reconnect storm don't churn the timer or hammer the count endpoint.
    function markSseUnhealthy() {
        clearSseWatchdog();
        if (sseHealthy) {
            sseHealthy = false;
            restartPolling();
            if (!document.hidden) {
                refreshUnreadCount();
            }
        }
    }

    function onVisibilityChange() {
        if (document.hidden) {
            stopPolling();
        } else {
            startPolling();
            refreshUnreadCount();
            // A stream can silently die while the tab is backgrounded (and timers are throttled, so
            // the watchdog may not have fired). Re-arm the liveness window so a now-dead "healthy"
            // stream self-corrects to the fast poll within SSE_LIVENESS_TIMEOUT_MS of return.
            if (sseHealthy) {
                bumpSseWatchdog();
            }
        }
    }

    // Best-effort real-time push (REQ-NOTIF-010): refresh immediately when the server pushes a
    // "notification" event. Reconnection is managed here rather than left to the native EventSource
    // auto-reconnect so it can be JITTERED: after a frontend redeploy every open tab's stream errors
    // at the same instant, and the browser's fixed ~3 s auto-reconnect would resynchronise them into
    // one thundering herd that collapses onto the shared per-IP rate-limit bucket / the SSE relay
    // pool (#1130 / #1110). The polling fallback above is the guaranteed correctness path, so SSE
    // never needs to be reliable.
    const SSE_RECONNECT_BASE_MS = 3000;
    let sseSource = null;
    let sseReconnectTimer = null;
    let sseStopped = false;

    function scheduleSseReconnect() {
        // One pending reconnect at a time; never reconnect after a `reauth` handoff (the page is
        // redirecting to the login flow).
        if (sseReconnectTimer !== null || sseStopped) {
            return;
        }
        // Full jitter in [base, 2*base): spreads reconnects across a ~3 s window instead of firing
        // every tab on the same tick, matching the decorrelation the mission-presence reconnect uses.
        const delay = SSE_RECONNECT_BASE_MS + Math.floor(Math.random() * SSE_RECONNECT_BASE_MS);
        sseReconnectTimer = window.setTimeout(function () {
            sseReconnectTimer = null;
            startSse();
        }, delay);
    }

    function startSse() {
        if (
            typeof window.EventSource !== 'function' ||
            !document.getElementById('notification-badge')
        ) {
            return;
        }
        // Tear down any prior stream so this module owns the reconnect policy (a lingering native
        // auto-reconnect would race the jittered one).
        if (sseSource !== null) {
            try {
                sseSource.close();
            } catch (_error) {
                /* already closed */
            }
            sseSource = null;
        }
        try {
            const source = new EventSource('/notifications/stream');
            sseSource = source;
            // Connection established → push is live: mark healthy (backs the poll off to the slow
            // keepalive on the flip) and arm the liveness watchdog.
            source.addEventListener('open', function () {
                markSseHealthy();
            });
            // Stream dropped → fall back to the fast poll and reconnect ourselves after a jittered
            // delay. Close the source first so the browser does not ALSO auto-reconnect on its fixed
            // cadence. The reconnect-storm and tab-hidden guards live in markSseUnhealthy.
            source.addEventListener('error', function () {
                markSseUnhealthy();
                try {
                    source.close();
                } catch (_error) {
                    /* already closed */
                }
                if (sseSource === source) {
                    sseSource = null;
                }
                scheduleSseReconnect();
            });
            // Named keepalive (REQ-NOTIF-010): pure proof of life. Resets the liveness watchdog and
            // re-promotes to the slow cadence if a half-open stall had demoted us. The payload is
            // irrelevant — only its arrival matters.
            source.addEventListener('heartbeat', function () {
                markSseHealthy();
            });
            source.addEventListener('notification', function () {
                // A delivered notification is also proof the stream is live.
                markSseHealthy();
                refreshUnreadCount();
                const dropdown = document.getElementById('notification-dropdown');
                if (dropdown && !dropdown.classList.contains('notification-dropdown-hidden')) {
                    loadDropdown();
                }
            });
            // The server pushes a `reauth` event when the stream's session lost its OAuth2 token,
            // then closes the stream: redirect to the Keycloak login flow instead of reconnecting
            // against a dead session (REQ-SEC-012).
            source.addEventListener('reauth', function (event) {
                sseStopped = true;
                if (sseReconnectTimer !== null) {
                    window.clearTimeout(sseReconnectTimer);
                    sseReconnectTimer = null;
                }
                if (window.krtReauth) {
                    window.krtReauth.redirect(event && event.data ? event.data : null);
                }
            });
            // The consent gate answers a stream that has no accepted Terms of Use with a single
            // `terms-gate` event naming the consent page, then closes it (REQ-SEC-028). Without this
            // the stream would just error and reconnect on the jittered timer below — forever, since
            // consent cannot be given from a background request. Same shape as `reauth`: stop
            // reconnecting, then navigate.
            source.addEventListener('terms-gate', function (event) {
                sseStopped = true;
                if (sseReconnectTimer !== null) {
                    window.clearTimeout(sseReconnectTimer);
                    sseReconnectTimer = null;
                }
                try {
                    source.close();
                } catch (_error) {
                    /* already closed */
                }
                if (sseSource === source) {
                    sseSource = null;
                }
                if (window.krtTermsGate) {
                    window.krtTermsGate.redirect(event && event.data ? event.data : null);
                }
            });
            // #1156: the server retires the OLDEST of this user's streams with a `replaced` event
            // once they exceed the per-user cap (too many tabs/devices). Yield the live channel to
            // the newer tab: stop reconnecting on THIS stream and fall back to the polling path here
            // (the count endpoint still keeps this tab correct). Unlike `reauth` there is no redirect.
            source.addEventListener('replaced', function () {
                sseStopped = true;
                if (sseReconnectTimer !== null) {
                    window.clearTimeout(sseReconnectTimer);
                    sseReconnectTimer = null;
                }
                markSseUnhealthy();
                try {
                    source.close();
                } catch (_error) {
                    /* already closed */
                }
                if (sseSource === source) {
                    sseSource = null;
                }
            });
        } catch (_error) {
            /* SSE unavailable; the polling fallback remains */
        }
    }

    document.addEventListener('click', onDocumentClick);
    document.addEventListener('visibilitychange', onVisibilityChange);

    // Per-item buttons and the page-level mark-all / clear-read controls exist on the
    // /notifications page even when the bell is absent on a given render, so wiring is
    // unconditional; the badge poll only starts when a badge is present.
    startPolling();
    startSse();
})();
