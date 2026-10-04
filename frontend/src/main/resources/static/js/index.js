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
    'use strict';

    /** How many of the newest notifications the home card lists. */
    const NOTIFICATION_LIMIT = 3;

    /**
     * Expands or clamps the announcement text and relabels the toggle.
     *
     * @param {Element} toggle the "Weiterlesen" button
     */
    function toggleInfo(toggle) {
        const body = document.getElementById('info-content');
        if (!body) return;
        const expanded = body.classList.toggle('is-clamped') === false;
        toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
        const label = toggle.getAttribute(expanded ? 'data-less' : 'data-more');
        if (label) toggle.textContent = label;
    }

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'index-toggle-info', (toggle) => {
            toggleInfo(toggle);
        });
    }

    /**
     * Renders the newest notifications into the home card; the unread ones carry a dot and are
     * counted on the card's badge.
     *
     * @param {Array<{ text?: string, read?: boolean, createdAtDisplay?: string, href?: string }>} items
     *     the notifications, newest first
     */
    function renderNotifications(items) {
        const list = document.querySelector('[data-home-notif-list]');
        const empty = /** @type {HTMLElement | null} */ (
            document.querySelector('[data-home-notif-empty]')
        );
        const count = /** @type {HTMLElement | null} */ (
            document.querySelector('[data-home-notif-count]')
        );
        if (!list) return;
        list.replaceChildren();
        const shown = items.slice(0, NOTIFICATION_LIMIT);
        shown.forEach((item) => {
            const li = document.createElement('li');
            li.className = `home-notif${item.read ? '' : ' is-unread'}`;
            const safe =
                item.href && typeof window.safeSameOriginUrl === 'function'
                    ? window.safeSameOriginUrl(item.href)
                    : null;
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
        const unread = items.filter((item) => {
            return !item.read;
        }).length;
        if (count) {
            count.hidden = unread === 0;
            count.textContent = String(unread);
        }
    }

    /** Loads the newest notifications from the bell's endpoint. */
    function loadNotifications() {
        if (!document.querySelector('[data-home-notif-list]')) return;
        fetch('/notifications/recent', {
            headers: { 'X-Requested-With': 'XMLHttpRequest', Accept: 'application/json' },
            credentials: 'same-origin',
        })
            .then((res) => {
                return res.ok ? res.json() : [];
            })
            .then((items) => {
                renderNotifications(Array.isArray(items) ? items : []);
            })
            .catch(() => {
                renderNotifications([]);
            });
    }

    document.addEventListener('DOMContentLoaded', () => {
        loadNotifications();

        const announcementForm = document.getElementById('announcement-read-form');
        if (announcementForm && window.krtFetch) {
            announcementForm.addEventListener('submit', (event) => {
                event.preventDefault();
                const idInput = /** @type {HTMLInputElement | null} */ (
                    announcementForm.querySelector('input[name="id"]')
                );
                const announcementId = idInput ? idInput.value : '';
                window.krtFetch.write({
                    method: 'POST',
                    url: `/announcement/read?id=${encodeURIComponent(announcementId)}`,
                    toast: false,
                    onSuccess() {
                        announcementForm.remove();
                        const section = document.getElementById('info-section');
                        if (section) {
                            section.classList.remove('is-unread');
                            const dot = section.querySelector('.home-info__dot');
                            if (dot) dot.remove();
                        }
                    },
                });
            });
        }
    });
})();
