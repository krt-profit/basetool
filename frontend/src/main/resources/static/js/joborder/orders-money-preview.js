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
 * Enables saving while the displayed server preview matches the draft.
 *
 * @param {HTMLFormElement} form the provision form
 * @param {string} url the order's money URL
 * @param {() => PreviewTerms | null} readTerms the valid proposal at its observed holder revision
 */
export function bindProvisionPreview(form, url, readTerms) {
    const output = form.querySelector('[data-money-preview]');
    const retry = form.querySelector('[data-money-preview-retry]');
    const save = form.querySelector('button[type="submit"]');
    if (
        !(output instanceof HTMLElement) ||
        !(retry instanceof HTMLButtonElement) ||
        !(save instanceof HTMLButtonElement)
    ) {
        throw new Error('The order provision preview controls are missing');
    }
    /** @type {PreviewTerms | null} */
    let candidate = null;
    let shown = false;
    let timer = 0;

    /** Invalidates the old preview before waiting for a new response. */
    const reset = () => {
        window.clearTimeout(timer);
        candidate = null;
        shown = false;
        output.hidden = true;
        retry.hidden = true;
        save.disabled = true;
    };

    /**
     * Shows an authorised preview when it still describes the draft.
     *
     * @param {PreviewTerms} terms the proposed terms
     */
    const request = async (terms) => {
        const result = await krtFetch.write({
            method: 'POST',
            url: `${url}/provisions/preview`,
            payload: terms,
            toast: false,
            onError: () => true,
            onNetworkError: () => true,
        });
        if (candidate !== terms) return;
        /** @type {ProvisionPreview | null} */
        const preview = result.body;
        if (!result.ok || result.redirected || !preview || typeof preview.amount !== 'string') {
            retry.hidden = false;
            if (result.status === 409) {
                await joborderHooks.refreshOrderSection?.('money', { broadcast: false });
            }
            return;
        }
        const values = {
            amount: preview.amount,
            fee: preview.fee,
            holding: preview.resultingHolding,
        };
        for (const [name, value] of Object.entries(values)) {
            const element = output.querySelector(`[data-preview-value="${name}"]`);
            if (element && typeof value === 'string') element.textContent = formatWholeAuec(value);
        }
        shown = true;
        output.hidden = false;
        save.disabled = false;
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
            return shown && JSON.stringify(candidate) === JSON.stringify(terms) ? terms : null;
        },
    };
}
