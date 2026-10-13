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

import { toggleTransferOnlyControl } from '/js/bank/bank-controls.js';

/**
 * Applies one request row's state to the reused bank confirmation modal on every open
 * (REQ-BANK-040, REQ-BANK-041): the owner-approval checkbox gating the submit button, the split
 * and justification notices, the transfer destination-holder row and the holder label.
 *
 * @param {HTMLElement} trigger the clicked confirm button carrying the request's data-field-*
 * @param {HTMLElement} modal the confirm modal overlay
 */
export function applyConfirmModalState(trigger, modal) {
    const form = /** @type {HTMLFormElement | null} */ (modal.querySelector('form.bank-ajax-form'));
    if (!form) {
        return;
    }
    const requiresApproval = trigger.getAttribute('data-field-requiresownerapproval') === 'true';
    const alreadyGranted = trigger.getAttribute('data-field-ownerapprovalgranted') === 'true';
    const isTransfer = trigger.getAttribute('data-field-reqtype') === 'TRANSFER';
    const submit = /** @type {HTMLButtonElement | null} */ (
        form.querySelector('button[type="submit"]')
    );

    const block = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-owner-approval-block]')
    );
    const checkbox = /** @type {HTMLInputElement | null} */ (
        form.querySelector('[data-owner-approval-check]')
    );
    if (block) {
        block.hidden = !requiresApproval;
    }
    if (checkbox) {
        checkbox.disabled = !requiresApproval;
        checkbox.checked = requiresApproval && alreadyGranted;
    }
    if (submit) {
        submit.disabled = requiresApproval && !(checkbox && checkbox.checked);
    }

    const splitNotice = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-split-confirm]')
    );
    if (splitNotice) {
        const splitEnabled = trigger.getAttribute('data-field-splitenabled') === 'true';
        splitNotice.hidden = !splitEnabled;
        if (splitEnabled) {
            const percentEl = splitNotice.querySelector('[data-split-confirm-percent]');
            if (percentEl) {
                percentEl.textContent = Math.round(
                    Number(trigger.getAttribute('data-field-splitpercent')),
                ).toLocaleString('de-DE');
            }
        }
    }

    const justificationNotice = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-confirm-justification]')
    );
    if (justificationNotice) {
        const justification = trigger.getAttribute('data-field-justification');
        const present = justification != null && justification.trim() !== '';
        const valueEl = justificationNotice.querySelector('[data-bank-label="justification"]');
        if (valueEl) {
            valueEl.textContent = present ? justification : '';
        }
        justificationNotice.hidden = !present;
    }

    const destRow = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-confirm-destination-holder]')
    );
    toggleTransferOnlyControl(destRow, isTransfer);
    const holderLabel = form.querySelector('[data-confirm-holder-label]');
    if (holderLabel) {
        const text = isTransfer
            ? holderLabel.getAttribute('data-label-source')
            : holderLabel.getAttribute('data-label-default');
        if (text) {
            holderLabel.textContent = text;
        }
    }
}

/**
 * Registers the document listener that gates the confirmation modal's submit button on the owner
 * approval checkbox.
 */
export function initConfirmModal() {
    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const checkbox = /** @type {HTMLInputElement | null} */ (
            target.closest('[data-owner-approval-check]')
        );
        if (!checkbox) {
            return;
        }
        const form = checkbox.closest('form');
        const submit = /** @type {HTMLButtonElement | null} */ (
            form ? form.querySelector('button[type="submit"]') : null
        );
        if (submit) {
            submit.disabled = !checkbox.checked;
        }
    });
}
