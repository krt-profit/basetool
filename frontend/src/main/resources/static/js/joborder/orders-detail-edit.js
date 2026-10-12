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

/* global MSG_HANDOVER_SUCCESS, MSG_HANDOVER_FAILED, MSG_HANDOVER_NOITEMS, ORDER_AGE_YELLOW, ORDER_AGE_RED, MSG_UNIT_SCU, MSG_UNIT_PIECE, ORDER_CONFLICT, MSG_DELETE_TITLE, MSG_DELETE_MESSAGE, MSG_DELETE_CONFIRM, MSG_DELETE_CANCEL, MSG_DELETE_ERROR, MSG_UPDATE_SUCCESS, MSG_UPDATE_ERROR, MSG_MATERIAL_INVALID, MSG_INVENTORY_UNLINK_SUCCESS, MSG_INVENTORY_UNLINK_ERROR, ORDER_HANDOVER_I18N */

import { validateItemHandoverForm } from '/js/joborder/orders-detail-reports.js';
import {
    _odEditVersionInput,
    _odFieldValue,
    _publishOrdersQueue,
    _serializeHandoverForm,
    _serializeItemHandoverForm,
    resetInventoryCache,
    validateHandoverAmounts,
} from '/js/joborder/orders-detail.js';
import {
    showFrontendErrorToast,
    showFrontendSuccessToast,
    showKrtConfirm,
} from '/js/kernel/toast.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtEnhanceComboboxes } from '/js/kernel/krt-searchable-select.js';

document.addEventListener('DOMContentLoaded', () => {
    const handoverForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('handover-form')
    );
    if (handoverForm) {
        handoverForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!validateHandoverAmounts()) return;
            const orderId = handoverForm.getAttribute('data-order-id');
            if (!orderId || !krtFetch || typeof krtOrderWrite !== 'function') {
                handoverForm.submit();
                return;
            }
            const payload = _serializeHandoverForm();
            if (!payload.items.length) {
                showFrontendErrorToast(MSG_HANDOVER_NOITEMS);
                return;
            }
            await krtOrderWrite({
                method: 'POST',
                url: `/orders/${orderId}/handovers`,
                payload,
                toast: false,
                errorMessage: MSG_HANDOVER_FAILED,
                onSuccess(order) {
                    const editVer = _odEditVersionInput();
                    if (editVer && order && order.version != null) editVer.value = order.version;
                    const modal = document.getElementById('handover-modal');
                    if (modal) krtModal.close(modal);
                    resetInventoryCache();
                    const itemsContainer = document.getElementById('handover-items-container');
                    if (itemsContainer) itemsContainer.replaceChildren();
                    showFrontendSuccessToast(MSG_HANDOVER_SUCCESS);
                    _refreshMaterialsSection(orderId);
                    _swapOrderSection(orderId, 'order-handovers-results', 'handovers');
                    _swapOrderSection(orderId, 'order-header-results', 'header');
                    if (order && (order.status === 'COMPLETED' || order.status === 'REJECTED')) {
                        _publishOrdersQueue();
                    }
                },
            });
        });
    }
    const itemHandoverForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('item-handover-form')
    );
    if (itemHandoverForm) {
        itemHandoverForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!validateItemHandoverForm()) return;
            const orderId = itemHandoverForm.getAttribute('data-order-id');
            if (!orderId || !krtFetch || typeof krtOrderWrite !== 'function') {
                itemHandoverForm.submit();
                return;
            }
            const payload = _serializeItemHandoverForm();
            if (!payload.entries.length) {
                showFrontendErrorToast(MSG_HANDOVER_NOITEMS);
                return;
            }
            await krtOrderWrite({
                method: 'POST',
                url: `/orders/${orderId}/item-handovers`,
                payload,
                toast: false,
                errorMessage: MSG_HANDOVER_FAILED,
                onSuccess(order) {
                    const editVer = _odEditVersionInput();
                    if (editVer && order && order.version != null) editVer.value = order.version;
                    const modal = document.getElementById('item-handover-modal');
                    if (modal) krtModal.close(modal);
                    showFrontendSuccessToast(MSG_HANDOVER_SUCCESS);
                    _swapOrderSection(orderId, 'order-items-results', 'items');
                    _swapOrderSection(orderId, 'order-item-handovers-results', 'item-handovers');
                    _swapOrderSection(orderId, 'item-handover-lines', 'item-handover-lines');
                    _swapOrderSection(orderId, 'order-header-results', 'header');
                    _swapOrderSection(orderId, 'order-kpi-results', 'kpi');
                    if (krtLiveSync && typeof krtLiveSync.sendChanged === 'function') {
                        krtLiveSync.sendChanged('inventory', ['stock']);
                    }
                    if (order && (order.status === 'COMPLETED' || order.status === 'REJECTED')) {
                        _publishOrdersQueue();
                    }
                },
            });
        });
    }
});

/**
 * Renders the created dates, elapsed days and handover times under `root` in the viewer's locale.
 *
 * @param {ParentNode | null | undefined} [root] the subtree to localize; the document when absent
 */
function localizeOrderDates(root) {
    const scope = root || document;
    const now = new Date();
    scope.querySelectorAll('.od-created-date[data-utc]').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (!utcDateStr) return;
        const date = new Date(utcDateStr);
        if (!isNaN(date.getTime())) {
            el.textContent = date.toLocaleDateString();
        }
    });
    scope.querySelectorAll('.od-elapsed-days[data-utc]').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (!utcDateStr) return;
        const date = new Date(utcDateStr);
        if (isNaN(date.getTime())) return;
        const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
        el.textContent = String(diffDays > 0 ? diffDays : 0);
        const targetEl = el.closest('.od-elapsed-cell') || el;
        if (diffDays >= ORDER_AGE_RED) {
            targetEl.classList.add('text-danger');
        } else if (diffDays >= ORDER_AGE_YELLOW) {
            targetEl.classList.add('text-warning');
        }
    });
    scope.querySelectorAll('.handover-time-span').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (utcDateStr) {
            const dateObj = new Date(utcDateStr);
            if (!isNaN(dateObj.getTime())) {
                el.textContent = dateObj.toLocaleString([], {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit',
                });
            }
        }
    });
}
document.addEventListener('krt:swapped', (e) => {
    localizeOrderDates(e && e.detail ? e.detail.container : document);
});

document.addEventListener('DOMContentLoaded', () => {
    localizeOrderDates(document);

    const materialsContainer = document.getElementById('materials-container');
    const addMaterialBtn = document.getElementById('add-material-btn');
    if (addMaterialBtn && materialsContainer) {
        /**
         * Shows the unit and the SCU hint matching the material chosen in an edit-form row.
         *
         * @param {Element | null} row the material row
         */
        function refreshEditMaterialUnit(row) {
            if (!row) {
                return;
            }
            const sel = /** @type {HTMLSelectElement | HTMLInputElement | null} */ (
                row.querySelector('[data-role="material-select"]')
            );
            const amountInput = row.querySelector('[data-role="material-amount"]');
            const unitSpan = row.querySelector('[data-role="amount-unit"]');
            const hint = row.querySelector('.scu-hint');
            if (!sel || !amountInput) {
                return;
            }
            let qt = sel.dataset.quantityType || '';
            if (!qt && sel.tagName === 'SELECT') {
                const selectedOptions = /** @type {HTMLSelectElement} */ (sel).selectedOptions;
                const opt = selectedOptions && selectedOptions[0];
                qt = (opt && opt.getAttribute('data-quantity-type')) || '';
            }
            if (qt === 'PIECE') {
                amountInput.setAttribute('step', '1');
                amountInput.setAttribute('min', '1');
                if (unitSpan) unitSpan.textContent = `(${MSG_UNIT_PIECE})`;
                if (hint) hint.classList.add('is-hidden');
            } else if (qt === 'SCU') {
                amountInput.setAttribute('step', '0.001');
                amountInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = `(${MSG_UNIT_SCU})`;
                if (hint) hint.classList.remove('is-hidden');
            } else {
                amountInput.setAttribute('step', '0.001');
                amountInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '';
                if (hint) hint.classList.add('is-hidden');
            }
        }

        addMaterialBtn.addEventListener('click', () => {
            const nextIndex = materialsContainer.querySelectorAll('.material-row').length;
            const rowTemplate = /** @type {HTMLTemplateElement | null} */ (
                document.getElementById('edit-material-row-template')
            );
            if (!rowTemplate) {
                return;
            }
            const fragment = /** @type {DocumentFragment} */ (rowTemplate.content.cloneNode(true));
            fragment.querySelectorAll('select, input').forEach((field) => {
                const name = field.getAttribute('name');
                if (name) {
                    field.setAttribute('name', name.replace(/\[\d+\]/, `[${nextIndex}]`));
                }
            });
            materialsContainer.appendChild(fragment);
            const newRow = /** @type {Element} */ (materialsContainer.lastElementChild);
            if (krtEnhanceComboboxes) {
                krtEnhanceComboboxes(newRow);
            }
            refreshEditMaterialUnit(newRow);
        });

        materialsContainer.addEventListener('change', (e) => {
            const target = /** @type {Element | null} */ (e.target);
            if (target && target.matches && target.matches('[data-role="material-select"]')) {
                refreshEditMaterialUnit(target.closest('.material-row'));
            }
        });
        materialsContainer.querySelectorAll('.material-row').forEach(refreshEditMaterialUnit);

        materialsContainer.addEventListener('click', (e) => {
            const target = /** @type {Element} */ (e.target);
            const removeBtn = target.closest && target.closest('.remove-material-btn');
            if (removeBtn) {
                const row = removeBtn.closest('.material-row');
                if (materialsContainer.querySelectorAll('.material-row').length > 1) {
                    krtModal.open(document.getElementById('material-delete-confirm-modal'));
                    window.materialRowToDelete = row;
                } else {
                    showFrontendErrorToast(ORDER_HANDOVER_I18N.keepOneMaterial);
                }
            }
        });

        window.confirmRemoveMaterial = function () {
            if (window.materialRowToDelete) {
                window.materialRowToDelete.remove();
                reindexMaterials();
                window.materialRowToDelete = null;
            }
            krtModal.close(document.getElementById('material-delete-confirm-modal'));
        };

        /** Renumbers the names and ids of the material rows after a row was removed. */
        function reindexMaterials() {
            const rows = /** @type {HTMLElement} */ (materialsContainer).querySelectorAll(
                '.material-row',
            );
            rows.forEach((row, index) => {
                const inputs = row.querySelectorAll('input, select');
                inputs.forEach((input) => {
                    const name = input.getAttribute('name');
                    if (name) {
                        input.setAttribute('name', name.replace(/\[\d+\]/, `[${index}]`));
                        const id = input.getAttribute('id');
                        if (id) {
                            input.setAttribute('id', id.replace(/\d+/, String(index)));
                        }
                    }
                });
            });
        }
    }
});

/**
 * Sends an order write through krtFetch, serialized per order and with the order's conflict
 * dialog strings.
 *
 * @param {KrtWriteOpts} opts the write options
 * @returns {Promise<KrtWriteResult>} the outcome of the write
 */
export function krtOrderWrite(opts) {
    return krtFetch.write(
        Object.assign({}, opts, {
            serialize: opts.serialize || 'order',
            conflict: {
                title: ORDER_CONFLICT.title,
                reloadLabel: ORDER_CONFLICT.reload,
                reloadQuestion: ORDER_CONFLICT.reloadQuestion,
                dismissLabel: ORDER_CONFLICT.dismiss,
                reloadDetailFallback: ORDER_CONFLICT.detail,
            },
        }),
    );
}

function initOrdersDetailEdit() {
    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-order-form')
    );
    if (!form) return;
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const orderId = form.getAttribute('data-order-id');
        if (!orderId || typeof showKrtConfirm !== 'function' || !krtFetch) {
            form.submit();
            return;
        }
        const ok = await showKrtConfirm(
            MSG_DELETE_TITLE,
            MSG_DELETE_MESSAGE,
            MSG_DELETE_CONFIRM,
            MSG_DELETE_CANCEL,
        );
        if (!ok) return;
        const res = await krtOrderWrite({
            method: 'DELETE',
            url: `/orders/${orderId}`,
            toast: false,
            errorMessage: MSG_DELETE_ERROR,
        });
        if (res.ok) {
            _publishOrdersQueue();
            window.location.assign('/orders');
        }
    });
}

initOrdersDetailEdit();

/**
 * Collects the order edit form into the request body.
 *
 * @returns {{requestingOrgUnitId: string | null, handle: string, comment: string, version: number | null, materials: Array<{materialId: string, minQuality: number | null, amount: number}>}} the body
 */
function _serializeEditForm() {
    /** @type {Array<{materialId: string, minQuality: number | null, amount: number}>} */
    const materials = [];
    document.querySelectorAll('#materials-container .material-row').forEach((row) => {
        const matSel = /** @type {HTMLSelectElement | HTMLInputElement | null} */ (
            row.querySelector('[name$=".materialId"]')
        );
        const qualSel = /** @type {HTMLSelectElement | HTMLInputElement | null} */ (
            row.querySelector('[name$=".minQuality"]')
        );
        const amtInput = /** @type {HTMLInputElement | null} */ (
            row.querySelector('[name$=".amount"]')
        );
        const materialId = matSel ? matSel.value : '';
        const amount =
            amtInput && krtScuInput
                ? krtScuInput.parse(amtInput.value)
                : amtInput
                  ? parseFloat(amtInput.value)
                  : NaN;
        if (!materialId || !(amount > 0)) return;
        materials.push({
            materialId,
            minQuality: qualSel && qualSel.value ? parseInt(qualSel.value, 10) : null,
            amount,
        });
    });
    const versionInput = _odEditVersionInput();
    return {
        requestingOrgUnitId: _odFieldValue('requestingOrgUnitId') || null,
        handle: _odFieldValue('handle'),
        comment: _odFieldValue('edit-comment'),
        version: versionInput ? parseInt(versionInput.value, 10) : null,
        materials,
    };
}
function initOrdersDetailEdit2() {
    const form = /** @type {HTMLFormElement | null} */ (document.getElementById('edit-order-form'));
    if (!form) return;
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const orderId = form.getAttribute('data-order-id');
        if (!orderId || !krtFetch) {
            form.submit();
            return;
        }
        const payload = _serializeEditForm();
        if (!payload.materials.length) {
            showFrontendErrorToast(MSG_MATERIAL_INVALID);
            return;
        }
        const requesterView = form.getAttribute('data-requester-view') === 'true';
        const updateUrl = requesterView
            ? `/orders/${orderId}/requested-update`
            : `/orders/${orderId}/update`;
        await krtOrderWrite({
            method: 'POST',
            url: updateUrl,
            payload,
            toast: false,
            errorMessage: MSG_UPDATE_ERROR,
            onSuccess(dto) {
                const editVer = _odEditVersionInput();
                if (editVer && dto && dto.version != null) editVer.value = dto.version;
                const modal = document.getElementById('edit-modal');
                if (modal) krtModal.close(modal);
                showFrontendSuccessToast(MSG_UPDATE_SUCCESS);
                _swapOrderSection(orderId, 'order-header-results', 'header');
                _refreshMaterialsSection(orderId);
                _publishOrdersQueue();
            },
        });
    });
}

initOrdersDetailEdit2();

/**
 * Re-renders the materials, aggregated and KPI sections of the order in place.
 *
 * @param {string | null} [orderId] the order; the page's order when absent
 * @param {KrtSectionRefreshOpts} [broadcastOpts] the live-sync broadcast options
 * @returns {Promise<boolean> | void} the swap outcome, absent when the section seam handled it
 */
export function _refreshMaterialsSection(orderId, broadcastOpts) {
    if (window.krtRefreshOrderSection) {
        return window.krtRefreshOrderSection(['materials', 'aggregated', 'kpi'], broadcastOpts);
    }
    orderId ||= window.orderId || _odFieldValue('claim-order-id');
    const mat = document.getElementById('order-materials-results');
    const agg = document.getElementById('order-aggregated-results');
    const container = mat || agg;
    if (!orderId || !container || !krtFetch || !krtFetch.swap) return Promise.resolve(false);
    return krtFetch.swap({
        url: `/orders/${orderId}`,
        container,
        fragmentValue: mat ? 'materials' : 'aggregated',
        history: false,
        preserveScroll: true,
    });
}

/**
 * Re-renders one section of the order in place.
 *
 * @param {string | null | undefined} orderId the order
 * @param {string} containerId the id of the section's container
 * @param {string} fragmentValue the fragment name of the section
 * @param {KrtSectionRefreshOpts} [broadcastOpts] the live-sync broadcast options
 * @returns {Promise<boolean> | void} the swap outcome, absent when the section seam handled it
 */
export function _swapOrderSection(orderId, containerId, fragmentValue, broadcastOpts) {
    if (window.krtRefreshOrderSection) {
        return window.krtRefreshOrderSection(fragmentValue, broadcastOpts);
    }
    const c = document.getElementById(containerId);
    if (!c || !orderId || !krtFetch || !krtFetch.swap) return Promise.resolve(false);
    return krtFetch.swap({
        url: `/orders/${orderId}`,
        container: c,
        fragmentValue,
        history: false,
        preserveScroll: true,
    });
}

/**
 * Unlinks an inventory entry from the order and refreshes the materials section.
 *
 * @param {Element} btn the unlink button carrying the order and entry ids
 */
export async function unlinkInventoryItem(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const invId = btn.getAttribute('data-inventory-item-id');
    if (!orderId || !invId) return;
    await krtOrderWrite({
        method: 'DELETE',
        url: `/orders/${orderId}/inventory/${invId}/unlink/ajax`,
        toast: false,
        errorMessage: MSG_INVENTORY_UNLINK_ERROR,
        onSuccess(data) {
            if (data && data.version != null) {
                const sel = document.getElementById('status-select');
                if (sel) sel.dataset.version = data.version;
                const editVer = _odEditVersionInput();
                if (editVer) editVer.value = data.version;
            }
            showFrontendSuccessToast(MSG_INVENTORY_UNLINK_SUCCESS);
            _refreshMaterialsSection(orderId);
        },
    });
}
