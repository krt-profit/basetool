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
    if (!window.krtFetch) {
        return;
    }
    const krtFetch = window.krtFetch;
    /** @type {Record<string, string>} */
    const i18n = window.krtProfileI18n || {};

    const BASE_URL = '/profile/notification-preferences';
    const HOST = '#profile-notifications-host';

    /**
     * Re-renders the card from the server, which restores the stored state after a failed write.
     *
     * @returns {Promise<boolean> | undefined} the swap, or undefined when no swap helper exists
     */
    function refreshCard() {
        if (!krtFetch.swap) {
            return undefined;
        }
        return krtFetch.swap({
            url: BASE_URL,
            container: HOST,
            fragmentValue: 'card',
            errorMessage: i18n.notificationPrefsError,
        });
    }

    const host = document.querySelector(HOST);
    if (!host) {
        return;
    }

    host.addEventListener('change', (event) => {
        const target = event.target;
        if (!(target instanceof HTMLInputElement) || target.type !== 'checkbox') {
            return;
        }
        const type = target.dataset.notificationType;
        if (!type) {
            return;
        }
        krtFetch.write({
            method: 'PUT',
            url: `${BASE_URL}/${encodeURIComponent(type)}`,
            payload: { muted: !target.checked },
            serialize: 'profile-notification-prefs',
            submitter: target,
            successMessage: i18n.saved,
            errorMessage: i18n.notificationPrefsError,
            onError() {
                refreshCard();
                return false;
            },
            onNetworkError() {
                refreshCard();
                return false;
            },
        });
    });
})();
