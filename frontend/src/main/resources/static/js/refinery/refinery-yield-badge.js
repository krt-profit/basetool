import { krtFetch } from '/js/kernel/krt-fetch.js';

/** @type {{ yieldByMaterialId: Record<string, KrtRefineryYieldBonus>, texts: KrtRefineryYieldTexts }} */
const state = {
    yieldByMaterialId: {},
    texts: { help: '', weekAverage: '', monthAverage: '' },
};

/**
 * Replaces the in-memory yield map and tooltip texts with the server-rendered initial state.
 *
 * @param {Record<string, KrtRefineryYieldBonus> | null | undefined} initialMap the yield bonus per
 *     material id
 * @param {Partial<KrtRefineryYieldTexts> | null | undefined} texts the badge tooltip texts
 */
function init(initialMap, texts) {
    state.yieldByMaterialId = initialMap || {};
    state.texts = {
        help: (texts && texts.help) || '',
        weekAverage: (texts && texts.weekAverage) || '',
        monthAverage: (texts && texts.monthAverage) || '',
    };
}

/**
 * Formats a percent value the way the badge shows it: a leading `+` on a bonus, none on a malus.
 *
 * @param {number} percent the percent value
 * @returns {string} the signed percent text
 */
function formatPercent(percent) {
    return percent > 0 ? `+${percent}%` : `${percent}%`;
}

/**
 * Builds the badge tooltip: the help text, then one line per average UEX knows.
 *
 * @param {KrtRefineryYieldBonus} bonus the material's yield bonus
 * @returns {string} the tooltip text, lines separated by `\n`
 */
function tooltipFor(bonus) {
    const lines = [state.texts.help];
    if (bonus.weekAveragePercent !== undefined && bonus.weekAveragePercent !== null) {
        lines.push(state.texts.weekAverage.replace('{0}', formatPercent(bonus.weekAveragePercent)));
    }
    if (bonus.monthAveragePercent !== undefined && bonus.monthAveragePercent !== null) {
        lines.push(
            state.texts.monthAverage.replace('{0}', formatPercent(bonus.monthAveragePercent)),
        );
    }
    return lines.filter((line) => line).join('\n');
}

/**
 * Sets, updates or removes the yield-bonus badge of a row; a bonus without a current percent
 * removes it.
 *
 * @param {string | number} rowIndex the material row's index
 * @param {KrtRefineryYieldBonus | null | undefined} bonus the yield bonus with its averages
 */
function setBadge(rowIndex, bonus) {
    const label = document.querySelector(`label[for="outputQuantity_${rowIndex}"]`);
    if (!label) return;
    let badge = /** @type {HTMLElement | null} */ (label.querySelector(`#yieldBonus_${rowIndex}`));
    const percent = bonus ? bonus.percent : undefined;
    if (!bonus || percent === undefined || percent === null) {
        if (badge) badge.remove();
        return;
    }
    if (!badge) {
        badge = document.createElement('span');
        badge.id = `yieldBonus_${rowIndex}`;
        badge.className = 'yield-bonus-badge';
        label.appendChild(badge);
    }
    badge.title = tooltipFor(bonus);
    badge.classList.remove('yield-positive', 'yield-negative', 'yield-zero');
    if (percent > 0) {
        badge.classList.add('yield-positive');
    } else if (percent < 0) {
        badge.classList.add('yield-negative');
    } else {
        badge.classList.add('yield-zero');
    }
    badge.textContent = formatPercent(percent);
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
            state.yieldByMaterialId = /** @type {Record<string, KrtRefineryYieldBonus>} */ (
                map || {}
            );
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
