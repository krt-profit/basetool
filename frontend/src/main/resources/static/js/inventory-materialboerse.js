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
(function () {
    'use strict';

    let i18n = window.materialboerseInventoryI18n || {};
    if (!window.krtEvents || !window.krtMaterialRelease || !window.krtFetch) {
        return;
    }

    function setStatus(itemId, released) {
        let chip = document.querySelector('[data-boerse-status-for="' + itemId + '"]');
        if (!chip) {
            return;
        }
        chip.textContent = released ? i18n.statusOn : i18n.statusPrivate;
        chip.classList.toggle('chip--primary', released);
        chip.classList.toggle('chip--muted', !released);
    }

    window.krtEvents.on('change', 'inv-boerse-toggle', function (el) {
        let itemId = el.getAttribute('data-id');
        if (el.checked) {
            // Newly released: open the remark dialog pre-filled with this Lager item.
            window.krtMaterialRelease.open(
                'lager',
                {
                    itemId: itemId,
                    material: el.getAttribute('data-material'),
                    quality: el.getAttribute('data-quality'),
                    amount: el.getAttribute('data-amount'),
                },
                {
                    onDone: function () {
                        setStatus(itemId, true);
                    },
                    onCancel: function () {
                        el.checked = false;
                    },
                },
            );
            return;
        }
        // Un-checked: confirm, then take the item's active offer off the board.
        window
            .showKrtConfirm(
                i18n.deactivateTitle,
                i18n.deactivateBody,
                i18n.confirmYes,
                i18n.confirmNo,
            )
            .then(function (ok) {
                if (!ok) {
                    el.checked = true;
                    return;
                }
                window.krtFetch.write({
                    method: 'POST',
                    url: '/materialboerse/items/' + itemId + '/deactivate/ajax',
                    successMessage: i18n.deactivated,
                    errorMessage: i18n.error,
                    serialize: 'materialboerse',
                    onSuccess: function () {
                        setStatus(itemId, false);
                        if (window.krtMaterialboardPresence) {
                            window.krtMaterialboardPresence.sendChanged(['board']);
                        }
                    },
                    onError: function () {
                        el.checked = true;
                    },
                });
            });
    });
})();
