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

/* global poolChangeI18n, inventoryConflictI18n */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

/**
 * The entry the open pool-change dialog moves.
 *
 * @type {{id: string | null, version: string | null, amount: number}}
 */
const poolChangeEntry = { id: null, version: null, amount: 0 };

/** Whether a pool change is being written. */
let poolChangeInFlight = false;

/**
 * What the page refreshes after a pool change.
 *
 * @type {() => void}
 */
let poolChangeDone = () => {};

/**
 * Reads one control of the pool-change dialog.
 *
 * @param {string} id the control's id
 * @returns {HTMLInputElement | null} the control
 */
function poolChangeInput(id) {
    return /** @type {HTMLInputElement | null} */ (document.getElementById(id));
}

/**
 * Fills the target picker with the pools the entry may move into.
 *
 * @param {string} entryId the entry
 * @param {HTMLSelectElement} select the picker
 * @param {HTMLButtonElement | null} submit the submit button, enabled only with a target
 */
function fillPoolTargets(entryId, select, submit) {
    const noTarget = document.getElementById('poolChangeNoTarget');
    select.replaceChildren();
    if (submit) submit.disabled = true;
    krtFetch
        .getJson(`/inventory/${encodeURIComponent(entryId)}/pool-targets`)
        .then((/** @type {Array<ApiDto<'OrgUnitReferenceDto'>>} */ targets) => {
            const units = Array.isArray(targets) ? targets : [];
            units.forEach((unit) => {
                const option = document.createElement('option');
                option.value = unit.id ?? '';
                option.textContent = unit.name ?? '';
                select.appendChild(option);
            });
            if (noTarget) noTarget.classList.toggle('is-hidden', units.length > 0);
            if (submit) submit.disabled = units.length === 0;
        })
        .catch(() => {
            showFrontendErrorToast(poolChangeI18n.loadError);
        });
}

/**
 * Opens the pool-change dialog for one entry of the Globales Lager.
 *
 * @param {Element} el the entry's action button
 */
function openPoolChangeModal(el) {
    const id = el.getAttribute('data-id');
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('poolChangeTarget')
    );
    const amountEl = poolChangeInput('poolChangeAmount');
    const mergeEl = poolChangeInput('poolChangeMergeStock');
    const message = document.getElementById('poolChangeMessage');
    const submit = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('poolChangeSubmitBtn')
    );
    const modal = document.getElementById('poolChangeModal');
    if (!id || !select || !amountEl || !mergeEl || !modal) return;
    const amount = el.getAttribute('data-amount') ?? '';
    const isScu = el.getAttribute('data-quantity-type') !== 'PIECE';
    poolChangeEntry.id = id;
    poolChangeEntry.version = el.getAttribute('data-version');
    poolChangeEntry.amount = krtScuInput.parse(amount) || 0;
    amountEl.value = amount;
    amountEl.max = amount;
    amountEl.step = isScu ? '0.001' : '1';
    mergeEl.checked = false;
    if (message) {
        message.textContent = poolChangeI18n.message.replace(
            '{0}',
            el.getAttribute('data-org-unit-name') ?? '',
        );
    }
    fillPoolTargets(id, select, submit);
    krtModal.open(modal);
}

/** Closes the pool-change dialog. */
function closePoolChangeModal() {
    krtModal.close('poolChangeModal');
    poolChangeEntry.id = null;
}

/**
 * Writes the pool change in place through krtFetch, then lets the page refresh.
 *
 * @param {Event} event the form submit
 */
function submitPoolChange(event) {
    event.preventDefault();
    if (poolChangeInFlight || !poolChangeEntry.id) return;
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('poolChangeTarget')
    );
    const amountEl = poolChangeInput('poolChangeAmount');
    const mergeEl = poolChangeInput('poolChangeMergeStock');
    const submit = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('poolChangeSubmitBtn')
    );
    if (!select || !amountEl || !mergeEl || !select.value) return;
    const amount = krtScuInput.parse(amountEl.value);
    if (!Number.isFinite(amount) || amount <= 0 || amount > poolChangeEntry.amount) {
        showFrontendErrorToast(poolChangeI18n.invalidAmount);
        return;
    }
    const entryId = poolChangeEntry.id;
    poolChangeInFlight = true;
    if (submit) submit.disabled = true;
    krtFetch
        .write({
            method: 'POST',
            url: `/inventory/${encodeURIComponent(entryId)}/pool`,
            payload: {
                version: parseInt(poolChangeEntry.version ?? '', 10),
                targetOwningOrgUnitId: select.value,
                amount: amount === poolChangeEntry.amount ? null : amount,
                mergeStock: mergeEl.checked,
            },
            successMessage: poolChangeI18n.success,
            errorMessage: poolChangeI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess() {
                closePoolChangeModal();
                poolChangeDone();
            },
        })
        .then(() => {
            poolChangeInFlight = false;
            if (submit) submit.disabled = false;
        });
}

/**
 * Wires the pool-change dialog of the Globales Lager (REQ-INV-059).
 *
 * @param {() => void} onChanged what the page refreshes after a change: its table and its peers
 */
export function initPoolChange(onChanged) {
    poolChangeDone = onChanged;
    krtEvents.on('click', 'inv-admin-pool', openPoolChangeModal);
    krtEvents.on('click', 'inv-admin-close-pool', closePoolChangeModal);
    const form = document.getElementById('poolChangeForm');
    if (form) form.addEventListener('submit', submitPoolChange);
}
