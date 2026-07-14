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
 * Material-collection page module (/material-collection), extracted verbatim from the former inline
 * script of material-collection.html (ADR-0069, follow-up to #924).
 *
 * In-place #577 collection writes through the shared krtFetch/krtCsrf foundation: an owner/location
 * change transfers the row's full amount (the backend deletes the source item and appends a new
 * target item, whose id/version is re-keyed onto the <tr> in place), and the delivered checkbox PATCHes
 * with a syncVersion + revert-on-failure. Delegated on data-role="owner-select" so it survives the
 * searchable-combobox enhancement.
 *
 * The MSG_OWNER_UPDATED / MSG_LOCATION_UPDATED / MSG_DELIVERED_UPDATED / MSG_ERROR_GENERIC toast
 * strings are defined by the inline Thymeleaf bootstrap block of material-collection.html, which
 * executes immediately before this classic script.
 */

/* global MSG_OWNER_UPDATED, MSG_LOCATION_UPDATED, MSG_DELIVERED_UPDATED, MSG_ERROR_GENERIC */

// #577: material-collection writes (owner/location transfer, delivered) save in place through
// the shared krtFetch/krtCsrf foundation (REQ-FE-002). The page-local csrfHeaders/getVersion/
// syncVersion/handleConflict/reload helpers are gone: krtFetch.write reads CSRF (with
// retry-on-403), and for `delivered` it syncs the fresh version onto the row via containerSelector
// so a follow-up action on the same row does not 409. A transfer moves the row's FULL amount: the
// backend deletes the source item and appends a new target item (still linked to this job order),
// returning that target DTO, so we re-key the <tr> to the new id/version in place.

function collectionRow(inventoryId) {
    return document.querySelector('tr[data-inventory-id="' + inventoryId + '"]');
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
            // The backend always deletes the full-amount source item and appends a brand-new
            // target item (its own id + version), returning that target DTO (never 204). The
            // target keeps this job-order link, so the row stays in the collection: re-key the
            // <tr> and its controls to the new id/version so a follow-up move or delivered-toggle
            // on the same row hits the live item instead of the deleted source (which would 404).
            // The fresh target row is not yet delivered.
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
        },
    });
    // Re-enable the row's selects whether the move succeeded (the row was re-keyed and stays
    // interactive) or failed (nothing changed) so the control is never left permanently disabled.
    row.querySelectorAll('select').forEach(function (s) {
        s.disabled = false;
    });
}

// The owner picker is upgraded into a searchable combobox by the global enhancer, which replaces
// the <select> (so a per-element listener bound on load would be lost). Delegate on the preserved
// data-role instead — the combobox copies data-role + data-inventory-id onto its hidden input,
// which is what dispatches the change once enhanced (and the plain <select> still matches before
// enhancement / no-JS).
document.addEventListener('change', function (e) {
    const el = e.target;
    if (!el.matches || !el.matches('[data-role="owner-select"]')) {
        return;
    }
    collectionTransfer(
        el.getAttribute('data-inventory-id'),
        { targetUserId: el.value },
        MSG_OWNER_UPDATED,
    );
});

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.location-select').forEach(function (select) {
        select.addEventListener('change', function () {
            collectionTransfer(
                this.getAttribute('data-inventory-id'),
                { targetLocationId: this.value },
                MSG_LOCATION_UPDATED,
            );
        });
    });

    document.querySelectorAll('.delivered-checkbox').forEach(function (checkbox) {
        checkbox.addEventListener('change', async function () {
            const cb = this;
            const inventoryId = cb.getAttribute('data-inventory-id');
            const row = collectionRow(inventoryId);
            if (!window.krtFetch || !row) return;
            const previous = !cb.checked; // state before this toggle, for revert-on-failure
            const result = await window.krtFetch.write({
                method: 'PATCH',
                url: '/inventory/' + inventoryId + '/delivered',
                payload: {
                    delivered: cb.checked,
                    // Variante C (REQ-INV-027): delivered is per-order — this whole page is one job
                    // order's collection, so send its id and the backend flips only that slice.
                    jobOrderId: cb.getAttribute('data-job-order-id'),
                    version: parseInt(row.getAttribute('data-version'), 10),
                },
                containerSelector: 'tr[data-inventory-id="' + inventoryId + '"]',
                toast: false,
                errorMessage: MSG_ERROR_GENERIC,
                onSuccess: function () {
                    window.showFrontendSuccessToast(MSG_DELIVERED_UPDATED);
                },
            });
            // A failed write (409 stale version / 5xx) already surfaced an error toast via
            // krtFetch.handleProblem, but the native checkbox kept its new, unpersisted state —
            // revert it so the control matches the persisted flag instead of lying until a reload
            // (mirrors the status <select> revert on a failed order-status change).
            if (!result || !result.ok) {
                cb.checked = previous;
            }
        });
    });
});
