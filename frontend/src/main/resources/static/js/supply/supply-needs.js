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
import { showKrtConfirm } from '/js/kernel/toast.js';
import {
    bindFilterForm,
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
        topicClass: 'SUPPLY_NEEDS',
    });
    bindFilterForm({
        formId: 'supply-needs-filter',
        containerSelector: '#supply-needs-results',
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
        showJobOrdersOf(select('supply-need-unit').value, '', '');
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
        showJobOrdersOf(
            button.getAttribute('data-unit-id') || '',
            button.getAttribute('data-job-order-id') || '',
            button.getAttribute('data-job-order-label') || '',
        );
        syncPurchaseFields();
        krtModal.open(modal);
    }

    const jobOrder = select('supply-need-job-order');

    /**
     * Offers only the job orders of one unit and selects one of them. A linked order that is no
     * longer offered, because it has closed, stays selectable under its label so an edit keeps it.
     *
     * @param {string} unitId the raising unit
     * @param {string} selectedId the linked order, or an empty string for none
     * @param {string} selectedLabel the label of the linked order, used when it is not offered
     */
    function showJobOrdersOf(unitId, selectedId, selectedLabel) {
        jobOrder.querySelectorAll('option[data-kept-link]').forEach((option) => option.remove());
        let offered = false;
        Array.from(jobOrder.options).forEach((option) => {
            const ownUnit = option.dataset.unitId;
            const visible = !ownUnit || ownUnit === unitId;
            option.hidden = !visible;
            option.disabled = !visible;
            if (visible && option.value === selectedId) offered = true;
        });
        if (selectedId && !offered) {
            const kept = document.createElement('option');
            kept.value = selectedId;
            kept.textContent = selectedLabel || selectedId;
            kept.dataset.keptLink = 'true';
            jobOrder.append(kept);
        }
        jobOrder.value = selectedId;
    }

    const unitSelect = select('supply-need-unit');
    unitSelect.addEventListener('change', () => showJobOrdersOf(unitSelect.value, '', ''));

    krtEvents.on('click', 'supply-open-need', openCreate);
    krtEvents.on('click', 'supply-edit-need', openEdit);

    const SHORTCUT_PARAMS = ['needUnit', 'needMaterial', 'needItem', 'needTier', 'needAmount'];

    /**
     * Selects an option by value when the select offers it; any other value leaves it unchanged.
     *
     * @param {HTMLSelectElement} target the select
     * @param {string | null} value the wanted option value
     */
    function selectIfOffered(target, value) {
        if (value && Array.from(target.options).some((option) => option.value === value)) {
            target.value = value;
            target.dispatchEvent(new Event('change'));
        }
    }

    /**
     * Opens the need form prefilled from the shortcut link of a low minimum stock level and drops
     * the shortcut parameters from the address.
     */
    function openFromShortcut() {
        const params = new URLSearchParams(window.location.search);
        const materialId = params.get('needMaterial');
        const itemId = params.get('needItem');
        if (!materialId && !itemId) return;
        openCreate();
        selectIfOffered(select('supply-need-unit'), params.get('needUnit'));
        if (itemId) {
            select('supply-need-kind').value = 'item';
            select('supply-need-kind').dispatchEvent(new Event('change'));
            selectIfOffered(select('supply-need-item'), itemId);
        } else {
            selectIfOffered(select('supply-need-material'), materialId);
            selectIfOffered(select('supply-need-tier'), params.get('needTier'));
        }
        const amount = params.get('needAmount');
        if (amount) field('supply-need-amount').value = amount;
        SHORTCUT_PARAMS.forEach((name) => params.delete(name));
        const rest = params.toString();
        window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : ''));
    }
    openFromShortcut();
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
            jobOrderId: textOrNull(jobOrder.value),
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
    krtEvents.on('click', 'supply-need-withdraw', async (button) => {
        const confirmed = await showKrtConfirm(
            String(supplyI18n.withdrawNeedTitle),
            String(supplyI18n.withdrawNeedText),
            String(supplyI18n.withdrawNeedAction),
        );
        if (confirmed) changeNeed(button, 'withdraw');
    });
});
