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

/* global stockLevelI18n, stockLevelConflict */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { showKrtConfirm } from '/js/kernel/toast.js';

const RESULTS = '#stock-level-results';

/**
 * Parses a number field; an empty or invalid value is `null`.
 *
 * @param {string} value the field value
 * @returns {number | null} the number, or null
 */
function numberOrNull(value) {
    if (value.trim() === '') return null;
    const parsed = Number(value.replace(',', '.'));
    return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Looks up a form control by id.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement} the control, typed as an input for its value
 */
function control(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
}

/**
 * Shows or hides a form row.
 *
 * @param {string} id the row id
 * @param {boolean} visible whether the row is shown
 */
function showRow(id, visible) {
    const row = document.getElementById(id);
    if (row) row.hidden = !visible;
}

/**
 * Whether the create dialog is set to a game item rather than a material.
 *
 * @returns {boolean} true when the item picker is the active subject
 */
function itemKindSelected() {
    return control('stock-level-kind').value === 'item';
}

/**
 * Shows the subject picker of the chosen kind; a game item has no quality tier.
 */
function syncKind() {
    const item = itemKindSelected();
    showRow('stock-level-material-row', !item);
    showRow('stock-level-item-row', item);
    showRow('stock-level-tier-row', !item);
    if (item) control('stock-level-tier').value = '';
}

document.addEventListener('DOMContentLoaded', () => {
    const seam = krtPageSections.create({
        topicClass: 'INVENTORY_ALL',
        sections: {
            'stock-levels': { container: RESULTS, fragmentValue: 'results' },
            stock: { container: RESULTS, fragmentValue: 'results' },
        },
        pageUrl() {
            return window.location.pathname;
        },
        topic: 'inventory',
        dict() {
            return { refreshError: stockLevelI18n.refreshError };
        },
        keys: { refreshErrorKey: 'refreshError' },
        receiver: document.querySelector(RESULTS) ? { coalesceMs: 1500 } : undefined,
    });

    /**
     * Sends one level write and re-renders the lists for the caller and the peers.
     *
     * @param {string} method the HTTP method
     * @param {string} url the proxy path
     * @param {object | undefined} payload the JSON body
     * @param {string} successMessage the localized success toast
     * @param {HTMLElement | null} submitter the control to disable while the request runs
     * @param {() => void} [after] called after a successful write
     * @returns {Promise<unknown>} resolves when the write has settled
     */
    function send(method, url, payload, successMessage, submitter, after) {
        return seam.write({
            method,
            url,
            payload,
            successMessage,
            errorMessage: stockLevelI18n.error,
            conflict: stockLevelConflict,
            submitter: submitter || undefined,
            refresh: ['stock-levels'],
            onSuccess() {
                if (after) after();
            },
        });
    }

    krtEvents.on('click', 'stock-level-delete', async (button) => {
        const confirmed = await showKrtConfirm(
            String(stockLevelI18n.deleteTitle),
            String(stockLevelI18n.deleteText),
            String(stockLevelI18n.deleteAction),
        );
        if (!confirmed) return;
        const id = button.getAttribute('data-level-id') || '';
        const version = button.getAttribute('data-version') || '';
        send(
            'DELETE',
            `/inventory/stock-levels/${encodeURIComponent(id)}?version=${encodeURIComponent(version)}`,
            undefined,
            stockLevelI18n.deleted,
            button,
        );
    });

    const modal = document.getElementById('stock-level-modal');
    const form = document.getElementById('stock-level-form');
    if (!modal || !(form instanceof HTMLFormElement)) return;
    const entry = document.getElementById('stock-level-entry');

    krtEvents.on('click', 'stock-level-open-create', () => {
        form.reset();
        control('stock-level-id').value = '';
        control('stock-level-version').value = '';
        showRow('stock-level-unit-row', true);
        showRow('stock-level-kind-row', true);
        syncKind();
        if (entry) entry.hidden = true;
        krtModal.open(modal);
    });

    krtEvents.on('click', 'stock-level-edit', (button) => {
        form.reset();
        control('stock-level-id').value = button.getAttribute('data-level-id') || '';
        control('stock-level-version').value = button.getAttribute('data-version') || '';
        control('stock-level-tier').value = button.getAttribute('data-tier-id') || '';
        control('stock-level-minimum').value = button.getAttribute('data-minimum') || '';
        control('stock-level-target').value = button.getAttribute('data-target') || '';
        showRow('stock-level-unit-row', false);
        showRow('stock-level-kind-row', false);
        showRow('stock-level-material-row', false);
        showRow('stock-level-item-row', false);
        showRow('stock-level-tier-row', button.getAttribute('data-item') !== 'true');
        if (entry) {
            entry.textContent = button.getAttribute('data-entry') || '';
            entry.hidden = false;
        }
        krtModal.open(modal);
    });

    control('stock-level-kind').addEventListener('change', syncKind);

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const id = control('stock-level-id').value;
        const tier = control('stock-level-tier').value || null;
        const common = {
            qualityTierId: tier,
            minimum: numberOrNull(control('stock-level-minimum').value),
            target: numberOrNull(control('stock-level-target').value),
        };
        const payload = id
            ? { ...common, version: numberOrNull(control('stock-level-version').value) }
            : {
                  ...common,
                  orgUnitId: control('stock-level-unit').value,
                  materialId: itemKindSelected() ? null : control('stock-level-material').value,
                  gameItemId: itemKindSelected() ? control('stock-level-item').value || null : null,
              };
        send(
            id ? 'PUT' : 'POST',
            id ? `/inventory/stock-levels/${encodeURIComponent(id)}` : '/inventory/stock-levels',
            payload,
            stockLevelI18n.saved,
            /** @type {HTMLButtonElement | null} */ (form.querySelector('button[type="submit"]')),
            () => krtModal.close(modal),
        );
    });
});
