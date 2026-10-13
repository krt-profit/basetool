import { krtFetch } from '/js/kernel/krt-fetch.js';

/** @type {{ yieldByMaterialId: Record<string, number>, helpText: string }} */
const state = {
    yieldByMaterialId: {},
    helpText: '',
};

/**
 * Replaces the in-memory yield map and help text with the server-rendered initial state.
 *
 * @param {Record<string, number> | null | undefined} initialMap the bonus percent per material id
 * @param {string | null | undefined} helpText the badge tooltip text
 */
function init(initialMap, helpText) {
    state.yieldByMaterialId = initialMap || {};
    state.helpText = helpText || '';
}

/**
 * Sets, updates or removes the yield-bonus badge of a row; a null or undefined bonus
 * removes it.
 *
 * @param {string | number} rowIndex the material row's index
 * @param {number | null | undefined} bonus the bonus percent
 */
function setBadge(rowIndex, bonus) {
    const label = document.querySelector(`label[for="outputQuantity_${rowIndex}"]`);
    if (!label) return;
    let badge = label.querySelector(`#yieldBonus_${rowIndex}`);
    if (bonus === undefined || bonus === null) {
        if (badge) badge.remove();
        return;
    }
    if (!badge) {
        badge = document.createElement('span');
        badge.id = `yieldBonus_${rowIndex}`;
        badge.className = 'yield-bonus-badge';
        /** @type {HTMLElement} */ (badge).title = state.helpText;
        label.appendChild(badge);
    }
    badge.classList.remove('yield-positive', 'yield-negative', 'yield-zero');
    if (bonus > 0) {
        badge.classList.add('yield-positive');
        badge.textContent = `+${bonus}%`;
    } else if (bonus < 0) {
        badge.classList.add('yield-negative');
        badge.textContent = `${bonus}%`;
    } else {
        badge.classList.add('yield-zero');
        badge.textContent = '0%';
    }
}

/**
 * Refreshes the badge of the row that owns the given input-material select from the yield map.
 *
 * @param {KrtRefineryControl | null | undefined} inputMaterialSelect the row's material control
 */
function refreshFor(inputMaterialSelect) {
    if (!inputMaterialSelect || !inputMaterialSelect.id) return;
    const indexMatch = inputMaterialSelect.id.match(/_(\d+)$/);
    if (!indexMatch) return;
    const rowIndex = indexMatch[1];
    const materialId = inputMaterialSelect.value;
    const bonus =
        materialId && state.yieldByMaterialId ? state.yieldByMaterialId[materialId] : undefined;
    setBadge(rowIndex, bonus);
}

/** Re-renders every row's badge against the current map. */
function refreshAll() {
    document.querySelectorAll('[id^="inputMaterialId_"]').forEach((sel) => {
        refreshFor(/** @type {KrtRefineryControl} */ (sel));
    });
}

/**
 * Re-fetches the yield map for the chosen refinery and re-renders every badge; any failure
 * falls back to an empty map.
 *
 * @param {KrtRefineryControl | null | undefined} selectElement the refinery location control
 * @returns {Promise<void>} resolves once the badges are re-rendered
 */
function onLocationChange(selectElement) {
    const locationId = selectElement && selectElement.value;
    if (!locationId) {
        state.yieldByMaterialId = {};
        refreshAll();
        return Promise.resolve();
    }
    return krtFetch
        .getJson(`/refinery-orders/locations/${encodeURIComponent(locationId)}/yields`)
        .catch(() => {
            return {};
        })
        .then((map) => {
            state.yieldByMaterialId = map || {};
            refreshAll();
        });
}

/** @type {KrtRefineryYieldApi} */
export const krtRefineryYield = {
    init,
    setBadge,
    refreshFor,
    refreshAll,
    onLocationChange,
};
