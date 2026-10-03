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

/* global MSG_OWNER_UPDATED, MSG_LOCATION_UPDATED, MSG_DELIVERED_UPDATED, MSG_ERROR_GENERIC */

function collectionRow(inventoryId) {
    return document.querySelector('tr[data-inventory-id="' + inventoryId + '"]');
}

/**
 * Formats an amount in the page language with up to three fraction digits.
 *
 * @param {number} value the amount
 * @returns {string} the formatted amount
 */
function formatCollectionAmount(value) {
    return value.toLocaleString(document.documentElement.lang || undefined, {
        maximumFractionDigits: 3,
    });
}

/**
 * Writes one progress bar: its fill width, its aria value and its label from the
 * data-progress-template, whose %0 and %1 take the delivered and the total amount, or the
 * percentage for the overall bar.
 *
 * @param {Element | null} el the [data-collection-progress] element
 * @param {number} delivered the delivered amount
 * @param {number} total the earmarked amount
 */
function renderCollectionProgress(el, delivered, total) {
    if (!el) return;
    const percent = total > 0 ? Math.round((Math.min(delivered, total) * 100) / total) : 0;
    el.setAttribute('aria-valuenow', String(percent));
    const fill = el.querySelector('.collection-progress__fill');
    if (fill instanceof HTMLElement) {
        fill.setAttribute('data-krtm-width', String(percent));
        fill.style.width = percent + '%';
    }
    const label = el.querySelector('.collection-progress__label');
    const template = el.getAttribute('data-progress-template');
    if (label && template) {
        const isGroup = el.getAttribute('data-collection-progress') === 'group';
        label.textContent = isGroup
            ? template
                  .replace('%0', formatCollectionAmount(delivered))
                  .replace('%1', formatCollectionAmount(total))
            : template.replace('%0', String(percent));
    }
}

/**
 * Recomputes every group bar and the overall bar from the rows' data-allocated amounts and their
 * delivered checkboxes.
 */
function updateCollectionProgress() {
    const table = document.getElementById('item-collection-table');
    if (!table) return;
    let delivered = 0;
    let total = 0;
    table.querySelectorAll('tbody[data-collection-group]').forEach(function (group) {
        let groupDelivered = 0;
        let groupTotal = 0;
        group.querySelectorAll('tr[data-allocated]').forEach(function (row) {
            const amount = parseFloat(row.getAttribute('data-allocated') || '') || 0;
            const checkbox = row.querySelector('.delivered-checkbox');
            groupTotal += amount;
            if (checkbox instanceof HTMLInputElement && checkbox.checked) {
                groupDelivered += amount;
            }
        });
        renderCollectionProgress(
            group.querySelector('[data-collection-progress="group"]'),
            groupDelivered,
            groupTotal,
        );
        delivered += groupDelivered;
        total += groupTotal;
    });
    renderCollectionProgress(
        document.querySelector('[data-collection-progress="total"]'),
        delivered,
        total,
    );
}

function broadcastCollectionChanged() {
    if (
        window.orderId &&
        window.krtLiveSync &&
        typeof window.krtLiveSync.sendChanged === 'function'
    ) {
        window.krtLiveSync.sendChanged('order:' + window.orderId, ['items']);
    }
}

async function collectionTransfer(inventoryId, target, successMessage) {
    const row = collectionRow(inventoryId);
    if (!window.krtFetch || !row) return;
    const amount = parseFloat(row.getAttribute('data-amount'));
    const version = parseInt(row.getAttribute('data-version'), 10);
    row.querySelectorAll('select').forEach(function (s) {
        s.disabled = true;
    });
    const _result = await window.krtFetch.write({
        method: 'POST',
        url: '/inventory/' + inventoryId + '/transfer',
        payload: {
            amount,
            targetUserId: target.targetUserId || null,
            targetLocationId: target.targetLocationId || null,
            type: 'TRANSFER',
            terminal: null,
            sellAmount: null,
            version,
        },
        toast: false,
        errorMessage: MSG_ERROR_GENERIC,
        onSuccess(body) {
            window.showFrontendSuccessToast(successMessage);
            if (body && body.id) {
                row.setAttribute('data-inventory-id', body.id);
                if (body.version != null) {
                    row.setAttribute('data-version', body.version);
                }
                row.querySelectorAll('[data-inventory-id]').forEach(function (el) {
                    el.setAttribute('data-inventory-id', body.id);
                });
                const deliveredCheckbox = row.querySelector('.delivered-checkbox');
                if (deliveredCheckbox) {
                    deliveredCheckbox.checked = false;
                }
            }
            broadcastCollectionChanged();
        },
    });
    row.querySelectorAll('select').forEach(function (s) {
        s.disabled = false;
    });
    updateCollectionProgress();
}

document.addEventListener('change', function (e) {
    const el = e.target;
    if (!el || !el.matches) return;
    if (el.matches('[data-role="owner-select"]')) {
        collectionTransfer(
            el.getAttribute('data-inventory-id'),
            { targetUserId: el.value },
            MSG_OWNER_UPDATED,
        );
    } else if (el.matches('.location-select')) {
        collectionTransfer(
            el.getAttribute('data-inventory-id'),
            { targetLocationId: el.value },
            MSG_LOCATION_UPDATED,
        );
    } else if (el.matches('.delivered-checkbox')) {
        onDeliveredToggle(el);
    }
});

async function onDeliveredToggle(cb) {
    const inventoryId = cb.getAttribute('data-inventory-id');
    const row = collectionRow(inventoryId);
    if (!window.krtFetch || !row) return;
    const previous = !cb.checked;
    const result = await window.krtFetch.write({
        method: 'PATCH',
        url: '/inventory/' + inventoryId + '/delivered',
        payload: {
            delivered: cb.checked,
            jobOrderId: cb.getAttribute('data-job-order-id'),
            version: parseInt(row.getAttribute('data-version'), 10),
        },
        containerSelector: 'tr[data-inventory-id="' + inventoryId + '"]',
        toast: false,
        errorMessage: MSG_ERROR_GENERIC,
        onSuccess() {
            window.showFrontendSuccessToast(MSG_DELIVERED_UPDATED);
            broadcastCollectionChanged();
        },
    });
    if (!result || !result.ok) {
        cb.checked = previous;
    }
    updateCollectionProgress();
}

const ITEM_COLLECTION_SECTIONS = {
    items: { container: '#item-collection-results', fragmentValue: 'results' },
};

document.addEventListener('DOMContentLoaded', function () {
    if (
        window.orderId &&
        window.krtLiveSync &&
        typeof window.krtLiveSync.createReceiver === 'function' &&
        window.krtFetch &&
        typeof window.krtFetch.swap === 'function' &&
        document.getElementById('item-collection-results')
    ) {
        window.krtLiveSync.createReceiver({
            topic: 'order:' + window.orderId,
            sections: ITEM_COLLECTION_SECTIONS,
            coalesceMs: 1500,
            refresh() {
                window.krtFetch.swap({
                    url: '/orders/' + window.orderId + '/item-collection',
                    container: '#item-collection-results',
                    fragmentValue: ITEM_COLLECTION_SECTIONS.items.fragmentValue,
                    history: false,
                });
            },
        });
    }
});
