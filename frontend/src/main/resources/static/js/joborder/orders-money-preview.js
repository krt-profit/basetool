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
import { formatParts, separatorsFor } from '/js/kernel/krt-number-format.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

const seps = separatorsFor(document.documentElement.lang);

/** @param {string} amount the exact server amount */
function formatWholeAuec(amount) {
    return formatParts({ neg: false, int: amount, frac: null }, seps);
}

/** @typedef {ApiDto<'PreviewJobOrderMoneyProvisionDto'>} PreviewTerms */
/** @typedef {ApiDto<'JobOrderMoneyProvisionPreview'>} ProvisionPreview */

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
            retry.hidden = false;
            const details = result.body;
            if (
                problem &&
                details &&
                typeof details === 'object' &&
                'detail' in details &&
                typeof details.detail === 'string'
            ) {
                problem.textContent = details.detail;
                problem.removeAttribute('hidden');
            }
            if (result.status === 409) {
                await joborderHooks.refreshOrderSection?.('money', { broadcast: false });
            }
            return;
        }
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
 * Projects the exact provision and the resulting holding.
 *
 * @param {HTMLFormElement} form the provision form
 * @param {string} url the order's money URL
 * @param {() => PreviewTerms | null} readTerms the draft at its observed holder revision
 */
export function bindProvisionPreview(form, url, readTerms) {
    return bindMoneyPreview(form, `${url}/provisions/preview`, readTerms, (output, body, terms) => {
        const preview = /** @type {ProvisionPreview | null} */ (body);
        if (!preview || typeof preview.amount !== 'string') return undefined;
        showPreviewAmounts(output, {
            amount: preview.amount,
            fee: preview.fee,
            holding: preview.resultingHolding,
        });
        return terms;
    });
}
