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

(function () {
    const POLL_INTERVAL_FAST_MS = 60000;
    const POLL_INTERVAL_SLOW_MS = 300000;
    const SSE_LIVENESS_TIMEOUT_MS = 60000;
    const bell = document.getElementById('notification-bell');
    const i18n = readMessages();
    /** @type {number | null} */
    let pollTimer = null;
    let sseHealthy = false;
    /** @type {number | null} */
    let sseWatchdogTimer = null;

    function readMessages() {
        const holder = document.getElementById('notification-i18n');
        const data = holder ? holder.dataset : {};
        return {
            loading: window.krtI18nText(data.loading, 'data-loading'),
            empty: window.krtI18nText(data.empty, 'data-empty'),
            markRead: window.krtI18nText(data.markRead, 'data-mark-read'),
            deleteLabel: window.krtI18nText(data.delete, 'data-delete'),
            deleted: window.krtI18nText(data.deleted, 'data-deleted'),
            allRead: window.krtI18nText(data.allRead, 'data-all-read'),
            cleared: window.krtI18nText(data.cleared, 'data-cleared'),
            confirmClearTitle: window.krtI18nText(
                data.confirmClearTitle,
                'data-confirm-clear-title',
            ),
            confirmClearBody: window.krtI18nText(data.confirmClearBody, 'data-confirm-clear-body'),
            confirmOk: window.krtI18nText(data.confirmOk, 'data-confirm-ok'),
            confirmCancel: window.krtI18nText(data.confirmCancel, 'data-confirm-cancel'),
            error: window.krtI18nText(data.error, 'data-error'),
        };
    }

    /**
     * Reads one of this module's JSON endpoints through the shared read path (REQ-FE-031).
     *
     * @param {string} url the endpoint
     * @returns {Promise<Response | null>} the response, or null when a gate took over
     */
    function readNotifications(url) {
        return window.krtFetch.get(url, { accept: 'application/json' });
    }

    /**
     * Reads the JSON payload of one of this module's GETs, or resolves to `fallback` when the
     * answer is not that payload.
     *
     * A null response means `krtFetch.get` handed the answer to the re-auth (REQ-SEC-012) or
     * consent (REQ-SEC-028) gate or refused it as redirected; the stream and the badge poll then
     * stop, because the page is leaving or the session is unusable.
     *
     * @param {Response | null} res the response to read
     * @param {any} fallback the value to resolve to when the answer is not the payload
     * @returns {any} the parsed body, or the fallback
     */
    function readJson(res, fallback) {
        if (!res) {
            stopSse();
            stopPolling();
            return fallback;
        }
        if (res.status === 401) {
            stopSse();
        }
        if (!res.ok) {
            return fallback;
        }
        return res.json();
    }

    function setBadge(count) {
        const badge = document.getElementById('notification-badge');
        if (!badge) {
            return;
        }
        const n = typeof count === 'number' ? count : 0;
        const text = n > 99 ? '99+' : String(n);
        [badge, ...Array.from(document.querySelectorAll('[data-notif-badge]'))].forEach((el) => {
            el.textContent = text;
            el.classList.toggle('notification-badge-hidden', n <= 0);
        });
    }

    function refreshUnreadCount() {
        return readNotifications('/notifications/unread-count')
            .then((res) => {
                return readJson(res, null);
            })
            .then((data) => {
                if (data && data.count != null) {
                    setBadge(Number(data.count));
                }
            })
            .catch(() => {});
    }

    /**
     * Builds one notification row.
     *
     * @param {any} item the server-localized notification view
     * @param {boolean} [linkable] whether the row links to the page the notification is about
     * @returns {HTMLLIElement} the row
     */
    function buildItem(item, linkable) {
        const li = document.createElement('li');
        li.className = `notification-item${item.read ? ' is-read' : ''}`;
        li.setAttribute('data-notif-id', item.id);
        li.setAttribute('data-notif-read', item.read ? 'true' : 'false');

        const href =
            linkable && typeof window.safeSameOriginUrl === 'function'
                ? window.safeSameOriginUrl(item.href)
                : null;
        const body = document.createElement('div');
        body.className = 'notification-item-body';
        const text = document.createElement(href ? 'span' : 'p');
        text.className = 'notification-item-text';
        text.textContent = item.text != null ? item.text : '';
        const time = document.createElement('span');
        time.className = 'notification-item-time';
        time.textContent = item.createdAtDisplay != null ? item.createdAtDisplay : '';
        if (href) {
            const link = document.createElement('a');
            link.className = 'row-link';
            link.setAttribute('data-testid', 'row-link');
            link.href = href;
            link.appendChild(text);
            body.appendChild(link);
        } else {
            body.appendChild(text);
        }
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
        if (href) {
            actions.appendChild(chevron());
        }

        li.appendChild(body);
        li.appendChild(actions);
        return li;
    }

    /**
     * The decorative chevron that ends a linked row.
     *
     * @returns {SVGSVGElement} the icon
     */
    function chevron() {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'row-chevron');
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', '#krt-icon-chevron-right');
        svg.appendChild(use);
        return svg;
    }

    function actionButton(attr, label, icon, className) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = className;
        btn.setAttribute(`data-${attr}`, '');
        btn.setAttribute('aria-label', label);
        btn.title = label;
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'krt-icon');
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', `#${icon}`);
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
        readNotifications('/notifications/recent')
            .then((res) => {
                return readJson(res, []);
            })
            .then((items) => {
                if (!Array.isArray(items) || items.length === 0) {
                    if (empty) {
                        empty.classList.remove('notification-badge-hidden');
                    }
                    return;
                }
                items.forEach((item) => {
                    list.appendChild(buildItem(item));
                });
            })
            .catch(() => {
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

    function eachItem(id, fn) {
        const nodes = document.querySelectorAll(`[data-notif-id="${cssEscape(id)}"]`);
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
                url: `/notifications/${encodeURIComponent(id)}/read`,
                toast: false,
                errorMessage: i18n.error,
                submitter,
                onSuccess() {
                    eachItem(id, markReadInPlace);
                },
            })
            .finally(refreshUnreadCount);
    }

    function doDelete(id, submitter) {
        if (!window.krtFetch) {
            return;
        }
        window.krtFetch
            .write({
                method: 'DELETE',
                url: `/notifications/${encodeURIComponent(id)}`,
                successMessage: i18n.deleted,
                errorMessage: i18n.error,
                submitter,
                onSuccess() {
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
                submitter,
                onSuccess() {
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
        confirmThen(i18n.confirmClearTitle, i18n.confirmClearBody, () => {
            window.krtFetch
                .write({
                    method: 'DELETE',
                    url: '/notifications/read',
                    successMessage: i18n.cleared,
                    errorMessage: i18n.error,
                    submitter,
                    onSuccess() {
                        const nodes = document.querySelectorAll('.notification-item.is-read');
                        Array.prototype.forEach.call(nodes, removeItem);
                    },
                })
                .finally(refreshUnreadCount);
        });
    }

    function loadMorePage(btn) {
        const list = document.getElementById('notification-page-list');
        if (!list || btn.disabled) {
            return;
        }
        const page = parseInt(btn.getAttribute('data-notif-next-page'), 10) || 1;
        btn.disabled = true;
        readNotifications(`/notifications/page-items?page=${page}`)
            .then((res) => {
                return readJson(res, null);
            })
            .then((data) => {
                if (!data || !Array.isArray(data.items)) {
                    return;
                }
                data.items.forEach((item) => {
                    if (!list.querySelector(`[data-notif-id="${cssEscape(item.id)}"]`)) {
                        list.appendChild(buildItem(item, true));
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
            .catch(() => {})
            .finally(() => {
                btn.disabled = false;
            });
    }

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
            window.showKrtConfirm(title, body, i18n.confirmOk, i18n.confirmCancel).then((ok) => {
                if (ok) {
                    action();
                }
            });
        } else {
            action();
        }
    }

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

    function restartPolling() {
        if (!pollTimer) {
            return;
        }
        stopPolling();
        startPolling();
    }

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

    function onSseWatchdogTimeout() {
        sseWatchdogTimer = null;
        markSseUnhealthy();
    }

    function markSseHealthy() {
        bumpSseWatchdog();
        if (!sseHealthy) {
            sseHealthy = true;
            restartPolling();
        }
    }

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
            if (sseHealthy) {
                bumpSseWatchdog();
            }
        }
    }

    const SSE_RECONNECT_BASE_MS = 3000;
    const SSE_MAX_BACKOFF_STEPS = 3;
    /** @type {EventSource | null} */
    let sseSource = null;
    /** @type {number | null} */
    let sseReconnectTimer = null;
    let sseStopped = false;
    let sseRefusals = 0;

    function scheduleSseReconnect() {
        if (sseReconnectTimer !== null || sseStopped) {
            return;
        }
        const base =
            SSE_RECONNECT_BASE_MS * Math.pow(2, Math.min(sseRefusals, SSE_MAX_BACKOFF_STEPS));
        const delay = base + Math.floor(Math.random() * base);
        sseReconnectTimer = window.setTimeout(() => {
            sseReconnectTimer = null;
            if (!sseStopped) {
                startSse();
            }
        }, delay);
    }

    /**
     * Stops the push stream for good on this page, closing the source and cancelling any pending
     * reconnect; the badge poll keeps running.
     */
    function stopSse() {
        sseStopped = true;
        if (sseReconnectTimer !== null) {
            window.clearTimeout(sseReconnectTimer);
            sseReconnectTimer = null;
        }
        if (sseSource !== null) {
            try {
                sseSource.close();
            } catch (_error) {}
            sseSource = null;
        }
        clearSseWatchdog();
    }

    function startSse() {
        if (
            typeof window.EventSource !== 'function' ||
            !document.getElementById('notification-badge')
        ) {
            return;
        }
        if (sseSource !== null) {
            try {
                sseSource.close();
            } catch (_error) {}
            sseSource = null;
        }
        try {
            const source = new EventSource('/notifications/stream');
            sseSource = source;
            let opened = false;
            source.addEventListener('open', () => {
                opened = true;
                sseRefusals = 0;
                markSseHealthy();
            });
            source.addEventListener('error', () => {
                markSseUnhealthy();
                try {
                    source.close();
                } catch (_error) {}
                if (sseSource === source) {
                    sseSource = null;
                }
                if (!opened) {
                    sseRefusals += 1;
                    refreshUnreadCount();
                }
                scheduleSseReconnect();
            });
            source.addEventListener('heartbeat', () => {
                markSseHealthy();
            });
            source.addEventListener('notification', () => {
                markSseHealthy();
                refreshUnreadCount();
                const dropdown = document.getElementById('notification-dropdown');
                if (dropdown && !dropdown.classList.contains('notification-dropdown-hidden')) {
                    loadDropdown();
                }
            });
            source.addEventListener('reauth', (event) => {
                sseStopped = true;
                if (sseReconnectTimer !== null) {
                    window.clearTimeout(sseReconnectTimer);
                    sseReconnectTimer = null;
                }
                if (window.krtReauth) {
                    window.krtReauth.redirect(event && event.data ? event.data : null);
                }
            });
            source.addEventListener('terms-gate', (event) => {
                sseStopped = true;
                if (sseReconnectTimer !== null) {
                    window.clearTimeout(sseReconnectTimer);
                    sseReconnectTimer = null;
                }
                try {
                    source.close();
                } catch (_error) {}
                if (sseSource === source) {
                    sseSource = null;
                }
                if (window.krtTermsGate) {
                    window.krtTermsGate.redirect(event && event.data ? event.data : null);
                }
            });
            source.addEventListener('replaced', () => {
                sseStopped = true;
                if (sseReconnectTimer !== null) {
                    window.clearTimeout(sseReconnectTimer);
                    sseReconnectTimer = null;
                }
                markSseUnhealthy();
                try {
                    source.close();
                } catch (_error) {}
                if (sseSource === source) {
                    sseSource = null;
                }
            });
        } catch (_error) {}
    }

    document.addEventListener('click', onDocumentClick);
    document.addEventListener('visibilitychange', onVisibilityChange);

    startPolling();
    startSse();
})();
