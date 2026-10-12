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
import { showFrontendSuccessToast } from '/js/kernel/toast.js';

/**
 * Registers the document listeners of the type-to-confirm hurdle and the admin wipe-reset form it
 * guards.
 */
export function initTypeToConfirm() {
    /**
     * Type-to-confirm hurdle: the named submit button stays disabled until the input exactly matches
     * `data-confirm-token`.
     */
    document.addEventListener('input', (event) => {
        const target = /** @type {Element} */ (event.target);
        const input = /** @type {HTMLInputElement | null} */ (
            target.closest('input[data-confirm-token][data-confirm-submit]')
        );
        if (!input) {
            return;
        }
        const submit = /** @type {HTMLButtonElement | null} */ (
            document.getElementById(
                /** @type {string} */ (input.getAttribute('data-confirm-submit')),
            )
        );
        if (submit) {
            submit.disabled = input.value !== input.getAttribute('data-confirm-token');
        }
    });

    /**
     * Admin wipe-reset: posts in place and reports the outcome as a toast; on success the modal
     * closes and the confirm input resets. Without krtFetch the form submits natively.
     */
    document.addEventListener('submit', (event) => {
        const target = /** @type {Element} */ (event.target);
        const form = /** @type {HTMLFormElement | null} */ (target.closest('form[data-bank-wipe]'));
        if (!form) {
            return;
        }
        event.preventDefault();
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        const confirmInput = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="confirm"]')
        );
        const confirmValue = confirmInput ? confirmInput.value : '';
        const submitBtn = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        const url = `${form.getAttribute('action')}?confirm=${encodeURIComponent(confirmValue)}`;
        window.krtFetch
            .write({
                method: 'POST',
                url,
                toast: false,
                errorMessage: form.getAttribute('data-wipe-error') || genericError(),
                onSuccess(body) {
                    const accountsReset =
                        body && body.accountsReset != null ? body.accountsReset : 0;
                    const zeroed =
                        body && body.holderStashesZeroed != null ? body.holderStashesZeroed : 0;
                    if (typeof showFrontendSuccessToast === 'function') {
                        const message =
                            accountsReset === 0
                                ? form.getAttribute('data-wipe-noop') || ''
                                : (form.getAttribute('data-wipe-success') || '')
                                      .replace('{0}', accountsReset)
                                      .replace('{1}', zeroed);
                        showFrontendSuccessToast(message);
                    }
                    const overlay = form.closest('.krt-modal-overlay');
                    const closeBtn = /** @type {HTMLElement | null} */ (
                        overlay
                            ? overlay.querySelector('[data-trigger="close-modal-display"]')
                            : null
                    );
                    if (closeBtn) {
                        closeBtn.click();
                    }
                    if (confirmInput) {
                        confirmInput.value = '';
                    }
                },
            })
            .finally(() => {
                if (submitBtn && confirmInput) {
                    submitBtn.disabled =
                        confirmInput.value !== confirmInput.getAttribute('data-confirm-token');
                } else if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    });
}
