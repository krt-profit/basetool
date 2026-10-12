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
import { clearErrors, showError } from '/js/bank/bank-form-errors.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtCsrf, krtFetch } from '/js/kernel/krt-fetch.js';

/**
 * Builds the JSON + CSRF request headers via the shared window.krtCsrf, falling back to reading
 * the CSRF meta tags when krt-fetch.js is absent.
 *
 * @param {Object<string,string>} [base] optional base headers merged under the CSRF header
 * @returns {Object<string,string>} headers for a same-origin JSON fetch
 */
function csrfHeaders(base) {
    if (krtCsrf && typeof krtCsrf.headers === 'function') {
        return krtCsrf.headers(base);
    }
    const headers = Object.assign(
        { Accept: 'application/json', 'Content-Type': 'application/json' },
        base || {},
    );
    const token = /** @type {HTMLMetaElement | null} */ (
        document.querySelector('meta[name="_csrf"]')
    )?.content;
    const header = /** @type {HTMLMetaElement | null} */ (
        document.querySelector('meta[name="_csrf_header"]')
    )?.content;
    if (token && header && header !== 'undefined' && token !== 'undefined') {
        headers[header] = token;
    }
    return headers;
}

/**
 * Fetches one bank PDF with CSRF and the browser's IANA time zone and saves it as a file.
 *
 * @param {string} url the proxy URL
 * @param {string} filename the download filename
 * @param {(message: string) => void} onError receives the localized error message
 */
async function downloadBankPdf(url, filename, onError) {
    const headers = csrfHeaders();
    delete headers['Content-Type'];
    const userTimeZone =
        window.Intl && Intl.DateTimeFormat ? Intl.DateTimeFormat().resolvedOptions().timeZone : '';
    if (userTimeZone) {
        headers['X-User-Time-Zone'] = userTimeZone;
    }
    let response;
    try {
        response = await krtFetch.get(url, { headers });
    } catch {
        response = null;
    }
    if (!response || !response.ok) {
        onError(genericError());
        return;
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = filename;
    krtModal.layerRoot().appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(objectUrl);
}

/**
 * Registers the document listener behind every `.bank-download-btn[data-download-url]`.
 */
export function initDownloadButtons() {
    document.addEventListener('click', (event) => {
        const target = /** @type {Element} */ (event.target);
        const button = /** @type {HTMLButtonElement | null} */ (
            target.closest('.bank-download-btn[data-download-url]')
        );
        if (!button) {
            return;
        }
        button.disabled = true;
        downloadBankPdf(
            /** @type {string} */ (button.getAttribute('data-download-url')),
            button.getAttribute('data-filename') || 'report.pdf',
            (message) => {
                if (typeof showFrontendErrorToast === 'function') {
                    showFrontendErrorToast(message);
                }
            },
        ).finally(() => {
            button.disabled = false;
        });
    });
}

/**
 * Registers the document listener that validates and sends the statement export form.
 */
export function initStatementExport() {
    /**
     * Statement export form: requires the hidden UTC `from`/`to` instants, then downloads the PDF.
     */
    document.addEventListener('submit', (event) => {
        const target = /** @type {Element} */ (event.target);
        const form = /** @type {HTMLFormElement | null} */ (
            target.closest('form.bank-download-form')
        );
        if (!form) {
            return;
        }
        event.preventDefault();
        clearErrors(form);
        const from = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="from"]')
        );
        const to = /** @type {HTMLInputElement | null} */ (form.querySelector('input[name="to"]'));
        const required = form.getAttribute('data-period-required') || genericError();
        let valid = true;
        if (!from || !from.value) {
            showError(form, 'from', required);
            valid = false;
        }
        if (!to || !to.value) {
            showError(form, 'to', required);
            valid = false;
        }
        if (!valid) {
            return;
        }
        const url = `${form.getAttribute('data-endpoint')}?from=${encodeURIComponent(
            /** @type {HTMLInputElement} */ (from).value,
        )}&to=${encodeURIComponent(/** @type {HTMLInputElement} */ (to).value)}`;
        downloadBankPdf(url, form.getAttribute('data-filename') || 'kontoauszug.pdf', (message) => {
            showError(form, '_global', message);
        });
    });
}
