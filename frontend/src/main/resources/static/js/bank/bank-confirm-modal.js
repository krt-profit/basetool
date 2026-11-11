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

import { toggleRequiredPicker } from '/js/bank/bank-controls.js';

/**
 * Applies one request row's state to the reused bank confirmation modal on every open
 * (REQ-BANK-040, REQ-BANK-041): the owner-approval checkbox gating the submit button, the
 * approver note an attested confirm requires, the split and justification notices, the transfer
 * destination-holder row and the holder label.
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
    const type = trigger.getAttribute('data-field-reqtype');
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
    applyApproverNoteState(form, requiresApproval && !alreadyGranted);

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
                ).toLocaleString(document.documentElement.lang || undefined);
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

    applyCustodianState(form, trigger.closest('tr'), type);
    const isTransfer = type === 'TRANSFER';
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
 * Shows the submitted custodians for a linked request and the editable pickers for ordinary requests.
 *
 * @param {HTMLFormElement} form the confirmation form
 * @param {HTMLTableRowElement | null} requestRow the pending request's queue row
 * @param {string | null} type the request type
 */
function applyCustodianState(form, requestRow, type) {
    const holderId = requestRow?.dataset.linkedHolderId ?? '';
    const destinationId = requestRow?.dataset.linkedDestinationId ?? '';
    const linked = holderId !== '';
    toggleRequiredPicker(form.querySelector('[data-confirm-source-holder]'), !linked);
    toggleRequiredPicker(
        form.querySelector('[data-confirm-destination-holder]'),
        !linked && type === 'TRANSFER',
    );
    const hint = /** @type {HTMLElement} */ (form.querySelector('[data-confirm-picker-hint]'));
    const fixed = /** @type {HTMLElement} */ (form.querySelector('[data-confirm-fixed-parties]'));
    hint.hidden = linked;
    fixed.hidden = !linked;
    setFixedCustodian(fixed, 'holder', holderId, requestRow?.dataset.linkedHolder ?? '');
    setFixedCustodian(
        fixed,
        'destination',
        destinationId,
        requestRow?.dataset.linkedDestination ?? '',
    );
    const destinationRow = /** @type {HTMLElement} */ (
        fixed.querySelector('[data-fixed-destination-row]')
    );
    destinationRow.hidden = destinationId === '';
    const amount = /** @type {HTMLElement} */ (fixed.querySelector('[data-fixed-amount]'));
    amount.textContent = requestRow?.querySelector('[data-request-amount]')?.textContent ?? '';
    const fee = /** @type {HTMLElement} */ (fixed.querySelector('[data-fixed-fee]'));
    fee.textContent = requestRow?.querySelector('[data-linked-fee]')?.textContent ?? '';
    fee.hidden = !fee.textContent;
    const authorisationHint = /** @type {HTMLElement} */ (
        fixed.querySelector('[data-fixed-authorisation-hint]')
    );
    authorisationHint.hidden = type !== 'AUTHORISATION';
}

/**
 * Copies a submitted custodian to the confirmation payload and its read-only label.
 *
 * @param {HTMLElement} fixed the submitted party block
 * @param {string} field the holder or destination field
 * @param {string} id the submitted holder id
 * @param {string} label the submitted holder's display name
 */
function setFixedCustodian(fixed, field, id, label) {
    const input = /** @type {HTMLInputElement} */ (fixed.querySelector(`[data-fixed-${field}-id]`));
    input.value = id;
    input.disabled = id === '';
    const name = /** @type {HTMLElement} */ (fixed.querySelector(`[data-fixed-${field}]`));
    name.textContent = label;
}

/**
 * Makes the staff note mandatory, with its "Wer hat freigegeben?" hint shown, on an attested
 * over-limit confirm, and optional otherwise (REQ-BANK-041, ADR-0353).
 *
 * @param {HTMLFormElement} form the confirm form
 * @param {boolean} attested whether the request needs approval and none was recorded in the app
 */
function applyApproverNoteState(form, attested) {
    const note = /** @type {HTMLInputElement | null} */ (
        form.querySelector('[data-approver-note]')
    );
    const hint = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-approver-note-hint]')
    );
    if (note) {
        note.required = attested;
    }
    if (hint) {
        hint.hidden = !attested;
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
