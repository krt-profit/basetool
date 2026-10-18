/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Mein-Lager "Für Börse freigeben" checkbox wiring (REQ-MARKET-002). Checking a
 * leaf row opens the shared Materialbörse release modal (window.krtMaterialRelease)
 * pre-filled with that item; un-checking confirms and deactivates the item's offer.
 * The location is never sent — the modal only carries material / quality / amount.
 * Delegated via window.krtEvents so lazily-loaded leaf rows need no re-binding.
 */

import { showKrtConfirm } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtHooks } from '/js/kernel/krt-hooks.js';

function initInventoryMaterialboerse() {
    const i18n = window.materialboerseInventoryI18n || {};
    const release = krtHooks.materialRelease;
    if (!release) {
        return;
    }

    /**
     * Updates the status chip of one Lager item.
     *
     * @param {string | null} itemId the item the chip belongs to
     * @param {boolean} released whether the item is now offered on the board
     */
    function setStatus(itemId, released) {
        const chip = document.querySelector(`[data-boerse-status-for="${itemId}"]`);
        if (!chip) {
            return;
        }
        chip.textContent = released ? i18n.statusOn : i18n.statusPrivate;
        chip.classList.toggle('chip--primary', released);
        chip.classList.toggle('chip--muted', !released);
    }

    krtEvents.on('change', 'inv-boerse-toggle', (element) => {
        const el = /** @type {HTMLInputElement} */ (element);
        const itemId = el.getAttribute('data-id');
        if (el.checked) {
            release.open(
                'lager',
                {
                    itemId,
                    material: el.getAttribute('data-material'),
                    kind: el.getAttribute('data-kind'),
                    quality: el.getAttribute('data-quality'),
                    amount: el.getAttribute('data-amount'),
                    quantityType: el.getAttribute('data-quantity-type'),
                },
                {
                    onDone() {
                        setStatus(itemId, true);
                    },
                    onCancel() {
                        el.checked = false;
                    },
                },
            );
            return;
        }

        showKrtConfirm(
            i18n.deactivateTitle,
            i18n.deactivateBody,
            i18n.confirmYes,
            i18n.confirmNo,
        ).then((ok) => {
            if (!ok) {
                el.checked = true;
                return;
            }
            krtFetch.write({
                method: 'POST',
                url: `/materialboerse/items/${itemId}/deactivate/ajax`,
                successMessage: i18n.deactivated,
                errorMessage: i18n.error,
                serialize: 'materialboerse',
                onSuccess() {
                    setStatus(itemId, false);
                    krtPageSections.publish('materialboard', ['board']);
                },
                onError() {
                    el.checked = true;
                },
            });
        });
    });
}

initInventoryMaterialboerse();
