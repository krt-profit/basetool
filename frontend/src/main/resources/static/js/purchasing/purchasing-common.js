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

/* global purchasingConflict */

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';

/**
 * Looks up an element by id and returns it as an input; the purchasing templates always render
 * the ids the modules ask for.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement} the element, typed as an input for its value and checked state
 */
export function field(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
}

/**
 * Looks up a select by id.
 *
 * @param {string} id the element id
 * @returns {HTMLSelectElement} the select
 */
export function select(id) {
    return /** @type {HTMLSelectElement} */ (document.getElementById(id));
}

/**
 * Parses a number field; an empty or invalid value is `null`.
 *
 * @param {string} value the field value
 * @returns {number | null} the number, or null
 */
export function numberOrNull(value) {
    if (value === '') return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Returns the trimmed value of a field, or null when it is empty.
 *
 * @param {string} value the field value
 * @returns {string | null} the trimmed value, or null
 */
export function textOrNull(value) {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
}

/**
 * Returns the submit button of a form.
 *
 * @param {HTMLElement} form the form
 * @returns {HTMLButtonElement | null} the submit button, or null
 */
export function submitterOf(form) {
    return /** @type {HTMLButtonElement | null} */ (form.querySelector('button[type="submit"]'));
}

/**
 * Sends a JSON write to a purchasing proxy and runs `afterWrite` once it succeeded.
 *
 * @param {object} opts the request
 * @param {string} opts.method the HTTP method
 * @param {string} opts.url the proxy path
 * @param {object | undefined} opts.payload the JSON body
 * @param {string} opts.successMessage the localized success toast
 * @param {string} opts.errorMessage the localized fallback error text
 * @param {HTMLElement | null} [opts.submitter] the control to disable while the request runs
 * @param {(body: any) => void} opts.afterWrite called after a successful write with the response
 * @returns {Promise<unknown>} resolves when the write has settled
 */
export function sendWrite(opts) {
    return krtFetch.write({
        method: opts.method,
        url: opts.url,
        payload: opts.payload,
        successMessage: opts.successMessage,
        errorMessage: opts.errorMessage,
        conflict: purchasingConflict,
        submitter: opts.submitter || undefined,
        onSuccess(body) {
            opts.afterWrite(body);
        },
    });
}

/**
 * Wires the live refresh of a results container: reloads it when a peer reports a change and tells
 * the peers after the own write.
 *
 * @param {object} cfg the wiring
 * @param {string} cfg.containerSelector the selector of the results container
 * @param {string} cfg.listUrl the URL of the current list, fragment parameter excluded
 * @param {string} cfg.section the section key of the container
 * @param {string} [cfg.fragment] the value of the fragment parameter, `results` by default
 * @returns {() => void} the function to call after an own write
 */
export function bindResults(cfg) {
    const container = document.querySelector(cfg.containerSelector);
    const topic = container ? container.getAttribute('data-live-sync-topic') || '' : '';
    const sections = { [cfg.section]: { container: cfg.containerSelector } };
    const fragment = cfg.fragment || 'results';

    /** Re-renders the container from the server. */
    function reswap() {
        const query = new URLSearchParams(window.location.search);
        query.set('fragment', fragment);
        krtFetch.swap({
            url: `${cfg.listUrl}?${query.toString()}`,
            container: cfg.containerSelector,
            history: false,
        });
    }

    if (topic) {
        krtLiveSync.createReceiver({
            topic,
            sections,
            coalesceMs: 1500,
            refresh() {
                reswap();
            },
        });
    }
    krtFetch.bindSwap({ container: cfg.containerSelector, history: fragment === 'results' });

    return function afterWrite() {
        reswap();
        if (topic && typeof krtLiveSync.sendChanged === 'function') {
            krtLiveSync.sendChanged(topic, Object.keys(sections));
        }
    };
}
