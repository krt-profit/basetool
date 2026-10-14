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

/* global supplyI18n */

import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { field, numberOrNull, select, sendWrite, textOrNull } from '/js/supply/supply-common.js';

/**
 * @typedef {object} LagerUnit
 * @property {string} orgUnitId the unit
 * @property {string} name the unit name
 * @property {boolean} acceptsDonations whether the unit takes donations
 */

/** @type {LagerUnit[] | null} */
let cachedUnits = null;

/**
 * Loads the units that accept donations, once per page.
 *
 * @returns {Promise<LagerUnit[]>} the receiving units
 */
async function receivingUnits() {
    if (cachedUnits === null) {
        const units = await krtFetch.getJson('/api/proxy/supply/units');
        cachedUnits = Array.isArray(units)
            ? units.filter((/** @type {LagerUnit} */ unit) => unit.acceptsDonations)
            : [];
    }
    return cachedUnits;
}

/**
 * Fills the unit select with the receiving units.
 *
 * @param {HTMLSelectElement} target the select
 * @param {LagerUnit[]} units the receiving units
 */
function fillUnits(target, units) {
    target.replaceChildren();
    for (const unit of units) {
        const option = document.createElement('option');
        option.value = unit.orgUnitId;
        option.textContent = unit.name;
        target.appendChild(option);
    }
}

/**
 * Formats a number for the hint under the amount field.
 *
 * @param {number} value the amount
 * @param {string} quantityType `PIECE`, `ITEM` or `SCU`
 * @returns {string} the formatted amount
 */
function formatAmount(value, quantityType) {
    const whole = quantityType === 'PIECE' || quantityType === 'ITEM';
    return whole ? String(Math.round(value)) : value.toFixed(3);
}

document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('supply-lager-modal');
    const form = document.getElementById('supply-lager-form');
    if (!modal || !form) return;

    krtEvents.on('click', 'supply-lager-donate', async (button) => {
        const units = await receivingUnits();
        const unitSelect = select('supply-lager-unit');
        /** @type {HTMLFormElement} */ (form).reset();
        fillUnits(unitSelect, units);
        const amount = Number(button.getAttribute('data-amount') || '0');
        const reserved = Number(button.getAttribute('data-reserved') || '0');
        const quantityType = button.getAttribute('data-quantity-type') || 'SCU';
        const free = Math.max(0, amount - reserved);
        field('supply-lager-source-id').value = button.getAttribute('data-id') || '';
        const label = document.getElementById('supply-lager-label');
        if (label) label.textContent = button.getAttribute('data-label') || '';
        const amountInput = field('supply-lager-amount');
        amountInput.value = free > 0 ? String(free) : '';
        amountInput.max = String(free);
        const hint = document.getElementById('supply-lager-free');
        if (hint) {
            hint.textContent = String(supplyI18n.lagerFree).replace(
                '{0}',
                formatAmount(free, quantityType),
            );
        }
        const submit = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        if (submit) submit.disabled = units.length === 0;
        if (units.length === 0 && hint) hint.textContent = String(supplyI18n.lagerNoUnits);
        krtModal.open(modal);
    });

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const unitId = textOrNull(select('supply-lager-unit').value);
        const submitter = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        sendWrite({
            method: 'POST',
            url: '/api/proxy/supply/donations',
            payload: {
                needId: null,
                receivingOrgUnitId: unitId,
                materialId: null,
                gameItemId: null,
                quality: null,
                amount: numberOrNull(field('supply-lager-amount').value),
                sourceInventoryItemId: textOrNull(field('supply-lager-source-id').value),
                anonymous: field('supply-lager-anonymous').checked,
            },
            successMessage: supplyI18n.pledgeSaved,
            errorMessage: supplyI18n.pledgeError,
            submitter,
            afterWrite() {
                krtModal.close(modal);
                document.dispatchEvent(new CustomEvent('krt:inventory-changed'));
                if (typeof krtLiveSync.sendChanged === 'function') {
                    krtLiveSync.sendChanged('inventory', ['stock']);
                    krtLiveSync.sendChanged('supply-needs', ['mine']);
                    if (unitId)
                        krtLiveSync.sendChanged(`supply-donations:${unitId}`, ['donations']);
                }
            },
        });
    });
});
