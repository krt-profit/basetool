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

/* global labelPiece, labelScu, scuHintText, labelMenge, MSG_HANDOVER_REPORT_VALIDATION_AMOUNT, MSG_HANDOVER_MISSION_HERKUNFT, MSG_HANDOVER_MISSION_REST, MSG_HANDOVER_MISSION_MIN, MSG_STOLEN, KRT_ORDER_LIVESYNC_UPDATES, KRT_ORDER_SECTION_REFRESH_ERROR, ORDER_HANDOVER_I18N */

import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

/** @type {any[]} */
export let cachedInventoryItems = [];
let isInventoryCached = false;
/** @type {Map<string, string>} */
let handoverTierSuggestion = new Map();

/**
 * Forgets the inventory fetched for the handover dialog, so the next opening loads it again.
 */
export function resetInventoryCache() {
    isInventoryCached = false;
    cachedInventoryItems = [];
    handoverTierSuggestion = new Map();
}

function initOrdersDetail() {
    const order = krtPageSections.create({
        dict() {
            return {
                'orders.section.refresh.error':
                    typeof KRT_ORDER_SECTION_REFRESH_ERROR !== 'undefined'
                        ? KRT_ORDER_SECTION_REFRESH_ERROR
                        : '',
            };
        },
        keys: { refreshErrorKey: 'orders.section.refresh.error' },
        sections: {
            header: { container: '#order-header-results', fragmentValue: 'header' },
            kpi: { container: '#order-kpi-results', fragmentValue: 'kpi' },
            materials: { container: '#order-materials-results', fragmentValue: 'materials' },
            aggregated: { container: '#order-aggregated-results', fragmentValue: 'aggregated' },
            items: { container: '#order-items-results', fragmentValue: 'items' },
            'item-stock': { container: '#order-items-results', fragmentValue: 'items' },
            handovers: { container: '#order-handovers-results', fragmentValue: 'handovers' },
            'item-handovers': {
                container: '#order-item-handovers-results',
                fragmentValue: 'item-handovers',
            },
            'item-handover-lines': {
                container: '#item-handover-lines',
                fragmentValue: 'item-handover-lines',
            },
            'blueprint-owners': {
                container: '#blueprint-owners-section',
                fragmentValue: 'blueprint-owners',
            },
            assignees: { container: '#order-assignees-results', fragmentValue: 'assignees' },
        },
        pageUrl() {
            return window.orderId ? `/orders/${window.orderId}` : null;
        },
        topic() {
            return window.orderId ? `order:${window.orderId}` : null;
        },
        receiver: {
            pill: {
                label() {
                    return typeof KRT_ORDER_LIVESYNC_UPDATES !== 'undefined'
                        ? KRT_ORDER_LIVESYNC_UPDATES
                        : undefined;
                },
            },
        },
    });
    joborderHooks.refreshOrderSection = order.refresh;
    joborderHooks.notifyOrderChanged = order.notify;
}

initOrdersDetail();

export function _publishOrdersQueue() {
    krtPageSections.publish('orders', ['queue', 'demand']);
}

/**
 * Reads the value of a form control by element id.
 *
 * @param {string} id the control's element id
 * @returns {string} the control's value, or an empty string when it is missing or empty
 */
export function _odFieldValue(id) {
    const field = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        document.getElementById(id)
    );
    return (field && field.value) || '';
}

/**
 * Looks up an element the page always renders.
 *
 * @param {string} id the element id
 * @returns {HTMLElement} the element; dereferencing it throws when the page lacks it
 */
export function _odElementById(id) {
    return /** @type {HTMLElement} */ (document.getElementById(id));
}

/**
 * Looks up a form control the page always renders.
 *
 * @param {string} id the control's element id
 * @returns {HTMLInputElement | HTMLSelectElement} the control; dereferencing it throws when the
 *     page lacks it
 */
export function _odControlById(id) {
    return /** @type {HTMLInputElement | HTMLSelectElement} */ (document.getElementById(id));
}

/**
 * Finds the version input of the order edit modal.
 *
 * @returns {HTMLInputElement | null} the input, or null when the page has none
 */
export function _odEditVersionInput() {
    return /** @type {HTMLInputElement | null} */ (
        document.querySelector('#edit-modal input[name="version"]')
    );
}

/**
 * @typedef {{targetId: string | null, amount: number}} OdMissionReduction
 * @typedef {{inventoryItemId: string, amount: number, missionReductions: OdMissionReduction[] | null, qualityRequirement: string | null}} OdHandoverItem
 */

/**
 * Collects the material handover form into the request body.
 *
 * @returns {{handoverTime: string, recipientHandle: string, recipientSquadron: string, items: OdHandoverItem[]}} the body
 */
export function _serializeHandoverForm() {
    /** @type {OdHandoverItem[]} */
    const items = [];
    document.querySelectorAll('#handover-items-container .handover-item-row').forEach((row) => {
        const sel = /** @type {HTMLSelectElement | null} */ (
            row.querySelector('select[name$=".inventoryItemId"]')
        );
        const amtInput = /** @type {HTMLInputElement | null} */ (
            row.querySelector('input[name$=".amount"]')
        );
        const inventoryItemId = sel ? sel.value : '';
        const amount = amtInput ? krtScuInput.parse(amtInput.value) : NaN;
        if (!inventoryItemId || !(amount > 0)) return;
        const tierSel = /** @type {HTMLSelectElement | null} */ (
            row.querySelector('select[data-role="handover-tier"]')
        );
        const tierHolder = row.querySelector('[data-role="handover-tier-holder"]');
        const qualityRequirement =
            tierSel && tierHolder && !tierHolder.classList.contains('is-hidden') && tierSel.value
                ? tierSel.value
                : null;
        items.push({
            inventoryItemId,
            amount,
            missionReductions: _collectHandoverMissionReductions(row),
            qualityRequirement,
        });
    });
    return {
        handoverTime: _odFieldValue('handoverTime'),
        recipientHandle: _odFieldValue('recipientHandle'),
        recipientSquadron: _odFieldValue('recipientSquadron'),
        items,
    };
}

/**
 * Collects the item handover form into the request body.
 *
 * @returns {{handoverTime: string, recipientHandle: string, entries: Array<{jobOrderItemId: string, amount: number}>}} the body
 */
export function _serializeItemHandoverForm() {
    /** @type {Array<{jobOrderItemId: string, amount: number}>} */
    const entries = [];
    document.querySelectorAll('#item-handover-modal .item-handover-line').forEach((line) => {
        const idInput = /** @type {HTMLInputElement | null} */ (
            line.querySelector('input[name$=".jobOrderItemId"]')
        );
        const amtInput = /** @type {HTMLInputElement | null} */ (
            line.querySelector('input[name$=".amount"]')
        );
        const jobOrderItemId = idInput ? idInput.value : '';
        const amount = amtInput ? parseInt(amtInput.value, 10) : NaN;
        if (!jobOrderItemId || !(amount > 0)) return;
        entries.push({ jobOrderItemId, amount });
    });
    return {
        handoverTime: _odFieldValue('itemHandoverTime'),
        recipientHandle: _odFieldValue('itemRecipientHandle'),
        entries,
    };
}

/**
 * Reads how much of an inventory entry is earmarked for the current order.
 *
 * @param {any} inv the inventory entry, or null
 * @returns {number} the order's slice of the entry, 0 when there is none
 */
function orderSliceAmount(inv) {
    if (!inv || !Array.isArray(inv.jobOrderAllocations)) return 0;
    const slice = inv.jobOrderAllocations.find(
        (/** @type {any} */ a) => a.jobOrderId === window.orderId,
    );
    return slice && typeof slice.amount === 'number' ? slice.amount : 0;
}

/**
 * Formats an amount for display: whole for pieces, three decimals for SCU.
 *
 * @param {number} n the amount
 * @param {boolean} isPiece whether the material is counted in pieces
 * @returns {string} the formatted amount
 */
function _handoverFmtAmount(n, isPiece) {
    return isPiece ? String(Math.round(n)) : Number(n).toFixed(3);
}

/**
 * Tells whether the inventory entry chosen in a handover row is counted in pieces.
 *
 * @param {Element} row the handover row
 * @returns {boolean} true for a piece material
 */
function _handoverRowIsPiece(row) {
    const sel = row.querySelector('select');
    const inv = sel ? cachedInventoryItems.find((i) => i.id === sel.value) : null;
    return !!(inv && inv.material && inv.material.quantityType === 'PIECE');
}

/**
 * Lists the quality tiers the order asks the material in, highest floor first, read from the
 * material table (REQ-ORDERS-038).
 *
 * @param {string} materialId the material
 * @returns {Array<{id: string, code: string, label: string, floor: number}>} the tiers
 */
function _orderTiersForMaterial(materialId) {
    /** @type {Array<{id: string, code: string, label: string, floor: number}>} */
    const tiers = [];
    document.querySelectorAll('.material-row[data-material-id]').forEach((el) => {
        const row = /** @type {HTMLElement} */ (el);
        if (row.dataset.materialId !== materialId || !row.dataset.qualityCode) return;
        if (tiers.some((t) => t.code === row.dataset.qualityCode)) return;
        tiers.push({
            id: row.dataset.qualityTierId || '',
            code: row.dataset.qualityCode || '',
            label: row.dataset.qualityLabel || row.dataset.qualityCode || '',
            floor: parseInt(row.dataset.qualityFloor || '0', 10) || 0,
        });
    });
    tiers.sort((a, b) => b.floor - a.floor);
    return tiers;
}

/**
 * Loads the bucket each linked row currently counts toward, as the pre-filled suggestion of the
 * handover's tier choice (REQ-ORDERS-037/038).
 *
 * @param {string[]} materialIds the order's materials
 * @param {string} orderId the order
 * @returns {Promise<Map<string, string>>} row id to suggested tier code
 */
async function _loadHandoverTierSuggestions(materialIds, orderId) {
    /** @type {Map<string, string>} */
    const suggestion = new Map();
    for (const materialId of materialIds) {
        const tiers = _orderTiersForMaterial(materialId);
        if (tiers.length < 2) continue;
        try {
            const entries = await krtFetch.getJson(
                `/orders/${orderId}/materials/${materialId}/attribution`,
            );
            /** @type {Map<string, {code: string, amount: number}>} */
            const best = new Map();
            for (const entry of entries) {
                const tier = tiers.find((t) => t.id === entry.qualityTierId);
                if (!tier) continue;
                const current = best.get(entry.inventoryItemId);
                if (!current || entry.amount > current.amount) {
                    best.set(entry.inventoryItemId, { code: tier.code, amount: entry.amount });
                }
            }
            best.forEach((v, k) => suggestion.set(k, v.code));
        } catch (_e) {
            continue;
        }
    }
    return suggestion;
}

/**
 * Fills a handover row's tier choice with the tiers the chosen entry's quality meets; the choice
 * is shown only when the order asks the material in more than one tier (REQ-ORDERS-038).
 *
 * @param {HTMLElement} row the handover row
 * @param {any} inv the chosen inventory entry, or null
 */
function _refreshHandoverTierChoice(row, inv) {
    const holder = row.querySelector('[data-role="handover-tier-holder"]');
    const select = /** @type {HTMLSelectElement | null} */ (
        row.querySelector('select[data-role="handover-tier"]')
    );
    if (!holder || !select) return;
    select.textContent = '';
    const materialId = inv && inv.material ? inv.material.id : null;
    const tiers = materialId ? _orderTiersForMaterial(materialId) : [];
    if (tiers.length < 2) {
        holder.classList.add('is-hidden');
        return;
    }
    const quality = inv && typeof inv.quality === 'number' ? inv.quality : 0;
    const eligible = tiers.filter((t) => quality >= t.floor);
    const suggested = handoverTierSuggestion.get(inv.id);
    eligible.forEach((t, i) => {
        const option = document.createElement('option');
        option.value = t.code;
        option.textContent = t.label;
        option.selected = suggested ? t.code === suggested : i === 0;
        select.appendChild(option);
    });
    holder.classList.toggle('is-hidden', eligible.length === 0);
}

export async function openHandoverModal() {
    krtModal.open(document.getElementById('handover-modal'));
    if (!isInventoryCached) {
        try {
            /** @type {Map<string, HTMLElement>} */
            const byMaterial = new Map();
            document.querySelectorAll('.material-row[data-material-id]').forEach((el) => {
                const row = /** @type {HTMLElement} */ (el);
                if (row.dataset.materialId && !byMaterial.has(row.dataset.materialId)) {
                    byMaterial.set(row.dataset.materialId, row);
                }
            });
            const materials = Array.from(byMaterial.values());
            const promises = Array.from(materials).map((row) => {
                const orderId = row.dataset.orderId;
                const matId = row.dataset.materialId;
                if (orderId && matId) {
                    return krtFetch
                        .getJson(`/orders/${orderId}/materials/${matId}/inventory`)
                        .catch(() => []);
                }
                return Promise.resolve([]);
            });
            const results = await Promise.all(promises);
            results.forEach((arr) => {
                if (arr && arr.length > 0) {
                    cachedInventoryItems.push(...arr);
                }
            });
            const firstRow = materials[0];
            handoverTierSuggestion = firstRow
                ? await _loadHandoverTierSuggestions(
                      Array.from(byMaterial.keys()),
                      firstRow.dataset.orderId || '',
                  )
                : new Map();
            isInventoryCached = true;
        } catch (e) {
            console.error('Failed to load inventory items for handover', e);
        }
    }
}

/**
 * Substitutes the MessageFormat-style `{0}`, `{1}`, … in a localized template with the values.
 *
 * @param {string} template the localized template
 * @param {Array<unknown>} values the values for {0}, {1}, …
 * @returns {string} the filled-in text
 */
export function fillPlaceholders(template, values) {
    return values.reduce((/** @type {string} */ text, value, i) => {
        return text.split(`{${i}}`).join(value == null ? '' : String(value));
    }, template);
}

/** Appends a handover row with the inventory entry, amount and tier controls. */
export function addHandoverItemRow() {
    const container = document.getElementById('handover-items-container');
    if (!container) return;
    const index = container.children.length;

    const row = document.createElement('div');
    row.className = 'handover-item-row';
    row.style.display = 'grid';
    row.style.gridTemplateColumns = '2fr 1fr 1fr auto';
    row.style.gap = '1rem';
    row.style.alignItems = 'end';
    row.style.marginBottom = '1rem';
    row.style.background = 'var(--color-surface-input)';
    row.style.padding = '1rem';
    row.style.border = '1px solid var(--color-gray-3)';

    const options = [
        krtHtml`<option value="" disabled selected>${ORDER_HANDOVER_I18N.choose}</option>`,
    ];
    cachedInventoryItems.forEach((inv) => {
        const isPiece = inv.material && inv.material.quantityType === 'PIECE';
        const qtyLabel = isPiece ? labelPiece : labelScu;
        const formattedAmount = isPiece ? inv.amount.toFixed(0) : inv.amount.toFixed(3);
        const matName = (inv.material && inv.material.name) || '';
        const userName = (inv.user && inv.user.effectiveName) || '';
        const optionLabel =
            fillPlaceholders(ORDER_HANDOVER_I18N.option, [
                matName,
                inv.quality,
                formattedAmount,
                qtyLabel,
                userName,
            ]) + (inv.stolen ? ` · ${MSG_STOLEN}` : '');
        options.push(krtHtml`<option value="${inv.id}">${optionLabel}</option>`);
    });

    krtHtml.set(
        row,
        krtHtml`
            <div>
                <label class="form-label-sm">${ORDER_HANDOVER_I18N.entry}</label>
                <select name="items[${index}].inventoryItemId" required class="w-full">
                    ${options}
                </select>
            </div>
            <div>
                <label class="form-label-sm">${labelMenge} <span data-role="amount-unit"></span> <span class="scu-hint is-hidden" data-role="scu-hint" tabindex="0" role="img" aria-label="${scuHintText}"><span aria-hidden="true">?</span><span class="scu-hint__bubble" aria-hidden="true">${scuHintText}</span></span></label>
                <input type="text" inputmode="decimal" data-scu-decimal step="0.001" name="items[${index}].amount" min="0.001" required class="w-full">
            </div>
            <div data-role="handover-tier-holder" class="is-hidden">
                <label class="form-label-sm">${ORDER_HANDOVER_I18N.tier}</label>
                <select data-role="handover-tier" class="w-full" data-testid="handover-tier"></select>
            </div>
            <div>
                <button type="button" class="btn btn-quiet-danger btn-icon od-remove-btn" data-trigger="od-remove-handover-row" title="${ORDER_HANDOVER_I18N.remove}" aria-label="${ORDER_HANDOVER_I18N.remove}"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg></button>
            </div>
        `,
    );
    const sel = row.querySelector('select');
    const amtInput = /** @type {HTMLInputElement | null} */ (
        row.querySelector('input[data-scu-decimal]')
    );
    if (sel && amtInput) {
        sel.addEventListener('change', () => {
            const inv = cachedInventoryItems.find((i) => i.id === sel.value);
            if (inv) {
                amtInput.max = String(orderSliceAmount(inv));
            } else {
                amtInput.removeAttribute('max');
            }
            const unitSpan = row.querySelector('[data-role="amount-unit"]');
            const rowScuHint = row.querySelector('[data-role="scu-hint"]');
            const qt = inv && inv.material ? inv.material.quantityType : null;
            if (qt === 'PIECE') {
                amtInput.setAttribute('step', '1');
                amtInput.setAttribute('min', '1');
                if (unitSpan) unitSpan.textContent = `(${labelPiece})`;
                if (rowScuHint) rowScuHint.classList.add('is-hidden');
            } else if (qt === 'SCU') {
                amtInput.setAttribute('step', '0.001');
                amtInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = `(${labelScu})`;
                if (rowScuHint) rowScuHint.classList.remove('is-hidden');
            } else {
                amtInput.setAttribute('step', '0.001');
                amtInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '';
                if (rowScuHint) rowScuHint.classList.add('is-hidden');
            }
            _refreshHandoverMissionPicker(row);
            _refreshHandoverTierChoice(row, inv);
        });
        amtInput.addEventListener('input', () => _refreshHandoverMissionPicker(row));
    }
    container.appendChild(row);
}

/**
 * Shows or hides the mission-origin split of a handover row, depending on whether the handed
 * amount exceeds what the entry holds outside missions.
 *
 * @param {Element} row the handover row
 */
function _refreshHandoverMissionPicker(row) {
    const sel = row.querySelector('select');
    const amtInput = /** @type {HTMLInputElement | null} */ (
        row.querySelector('input[data-scu-decimal]')
    );
    const inv = sel ? cachedInventoryItems.find((i) => i.id === sel.value) : null;
    const missions = inv && Array.isArray(inv.missionAllocations) ? inv.missionAllocations : [];
    const missionRest = inv && typeof inv.missionRest === 'number' ? inv.missionRest : 0;
    const isPiece = !!(inv && inv.material && inv.material.quantityType === 'PIECE');
    const handed = amtInput ? krtScuInput.parse(amtInput.value) : Number.NaN;
    const ambiguous = missions.length >= 2 && isFinite(handed) && handed > missionRest + 0.0005;

    let holder = /** @type {HTMLElement | null} */ (
        row.querySelector('[data-role="mission-herkunft"]')
    );
    if (!ambiguous) {
        if (holder) holder.remove();
        return;
    }
    if (!holder) {
        holder = document.createElement('div');
        holder.setAttribute('data-role', 'mission-herkunft');
        holder.style.gridColumn = '1 / -1';
        holder.style.marginTop = '0.5rem';
        holder.style.paddingTop = '0.5rem';
        holder.style.borderTop = '1px solid var(--color-gray-3)';
        row.appendChild(holder);
    }
    if (holder.getAttribute('data-entry') !== inv.id) {
        holder.setAttribute('data-entry', inv.id);
        holder.textContent = '';
        const title = document.createElement('div');
        title.style.fontSize = '0.7rem';
        title.style.textTransform = 'uppercase';
        title.style.letterSpacing = '0.08em';
        title.style.color = 'var(--color-gray-2-text)';
        title.style.marginBottom = '0.35rem';
        title.textContent = MSG_HANDOVER_MISSION_HERKUNFT;
        holder.appendChild(title);
        missions.forEach((/** @type {Required<ApiDto<'MissionAllocationDto'>>} */ m) => {
            const line = document.createElement('label');
            line.style.display = 'flex';
            line.style.gap = '0.5rem';
            line.style.alignItems = 'center';
            line.style.marginBottom = '0.25rem';
            const name = document.createElement('span');
            name.style.flex = '1 1 auto';
            name.style.fontSize = '0.7rem';
            name.textContent = m.missionName;
            const input = document.createElement('input');
            input.type = 'number';
            input.min = '0';
            input.step = isPiece ? '1' : '0.001';
            input.max = String(m.amount);
            input.value = '0';
            input.style.width = '6rem';
            input.style.textAlign = 'right';
            input.setAttribute('data-mission-input', '');
            input.setAttribute('data-mission-id', m.missionId);
            input.addEventListener('input', () => _updateHandoverMissionRest(row));
            const max = document.createElement('span');
            max.style.fontSize = '0.7rem';
            max.style.color = 'var(--color-gray-2-text)';
            max.textContent = `/ ${_handoverFmtAmount(m.amount, isPiece)}`;
            line.appendChild(name);
            line.appendChild(input);
            line.appendChild(max);
            holder.appendChild(line);
        });
        const rest = document.createElement('span');
        rest.className = 'chip chip--muted';
        rest.setAttribute('data-mission-rest', '');
        holder.appendChild(rest);
    }
    _updateHandoverMissionRest(row);
}

/**
 * Refreshes the chip showing how much of the handed amount is still unassigned to missions.
 *
 * @param {Element} row the handover row
 */
function _updateHandoverMissionRest(row) {
    const holder = row.querySelector('[data-role="mission-herkunft"]');
    const sel = row.querySelector('select');
    const amtInput = /** @type {HTMLInputElement | null} */ (
        row.querySelector('input[data-scu-decimal]')
    );
    if (!holder || !sel || !amtInput) return;
    const inv = cachedInventoryItems.find((i) => i.id === sel.value);
    const missionRest = inv && typeof inv.missionRest === 'number' ? inv.missionRest : 0;
    const isPiece = !!(inv && inv.material && inv.material.quantityType === 'PIECE');
    const handed = krtScuInput.parse(amtInput.value);
    let assigned = 0;
    holder.querySelectorAll('[data-mission-input]').forEach((el) => {
        const v = parseFloat(/** @type {HTMLInputElement} */ (el).value);
        if (!isNaN(v) && v > 0) assigned += v;
    });
    const restEl = holder.querySelector('[data-mission-rest]');
    if (!restEl) return;
    const fromRest = isFinite(handed) ? handed - assigned : 0;
    const minRequired = isFinite(handed) ? Math.max(0, handed - missionRest) : 0;
    const invalid = assigned < minRequired - 1e-6 || fromRest < -1e-6;
    restEl.classList.toggle('chip--danger', invalid);
    restEl.classList.toggle('chip--muted', !invalid);
    restEl.textContent = invalid
        ? MSG_HANDOVER_MISSION_MIN.replace('{0}', _handoverFmtAmount(minRequired, isPiece))
        : MSG_HANDOVER_MISSION_REST.replace(
              '{0}',
              _handoverFmtAmount(Math.max(0, fromRest), isPiece),
          );
}

/**
 * Reads the per-mission amounts the user assigned in a handover row.
 *
 * @param {Element} row the handover row
 * @returns {OdMissionReduction[] | null} the positive reductions, or null when there are none
 */
function _collectHandoverMissionReductions(row) {
    const holder = row.querySelector('[data-role="mission-herkunft"]');
    if (!holder) return null;
    const isPiece = _handoverRowIsPiece(row);
    /** @type {OdMissionReduction[]} */
    const list = [];
    holder.querySelectorAll('[data-mission-input]').forEach((el) => {
        const inp = /** @type {HTMLInputElement} */ (el);
        const v = parseFloat(inp.value);
        if (!isNaN(v) && v > 0) {
            list.push({
                targetId: inp.getAttribute('data-mission-id'),
                amount: isPiece ? Math.round(v) : Math.round(v * 1000) / 1000,
            });
        }
    });
    return list.length > 0 ? list : null;
}

/**
 * Checks every handover row against the order's slice and mission split, toasting the first
 * violation.
 *
 * @returns {boolean} true when all rows are valid
 */
export function validateHandoverAmounts() {
    const rows = document.querySelectorAll('#handover-items-container .handover-item-row');
    for (const row of rows) {
        const sel = row.querySelector('select');
        const inp = /** @type {HTMLInputElement | null} */ (
            row.querySelector('input[data-scu-decimal]')
        );
        if (!sel || !sel.value || !inp || !inp.value) continue;
        const inv = cachedInventoryItems.find((i) => i.id === sel.value);
        if (!inv) continue;
        const isPiece = !!(inv.material && inv.material.quantityType === 'PIECE');
        const entered = /** @type {KrtScuInputApi} */ (krtScuInput).parse(inp.value);
        const sliceMax = orderSliceAmount(inv);
        if (entered > sliceMax) {
            const available = _handoverFmtAmount(sliceMax, isPiece);
            const materialName = inv.material ? inv.material.name : sel.value;
            const msg = MSG_HANDOVER_REPORT_VALIDATION_AMOUNT.replace('{0}', materialName).replace(
                '{1}',
                available,
            );
            showFrontendErrorToast(msg);
            return false;
        }
        const holder = row.querySelector('[data-role="mission-herkunft"]');
        if (holder) {
            const missionRest = typeof inv.missionRest === 'number' ? inv.missionRest : 0;
            let assigned = 0;
            let overSlice = false;
            holder.querySelectorAll('[data-mission-input]').forEach((el) => {
                const inp2 = /** @type {HTMLInputElement} */ (el);
                const v = parseFloat(inp2.value) || 0;
                const cap = parseFloat(inp2.max) || 0;
                if (v > cap + 1e-6) overSlice = true;
                if (v > 0) assigned += v;
            });
            const minRequired = Math.max(0, entered - missionRest);
            if (overSlice || assigned > entered + 1e-6 || assigned < minRequired - 1e-6) {
                showFrontendErrorToast(
                    MSG_HANDOVER_MISSION_MIN.replace(
                        '{0}',
                        _handoverFmtAmount(minRequired, isPiece),
                    ),
                );
                return false;
            }
        }
    }
    return true;
}

export function updateHandoverIndexes() {
    const rows = document.querySelectorAll('.handover-item-row');
    rows.forEach((row, index) => {
        const select = row.querySelector('select');
        const input = row.querySelector('input');
        if (select) select.name = `items[${index}].inventoryItemId`;
        if (input) input.name = `items[${index}].amount`;
    });
}
