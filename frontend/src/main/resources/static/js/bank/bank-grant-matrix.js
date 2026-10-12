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

import { genericError } from '/js/bank/bank-messages.js';

/** Maps a grant flag name onto the row attribute the next toggle click reads. */
const FLAG_ATTR = {
    canDeposit: 'data-can-deposit',
    canWithdraw: 'data-can-withdraw',
    canTransfer: 'data-can-transfer',
};

/**
 * Applies a successful grant flag PATCH in place: sets the button's pressed state, the row's
 * `data-can-<flag>` attribute and the row's `data-version` from the response.
 *
 * @param {HTMLButtonElement} button the clicked matrix-flag button
 * @param {HTMLTableRowElement} row the grant row carrying the flag + version attributes
 * @param {string} flag the toggled flag name (canDeposit / canWithdraw / canTransfer)
 * @param {boolean} newValue the value the flag was toggled to
 * @param {Object} payload the BankGrantDto response body (carries the fresh version)
 */
function applyGrantFlagResult(button, row, flag, newValue, payload) {
    button.classList.toggle('on', newValue);
    button.setAttribute('aria-pressed', String(newValue));
    const attr = FLAG_ATTR[flag];
    if (attr) {
        row.setAttribute(attr, String(newValue));
    }
    const version = payload ? payload.version : null;
    if (version == null) {
        return;
    }
    if (window.krtFetch && typeof window.krtFetch.syncVersion === 'function') {
        window.krtFetch.syncVersion(row, version);
    } else {
        row.setAttribute('data-version', String(version));
    }
}

/**
 * Registers the document listeners of the grants matrix: the flag toggle and the grants filter.
 */
export function initGrantMatrix() {
    /**
     * Grants matrix: clicking a flag cell PATCHes the grant with all three flags and the row's
     * version, then patches the row in place.
     */
    document.addEventListener('click', async (event) => {
        const target = /** @type {Element} */ (event.target);
        const flagButton = /** @type {HTMLButtonElement | null} */ (
            target.closest('button.matrix-flag[data-flag]')
        );
        if (!flagButton) {
            return;
        }
        const row = /** @type {HTMLTableRowElement | null} */ (
            flagButton.closest('tr[data-user-id][data-account-id]')
        );
        if (!row) {
            return;
        }
        const flag = /** @type {string} */ (flagButton.getAttribute('data-flag'));
        const newValue = row.getAttribute(FLAG_ATTR[flag]) !== 'true';
        const endpoint = `/api/proxy/bank/grants/${encodeURIComponent(
            /** @type {string} */ (row.getAttribute('data-user-id')),
        )}/${encodeURIComponent(/** @type {string} */ (row.getAttribute('data-account-id')))}`;
        await window.krtFetch.write({
            method: 'PATCH',
            url: endpoint,
            serialize: `bank-grant:${row.getAttribute('data-user-id')}:${row.getAttribute(
                'data-account-id',
            )}`,
            payload() {
                const p = {
                    canDeposit: row.getAttribute('data-can-deposit') === 'true',
                    canWithdraw: row.getAttribute('data-can-withdraw') === 'true',
                    canTransfer: row.getAttribute('data-can-transfer') === 'true',
                    version: Number(row.getAttribute('data-version')),
                };
                p[flag] = newValue;
                return p;
            },
            submitter: flagButton,
            toast: false,
            errorMessage: genericError(),
            onSuccess(payload) {
                applyGrantFlagResult(flagButton, row, flag, newValue, payload);
                if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                    window.krtLiveSync.sendChanged('bank', ['grants']);
                }
            },
            onError(status, payload) {
                const message = payload && payload.message ? payload.message : genericError();
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(message);
                }
                return true;
            },
            onNetworkError() {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(genericError());
                }
                return true;
            },
        });
    });

    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const select = /** @type {HTMLSelectElement | null} */ (
            target.closest('[data-role="bank-grants-filter"]')
        );
        if (!select) {
            return;
        }
        const params = new URLSearchParams();
        params.set('view', /** @type {string} */ (select.getAttribute('data-view')));
        if (select.value) {
            params.set(/** @type {string} */ (select.getAttribute('data-param')), select.value);
        }
        window.location.assign(`/bank/grants?${params.toString()}`);
    });
}
