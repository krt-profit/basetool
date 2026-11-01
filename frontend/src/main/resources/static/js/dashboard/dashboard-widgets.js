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

import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';

/** How often a widget without a live topic reloads while the tab is visible (REQ-UI-034). */
const POLL_INTERVAL_MS = 10 * 60 * 1000;

/** The delay between the first polls of two widgets, so they do not reload together. */
const POLL_STAGGER_MS = 1500;

/** How many staggered slots the first polls spread over. */
const POLL_STAGGER_SLOTS = 8;

/** The widget types a live channel updates; every other type polls. */
const LIVE_TYPES = new Set(['UPCOMING_DAYS', 'NEXT_MISSION', 'NOTIFICATIONS', 'HANGAR_READINESS']);

/** The options a widget request carries, as the item's data attributes name them. */
const OPTION_PARAMS = ['unit', 'days', 'rows', 'status'];

/** The document event the notification stream raises for every new notification. */
const NOTIFICATION_EVENT = 'krt:notification';

/** @type {Set<number>} the pending first-poll timers and poll intervals */
const timers = new Set();

/**
 * The widget instances on the page.
 *
 * @returns {HTMLElement[]} the instance elements in document order
 */
export function dashboardItems() {
    return /** @type {HTMLElement[]} */ (
        Array.from(document.querySelectorAll('[data-dashboard-item]'))
    );
}

/**
 * The URL of an instance's body, carrying its options.
 *
 * @param {HTMLElement} item the instance
 * @returns {string} the widget endpoint with the options as query
 */
function widgetUrl(item) {
    const query = new URLSearchParams();
    OPTION_PARAMS.forEach((name) => {
        const value = item.dataset[name];
        if (value) {
            query.set(name, value);
        }
    });
    const suffix = query.size > 0 ? `?${query.toString()}` : '';
    return `/dashboard/widgets/${item.dataset.widgetSlug}${suffix}`;
}

/**
 * Shows the error state of one instance, with a button that loads it again.
 *
 * @param {HTMLElement} item the instance
 * @param {Element} body the instance's body
 */
function showError(item, body) {
    const board = item.closest('#dashboard-board');
    const message = document.createElement('p');
    message.className = 'home-notifs__empty';
    message.setAttribute('role', 'alert');
    message.textContent = board instanceof HTMLElement ? (board.dataset.widgetError ?? '') : '';
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'btn btn-secondary btn-xs';
    retry.dataset.trigger = 'dashboard-widget-retry';
    retry.textContent = board instanceof HTMLElement ? (board.dataset.retry ?? '') : '';
    body.replaceChildren(message, retry);
}

/**
 * Renders the newest notifications into a notifications instance; the unread ones carry a dot and
 * are counted on the badge.
 *
 * @param {HTMLElement} content the instance's body content
 * @param {Array<{ text?: string, read?: boolean, createdAtDisplay?: string, href?: string }>} items
 *     the notifications, newest first
 */
function renderNotifications(content, items) {
    const list = content.querySelector('[data-home-notif-list]');
    const empty = /** @type {HTMLElement | null} */ (
        content.querySelector('[data-home-notif-empty]')
    );
    const count = /** @type {HTMLElement | null} */ (
        content.querySelector('[data-home-notif-count]')
    );
    if (!list) return;
    const rows = Number(content.dataset.notificationRows) || 5;
    const unreadOnly = content.dataset.notificationStatus === 'UNREAD';
    const unread = items.filter((item) => !item.read);
    const shown = (unreadOnly ? unread : items).slice(0, rows);
    list.replaceChildren();
    shown.forEach((item) => {
        const li = document.createElement('li');
        li.className = `home-notif${item.read ? '' : ' is-unread'}`;
        const safe = item.href ? safeSameOriginUrl(item.href) : null;
        const main = document.createElement(safe ? 'a' : 'div');
        main.className = 'home-notif__main';
        if (safe && main instanceof HTMLAnchorElement) main.href = safe;
        const text = document.createElement('span');
        text.className = 'cell-title';
        text.textContent = item.text != null ? item.text : '';
        const time = document.createElement('span');
        time.className = 'cell-sub';
        time.textContent = item.createdAtDisplay != null ? item.createdAtDisplay : '';
        main.appendChild(text);
        main.appendChild(time);
        li.appendChild(main);
        list.appendChild(li);
    });
    if (empty) empty.hidden = shown.length > 0;
    if (count) {
        count.hidden = unread.length === 0;
        count.textContent = String(unread.length);
    }
}

/**
 * Fills a freshly loaded notifications instance from the bell's endpoint.
 *
 * @param {HTMLElement} content the instance's body content
 */
async function fillNotifications(content) {
    try {
        const items = await krtFetch.getJson('/notifications/recent');
        renderNotifications(content, Array.isArray(items) ? items : []);
    } catch (_error) {
        renderNotifications(content, []);
    }
}

/** @type {KrtReceiver | null} the receiver of the member's own hangar room, once created */
let hangarReceiver = null;

/** The hangar room the receiver listens to now, so a reload does not subscribe again. */
let hangarTopic = '';

/**
 * Listens to the member's own hangar room, whose name the hangar-readiness body carries, and
 * reloads the hangar-readiness instances when a ship changes.
 *
 * @param {string} topic the room, or an empty string while the member is unknown
 */
function followHangarRoom(topic) {
    if (!topic || topic === hangarTopic) return;
    hangarTopic = topic;
    if (hangarReceiver) {
        hangarReceiver.retarget(topic);
        return;
    }
    hangarReceiver = krtLiveSync.createReceiver({
        topic,
        sections: { ships: { container: '[data-widget-type="HANGAR_READINESS"]' } },
        coalesceMs: 1500,
        refresh() {
            reloadType('HANGAR_READINESS');
        },
    });
}

/**
 * Loads one instance's body from its own endpoint; a failure shows that instance's error state and
 * leaves the others alone. An instance the active context does not offer loads nothing.
 *
 * @param {HTMLElement} item the instance
 * @returns {Promise<void>} settles when the body is in place
 */
export async function loadWidget(item) {
    const body = item.querySelector('[data-widget-body]');
    if (!body || item.dataset.available !== 'true') return;
    try {
        const response = await krtFetch.get(widgetUrl(item), {
            accept: 'text/html',
            key: `dashboard-${item.dataset.id}`,
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
        });
        if (!response) return;
        if (!response.ok) {
            showError(item, body);
            return;
        }
        krtFetch.setTrustedHtml(body, await response.text());
        const content = body.querySelector('[data-widget-content]');
        if (item.dataset.widgetType === 'NOTIFICATIONS' && content instanceof HTMLElement) {
            await fillNotifications(content);
        }
        if (item.dataset.widgetType === 'HANGAR_READINESS' && content instanceof HTMLElement) {
            followHangarRoom(content.dataset.liveSyncTopic ?? '');
        }
    } catch (_error) {
        showError(item, body);
    }
}

/**
 * Loads every instance of a widget type again.
 *
 * @param {string} type the widget type name
 */
function reloadType(type) {
    dashboardItems()
        .filter((item) => item.dataset.widgetType === type)
        .forEach((item) => {
            loadWidget(item);
        });
}

/** Stops every poll started by {@link startPolling}. */
export function stopPolling() {
    timers.forEach((id) => {
        window.clearTimeout(id);
        window.clearInterval(id);
    });
    timers.clear();
}

/**
 * Polls every instance without a live channel every ten minutes while the tab is visible, the
 * first polls staggered.
 */
export function startPolling() {
    stopPolling();
    dashboardItems()
        .filter((item) => !LIVE_TYPES.has(item.dataset.widgetType ?? ''))
        .forEach((item, index) => {
            const first = window.setTimeout(
                () => {
                    timers.delete(first);
                    const interval = window.setInterval(() => {
                        if (document.visibilityState === 'visible') loadWidget(item);
                    }, POLL_INTERVAL_MS);
                    timers.add(interval);
                },
                (index % POLL_STAGGER_SLOTS) * POLL_STAGGER_MS,
            );
            timers.add(first);
        });
}

/**
 * Loads every instance and starts the polls; call it again after the board was swapped.
 */
export function loadAll() {
    dashboardItems().forEach((item) => {
        loadWidget(item);
    });
    startPolling();
}

/**
 * Wires the live updates: the missions topic reloads the upcoming-days and next-mission instances,
 * a new notification the notifications instances, and a tab that becomes visible again reloads every
 * polled instance at once.
 */
export function wireLiveUpdates() {
    krtLiveSync.createReceiver({
        topic: 'missions',
        sections: {
            list: {
                container: '[data-widget-type="UPCOMING_DAYS"], [data-widget-type="NEXT_MISSION"]',
            },
        },
        coalesceMs: 1500,
        refresh() {
            reloadType('UPCOMING_DAYS');
            reloadType('NEXT_MISSION');
        },
    });
    document.addEventListener(NOTIFICATION_EVENT, () => {
        reloadType('NOTIFICATIONS');
    });
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState !== 'visible') return;
        dashboardItems()
            .filter((item) => !LIVE_TYPES.has(item.dataset.widgetType ?? ''))
            .forEach((item) => {
                loadWidget(item);
            });
    });
}

/**
 * Expands or clamps an announcement and relabels the toggle.
 *
 * @param {HTMLElement} toggle the "Weiterlesen" button
 */
function toggleInfo(toggle) {
    const body = toggle.closest('.dash-info')?.querySelector('[data-info-body]');
    if (!body) return;
    const expanded = body.classList.toggle('is-clamped') === false;
    toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    const label = toggle.getAttribute(expanded ? 'data-less' : 'data-more');
    if (label) toggle.textContent = label;
}

/**
 * Shows or hides the inline editor of the announcement.
 *
 * @param {HTMLElement} trigger a control inside the information instance
 * @param {boolean} editing whether the editor is shown
 */
function setInfoEditing(trigger, editing) {
    const info = trigger.closest('.dash-info');
    info?.querySelector('[data-info-editor]')?.classList.toggle('is-hidden', !editing);
    info?.querySelector('.dash-info__admin')?.classList.toggle('is-hidden', editing);
}

/**
 * Registers the delegated handlers of the widgets: the announcement's expander, its read marker and
 * inline editor, and the retry of a failed instance.
 */
export function initWidgetActions() {
    krtEvents.on('click', 'dashboard-info-toggle', toggleInfo);
    krtEvents.on('click', 'dashboard-info-edit', (trigger) => setInfoEditing(trigger, true));
    krtEvents.on('click', 'dashboard-info-cancel', (trigger) => setInfoEditing(trigger, false));
    krtEvents.on('click', 'dashboard-widget-retry', (trigger) => {
        const item = trigger.closest('[data-dashboard-item]');
        if (item instanceof HTMLElement) loadWidget(item);
    });
    krtEvents.on('submit', 'dashboard-info-read', (form, event) => {
        event.preventDefault();
        const id = form.querySelector('input[name="id"]');
        const item = form.closest('[data-dashboard-item]');
        krtFetch.write({
            method: 'POST',
            url: `/announcement/read?id=${encodeURIComponent(id instanceof HTMLInputElement ? id.value : '')}`,
            toast: false,
            onSuccess() {
                if (item instanceof HTMLElement) loadWidget(item);
            },
        });
    });
    krtEvents.on('submit', 'dashboard-info-save', (form, event) => {
        event.preventDefault();
        const text = form.querySelector('textarea[name="content"]');
        const item = form.closest('[data-dashboard-item]');
        const version = form.dataset.version;
        krtFetch.write({
            method: 'POST',
            url: '/admin/announcement/update',
            payload: {
                content: text instanceof HTMLTextAreaElement ? text.value : '',
                version: version ? Number(version) : null,
            },
            toast: false,
            errorMessage: form.dataset.error,
            onSuccess() {
                if (item instanceof HTMLElement) loadWidget(item);
            },
        });
    });
}
