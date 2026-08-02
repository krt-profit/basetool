// @ts-check
/*
 * Shared yield-bonus badge manager for refinery-order forms (create + detail page).
 *
 * Each material row hosts an optional ".yield-bonus-badge" <span> inside the
 * outputQuantity label that displays the UEX yield bonus/malus for the currently-
 * picked input material at the order's refinery. Page templates init this module
 * once with the server-rendered {materialId -> bonusPercent} map plus the i18n
 * help text; on every material/location change the page wires the relevant
 * helper from this module so the badge re-renders without a page reload.
 *
 * The /refinery-orders/locations/{id}/yields proxy endpoint exposed by the
 * frontend's RefineryOrderPageController is the canonical source for the map
 * when the location changes — onLocationChange() does the fetch + state update.
 */
(function () {
    'use strict';

    let state = {
        yieldByMaterialId: {},
        helpText: '',
    };

    /**
     * Replaces the in-memory yield map and help text. Page templates call this
     * once on DOMContentLoaded with the server-rendered initial state.
     */
    function init(initialMap, helpText) {
        state.yieldByMaterialId = initialMap || {};
        state.helpText = helpText || '';
    }

    /**
     * Sets / updates / removes the yield-bonus badge on the row identified by
     * rowIndex. Passing undefined or null for bonus collapses the badge entirely
     * (treated as "no UEX data for this material at this refinery").
     */
    function setBadge(rowIndex, bonus) {
        let label = document.querySelector('label[for="outputQuantity_' + rowIndex + '"]');
        if (!label) return;
        let badge = label.querySelector('#yieldBonus_' + rowIndex);
        if (bonus === undefined || bonus === null) {
            if (badge) badge.remove();
            return;
        }
        if (!badge) {
            badge = document.createElement('span');
            badge.id = 'yieldBonus_' + rowIndex;
            badge.className = 'yield-bonus-badge';
            /** @type {HTMLElement} */ (badge).title = state.helpText;
            label.appendChild(badge);
        }
        badge.classList.remove('yield-positive', 'yield-negative', 'yield-zero');
        if (bonus > 0) {
            badge.classList.add('yield-positive');
            badge.textContent = '+' + bonus + '%';
        } else if (bonus < 0) {
            badge.classList.add('yield-negative');
            badge.textContent = bonus + '%';
        } else {
            badge.classList.add('yield-zero');
            badge.textContent = '0%';
        }
    }

    /**
     * Refreshes the badge of the row that owns the given input-material <select>.
     * Reads the selected material id and looks it up in the in-memory map; a
     * missing entry collapses the badge (no UEX row for this pair).
     */
    function refreshFor(inputMaterialSelect) {
        if (!inputMaterialSelect || !inputMaterialSelect.id) return;
        let indexMatch = inputMaterialSelect.id.match(/_(\d+)$/);
        if (!indexMatch) return;
        let rowIndex = indexMatch[1];
        let materialId = inputMaterialSelect.value;
        let bonus =
            materialId && state.yieldByMaterialId ? state.yieldByMaterialId[materialId] : undefined;
        setBadge(rowIndex, bonus);
    }

    /** Re-renders every row's badge against the current map. */
    function refreshAll() {
        // Attribute-only selector: after combobox enhancement (REQ-FE-016) the control's id
        // lives on a hidden <input>, not a <select>; refreshFor only reads .id and .value,
        // which both elements carry.
        document.querySelectorAll('[id^="inputMaterialId_"]').forEach(function (sel) {
            refreshFor(sel);
        });
    }

    /**
     * Location dropdown changed -> refetch the new refinery's yield map from
     * the page-controller proxy and re-render every row. Network / 4xx failures
     * fall back to an empty map, matching the server-side fallback path, so the
     * form stays usable when UEX or the backend is misbehaving.
     */
    function onLocationChange(selectElement) {
        let locationId = selectElement && selectElement.value;
        if (!locationId) {
            state.yieldByMaterialId = {};
            refreshAll();
            return Promise.resolve();
        }
        return fetch('/refinery-orders/locations/' + encodeURIComponent(locationId) + '/yields', {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : {};
            })
            .catch(function () {
                return {};
            })
            .then(function (map) {
                state.yieldByMaterialId = map || {};
                refreshAll();
            });
    }

    window.krtRefineryYield = {
        init: init,
        setBadge: setBadge,
        refreshFor: refreshFor,
        refreshAll: refreshAll,
        onLocationChange: onLocationChange,
    };
})();
