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

import { approvalRequestFiledMessage, genericError } from '/js/bank/bank-messages.js';
import { clearErrors, showError } from '/js/bank/bank-form-errors.js';
import { publishBankLiveSync } from '/js/bank/bank-livesync.js';
import { showFrontendSuccessToast } from '/js/kernel/toast.js';

/** Form field names serialized as JSON numbers instead of strings. */
const NUMBER_FIELDS = [
    'amount',
    'version',
    'target',
    'limit',
    'splitPercent',
    'employeeCeiling',
    'areaLeadCeiling',
];

/** Form field names serialized as JSON booleans ("true"/"false" hidden inputs). */
const BOOLEAN_FIELDS = ['active'];

/**
 * Maps backend bank conflict codes onto the form field whose inline error slot
 * should show the localized message; unmapped codes land in the form's
 * `_global` slot.
 */
const CODE_FIELD = {
    BANK_OVERDRAFT: 'amount',
    BANK_HOLDER_OVERDRAFT: 'amount',
    BANK_SELF_TRANSFER: 'destinationAccountId',
    BANK_GRANTEE_MISSING_ROLE: 'userId',
    BANK_HOLDER_INACTIVE: 'holderId',
    BANK_SPLIT_NO_TARGETS: 'splitPercent',
    BANK_SPLIT_TOO_SMALL: 'splitPercent',
    BANK_JUSTIFICATION_REQUIRED: 'justification',
    BANK_FEE_EXCEEDS_AMOUNT: 'amount',
};

/**
 * Maps a form's `data-refresh` token to its swap container and whether the current query string
 * is preserved on the in-place re-render (REQ-FE-005). `accountBody` drops the query so the
 * booking history resets to page 0.
 */
const REFRESH_TARGETS = {
    accountBody: { container: '#bank-account-results', preserveQuery: false },
    manageBody: { container: '#bank-manage-results', preserveQuery: true },
    grantsMatrix: { container: '#bank-grants-results', preserveQuery: true },
    orgUnitBank: { container: '#org-unit-bank-results', preserveQuery: true },
    orgUnitBankSettings: { container: '#org-unit-bank-settings-results', preserveQuery: false },
    requestQueue: { container: '#bank-request-queue-results', preserveQuery: true },
    bankGrid: { container: '#bank-grid-results', preserveQuery: true },
};

/**
 * Converts one form control into its JSON value: checkboxes to booleans,
 * whitelisted names to numbers/booleans, everything else stays a string.
 *
 * @param {HTMLInputElement} el the form control
 * @returns {*} the JSON-ready value; '' and null mark "omit this field"
 */
function fieldValue(el) {
    if (el.type === 'checkbox') {
        return el.checked;
    }
    const value = el.value;
    if (value === '') {
        return '';
    }
    if (NUMBER_FIELDS.includes(el.name)) {
        return Number(value);
    }
    if (BOOLEAN_FIELDS.includes(el.name)) {
        return value === 'true';
    }
    return value;
}

/**
 * Serializes and sends one bank AJAX form. `_`-prefixed fields fill the endpoint's
 * `{placeholder}` slots instead of the body, `data-account-id` is sent under
 * `data-account-id-field` (default `accountId`); success refreshes in place, errors render inline.
 *
 * @param {HTMLFormElement} form the submitted form
 */
async function submitBankForm(form) {
    clearErrors(form);
    const movementSource = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        form.querySelector('[data-role="bank-movement-source"]')
    );
    if (movementSource) {
        form.setAttribute('data-account-id', movementSource.value || '');
    }
    const method = (form.getAttribute('data-method') || 'POST').toUpperCase();
    /** @type {Record<string, any>} */
    const body = {};
    /** @type {Record<string, string>} */
    const placeholders = {};
    for (const el of /** @type {HTMLInputElement[]} */ (Array.from(form.elements))) {
        if (!el.name || el.disabled) {
            continue;
        }
        if (el.name.startsWith('_')) {
            placeholders[el.name.slice(1).toLowerCase()] = el.value;
            continue;
        }
        const value = fieldValue(el);
        if (value === '') {
            continue;
        }
        body[el.name] = value;
    }
    const accountId = form.getAttribute('data-account-id');
    if (accountId) {
        body[form.getAttribute('data-account-id-field') || 'accountId'] = accountId;
    }
    const endpoint = /** @type {string} */ (form.getAttribute('data-endpoint')).replace(
        /\{([^}]+)\}/g,
        (match, name) => {
            const filled = placeholders[name.toLowerCase()];
            return filled !== undefined ? encodeURIComponent(filled) : match;
        },
    );

    const submitButton = form.querySelector('button[type="submit"]');
    await window.krtFetch.write({
        method,
        url: endpoint,
        payload: method !== 'GET' && method !== 'DELETE' ? body : undefined,
        submitter: submitButton,
        toast: false,
        errorMessage: genericError(),
        onSuccess(payload) {
            handleBankSuccess(form);
            if (payload && payload.pendingRequest) {
                if (typeof showFrontendSuccessToast === 'function') {
                    showFrontendSuccessToast(approvalRequestFiledMessage());
                }
            }
        },
        onError(status, payload) {
            if (payload && payload.unauthenticated) {
                window.location.reload();
                return true;
            }
            if (payload && Array.isArray(payload.fieldErrors) && payload.fieldErrors.length > 0) {
                payload.fieldErrors.forEach((fe) => {
                    showError(form, fe.field, fe.message);
                });
                return true;
            }
            const message = payload && payload.message ? payload.message : genericError();
            const field =
                payload && payload.code && CODE_FIELD[payload.code]
                    ? CODE_FIELD[payload.code]
                    : '_global';
            showError(form, field, message);
            return true;
        },
        onNetworkError() {
            showError(form, '_global', genericError());
            return true;
        },
    });
}

/**
 * Handles a successful bank AJAX form write in place (REQ-FE-005): closes the modal, shows the
 * success toast, publishes live sync and re-renders the fragment named by `data-refresh`. A failed
 * refresh shows the "saved, but reload" message; without a swap target the page reloads.
 *
 * @param {HTMLFormElement} form the form whose write just succeeded
 */
function handleBankSuccess(form) {
    const modal = form.closest('.krt-modal-overlay');
    if (modal) {
        window.krtModal.close(modal);
    }
    const main = document.querySelector('main[data-bank-saved]');
    const savedMessage = main ? main.getAttribute('data-bank-saved') : null;
    if (savedMessage && typeof showFrontendSuccessToast === 'function') {
        showFrontendSuccessToast(savedMessage);
    }
    publishBankLiveSync(form);
    const spec = REFRESH_TARGETS[form.getAttribute('data-refresh')];
    if (!spec || !window.krtFetch || typeof window.krtFetch.swap !== 'function') {
        window.location.reload();
        return;
    }
    const refreshError = main ? main.getAttribute('data-bank-refresh-error') : null;
    const url = spec.preserveQuery
        ? window.location.pathname + window.location.search
        : window.location.pathname;
    const container = document.querySelector(spec.container);
    const frozen = container
        ? Array.from(
              container.querySelectorAll(
                  'button[data-trigger="open-modal-display"]:not([disabled])',
              ),
          )
        : [];
    frozen.forEach((button) => {
        button.disabled = true;
    });
    window.krtFetch
        .swap({
            url,
            container: spec.container,
            fragmentValue: /** @type {string} */ (form.getAttribute('data-refresh')),
            errorMessage: refreshError || genericError(),
        })
        .then((swapped) => {
            if (!swapped) {
                frozen.forEach((button) => {
                    button.disabled = false;
                });
            }
        });
}

/**
 * Registers the document listener that submits every `form.bank-ajax-form` through krtFetch.
 */
export function initFormSubmit() {
    document.addEventListener('submit', (event) => {
        const target = /** @type {Element} */ (event.target);
        const form = /** @type {HTMLFormElement | null} */ (target.closest('form.bank-ajax-form'));
        if (!form) {
            return;
        }
        event.preventDefault();
        submitBankForm(form);
    });
}
