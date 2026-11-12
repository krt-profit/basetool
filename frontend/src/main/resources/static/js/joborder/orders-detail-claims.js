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

/* global MSG_STATUS_SUCCESS, MSG_STATUS_ERROR, MSG_CLAIM_TITLE_ADD, MSG_CLAIM_TITLE_EDIT, MSG_CLAIM_MAX_HINT, MSG_CLAIM_SUCCESS, MSG_CLAIM_WITHDRAW_SUCCESS, MSG_CLAIM_ERROR, MSG_CLAIM_VALIDATION_SQUADRON, MSG_CLAIM_VALIDATION_AMOUNT, MSG_CLAIM_VALIDATION_OVERCLAIM, MSG_BP_COUNTING_SUCCESS, MSG_BP_COUNTING_ERROR */

import { _refreshMaterialsSection, krtOrderWrite } from '/js/joborder/orders-detail-edit.js';
import {
    _odControlById,
    _odEditVersionInput,
    _odElementById,
    _publishOrdersQueue,
} from '/js/joborder/orders-detail.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

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
 * @property {string} [version] the observed version of the claim being edited
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
    _odControlById('claim-version').value = opts.version || '';
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
        version: el.dataset.version,
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
            version: _odControlById('claim-version').value
                ? Number(_odControlById('claim-version').value)
                : null,
            amount,
        },
        toast: false,
        errorMessage: MSG_CLAIM_ERROR,
        onSuccess() {
            krtModal.close(document.getElementById('claim-modal'));
            showFrontendSuccessToast(MSG_CLAIM_SUCCESS);
            _refreshMaterialsSection();
            joborderHooks.refreshOrderSection?.('participations');
            _publishOrdersQueue();
        },
    });
}

/** Withdraws the claim open in the claim modal. */
export function withdrawClaimAction() {
    const orderId = _odControlById('claim-order-id').value;
    const claimId = _odControlById('claim-id').value;
    /** @type {string} */
    const version = _odControlById('claim-version').value;
    if (!claimId) {
        return;
    }
    krtOrderWrite({
        method: 'POST',
        url: `/orders/${orderId}/claims/${claimId}/withdraw?version=${encodeURIComponent(version)}`,
        toast: false,
        errorMessage: MSG_CLAIM_ERROR,
        onSuccess() {
            krtModal.close(document.getElementById('claim-modal'));
            showFrontendSuccessToast(MSG_CLAIM_WITHDRAW_SUCCESS);
            _refreshMaterialsSection();
        },
    });
}

/** @typedef {{orderId: string, status: string, version: number, previousStatus: string, select: HTMLSelectElement | null}} StatusChange */
/** @type {StatusChange | null} */
let pendingStatus = null;

/**
 * Applies a lifecycle action using the version displayed with that control.
 *
 * @param {HTMLSelectElement | HTMLButtonElement} control the status control
 */
export function updateStatus(control) {
    const { orderId, version } = control.dataset;
    const previousStatus = document.getElementById('order-head-meta')?.dataset.status;
    if (!orderId || !version || !previousStatus) return;
    const change = {
        orderId,
        status: control.value,
        version: Number(version),
        previousStatus,
        select: control instanceof HTMLSelectElement ? control : null,
    };
    if (isTerminalStatus(change.status)) {
        if (change.select) change.select.value = previousStatus;
        pendingStatus = change;
        krtModal.open(document.getElementById('status-warning-modal'));
        return;
    }
    saveStatus(change);
}

/** Discards the pending lifecycle action. */
export function cancelStatusChange() {
    pendingStatus = null;
    krtModal.close(document.getElementById('status-warning-modal'));
}

/** Confirms the lifecycle action with its observed version. */
export function confirmStatusChange() {
    const change = pendingStatus;
    pendingStatus = null;
    krtModal.close(document.getElementById('status-warning-modal'));
    if (change) saveStatus(change);
}

/**
 * Identifies a status that releases earmarks and ends promises.
 *
 * @param {string} status the order status
 * @returns {boolean} whether the status ends active work
 */
function isTerminalStatus(status) {
    return status === 'COMPLETED' || status === 'REJECTED' || status === 'CANCELLED';
}

/**
 * Writes a lifecycle change and re-renders the affected sections from their current versions.
 *
 * @param {StatusChange} change the observed control state
 */
function saveStatus({ orderId, status, version, previousStatus, select }) {
    const refresh =
        isTerminalStatus(status) || isTerminalStatus(previousStatus)
            ? [
                  'header',
                  'actions',
                  'participations',
                  'dialogs',
                  'kpi',
                  'money',
                  'materials',
                  'aggregated',
                  'items',
                  'handovers',
                  'item-handovers',
              ]
            : ['header'];
    krtOrderWrite({
        method: 'POST',
        url: `/orders/${encodeURIComponent(orderId)}/status`,
        payload: { status, version },
        toast: false,
        errorMessage: MSG_STATUS_ERROR,
        sectionKey: 'header',
        refresh,
        onSuccess() {
            _publishOrdersQueue();
            showFrontendSuccessToast(MSG_STATUS_SUCCESS);
        },
    }).then((result) => {
        if (select && !result?.ok) select.value = previousStatus;
    });
}

/**
 * Reads the order's need version for the blueprint-counting edit.
 *
 * @returns {number | null} the displayed need version, if present
 */
function _orderVersion() {
    const version =
        _odEditVersionInput()?.value ||
        document.getElementById('order-head-meta')?.dataset.orderVersion;
    return version ? Number(version) : null;
}

/**
 * Saves the blueprint variant counting switch and refreshes the blueprint owners.
 *
 * @param {HTMLInputElement} checkbox the switch, reverted on failure
 */
export function _toggleBlueprintCounting(checkbox) {
    const orderId = checkbox.getAttribute('data-order-id');
    const desired = checkbox.checked;
    if (!orderId) {
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
        sectionKey: 'header',
        refresh: ['header', 'blueprint-owners'],
        onSuccess(data) {
            if (data && data.version != null) {
                const editVer = _odEditVersionInput();
                if (editVer) editVer.value = data.version;
            }
            showFrontendSuccessToast(MSG_BP_COUNTING_SUCCESS);
        },
    }).then((res) => {
        checkbox.disabled = false;
        if (!res || !res.ok) {
            checkbox.checked = !desired;
        }
    });
}
