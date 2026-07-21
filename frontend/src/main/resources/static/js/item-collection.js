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

/*
 * Item-collection page module (/item-collection), the item sibling of material-collection.js
 * (REQ-ORDERS-031). It collects the game-item stock earmarked to an ITEM order: an owner/location
 * change transfers the row's full amount (the backend deletes the source item and appends a new
 * target item that KEEPS the order earmark — jobOrder link + delivered — whose id/version is
 * re-keyed onto the <tr> in place), and the delivered checkbox PATCHes with a version echo +
 * revert-on-failure. Both row controls delegate on document so they survive the searchable-combobox
 * enhancement and the live-sync fragment swap.
 *
 * REQ-FE-010: the page joins the order:{id} live-sync room and re-fetches its collectionResults
 * fragment in place when a peer flips a delivered flag or moves a row, and broadcasts the order's
 * `items` section on its own writes (the order-detail renders the earmarked stock inline in the
 * items table, REQ-ORDERS-028).
 *
 * The MSG_OWNER_UPDATED / MSG_LOCATION_UPDATED / MSG_DELIVERED_UPDATED / MSG_ERROR_GENERIC toast
 * strings are defined by the inline Thymeleaf bootstrap block of item-collection.html, which
 * executes immediately before this classic script.
 */

/* global MSG_OWNER_UPDATED, MSG_LOCATION_UPDATED, MSG_DELIVERED_UPDATED, MSG_ERROR_GENERIC */

function collectionRow(inventoryId) {
    return document.querySelector('tr[data-inventory-id="' + inventoryId + '"]');
}

// REQ-FE-010: tell other viewers of THIS order (order:{id}) that its item collection changed — a
// delivered flip or a row move (owner/location transfer) — so their standalone collection page
// re-fetches its table in place and the order-detail items section (which renders the earmarked
// stock inline) follows. The relay excludes the acting socket, so this never echoes back.
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
    // Disable the row's selects while the move is in flight to prevent concurrent changes.
    row.querySelectorAll('select').forEach(function (s) {
        s.disabled = true;
    });
    const _result = await window.krtFetch.write({
        method: 'POST',
        url: '/inventory/' + inventoryId + '/transfer',
        payload: {
            amount: amount,
            targetUserId: target.targetUserId || null,
            targetLocationId: target.targetLocationId || null,
            type: 'TRANSFER',
            terminal: null,
            sellAmount: null,
            version: version,
        },
        toast: false,
        errorMessage: MSG_ERROR_GENERIC,
        onSuccess: function (body) {
            window.showFrontendSuccessToast(successMessage);
            // The backend always deletes the full-amount source item and appends a brand-new target
            // item (its own id + version), returning that target DTO (never 204). A full move keeps
            // this order's earmark on the target, so the row stays in the collection: re-key the
            // <tr> and its controls to the new id/version so a follow-up move or delivered-toggle on
            // the same row hits the live item instead of the deleted source (which would 404). The
            // fresh target row is shown as not delivered; a live-sync refresh corrects it if the
            // backend inherited a delivered flag.
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
    // Re-enable the row's selects whether the move succeeded (the row was re-keyed and stays
    // interactive) or failed (nothing changed) so the control is never left permanently disabled.
    row.querySelectorAll('select').forEach(function (s) {
        s.disabled = false;
    });
}

// Both row controls (owner + location transfer, delivered toggle) bind with ONE delegated change
// listener on document — not per-element — so they survive the live-sync fragment swap (REQ-FE-010)
// that re-renders the table; the owner combobox is re-enhanced by krt-searchable-select on the
// krt:swapped event. The combobox copies data-role + data-inventory-id onto its hidden input, which
// dispatches the change once enhanced (and the plain <select> still matches before enhancement /
// no-JS).
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

// Persist a delivered flip in place (PATCH + syncVersion via containerSelector), toast, broadcast the
// change to peers, and revert the native checkbox on failure so it never lies until a reload.
async function onDeliveredToggle(cb) {
    const inventoryId = cb.getAttribute('data-inventory-id');
    const row = collectionRow(inventoryId);
    if (!window.krtFetch || !row) return;
    const previous = !cb.checked; // state before this toggle, for revert-on-failure
    const result = await window.krtFetch.write({
        method: 'PATCH',
        url: '/inventory/' + inventoryId + '/delivered',
        payload: {
            delivered: cb.checked,
            // Variante C (REQ-INV-027): delivered is per-order — this whole page is one job order's
            // collection, so send its id and the backend flips only that slice.
            jobOrderId: cb.getAttribute('data-job-order-id'),
            version: parseInt(row.getAttribute('data-version'), 10),
        },
        containerSelector: 'tr[data-inventory-id="' + inventoryId + '"]',
        toast: false,
        errorMessage: MSG_ERROR_GENERIC,
        onSuccess: function () {
            window.showFrontendSuccessToast(MSG_DELIVERED_UPDATED);
            broadcastCollectionChanged();
        },
    });
    if (!result || !result.ok) {
        cb.checked = previous;
    }
}

// REQ-FE-010: single source of truth for the receiver + the parity test. It reuses the ORDER topic's
// existing `items` section — the order-detail renders the earmarked stock inline in the items table,
// so this page renders a SUBSET of that topic and introduces no new section key; LiveSyncTopicClass
// .ORDER stays the whitelist. A peer's delivered flip / row move on this order re-fetches the
// collection table fragment in place.
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
            refresh: function () {
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
