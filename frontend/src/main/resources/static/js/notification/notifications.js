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

import { showKrtConfirm } from '/js/kernel/toast.js';
import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtFetch, krtReauth, krtTermsGate } from '/js/kernel/krt-fetch.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

const POLL_INTERVAL_FAST_MS = 60000;
const POLL_INTERVAL_SLOW_MS = 300000;
const SSE_LIVENESS_TIMEOUT_MS = 60000;
const OPEN_MARK_READ_WAIT_MS = 1500;
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
        loading: krtI18nText(data.loading, 'data-loading'),
        empty: krtI18nText(data.empty, 'data-empty'),
        markRead: krtI18nText(data.markRead, 'data-mark-read'),
        deleteLabel: krtI18nText(data.delete, 'data-delete'),
        deleted: krtI18nText(data.deleted, 'data-deleted'),
        allRead: krtI18nText(data.allRead, 'data-all-read'),
        cleared: krtI18nText(data.cleared, 'data-cleared'),
        confirmClearTitle: krtI18nText(data.confirmClearTitle, 'data-confirm-clear-title'),
        confirmClearBody: krtI18nText(data.confirmClearBody, 'data-confirm-clear-body'),
        confirmOk: krtI18nText(data.confirmOk, 'data-confirm-ok'),
        confirmCancel: krtI18nText(data.confirmCancel, 'data-confirm-cancel'),
        error: krtI18nText(data.error, 'data-error'),
    };
}

/**
 * Reads one of this module's JSON endpoints through the shared read path (REQ-FE-031).
 *
 * @param {string} url the endpoint
 * @returns {Promise<Response | null>} the response, or null when a gate took over
 */
function readNotifications(url) {
    return krtFetch.get(url, { accept: 'application/json' });
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

/**
 * Shows the unread count on every bell badge, capped at 99+.
 *
 * @param {unknown} count the unread count; anything but a number counts as zero
 */
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
 * Builds one notification row, linked to the subject it is about when it has one (REQ-NOTIF-032).
 *
 * @param {any} item the server-localized notification view
 * @returns {HTMLLIElement} the row
 */
function buildItem(item) {
    const li = document.createElement('li');
    li.className = `notification-item${item.read ? ' is-read' : ''}`;
    li.setAttribute('data-notif-id', item.id);
    li.setAttribute('data-notif-read', item.read ? 'true' : 'false');

    const href = safeSameOriginUrl(item.href);
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
        link.setAttribute('data-notif-open', '');
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

/**
 * Builds one icon button of a notification row.
 *
 * @param {string} attr the suffix of the `data-` marker attribute
 * @param {string} label the accessible label and tooltip
 * @param {string} icon the icon symbol id
 * @param {string} className the button's classes
 * @returns {HTMLButtonElement} the button
 */
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
    list.replaceChildren();
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

/**
 * Runs a callback for every rendered row of one notification.
 *
 * @param {string} id the notification id
 * @param {(li: HTMLElement) => void} fn the callback
 */
function eachItem(id, fn) {
    const nodes = document.querySelectorAll(`[data-notif-id="${cssEscape(id)}"]`);
    Array.prototype.forEach.call(nodes, fn);
}

/**
 * Escapes a value for use inside a quoted attribute selector.
 *
 * @param {string} value the raw value
 * @returns {string} the escaped value
 */
function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === 'function') {
        return window.CSS.escape(value);
    }
    return String(value).replace(/["\\]/g, '\\$&');
}

/**
 * Marks a rendered row as read and drops its mark-read button.
 *
 * @param {Element} li the notification row
 */
function markReadInPlace(li) {
    li.classList.add('is-read');
    li.setAttribute('data-notif-read', 'true');
    const btn = li.querySelector('[data-notif-mark-read]');
    if (btn) {
        btn.remove();
    }
}

/**
 * Removes a rendered row and refreshes the empty state of its list.
 *
 * @param {Element} li the notification row
 */
function removeItem(li) {
    const list = li.parentElement;
    li.remove();
    refreshEmptyState(list);
}

/**
 * Shows the empty hint next to a list exactly when it holds no notification row.
 *
 * @param {Element | null} list the list element
 */
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

/**
 * Marks one notification read and patches its rows in place.
 *
 * @param {string} id the notification id
 * @param {Element | null} submitter the button disabled while the write is in flight
 */
function doMarkRead(id, submitter) {
    krtFetch
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

/**
 * Marks one notification read on the way to its subject and patches its rows in place; a failure
 * leaves the row unread and shows no toast, because the member is already leaving for the subject.
 *
 * @param {string} id the notification id
 * @returns {Promise<unknown>} settles when the write is done, successful or not
 */
function markReadOnOpen(id) {
    return krtFetch
        .write({
            method: 'POST',
            url: `/notifications/${encodeURIComponent(id)}/read`,
            toast: false,
            onError: () => true,
            onNetworkError: () => true,
            onSuccess() {
                eachItem(id, markReadInPlace);
            },
        })
        .finally(refreshUnreadCount);
}

/**
 * Opens a notification's subject and marks the notification read (REQ-NOTIF-032).
 *
 * A plain activation waits for the mark-read write, at most `OPEN_MARK_READ_WAIT_MS`, and
 * then follows the link; a click that opens a new tab or window keeps the browser's own handling
 * and marks the row read in the background, since this page stays open.
 *
 * @param {MouseEvent} event the click or middle click on the row link
 * @param {HTMLAnchorElement} link the row link
 */
function openNotification(event, link) {
    const li = link.closest('[data-notif-id]');
    if (!li || li.getAttribute('data-notif-read') === 'true') {
        return;
    }
    const write = markReadOnOpen(/** @type {string} */ (li.getAttribute('data-notif-id')));
    const elsewhere =
        event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey;
    if (elsewhere) {
        return;
    }
    event.preventDefault();
    const href = link.href;
    const wait = new Promise((resolve) => {
        window.setTimeout(resolve, OPEN_MARK_READ_WAIT_MS);
    });
    Promise.race([write, wait]).finally(() => {
        window.location.assign(href);
    });
}

/**
 * Handles a middle click on a row link, which opens the subject in a new tab.
 *
 * @param {MouseEvent} event the auxiliary click
 */
function onDocumentAuxClick(event) {
    if (event.button !== 1) {
        return;
    }
    const link = /** @type {Element} */ (event.target).closest('a[data-notif-open]');
    if (link) {
        openNotification(event, /** @type {HTMLAnchorElement} */ (link));
    }
}

/**
 * Deletes one notification and removes its rows in place.
 *
 * @param {string} id the notification id
 * @param {Element | null} submitter the button disabled while the write is in flight
 */
function doDelete(id, submitter) {
    krtFetch
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

/**
 * Marks every notification read and patches the rows in place.
 *
 * @param {Element | null} submitter the button disabled while the write is in flight
 */
function doMarkAll(submitter) {
    krtFetch
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

/**
 * Deletes every read notification after a confirmation and removes the rows in place.
 *
 * @param {Element | null} submitter the button disabled while the write is in flight
 */
function doClearRead(submitter) {
    confirmThen(i18n.confirmClearTitle, i18n.confirmClearBody, () => {
        krtFetch
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

/**
 * Appends the next page of notifications to the notification page's list.
 *
 * @param {HTMLButtonElement} btn the load-more button
 */
function loadMorePage(btn) {
    const list = document.getElementById('notification-page-list');
    if (!list || btn.disabled) {
        return;
    }
    const page =
        parseInt(/** @type {string} */ (btn.getAttribute('data-notif-next-page')), 10) || 1;
    btn.disabled = true;
    readNotifications(`/notifications/page-items?page=${page}`)
        .then((res) => {
            return readJson(res, null);
        })
        .then((data) => {
            if (!data || !Array.isArray(data.items)) {
                return;
            }
            data.items.forEach((/** @type {any} */ item) => {
                if (!list.querySelector(`[data-notif-id="${cssEscape(item.id)}"]`)) {
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
        .catch(() => {})
        .finally(() => {
            btn.disabled = false;
        });
}

/**
 * Rewrites the "shown of total" hint of the notification page.
 *
 * @param {Element} list the list element
 * @param {number | string} total the total number of notifications
 */
function updatePageHint(list, total) {
    const hint = document.querySelector('[data-notif-hint]');
    if (!hint) {
        return;
    }
    const template = hint.getAttribute('data-notif-hint-template') || '';
    const shown = list.querySelectorAll('.notification-item').length;
    hint.textContent = template.replace('{shown}', String(shown)).replace('{total}', String(total));
}

/**
 * Asks for confirmation and runs the action only when it is given.
 *
 * @param {string} title the dialog title
 * @param {string} body the dialog text
 * @param {() => void} action the action to run on confirmation
 */
function confirmThen(title, body, action) {
    showKrtConfirm(title, body, i18n.confirmOk, i18n.confirmCancel).then((ok) => {
        if (ok) {
            action();
        }
    });
}

/**
 * Handles every click of the page: row links, row actions, bulk actions, load-more and the
 * dropdown toggle.
 *
 * @param {MouseEvent} event the click
 */
function onDocumentClick(event) {
    const target = /** @type {Element} */ (event.target);
    const openLink = target.closest('a[data-notif-open]');
    if (openLink) {
        openNotification(event, /** @type {HTMLAnchorElement} */ (openLink));
        return;
    }
    const markReadBtn = target.closest('[data-notif-mark-read]');
    if (markReadBtn) {
        const readLi = markReadBtn.closest('[data-notif-id]');
        if (readLi) {
            doMarkRead(/** @type {string} */ (readLi.getAttribute('data-notif-id')), markReadBtn);
        }
        return;
    }
    const deleteBtn = target.closest('[data-notif-delete]');
    if (deleteBtn) {
        const delLi = deleteBtn.closest('[data-notif-id]');
        if (delLi) {
            doDelete(/** @type {string} */ (delLi.getAttribute('data-notif-id')), deleteBtn);
        }
        return;
    }
    const markAllBtn = target.closest('[data-notif-mark-all]');
    if (markAllBtn) {
        doMarkAll(markAllBtn);
        return;
    }
    const clearReadBtn = target.closest('[data-notif-clear-read]');
    if (clearReadBtn) {
        doClearRead(clearReadBtn);
        return;
    }
    const loadMoreBtn = target.closest('[data-notif-load-more]');
    if (loadMoreBtn) {
        loadMorePage(/** @type {HTMLButtonElement} */ (loadMoreBtn));
        return;
    }
    if (target.closest('#notification-toggle')) {
        toggleDropdown();
        return;
    }
    if (bell && !target.closest('#notification-bell')) {
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
    const base = SSE_RECONNECT_BASE_MS * Math.pow(2, Math.min(sseRefusals, SSE_MAX_BACKOFF_STEPS));
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
            document.dispatchEvent(new CustomEvent('krt:notification'));
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
            krtReauth.redirect(event && event.data ? event.data : null);
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
            krtTermsGate.redirect(event && event.data ? event.data : null);
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
document.addEventListener('auxclick', onDocumentAuxClick);
document.addEventListener('visibilitychange', onVisibilityChange);

startPolling();
startSse();
