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
/** @typedef {ApiDto<'MoveJobOrderMoneyDto'>} MovementTerms */
/** @typedef {ApiDto<'JobOrderMoneyOutcome'>} MoneyOutcome */
/** @typedef {Omit<ApiDto<'SettleJobOrderMoneyDto'>, 'operationId'>} SettlementTerms */
/** @typedef {Omit<ApiDto<'ReassignJobOrderMoneyDto'>, 'operationId'>} ReassignmentTerms */
/** @typedef {Omit<ApiDto<'CorrectJobOrderMoneyDto'>, 'operationId'>} CorrectionTerms */
/**
 * @typedef {{ command: 'movements', terms: Omit<MovementTerms, 'operationId'> }
 *     | { command: 'movements/settlement', terms: SettlementTerms }
 *     | { command: 'movements/reassignment', terms: ReassignmentTerms, destinationNumber: string }}
 *     MovementDraft
 */
/**
 * @typedef {{ command: 'provisions', terms: Omit<ProvisionTerms, 'operationId'> }
 *     | MovementDraft | { command: 'corrections', terms: CorrectionTerms }} Draft
 */
/** @typedef {ApiDto<'ReplaceJobOrderBankAttemptDto'>} RepairTerms */
/** @typedef {{ command: 'bank-replacement', existingOperationId: string, terms: RepairTerms }} RepairDraft */
/** @typedef {(Draft | RepairDraft) & { operationId: string }} Submission */

/**
 * Matches the exact accepted logical attempt, including a replacement's expected revision.
 *
 * @param {Submission} terms the staged immutable submission
 * @param {MoneyOutcome | null} outcome the returned outcome
 * @returns {boolean} whether the outcome matches the immutable submission
 */
function matchesOutcome(terms, outcome) {
    if (outcome?.operationId !== terms.operationId || outcome.state === 'SUBMITTING') return false;
    if (terms.command !== 'bank-replacement') return true;
    return (
        outcome.amount === terms.terms.amount &&
        (terms.terms.outsideBank
            ? outcome.bankHandling === false && outcome.state === 'POSTED'
            : outcome.linkedRevision === Number(terms.terms.revision) + 1)
    );
}

/**
 * Builds the original command URL and wire terms for a staged submission.
 *
 * @param {string} url the Order money resource
 * @param {Submission} terms the staged immutable submission
 */
function commandRequest(url, terms) {
    if (terms.command === 'bank-replacement') {
        return {
            url: `${url}/operations/${terms.operationId}/bank-replacement`,
            payload: terms.terms,
        };
    }
    return {
        url: `${url}/${terms.command}`,
        payload: { ...terms.terms, operationId: terms.operationId },
    };
}

/**
 * Announces the authoritative result of the accepted attempt.
 *
 * @param {HTMLElement} root the localized money workspace
 * @param {MoneyOutcome} outcome the accepted result
 */
function announceOutcome(root, outcome) {
    switch (outcome.state) {
        case 'WAITING':
            showFrontendSuccessToast(root.dataset.bankWaiting || '');
            break;
        case 'AUTHORISED':
            showFrontendSuccessToast(root.dataset.bankAuthorised || '');
            break;
        case 'POSTED':
            showFrontendSuccessToast(root.dataset.recorded || '');
            break;
        case 'REJECTED':
        case 'CANCELLED':
        case 'REVERSED':
        case 'CLARIFICATION': {
            const status = {
                REJECTED: root.dataset.bankRejected,
                CANCELLED: root.dataset.bankCancelled,
                REVERSED: root.dataset.bankReversed,
                CLARIFICATION: root.dataset.bankClarification,
            };
            showFrontendErrorToast(status[outcome.state] || '');
            break;
        }
    }
}

/**
 * Identifies a permanently refused command from its authoritative outcome.
 *
 * @param {unknown} status the HTTP status
 * @param {unknown} problem the relayed problem document
 * @returns {boolean} whether the original operation can never book
 */
function definitivelyRefused(status, problem) {
    return (
        status === 409 &&
        problem !== null &&
        typeof problem === 'object' &&
        'code' in problem &&
        ['MONEY_PROVISION_REFUSED', 'MONEY_MOVEMENT_REFUSED'].some((code) => problem.code === code)
    );
}

/**
 * Binds outcome recovery to the authenticated account and the current order.
 *
 * @param {HTMLElement} root the recovery controls and account scope
 * @param {(draft: Submission) => void} restoreRefused the editor for the refused terms
 */
export function bindMoneyRecovery(root, restoreRefused) {
    const url = `/orders/${root.dataset.orderId}/money`;
    const key = `krt:order-provision:${root.dataset.accountId}:${root.dataset.orderId}`;
    const banner = root.querySelector('[data-money-recovery]');
    const check = root.querySelector('[data-money-check]');
    const retry = root.querySelector('[data-money-retry]');
    /** @type {Promise<boolean> | null} */
    let recovering = null;
    let sending = false;
    let opening = false;

    if (
        !(banner instanceof HTMLElement) ||
        !(check instanceof HTMLButtonElement) ||
        !(retry instanceof HTMLButtonElement)
    ) {
        throw new Error('The order money recovery controls are missing');
    }

    /** @param {boolean} waiting whether the result is being resolved */
    const showWaiting = (waiting) => {
        check.disabled = waiting;
        banner.setAttribute('aria-busy', String(waiting));
    };

    /** @returns {Submission | null} the submitted terms for this account and order */
    function pending() {
        const value = localStorage.getItem(key);
        if (!value) return null;
        const saved = JSON.parse(value);
        if (saved.command) return saved;
        const { operationId, ...terms } = saved;
        return { command: 'provisions', operationId, terms };
    }

    /**
     * Clears only this resolved operation from the account's current order slot.
     *
     * @param {Submission} terms the resolved submission
     * @returns {Promise<boolean>} whether this operation still held the recovery slot
     */
    const forget = (terms) =>
        navigator.locks.request(key, () => {
            const current = pending();
            const matches =
                current?.operationId === terms.operationId &&
                current.command === terms.command &&
                (terms.command !== 'bank-replacement' ||
                    (current.command === 'bank-replacement' &&
                        current.terms.revision === terms.terms.revision));
            if (matches) localStorage.removeItem(key);
            banner.hidden = pending() === null;
            return matches;
        });

    /** Reads the current holder and order state after a resolved submission. */
    const refresh = () =>
        joborderHooks.refreshOrderSection?.(['money', 'header', 'actions', 'participations'], {
            broadcast: false,
        });

    /**
     * Removes a resolved operation and refreshes its visible effects.
     *
     * @param {Submission} terms the submitted terms
     * @param {MoneyOutcome} outcome the accepted result
     * @returns {Promise<boolean>} whether the money section was refreshed
     */
    const finish = async (terms, outcome) => {
        if (await forget(terms)) announceOutcome(root, outcome);
        const [moneyRefreshed] = (await refresh()) || [];
        return moneyRefreshed === true;
    };

    /**
     * Restores the terms of a permanent refusal against the latest holder basis.
     *
     * @param {Submission} terms the refused submission
     */
    const refuse = async (terms) => {
        if (!(await forget(terms))) return;
        await refresh();
        await navigator.locks.request(key, () => {
            if (pending()) {
                banner.hidden = false;
                return;
            }
            restoreRefused(terms);
            showFrontendErrorToast(root.dataset.refused || '');
        });
    };

    /**
     * Checks an observed successor against the exact staged repair and its submitting actor.
     *
     * @param {Submission} terms the staged repair
     * @returns {Promise<boolean>} whether recovery was resolved
     */
    const verifyReplacement = async (terms) => {
        const result = await krtFetch.write({
            method: 'POST',
            ...commandRequest(url, terms),
            toast: false,
            onError: () => true,
            onNetworkError: () => true,
        });
        /** @type {MoneyOutcome | null} */
        const outcome = result.body;
        if (result.ok && !result.redirected && outcome && matchesOutcome(terms, outcome)) {
            return finish(terms, outcome);
        }
        if ([403, 409].includes(result.status)) {
            if (await forget(terms)) showFrontendErrorToast(root.dataset.refused || '');
            const [moneyRefreshed] = (await refresh()) || [];
            return moneyRefreshed === true;
        }
        return false;
    };

    /**
     * Looks up the original identity after an uncertain transport result.
     *
     * @param {Submission} terms the submitted terms
     */
    const lookup = async (terms) => {
        retry.hidden = true;
        try {
            /** @type {MoneyOutcome | null} */
            const outcome = await krtFetch.getJson(`${url}/operations/${terms.operationId}`);
            if (outcome?.operationId !== terms.operationId) return false;
            if (terms.command !== 'bank-replacement') {
                return matchesOutcome(terms, outcome) && (await finish(terms, outcome));
            }
            const revision = Number(outcome.linkedRevision);
            const submittedRevision = Number(terms.terms.revision);
            if (
                revision > submittedRevision ||
                (outcome.bankHandling === false && outcome.state === 'POSTED')
            ) {
                return await verifyReplacement(terms);
            }
            if (
                revision === submittedRevision &&
                (outcome.state === 'REJECTED' || outcome.state === 'CANCELLED')
            ) {
                retry.hidden = false;
            }
        } catch (error) {
            if (error instanceof Error && 'status' in error) {
                if ('problem' in error && definitivelyRefused(error.status, error.problem)) {
                    await refuse(terms);
                } else {
                    retry.hidden = error.status !== 404;
                }
            }
        }
        return false;
    };

    /** Shares the pending outcome lookup until its resulting state has refreshed. */
    const recover = () => {
        if (recovering) return recovering;
        if (sending) return Promise.resolve(false);
        const terms = pending();
        banner.hidden = !terms;
        if (!terms) return Promise.resolve(false);
        showWaiting(true);
        recovering = lookup(terms).finally(() => {
            recovering = null;
            showWaiting(false);
        });
        return recovering;
    };

    /**
     * Preserves one editor-opening click while the submitted operation is being recovered.
     *
     * @param {() => void} open the requested editor action
     */
    const openWhenRecovered = async (open) => {
        if (opening) return;
        if (!recovering && !sending && !pending()) {
            open();
            return;
        }
        opening = true;
        try {
            if ((await recover()) && !pending()) open();
        } finally {
            opening = false;
        }
    };

    /**
     * Sends the exact terms staged in browser storage for this identity.
     *
     * @param {Submission} terms the submitted terms
     * @param {HTMLElement | null} submitter the activated control
     * @param {boolean} retrying whether an earlier response was inconclusive
     * @returns {Promise<boolean>} true when the submission was accepted or its outcome is unknown
     */
    const send = async (terms, submitter, retrying) => {
        if (recovering || sending) return false;
        sending = true;
        showWaiting(true);
        try {
            const result = await krtFetch.write({
                method: 'POST',
                ...commandRequest(url, terms),
                submitter,
                toast: false,
                onError: () => true,
                onNetworkError: () => true,
            });
            /** @type {MoneyOutcome | null} */
            const outcome = result.body;
            if (result.ok && !result.redirected && outcome && matchesOutcome(terms, outcome)) {
                await finish(terms, outcome);
                return true;
            }
            if (definitivelyRefused(result.status, result.body)) {
                await refuse(terms);
                return false;
            }
            if (!retrying && [400, 403, 409, 422].includes(result.status)) {
                await forget(terms);
                showFrontendErrorToast(root.dataset.provideError || '');
                if (result.status === 403 || result.status === 409) {
                    await refresh();
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
            sending = false;
            showWaiting(false);
        }
    };

    /**
     * Stages a new identity before contacting the server.
     *
     * @param {Draft | RepairDraft} draft the explicitly submitted terms
     * @param {HTMLElement | null} submitter the activated control
     * @returns {Promise<boolean>} whether the submitted form can close
     */
    const submit = async (draft, submitter) => {
        let terms;
        try {
            terms = await navigator.locks.request(key, () => {
                if (recovering || sending || pending()) return null;
                const operationId =
                    draft.command === 'bank-replacement'
                        ? draft.existingOperationId
                        : crypto.randomUUID();
                const submitted = { ...draft, operationId };
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
    return { openWhenRecovered, recover, submit };
}
