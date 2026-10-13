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

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import {
    bindKindToggle,
    bindResults,
    field,
    initPledgeModal,
    numberOrNull,
    select,
    sendWrite,
    textOrNull,
} from '/js/supply/supply-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#supply-needs-results',
        listUrl: '/supply/needs',
        section: 'needs',
    });
    initPledgeModal(afterWrite);

    const modal = document.getElementById('supply-need-modal');
    const form = document.getElementById('supply-need-form');
    if (!modal || !form) return;

    bindKindToggle('supply-need-kind', 'supply-need-material-row', 'supply-need-item-row', [
        'supply-need-tier-row',
    ]);

    const purchasable = field('supply-need-purchasable');
    const purchaseFields = document.getElementById('supply-need-purchase-fields');

    /** Shows the reason and price fields only while the need may be bought. */
    function syncPurchaseFields() {
        if (purchaseFields) purchaseFields.hidden = !purchasable.checked;
    }
    purchasable.addEventListener('change', syncPurchaseFields);

    /**
     * Opens the need form empty, for a new need.
     */
    function openCreate() {
        /** @type {HTMLFormElement} */ (form).reset();
        field('supply-need-id').value = '';
        field('supply-need-version').value = '';
        [
            'supply-need-unit-row',
            'supply-need-kind-row',
            'supply-need-material-row',
            'supply-need-tier-row',
        ].forEach((id) => {
            const row = document.getElementById(id);
            if (row) row.hidden = false;
        });
        select('supply-need-kind').dispatchEvent(new Event('change'));
        syncPurchaseFields();
        krtModal.open(modal);
    }

    /**
     * Opens the need form on an existing need; the catalogue entry and the tier stay fixed.
     *
     * @param {HTMLElement} button the edit button carrying the need's data attributes
     */
    function openEdit(button) {
        /** @type {HTMLFormElement} */ (form).reset();
        field('supply-need-id').value = button.getAttribute('data-need-id') || '';
        field('supply-need-version').value = button.getAttribute('data-version') || '';
        field('supply-need-amount').value = button.getAttribute('data-amount') || '';
        select('supply-need-priority').value = button.getAttribute('data-priority') || 'NORMAL';
        purchasable.checked = button.getAttribute('data-purchasable') === 'true';
        select('supply-need-reason').value =
            button.getAttribute('data-reason') || 'HIGH_FARM_EFFORT';
        field('supply-need-max-price').value = button.getAttribute('data-max-price') || '';
        [
            'supply-need-unit-row',
            'supply-need-kind-row',
            'supply-need-material-row',
            'supply-need-item-row',
            'supply-need-tier-row',
        ].forEach((id) => {
            const row = document.getElementById(id);
            if (row) row.hidden = true;
        });
        syncPurchaseFields();
        krtModal.open(modal);
    }

    krtEvents.on('click', 'supply-open-need', openCreate);
    krtEvents.on('click', 'supply-edit-need', openEdit);

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const id = textOrNull(field('supply-need-id').value);
        const isItem = select('supply-need-kind').value === 'item';
        const buy = purchasable.checked;
        const common = {
            amount: numberOrNull(field('supply-need-amount').value),
            priority: select('supply-need-priority').value,
            purchasable: buy,
            purchaseReason: buy ? select('supply-need-reason').value : null,
            maxUnitPrice: buy ? numberOrNull(field('supply-need-max-price').value) : null,
            jobOrderId: null,
        };
        const payload = id
            ? { ...common, version: numberOrNull(field('supply-need-version').value) }
            : {
                  ...common,
                  orgUnitId: select('supply-need-unit').value,
                  materialId: isItem ? null : textOrNull(select('supply-need-material').value),
                  gameItemId: isItem ? textOrNull(select('supply-need-item').value) : null,
                  qualityTierId: isItem ? null : textOrNull(select('supply-need-tier').value),
              };
        sendWrite({
            method: id ? 'PUT' : 'POST',
            url: id ? `/api/proxy/supply/needs/${id}` : '/api/proxy/supply/needs',
            payload,
            successMessage: supplyI18n.needSaved,
            errorMessage: supplyI18n.needError,
            submitter: /** @type {HTMLButtonElement | null} */ (
                form.querySelector('button[type="submit"]')
            ),
            afterWrite() {
                krtModal.close(modal);
                afterWrite();
            },
        });
    });

    /**
     * Sends a state change of a need that carries only the echoed version.
     *
     * @param {HTMLElement} button the action button carrying the need id and version
     * @param {string} action `pause`, `resume` or `withdraw`
     */
    function changeNeed(button, action) {
        sendWrite({
            method: 'POST',
            url: `/api/proxy/supply/needs/${button.getAttribute('data-need-id')}/${action}`,
            payload: { version: numberOrNull(button.getAttribute('data-version') || '') },
            successMessage: supplyI18n.needChanged,
            errorMessage: supplyI18n.needError,
            submitter: button,
            afterWrite,
        });
    }

    krtEvents.on('click', 'supply-need-pause', (button) => {
        changeNeed(button, 'pause');
    });
    krtEvents.on('click', 'supply-need-resume', (button) => {
        changeNeed(button, 'resume');
    });
    krtEvents.on('click', 'supply-need-withdraw', (button) => {
        changeNeed(button, 'withdraw');
    });
});
