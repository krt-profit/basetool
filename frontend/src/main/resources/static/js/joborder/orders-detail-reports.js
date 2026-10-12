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

/* global MSG_HANDOVER_REPORT_ERROR, MSG_HANDOVER_REPORT_VALIDATION_DATE, MSG_HANDOVER_REPORT_VALIDATION_TIME, MSG_HANDOVER_REPORT_VALIDATION_HANDLE, MSG_HANDOVER_REPORT_VALIDATION_ITEMS, MSG_OWNER, MSG_LOCATION, MSG_STOLEN, MSG_QUALITY, MSG_QUANTITY, MSG_SQUADRON, MSG_LOADING_INVENTORY, MSG_EMPTY_INVENTORY, MSG_INVENTORY_LOAD_ERROR, MSG_INVENTORY_BELOW_FLOOR, MSG_INVENTORY_PART_OF, MSG_INVENTORY_UNLINK_TOOLTIP, IS_LOGISTICIAN, ORDER_REQUESTING_SQUADRON_ID, ORDER_HANDOVER_I18N */

import {
    cachedInventoryItems,
    fillPlaceholders,
    validateHandoverAmounts,
} from '/js/joborder/orders-detail.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHtml } from '/js/kernel/krt-html.js';

/**
 * Downloads the PDF report of a material handover.
 *
 * @param {Element} btn the download button carrying the order, handover and handover time
 */
export async function downloadHandoverReport(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const handoverId = btn.getAttribute('data-handover-id');
    const handoverTimeRaw = btn.getAttribute('data-handover-time');
    const orderNumberRaw =
        document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
        document.getElementById('order-display-id')?.textContent?.trim() ||
        `#${orderId}`;
    const orderNumber = orderNumberRaw.replace(/^#/, '');
    let dateStr = '';
    let timeStr = '';
    if (handoverTimeRaw) {
        const d = new Date(handoverTimeRaw);
        dateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
            d.getDate(),
        ).padStart(2, '0')}`;
        timeStr = `${String(d.getHours()).padStart(2, '0')}-${String(d.getMinutes()).padStart(2, '0')}`;
    }
    const filename = fillPlaceholders(ORDER_HANDOVER_I18N.reportFilename, [
        orderNumber,
        dateStr,
        timeStr,
    ]);
    try {
        const userTimeZone =
            Intl && Intl.DateTimeFormat ? Intl.DateTimeFormat().resolvedOptions().timeZone : '';
        /** @type {Record<string, string>} */
        const downloadHeaders = {};
        if (userTimeZone) {
            downloadHeaders['X-User-Time-Zone'] = userTimeZone;
        }
        const response = await krtFetch.get(
            `/api/v1/orders/${orderId}/handovers/${handoverId}/report`,
            { headers: downloadHeaders },
        );
        if (!response || !response.ok) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.target = '_blank';
        a.download = filename;
        krtModal.layerRoot().appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
    }
}

/**
 * Downloads the PDF report of an item handover.
 *
 * @param {Element} btn the download button carrying the order, handover and handover time
 */
export async function downloadItemHandoverReport(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const handoverId = btn.getAttribute('data-handover-id');
    const handoverTimeRaw = btn.getAttribute('data-handover-time');
    const orderNumberRaw =
        document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
        document.getElementById('order-display-id')?.textContent?.trim() ||
        `#${orderId}`;
    const orderNumber = orderNumberRaw.replace(/^#/, '');
    let dateStr = '';
    let timeStr = '';
    if (handoverTimeRaw) {
        const d = new Date(handoverTimeRaw);
        dateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
            d.getDate(),
        ).padStart(2, '0')}`;
        timeStr = `${String(d.getHours()).padStart(2, '0')}-${String(d.getMinutes()).padStart(2, '0')}`;
    }
    const filename = fillPlaceholders(ORDER_HANDOVER_I18N.reportFilename, [
        orderNumber,
        dateStr,
        timeStr,
    ]);
    try {
        const userTimeZone =
            Intl && Intl.DateTimeFormat ? Intl.DateTimeFormat().resolvedOptions().timeZone : '';
        /** @type {Record<string, string>} */
        const downloadHeaders = {};
        if (userTimeZone) {
            downloadHeaders['X-User-Time-Zone'] = userTimeZone;
        }
        const response = await krtFetch.get(
            `/api/v1/orders/${orderId}/item-handovers/${handoverId}/report`,
            { headers: downloadHeaders },
        );
        if (!response || !response.ok) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.target = '_blank';
        a.download = filename;
        krtModal.layerRoot().appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
    }
}

/**
 * Checks that at least one item handover amount is positive, toasting otherwise.
 *
 * @returns {boolean} true when the form can be submitted
 */
export function validateItemHandoverForm() {
    const inputs = /** @type {NodeListOf<HTMLInputElement>} */ (
        document.querySelectorAll('#item-handover-modal input[type="number"]')
    );
    for (const inp of inputs) {
        const val = parseInt(inp.value, 10);
        if (!isNaN(val) && val > 0) {
            return true;
        }
    }
    showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_ITEMS);
    return false;
}

/**
 * Validates the handover form and opens a preview of its PDF report.
 *
 * @param {Element} btn the preview button carrying the order id
 */
export async function previewHandoverReport(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const orderNumber =
        document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
        document.getElementById('order-display-id')?.textContent?.trim() ||
        `#${orderId}`;
    const datePart = /** @type {HTMLInputElement | null} */ (
        document.querySelector('#handover-modal .date-part')
    )?.value;
    const timePart = /** @type {HTMLInputElement | null} */ (
        document.querySelector('#handover-modal .time-part')
    )?.value;
    const recipientHandle = /** @type {HTMLInputElement | null} */ (
        document.getElementById('recipientHandle')
    )?.value?.trim();
    const itemRows = document.querySelectorAll('#handover-items-container .handover-item-row');
    const hasValidItem = Array.from(itemRows).some((row) => {
        const sel = row.querySelector('select');
        const inp = /** @type {HTMLInputElement | null} */ (
            row.querySelector('input[type="number"]')
        );
        return sel && sel.value && inp && inp.value;
    });
    if (!datePart) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_DATE);
        return;
    }
    if (!timePart) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_TIME);
        return;
    }
    if (!recipientHandle) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_HANDLE);
        return;
    }
    if (!hasValidItem) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_ITEMS);
        return;
    }
    if (!validateHandoverAmounts()) {
        return;
    }
    /** @type {Array<{materialName: string, locationName: string | null, amount: number, quality: number, quantityType: string | null}>} */
    const items = [];
    document.querySelectorAll('.handover-item-row').forEach((row) => {
        const select = row.querySelector('select');
        const amountInput = /** @type {HTMLInputElement | null} */ (
            row.querySelector('input[type="number"]')
        );
        if (select && select.value && amountInput && amountInput.value) {
            const invId = select.value;
            const inv = cachedInventoryItems.find((i) => i.id === invId);
            if (inv) {
                items.push({
                    materialName: inv.material ? inv.material.name : '-',
                    locationName: inv.location ? inv.location.name : null,
                    amount: parseFloat(amountInput.value),
                    quality: inv.quality,
                    quantityType: inv.material ? inv.material.quantityType : null,
                });
            }
        }
    });
    let handoverTimeIso;
    try {
        const localDateStr = `${datePart}T${timePart}`;
        const localDate = new Date(localDateStr);
        if (isNaN(localDate.getTime())) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_DATE);
            return;
        }
        handoverTimeIso = localDateStr;
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_DATE);
        return;
    }
    const payload = {
        jobOrderNumber: orderNumber,
        handoverTime: handoverTimeIso,
        recipientHandle,
        items,
    };
    if (!krtFetch) return;
    const result = await krtFetch.write({
        method: 'POST',
        url: `/api/v1/orders/${encodeURIComponent(String(orderId))}/handovers/report/preview`,
        payload,
        accept: 'application/pdf, application/problem+json',
        responseType: 'blob',
        toast: false,
        errorMessage: MSG_HANDOVER_REPORT_ERROR,
        onError() {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
            return true;
        },
    });
    if (!result.ok || !(result.body instanceof Blob)) {
        if (result.ok) showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
        return;
    }
    try {
        const blob = result.body;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.target = '_blank';
        const previewOrderNumberRaw =
            document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
            document.getElementById('order-display-id')?.textContent?.trim() ||
            `#${orderId}`;
        const previewOrderNumber = previewOrderNumberRaw.replace(/^#/, '');
        const previewDate = datePart;
        const previewTime = timePart ? timePart.replace(':', '-') : '';
        a.download = fillPlaceholders(ORDER_HANDOVER_I18N.reportFilename, [
            previewOrderNumber,
            previewDate,
            previewTime,
        ]);
        krtModal.layerRoot().appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
    }
}

/**
 * Loads how much of each linked row counts toward the given quality bucket (REQ-ORDERS-037).
 *
 * @param {string | null} orderId the order
 * @param {string | null} materialId the material
 * @param {string | null} tierId the bucket's quality tier; without one, nothing is filtered
 * @returns {Promise<{here: Map<string, number>, unattributed: Set<string>} | null>} the rows
 *     counted toward the bucket with their amounts and the rows no bucket accepts, or null
 */
async function _loadStockAttribution(orderId, materialId, tierId) {
    if (!tierId || !orderId || !materialId) return null;
    try {
        const list = await krtFetch.getJson(
            `/orders/${orderId}/materials/${materialId}/attribution`,
        );
        /** @type {Map<string, number>} */
        const here = new Map();
        /** @type {Set<string>} */
        const unattributed = new Set();
        for (const entry of list) {
            if (entry.qualityTierId === tierId) {
                here.set(
                    entry.inventoryItemId,
                    (here.get(entry.inventoryItemId) || 0) + entry.amount,
                );
            } else if (entry.qualityTierId == null) {
                unattributed.add(entry.inventoryItemId);
            }
        }
        return { here, unattributed };
    } catch (_e) {
        return null;
    }
}

/**
 * Expands or collapses the linked inventory entries below a material row.
 *
 * @param {HTMLElement} row the material row
 */
export async function toggleInventory(row) {
    const orderId = row.getAttribute('data-order-id');
    const materialId = row.getAttribute('data-material-id');
    const amountType = row.getAttribute('data-amount-type');

    const nextRow = row.nextElementSibling;
    if (nextRow && nextRow.classList.contains('inventory-details-row')) {
        nextRow.remove();
        return;
    }

    const detailsRow = document.createElement('tr');
    detailsRow.classList.add('inventory-details-row');
    krtHtml.set(
        detailsRow,
        krtHtml`
            <td colspan="5" class="od-inv-cell">
                <div class="od-inv-note">${MSG_LOADING_INVENTORY}</div>
            </td>
        `,
    );
    /** @type {Node} */ (row.parentNode).insertBefore(detailsRow, row.nextSibling);

    try {
        const allItems = await krtFetch.getJson(
            `/orders/${orderId}/materials/${materialId}/inventory`,
        );
        const attribution = await _loadStockAttribution(
            orderId,
            materialId,
            row.getAttribute('data-quality-tier-id'),
        );
        const items = attribution
            ? allItems.filter(
                  (/** @type {any} */ it) =>
                      attribution.here.has(it.id) || attribution.unattributed.has(it.id),
              )
            : allItems;

        if (items.length === 0) {
            krtHtml.set(
                detailsRow,
                krtHtml`
                    <td colspan="5" class="od-inv-cell">
                        <div class="od-inv-note">${MSG_EMPTY_INVENTORY}</div>
                    </td>
                `,
            );
            return;
        }

        const unlinkColHeader = IS_LOGISTICIAN ? krtHtml`<th class="od-inv-th"></th>` : '';
        const subColspan = IS_LOGISTICIAN ? 7 : 6;

        const rows = items.map((/** @type {any} */ item) => {
            const ownerName = item.user ? item.user.effectiveName : '-';
            const locationName = item.location ? item.location.name : '-';
            /** @type {KrtHtml[]} */
            const chips = [];
            if (item.stolen) {
                chips.push(
                    krtHtml` <span class="chip chip--danger chip-xs" data-testid="stolen-chip">${MSG_STOLEN}</span>`,
                );
            }
            const quality = item.quality !== null ? item.quality : '-';
            const unit = amountType ? amountType : '';
            const counted = attribution ? attribution.here.get(item.id) : undefined;
            const total = item.amount !== null ? item.amount.toFixed(3) : '0.000';
            let quantity = `${total} ${unit}`;
            if (counted !== undefined && Math.abs(counted - (item.amount || 0)) > 0.0005) {
                quantity = MSG_INVENTORY_PART_OF.replace(
                    '{0}',
                    `${counted.toFixed(3)} ${unit}`,
                ).replace('{1}', `${total} ${unit}`);
            }
            if (attribution && counted === undefined && attribution.unattributed.has(item.id)) {
                chips.push(
                    krtHtml` <span class="chip chip-xs" data-testid="below-floor-chip">${MSG_INVENTORY_BELOW_FLOOR}</span>`,
                );
            }
            const itemSquadron = item.owningSquadron || null;
            const itemSquadronId = itemSquadron ? itemSquadron.id : null;
            const itemSquadronShorthand = itemSquadron ? itemSquadron.shorthand : null;
            const itemSquadronName = itemSquadron ? itemSquadron.name : '';
            const isForeignSquadron =
                itemSquadronId !== null &&
                ORDER_REQUESTING_SQUADRON_ID !== null &&
                itemSquadronId !== ORDER_REQUESTING_SQUADRON_ID;
            const rowClass = isForeignSquadron ? 'inventory-row-foreign-jobOrder' : '';
            const squadronBadgeClass = isForeignSquadron
                ? 'squadron-badge squadron-badge-foreign'
                : 'squadron-badge';
            const squadronCell = itemSquadronShorthand
                ? krtHtml`<span class="${squadronBadgeClass}" title="${itemSquadronName}">${itemSquadronShorthand}</span>`
                : krtHtml`<span class="squadron-badge squadron-badge-muted">&mdash;</span>`;
            const unlinkCell = IS_LOGISTICIAN
                ? krtHtml`
                                        <td data-trigger="stop-propagation">
                                            <button type="button" class="btn btn-quiet-danger od-inv-unlink-btn"
                                                    data-trigger="od-unlink-inventory"
                                                    data-order-id="${orderId}"
                                                    data-inventory-item-id="${item.id}"
                                                    title="${MSG_INVENTORY_UNLINK_TOOLTIP}">&times;</button>
                                        </td>`
                : '';

            return krtHtml`
                                    <tr class="${rowClass}">
                                        <td>${ownerName}</td>
                                        <td>${squadronCell}</td>
                                        <td>${locationName}${chips}</td>
                                        <td>${quality}</td>
                                        <td>${quantity}</td>
                                        ${unlinkCell}
                                    </tr>
                `;
        });

        krtHtml.set(
            detailsRow,
            krtHtml`
                <td colspan="${subColspan}" class="p-0">
                    <div class="od-inv-panel">
                        <div class="table-responsive">
                            <table class="data-table od-inv-table">
                                <thead>
                                    <tr>
                                        <th class="od-inv-th">${MSG_OWNER}</th>
                                        <th class="od-inv-th">${MSG_SQUADRON}</th>
                                        <th class="od-inv-th">${MSG_LOCATION}</th>
                                        <th class="od-inv-th">${MSG_QUALITY}</th>
                                        <th class="od-inv-th">${MSG_QUANTITY}</th>
                                        ${unlinkColHeader}
                                    </tr>
                                </thead>
                                <tbody>
            ${rows}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </td>
            `,
        );
    } catch (error) {
        console.error('Error fetching inventory items:', error);
        krtHtml.set(
            detailsRow,
            krtHtml`
                <td colspan="5" class="od-inv-cell">
                    <div class="text-danger">${window.krtI18nText(MSG_INVENTORY_LOAD_ERROR, 'MSG_INVENTORY_LOAD_ERROR')}</div>
                </td>
            `,
        );
    }
}
