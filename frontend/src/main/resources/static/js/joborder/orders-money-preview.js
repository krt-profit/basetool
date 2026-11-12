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
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { formatParts, separatorsFor } from '/js/kernel/krt-number-format.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

/**
 * Keeps a draft's related order basis current through its authorized order room.
 *
 * @returns {(order: string, refresh: (changed: boolean) => void) => void} the peer room binding
 */
export function bindMoneyPeer() {
    let subscribedOrder = '';
    /** @type {{unsubscribe(): void} | null} */
    let subscription = null;
    return (order, refresh) => {
        if (order === subscribedOrder) return;
        subscription?.unsubscribe();
        subscribedOrder = order;
        /** @param {boolean} changed whether the room reported an actual change */
        const refreshCurrent = (changed) => {
            if (order === subscribedOrder) refresh(changed);
        };
        subscription = order
            ? krtLiveSync.subscribe(`order:${order}`, {
                  onSubscribed: () => refreshCurrent(false),
                  onChanged(sections) {
                      if (sections.includes('money') || sections.includes('header')) {
                          refreshCurrent(true);
                      }
                  },
                  onDenied: () => refreshCurrent(true),
              })
            : null;
    };
}

const seps = separatorsFor(document.documentElement.lang);

/** @param {string} amount the exact server amount */
function formatWholeAuec(amount) {
    return formatParts({ neg: false, int: amount, frac: null }, seps);
}

/** @typedef {ApiDto<'PreviewJobOrderMoneyProvisionDto'>} PreviewTerms */
/** @typedef {ApiDto<'JobOrderMoneyProvisionPreview'>} ProvisionPreview */
/** @typedef {ApiDto<'JobOrderMoneyBankPreview'>} BankPreview */

/**
 * Enables saving while the server preview matches the draft.
 *
 * @template {object} Terms
 * @template {object} Ready
 * @param {HTMLFormElement} form the money form
 * @param {string | ((terms: Terms) => {url: string, payload: object})} url the preview request
 * @param {() => Terms | null} readTerms the draft at its observed revisions
 * @param {(output: HTMLElement, body: unknown, terms: Terms) => Ready | null | undefined} render
 *     the preview renderer; null indicates incomplete terms and undefined indicates a failed preview
 */
export function bindMoneyPreview(form, url, readTerms, render) {
    const output = form.querySelector('[data-money-preview]');
    const retry = form.querySelector('[data-money-preview-retry]');
    const problem = form.querySelector('[data-money-preview-error]');
    const save = form.querySelector('button[type="submit"]');
    if (
        !(output instanceof HTMLElement) ||
        !(retry instanceof HTMLButtonElement) ||
        !(save instanceof HTMLButtonElement)
    ) {
        throw new Error('The order money preview controls are missing');
    }
    /** @type {Terms | null} */
    let candidate = null;
    /** @type {Ready | null} */
    let ready = null;
    let timer = 0;
    let refreshedBasis = '';

    /** Invalidates the old preview before waiting for a new response. */
    const reset = () => {
        window.clearTimeout(timer);
        candidate = null;
        ready = null;
        output.hidden = true;
        retry.hidden = true;
        problem?.setAttribute('hidden', '');
        save.disabled = true;
    };

    /**
     * Shows an authorised preview when it still describes the draft.
     *
     * @param {Terms} terms the proposed terms
     */
    const request = async (terms) => {
        const target = typeof url === 'string' ? { url, payload: terms } : url(terms);
        const result = await krtFetch.write({
            method: 'POST',
            ...target,
            toast: false,
            onError: () => true,
            onNetworkError: () => true,
        });
        if (candidate !== terms) return;
        const rendered =
            result.ok && !result.redirected ? render(output, result.body, terms) : undefined;
        if (rendered === undefined) {
            retry.hidden = result.status === 403;
            const details = result.body;
            if (problem instanceof HTMLElement) {
                const detail =
                    details &&
                    typeof details === 'object' &&
                    'detail' in details &&
                    typeof details.detail === 'string'
                        ? details.detail
                        : undefined;
                problem.textContent =
                    detail ||
                    (result.status === 409
                        ? problem.dataset.stale
                        : result.status === 403
                          ? problem.dataset.denied
                          : problem.dataset.unavailable) ||
                    '';
                problem.hidden = !problem.textContent;
            }
            const basis = JSON.stringify(terms);
            if (result.status === 409 && basis !== refreshedBasis) {
                refreshedBasis = basis;
                await joborderHooks.refreshOrderSection?.('money', { broadcast: false });
            }
            return;
        }
        refreshedBasis = '';
        ready = rendered;
        output.hidden = false;
        save.disabled = ready === null;
    };

    /** Replaces the preview for each distinct valid draft. */
    const refresh = () => {
        const terms = readTerms();
        if (JSON.stringify(candidate) === JSON.stringify(terms)) return;
        reset();
        candidate = terms;
        if (terms) timer = window.setTimeout(() => void request(terms), 220);
    };

    retry.addEventListener('click', () => {
        refreshedBasis = '';
        reset();
        refresh();
    });

    return {
        reset,
        refresh,
        ready() {
            const terms = readTerms();
            return JSON.stringify(candidate) === JSON.stringify(terms) ? ready : null;
        },
    };
}

/**
 * Displays exact server amounts in the current language.
 *
 * @param {HTMLElement} output the preview container
 * @param {Record<string, string | undefined>} values exact whole-aUEC strings
 */
export function showPreviewAmounts(output, values) {
    for (const [name, value] of Object.entries(values)) {
        const element = output.querySelector(`[data-preview-value="${name}"]`);
        if (element && typeof value === 'string') element.textContent = formatWholeAuec(value);
    }
}

/**
 * Displays the Bank fee payer, source debit and expected disposition.
 *
 * @param {HTMLElement} output the preview
 * @param {BankPreview | null | undefined} bank the authorised Bank effects
 */
export function showBankPreview(output, bank) {
    const debit = output.querySelector('[data-preview-bank-debit]');
    const payer = output.querySelector('[data-preview-bank-payer]');
    const payerLabel = payer?.querySelector('[data-preview-bank-payer-label]');
    const disposition = output.querySelector('[data-preview-bank-disposition]');
    const charge = bank?.bankSourceDebit;
    debit?.toggleAttribute('hidden', !charge || charge === '0');
    showPreviewAmounts(output, { bankDebit: charge });
    if (payerLabel instanceof HTMLElement) {
        const key =
            bank?.feeFunding === 'BANK_SOURCE' ? 'bankSource' : bank?.feeFunding?.toLowerCase();
        payerLabel.textContent = key ? payerLabel.dataset[key] || '' : '';
        payer?.toggleAttribute('hidden', !payerLabel.textContent || bank?.actualFee === '0');
    }
    if (disposition instanceof HTMLElement) {
        const key = bank?.disposition?.toLowerCase();
        disposition.textContent = key ? disposition.dataset[key] || '' : '';
        disposition.hidden = !disposition.textContent;
    }
}

/**
 * Projects the exact provision and the resulting holding.
 *
 * @param {HTMLFormElement} form the provision form
 * @param {string} url the order's money URL
 * @param {() => PreviewTerms | null} readTerms the draft at its observed holder revision
 */
export function bindProvisionPreview(form, url, readTerms) {
    return bindMoneyPreview(form, `${url}/provisions/preview`, readTerms, (output, body, terms) => {
        const preview = /** @type {ProvisionPreview | null} */ (body);
        if (
            !preview ||
            typeof preview.amount !== 'string' ||
            (terms.bankHandling && !preview.bank)
        ) {
            return undefined;
        }
        showPreviewAmounts(output, {
            amount: preview.amount,
            fee: preview.bank?.actualFee ?? preview.fee,
            holding: preview.resultingHolding,
        });
        showBankPreview(output, preview.bank);
        return {
            ...terms,
            ...(preview.bank ? { holderVersion: preview.holderVersion } : {}),
            ...(preview.bank?.rate ? { rate: preview.bank.rate } : {}),
        };
    });
}
