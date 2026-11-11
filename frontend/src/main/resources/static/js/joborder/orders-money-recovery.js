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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

/** @typedef {ApiDto<'ProvideJobOrderMoneyDto'>} ProvisionTerms */
/** @typedef {ApiDto<'JobOrderMoneyOutcome'>} ProvisionOutcome */

/**
 * Binds outcome recovery to the authenticated account and the current order.
 *
 * @param {HTMLElement} root the recovery controls and account scope
 */
export function bindProvisionRecovery(root) {
    const url = `/orders/${root.dataset.orderId}/money`;
    const key = `krt:order-provision:${root.dataset.accountId}:${root.dataset.orderId}`;
    const banner = root.querySelector('[data-money-recovery]');
    const check = root.querySelector('[data-money-check]');
    const retry = root.querySelector('[data-money-retry]');
    let busy = false;

    if (
        !(banner instanceof HTMLElement) ||
        !(check instanceof HTMLButtonElement) ||
        !(retry instanceof HTMLButtonElement)
    ) {
        throw new Error('The order provision recovery controls are missing');
    }

    /** @returns {ProvisionTerms | null} the submitted terms for this account and order */
    function pending() {
        const value = localStorage.getItem(key);
        return value ? JSON.parse(value) : null;
    }

    /**
     * Removes a resolved operation and refreshes its visible effects.
     *
     * @param {ProvisionTerms} terms the submitted terms
     */
    const finish = async (terms) => {
        if (pending()?.operationId === terms.operationId) localStorage.removeItem(key);
        banner.hidden = pending() === null;
        showFrontendSuccessToast(root.dataset.recorded || '');
        await joborderHooks.refreshOrderSection?.(['money', 'header', 'actions'], {
            broadcast: false,
        });
    };

    /**
     * Looks up the original identity after an uncertain transport result.
     *
     * @param {ProvisionTerms} terms the submitted terms
     */
    const lookup = async (terms) => {
        retry.hidden = true;
        try {
            /** @type {ProvisionOutcome | null} */
            const outcome = await krtFetch.getJson(`${url}/operations/${terms.operationId}`);
            if (outcome?.operationId === terms.operationId) await finish(terms);
        } catch (error) {
            retry.hidden = !(error instanceof Error && 'status' in error && error.status === 404);
        }
    };

    /** Reads the stored identity before allowing another command. */
    const recover = async () => {
        if (busy) return;
        const terms = pending();
        banner.hidden = !terms;
        if (!terms) return;
        busy = true;
        check.disabled = true;
        try {
            await lookup(terms);
        } finally {
            check.disabled = false;
            busy = false;
        }
    };

    /**
     * Sends the exact terms staged in browser storage for this identity.
     *
     * @param {ProvisionTerms} terms the submitted terms
     * @param {HTMLElement | null} submitter the activated control
     * @param {boolean} retrying whether an earlier response was inconclusive
     * @returns {Promise<boolean>} true when the submission was accepted or its outcome is unknown
     */
    const send = async (terms, submitter, retrying) => {
        if (busy) return false;
        busy = true;
        check.disabled = true;
        try {
            const result = await krtFetch.write({
                method: 'POST',
                url: `${url}/provisions`,
                payload: terms,
                submitter,
                toast: false,
                onError: () => true,
                onNetworkError: () => true,
            });
            /** @type {ProvisionOutcome | null} */
            const outcome = result.body;
            if (result.ok && !result.redirected && outcome?.operationId === terms.operationId) {
                await finish(terms);
                return true;
            }
            if (!retrying && [400, 403, 409, 422].includes(result.status)) {
                if (pending()?.operationId === terms.operationId) localStorage.removeItem(key);
                banner.hidden = true;
                showFrontendErrorToast(root.dataset.provideError || '');
                if (result.status === 403 || result.status === 409) {
                    await joborderHooks.refreshOrderSection?.(['money', 'header', 'actions'], {
                        broadcast: false,
                    });
                }
                return false;
            }
            banner.hidden = false;
            retry.hidden = true;
            if (retrying && result.status >= 400 && result.status < 500) {
                await lookup(terms);
            }
            return true;
        } finally {
            check.disabled = false;
            busy = false;
        }
    };

    /**
     * Stages a new identity before contacting the server.
     *
     * @param {Omit<ProvisionTerms, 'operationId'>} fields the explicitly submitted terms
     * @param {HTMLElement | null} submitter the activated control
     * @returns {Promise<boolean>} whether the submitted form can close
     */
    const submit = async (fields, submitter) => {
        let terms;
        try {
            terms = await navigator.locks.request(key, () => {
                if (pending()) return null;
                const submitted = { ...fields, operationId: crypto.randomUUID() };
                localStorage.setItem(key, JSON.stringify(submitted));
                return submitted;
            });
        } catch {
            showFrontendErrorToast(root.dataset.storageError || '');
            return false;
        }
        if (!terms) {
            await recover();
            return false;
        }
        return send(terms, submitter, false);
    };

    check.addEventListener('click', () => void recover());
    retry.addEventListener('click', () => {
        const terms = pending();
        if (terms) void send(terms, retry, true);
    });
    void recover();
    return { pending, recover, submit };
}
