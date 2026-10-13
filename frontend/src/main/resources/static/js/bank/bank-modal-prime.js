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

import { applyConfirmModalState } from '/js/bank/bank-confirm-modal.js';
import { clearErrors } from '/js/bank/bank-form-errors.js';
import { syncMovementRows } from '/js/bank/bank-movement-rows.js';
import { toggleSplitRow, updateFeePreview, updateLimitWarning } from '/js/bank/bank-previews.js';
import { syncRequestTypeRows } from '/js/bank/bank-request-rows.js';

/**
 * Copies the trigger's `data-field-*` attributes into the modal's matching form controls
 * (case-insensitive) and `[data-bank-label]` elements, then resets errors and re-syncs the
 * modal's dependent rows and previews.
 *
 * @param {HTMLElement} trigger the clicked open-modal button
 * @param {HTMLElement} modal the overlay element being opened
 */
function primeModal(trigger, modal) {
    const form = /** @type {HTMLFormElement | null} */ (modal.querySelector('form.bank-ajax-form'));
    if (form) {
        clearErrors(form);
        const staffNote = /** @type {HTMLInputElement | null} */ (
            form.querySelector('[name="staffNote"]')
        );
        if (staffNote) {
            staffNote.value = '';
        }
    }
    for (const attr of Array.from(trigger.attributes)) {
        if (!attr.name.startsWith('data-field-')) {
            continue;
        }
        const key = attr.name.slice('data-field-'.length);
        if (form) {
            for (const el of /** @type {HTMLInputElement[]} */ (Array.from(form.elements))) {
                if (el.name && el.name.toLowerCase() === key) {
                    el.value = attr.value;
                }
            }
        }
        modal.querySelectorAll('[data-bank-label]').forEach((el) => {
            if (/** @type {string} */ (el.getAttribute('data-bank-label')).toLowerCase() === key) {
                el.textContent = attr.value;
            }
        });
    }
    if (form && form.querySelector('[data-fee-preview]')) {
        updateFeePreview(form);
    }
    if (form && form.querySelector('[data-limit-warning]')) {
        const typeSelect = /** @type {HTMLSelectElement | null} */ (
            form.querySelector('select[data-role="org-unit-request-type"]')
        );
        if (typeSelect) {
            syncRequestTypeRows(typeSelect);
        }
        updateLimitWarning(form);
    }
    if (form && form.querySelector('[data-split-row]')) {
        const splitToggle = /** @type {HTMLInputElement | null} */ (
            form.querySelector('[data-role="bank-split-toggle"]')
        );
        if (splitToggle) {
            toggleSplitRow(splitToggle);
        }
    }
    if (form) {
        const movementType = /** @type {HTMLSelectElement | null} */ (
            form.querySelector('[data-role="bank-movement-type"]')
        );
        if (movementType) {
            syncMovementRows(movementType);
        }
    }
}

/**
 * Registers the document listener that primes a bank modal from its trigger's `data-field-*`
 * attributes when it is opened.
 */
export function initModalPriming() {
    document.addEventListener('click', (event) => {
        const target = /** @type {Element} */ (event.target);
        const trigger = /** @type {HTMLElement | null} */ (
            target.closest('[data-trigger="open-modal-display"][data-modal-id]')
        );
        if (!trigger) {
            return;
        }
        const modal = document.getElementById(
            /** @type {string} */ (trigger.getAttribute('data-modal-id')),
        );
        if (modal && modal.classList.contains('krt-modal-overlay')) {
            primeModal(trigger, modal);
            if (modal.id === 'bank-confirm-request-modal') {
                applyConfirmModalState(trigger, modal);
            }
        }
    });
}
