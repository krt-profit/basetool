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

// @ts-check

/* global MSG_STATUS_SUCCESS, MSG_STATUS_ERROR, MSG_CLAIM_TITLE_ADD, MSG_CLAIM_TITLE_EDIT, MSG_CLAIM_MAX_HINT, MSG_CLAIM_SUCCESS, MSG_CLAIM_WITHDRAW_SUCCESS, MSG_CLAIM_ERROR, MSG_CLAIM_VALIDATION_SQUADRON, MSG_CLAIM_VALIDATION_AMOUNT, MSG_CLAIM_VALIDATION_OVERCLAIM, MSG_BP_COUNTING_SUCCESS, MSG_BP_COUNTING_ERROR */

import {
    _refreshMaterialsSection,
    _swapOrderSection,
    krtOrderWrite,
} from '/js/joborder/orders-detail-edit.js';
import {
    _odControlById,
    _odEditVersionInput,
    _odElementById,
    _publishOrdersQueue,
} from '/js/joborder/orders-detail.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { krtModal } from '/js/kernel/krt-modal.js';

/**
 * @typedef {object} OdClaimModalOptions
 * @property {string} [materialId] the material of the quality bucket
 * @property {string} [quality] the bucket's quality requirement
 * @property {string} [qualityLabel] the bucket's quality label
 * @property {string} [materialName] the material's name
 * @property {string} [quantityType] the material's quantity type
 * @property {string} [open] the still unclaimed amount
 * @property {string} [claimId] the claim being edited
 * @property {string} [squadronId] the claiming squadron of the claim being edited
 * @property {string} [amount] the claimed amount of the claim being edited
 * @property {boolean} edit whether an existing claim is edited
 */

/**
 * Fills and opens the claim modal.
 *
 * @param {OdClaimModalOptions} opts what the modal shows
 */
function _openClaimModal(opts) {
    _odControlById('claim-material-id').value = opts.materialId || '';
    _odControlById('claim-quality').value = opts.quality || '';
    _odControlById('claim-id').value = opts.claimId || '';
    const sel = _odControlById('claim-squadron');
    const amountInput = _odControlById('claim-amount');
    const open = parseFloat(opts.open || '0') || 0;
    const qualityLabel = opts.qualityLabel || opts.quality || '';
    _odElementById('claim-modal-bucket').textContent =
        `${opts.materialName || ''} — ${qualityLabel}`;
    if (opts.edit) {
        _odElementById('claim-modal-title').textContent = MSG_CLAIM_TITLE_EDIT;
        sel.value = opts.squadronId || '';
        sel.disabled = true;
        amountInput.value = opts.amount || '';
        const maxEdit = open + (parseFloat(opts.amount || '0') || 0);
        _odControlById('claim-max').value = String(maxEdit);
        _odElementById('claim-open-hint').textContent = `${MSG_CLAIM_MAX_HINT} ${maxEdit}`;
        _odElementById('claim-withdraw-btn').hidden = false;
    } else {
        _odElementById('claim-modal-title').textContent = MSG_CLAIM_TITLE_ADD;
        sel.value = '';
        sel.disabled = false;
        amountInput.value = '';
        _odControlById('claim-max').value = String(open);
        _odElementById('claim-open-hint').textContent = `${MSG_CLAIM_MAX_HINT} ${open}`;
        _odElementById('claim-withdraw-btn').hidden = true;
    }
    const claimScuHint = document.getElementById('claim-scu-hint');
    if (claimScuHint) claimScuHint.classList.toggle('is-hidden', opts.quantityType === 'PIECE');
    krtModal.open(document.getElementById('claim-modal'));
}

/**
 * Opens the claim modal for a new claim on a quality bucket.
 *
 * @param {HTMLElement} el the control carrying the bucket's data attributes
 */
export function openClaimCreate(el) {
    _openClaimModal({
        materialId: el.dataset.materialId,
        quality: el.dataset.quality,
        qualityLabel: el.dataset.qualityLabel,
        materialName: el.dataset.materialName,
        quantityType: el.dataset.quantityType,
        open: el.dataset.open,
        edit: false,
    });
}

/**
 * Opens the claim modal for an existing claim.
 *
 * @param {HTMLElement} el the control carrying the claim's and bucket's data attributes
 */
export function openClaimEdit(el) {
    _openClaimModal({
        materialId: el.dataset.materialId,
        quality: el.dataset.quality,
        qualityLabel: el.dataset.qualityLabel,
        materialName: el.dataset.materialName,
        quantityType: el.dataset.quantityType,
        open: el.dataset.open,
        claimId: el.dataset.claimId,
        squadronId: el.dataset.squadronId,
        amount: el.dataset.amount,
        edit: true,
    });
}

/** Validates the claim modal and creates the claim. */
export function submitClaim() {
    const orderId = _odControlById('claim-order-id').value;
    const materialId = _odControlById('claim-material-id').value;
    const quality = _odControlById('claim-quality').value;
    const squadronId = _odControlById('claim-squadron').value;
    const amount = /** @type {KrtScuInputApi} */ (krtScuInput).parse(
        _odControlById('claim-amount').value,
    );
    const max = parseFloat(_odControlById('claim-max').value);
    if (!squadronId) {
        showFrontendErrorToast(MSG_CLAIM_VALIDATION_SQUADRON);
        return;
    }
    if (!(amount > 0)) {
        showFrontendErrorToast(MSG_CLAIM_VALIDATION_AMOUNT);
        return;
    }
    if (amount > max + 1e-9) {
        showFrontendErrorToast(MSG_CLAIM_VALIDATION_OVERCLAIM);
        return;
    }
    krtOrderWrite({
        method: 'POST',
        url: `/orders/${orderId}/claims`,
        payload: {
            materialId,
            qualityRequirement: quality,
            claimingOrgUnitId: squadronId,
            amount,
        },
        toast: false,
        errorMessage: MSG_CLAIM_ERROR,
        onSuccess() {
            krtModal.close(document.getElementById('claim-modal'));
            showFrontendSuccessToast(MSG_CLAIM_SUCCESS);
            _refreshMaterialsSection();
        },
    });
}

/** Withdraws the claim open in the claim modal. */
export function withdrawClaimAction() {
    const orderId = _odControlById('claim-order-id').value;
    const claimId = _odControlById('claim-id').value;
    if (!claimId) {
        return;
    }
    krtOrderWrite({
        method: 'POST',
        url: `/orders/${orderId}/claims/${claimId}/withdraw`,
        toast: false,
        errorMessage: MSG_CLAIM_ERROR,
        onSuccess() {
            krtModal.close(document.getElementById('claim-modal'));
            showFrontendSuccessToast(MSG_CLAIM_WITHDRAW_SUCCESS);
            _refreshMaterialsSection();
        },
    });
}

/** @type {string | null} */
let _pendingStatus = null;
/** @type {string | null} */
let _previousStatus = null;

/**
 * Reads the status the select last had saved.
 *
 * @param {HTMLSelectElement} selectElement the status select
 * @returns {string} the saved status
 */
function _knownStatus(selectElement) {
    const selectedOption = /** @type {HTMLOptionElement | null} */ (
        selectElement.querySelector('option[selected]')
    );
    return (
        selectElement.dataset.savedStatus ||
        (selectedOption && selectedOption.value) ||
        selectElement.options[0].value
    );
}

/**
 * Applies a status chosen in the select, asking first for the terminal statuses.
 *
 * @param {HTMLSelectElement} selectElement the status select
 */
export function updateStatus(selectElement) {
    const newStatus = selectElement.value;
    if (newStatus === 'COMPLETED' || newStatus === 'REJECTED') {
        _pendingStatus = newStatus;
        _previousStatus = _knownStatus(selectElement);
        krtModal.open(document.getElementById('status-warning-modal'));
    } else {
        _doStatusUpdate(selectElement.dataset.orderId, newStatus, selectElement);
    }
}

/** Dismisses the terminal-status warning and restores the previous status. */
export function cancelStatusChange() {
    krtModal.close(document.getElementById('status-warning-modal'));
    const sel = /** @type {HTMLSelectElement | null} */ (document.getElementById('status-select'));
    if (sel && _previousStatus) {
        sel.value = _previousStatus;
    }
    _pendingStatus = null;
    _previousStatus = null;
}

/** Confirms the terminal-status warning and saves the pending status. */
export function confirmStatusChange() {
    krtModal.close(document.getElementById('status-warning-modal'));
    const sel = /** @type {HTMLSelectElement | null} */ (document.getElementById('status-select'));
    if (!sel || !_pendingStatus) return;
    _doStatusUpdate(sel.dataset.orderId, _pendingStatus, sel);
    _pendingStatus = null;
    _previousStatus = null;
}

/**
 * Reads the order's current version from the status select, else from the edit modal.
 *
 * @returns {number | null} the version, or null when neither carries one
 */
function _orderVersion() {
    const sel = document.getElementById('status-select');
    if (sel && sel.dataset.version != null && sel.dataset.version !== '') {
        return parseInt(sel.dataset.version, 10);
    }
    const editVer = _odEditVersionInput();
    return editVer && editVer.value ? parseInt(editVer.value, 10) : null;
}

/**
 * Saves a new order status and refreshes the affected sections.
 *
 * @param {string | null | undefined} orderId the order
 * @param {string} status the new status
 * @param {HTMLSelectElement | null} selectElement the status select to revert on failure
 */
function _doStatusUpdate(orderId, status, selectElement) {
    const savedStatus = selectElement ? _knownStatus(selectElement) : null;
    krtOrderWrite({
        method: 'POST',
        url: `/orders/${orderId}/status`,
        payload() {
            return { status, version: _orderVersion() };
        },
        toast: false,
        errorMessage: MSG_STATUS_ERROR,
        onSuccess(data) {
            if (data && data.version != null && selectElement) {
                selectElement.dataset.version = data.version;
                const editVer = _odEditVersionInput();
                if (editVer) editVer.value = data.version;
            }
            if (selectElement) selectElement.dataset.savedStatus = status;
            _swapOrderSection(orderId, 'order-header-results', 'header');
            if (status === 'COMPLETED' || status === 'REJECTED') {
                document.querySelectorAll('.inventory-details-row').forEach((r) => {
                    r.remove();
                });
                _refreshMaterialsSection(orderId);
            }
            _publishOrdersQueue();
            showFrontendSuccessToast(MSG_STATUS_SUCCESS);
        },
    }).then((res) => {
        if (selectElement && (!res || !res.ok)) {
            selectElement.value = /** @type {string} */ (savedStatus);
        }
    });
}

/**
 * Saves the blueprint variant counting switch and refreshes the blueprint owners.
 *
 * @param {HTMLInputElement} checkbox the switch, reverted on failure
 */
export function _toggleBlueprintCounting(checkbox) {
    const orderId = checkbox.getAttribute('data-order-id');
    const desired = checkbox.checked;
    if (!orderId || !window.krtFetch) {
        return;
    }
    checkbox.disabled = true;
    krtOrderWrite({
        method: 'POST',
        url: `/orders/${orderId}/blueprint-variant-counting`,
        payload() {
            return { countBlueprintsWithVariants: desired, version: _orderVersion() };
        },
        toast: false,
        errorMessage: MSG_BP_COUNTING_ERROR,
        onSuccess(data) {
            if (data && data.version != null) {
                const sel = document.getElementById('status-select');
                if (sel) sel.dataset.version = data.version;
                const editVer = _odEditVersionInput();
                if (editVer) editVer.value = data.version;
            }
            showFrontendSuccessToast(MSG_BP_COUNTING_SUCCESS);
            _swapOrderSection(orderId, 'blueprint-owners-section', 'blueprint-owners');
        },
    }).then((res) => {
        checkbox.disabled = false;
        if (!res || !res.ok) {
            checkbox.checked = !desired;
        }
    });
}
