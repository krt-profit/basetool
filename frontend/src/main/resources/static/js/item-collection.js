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
