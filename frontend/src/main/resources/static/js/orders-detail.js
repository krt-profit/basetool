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
 * Page module for the job-order detail page (templates/orders-detail.html), extracted verbatim
 * from the former three end-of-body inline script blocks (#924 Part 2).
 *
 * Covers: the material-handover and item-handover modals (lazy inventory cache, dynamic row
 * builder, amount validation, AJAX submit via krtOrderWrite + in-place section re-swaps), order
 * date localization (re-run on every krt:swapped), the edit-modal material-row editor, the
 * krtOrderWrite conflict-aware write wrapper, the delete / edit / claim / status /
 * blueprint-variant-counting flows, handover-report download and preview, the per-material
 * inventory drill-down, the delegated krtEvents od-* / claim-* bindings, and the assignee
 * ("Bearbeiter") section IIFE with its bespoke oaSend fragment swap and oa-* bindings.
 *
 * Localized strings and per-request server values come from the th:inline bootstrap block that
 * precedes this script's loader tag in orders-detail.html; this file must load as a classic
 * synchronous script at the same end-of-body position, never with defer.
 */

/* global MSG_HANDOVER_SUCCESS, MSG_HANDOVER_FAILED, MSG_HANDOVER_NOITEMS, labelPiece, labelScu, scuHintText, labelMenge, ORDER_AGE_YELLOW, ORDER_AGE_RED, MSG_UNIT_SCU, MSG_UNIT_PIECE, MSG_STATUS_SUCCESS, MSG_STATUS_ERROR, ORDER_CONFLICT, MSG_DELETE_TITLE, MSG_DELETE_MESSAGE, MSG_DELETE_CONFIRM, MSG_DELETE_CANCEL, MSG_DELETE_ERROR, MSG_UPDATE_SUCCESS, MSG_UPDATE_ERROR, MSG_MATERIAL_INVALID, MSG_CLAIM_TITLE_ADD, MSG_CLAIM_TITLE_EDIT, MSG_CLAIM_MAX_HINT, MSG_QUALITY_GOOD, MSG_QUALITY_NONE, MSG_CLAIM_SUCCESS, MSG_CLAIM_WITHDRAW_SUCCESS, MSG_CLAIM_ERROR, MSG_CLAIM_VALIDATION_SQUADRON, MSG_CLAIM_VALIDATION_AMOUNT, MSG_CLAIM_VALIDATION_OVERCLAIM, MSG_BP_COUNTING_SUCCESS, MSG_BP_COUNTING_ERROR, MSG_HANDOVER_REPORT_ERROR, MSG_HANDOVER_REPORT_VALIDATION_DATE, MSG_HANDOVER_REPORT_VALIDATION_TIME, MSG_HANDOVER_REPORT_VALIDATION_HANDLE, MSG_HANDOVER_REPORT_VALIDATION_ITEMS, MSG_HANDOVER_REPORT_VALIDATION_AMOUNT, MSG_HANDOVER_MISSION_HERKUNFT, MSG_HANDOVER_MISSION_REST, MSG_HANDOVER_MISSION_MIN, MSG_OWNER, MSG_LOCATION, MSG_QUALITY, MSG_QUANTITY, MSG_SQUADRON, MSG_LOADING_INVENTORY, MSG_EMPTY_INVENTORY, MSG_INVENTORY_UNLINK_TOOLTIP, MSG_INVENTORY_UNLINK_SUCCESS, MSG_INVENTORY_UNLINK_ERROR, IS_LOGISTICIAN, ORDER_REQUESTING_SQUADRON_ID, I18N_ADDED, I18N_REMOVED, I18N_NOTE_SAVED, I18N_NOTE_DELETED, I18N_ADD_ERROR, I18N_REMOVE_ERROR, I18N_NOTE_ERROR, I18N_NOTE_CONFLICT, I18N_NOTE_FORBIDDEN, I18N_NOTE_FOR, showFrontendErrorToast, showFrontendSuccessToast, KRT_ORDER_LIVESYNC_UPDATES, KRT_ORDER_SECTION_REFRESH_ERROR, PRODUCTION_I18N */

let cachedInventoryItems = [];
let isInventoryCached = false;

// ---- Live multi-user sync — the order detail page (REQ-FE-010 / REQ-FE-015, ADR-0094) ---------
// A peer's status / edit / handover / claim / assignee change re-renders the affected order-detail
// section fragment in place for every other viewer of the SAME order, over the shared /ws/sync
// order room. Only opaque section keys cross the wire; each viewer re-pulls its own
// authorization-checked (and, for a requesting owner, redacted) fragment. Its keys mirror the server
// LiveSyncTopicClass.ORDER whitelist, and the same map drives both the write-side broadcast and the
// receive-side refresh (the three-mirror-points rule). ORDER_SECTIONS is that single source of truth.
const ORDER_SECTIONS = {
    header: { container: '#order-header-results', fragmentValue: 'header' },
    kpi: { container: '#order-kpi-results', fragmentValue: 'kpi' },
    materials: { container: '#order-materials-results', fragmentValue: 'materials' },
    aggregated: { container: '#order-aggregated-results', fragmentValue: 'aggregated' },
    items: { container: '#order-items-results', fragmentValue: 'items' },
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
};

(function () {
    if (!window.krtFetch || typeof window.krtFetch.sectionWrite !== 'function') {
        return; // no-JS / no-foundation: the classic forms + the bespoke swaps below run unchanged.
    }
    const orderSeam = window.krtFetch.sectionWrite({
        dict: function () {
            return {
                'orders.section.refresh.error':
                    typeof KRT_ORDER_SECTION_REFRESH_ERROR !== 'undefined'
                        ? KRT_ORDER_SECTION_REFRESH_ERROR
                        : '',
            };
        },
        keys: { refreshErrorKey: 'orders.section.refresh.error' },
        sections: ORDER_SECTIONS,
        pageUrl: function () {
            return window.orderId ? '/orders/' + window.orderId : null;
        },
        // Tell other users viewing THIS order that these sections changed (REQ-FE-015).
        broadcast: function (keys) {
            if (
                window.orderId &&
                window.krtLiveSync &&
                typeof window.krtLiveSync.sendChanged === 'function'
            ) {
                window.krtLiveSync.sendChanged('order:' + window.orderId, keys);
            }
        },
    });
    // krtRefreshOrderSection re-renders one or more sections in place (and broadcasts unless
    // {broadcast:false}); krtNotifyOrderChanged broadcasts only (for handlers that already swapped
    // their own DOM, e.g. the assignee section's bespoke outerHTML swap in oaSend).
    window.krtRefreshOrderSection = orderSeam.refresh;
    window.krtNotifyOrderChanged = orderSeam.notify;

    // Inbound peer changes: subscribe to order:{id} on /ws/sync and re-fetch the affected section
    // fragments locally with {broadcast:false} so an applied peer change never echoes back. The
    // receiver's default busy-guard holds a refresh while any of this page's modals is open (edit,
    // handover, claim, note, status-warning) and shows the deferred-refresh pill instead of yanking
    // the DOM; a missing container (order-kind- or requesterView-gated) is silently skipped.
    if (window.orderId && window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: 'order:' + window.orderId,
            sections: ORDER_SECTIONS,
            refresh: function (keys) {
                if (window.krtRefreshOrderSection) {
                    window.krtRefreshOrderSection(keys, { broadcast: false });
                }
            },
            pill: {
                label: function () {
                    return typeof KRT_ORDER_LIVESYNC_UPDATES !== 'undefined'
                        ? KRT_ORDER_LIVESYNC_UPDATES
                        : undefined;
                },
            },
        });
    }
})();

// Cross-publish the staff order queue (the `orders` global room) after a mutation that changes an
// order's queue-visible fields — status, edit, delete. Publishing needs no subscription (the
// sanctioned cross-topic case): the detail page subscribes to order:{id}, not to `orders`, yet every
// queue viewer must still see the change. A non-profit requester is refused the room server-side, so
// the key reaches only viewers who may read the queue.
function _publishOrdersQueue() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('orders', ['queue']);
    }
}

// Serialize the material-handover modal form to the JSON its AJAX twin binds (#575).
function _serializeHandoverForm() {
    const items = [];
    document
        .querySelectorAll('#handover-items-container .handover-item-row')
        .forEach(function (row) {
            const sel = row.querySelector('select[name$=".inventoryItemId"]');
            const amtInput = row.querySelector('input[name$=".amount"]');
            const inventoryItemId = sel ? sel.value : '';
            const amount =
                amtInput && window.krtScuInput
                    ? window.krtScuInput.parse(amtInput.value)
                    : amtInput
                      ? parseFloat(amtInput.value)
                      : NaN;
            if (!inventoryItemId || !(amount > 0)) return;
            items.push({
                inventoryItemId: inventoryItemId,
                amount: amount,
                missionReductions: _collectHandoverMissionReductions(row),
            });
        });
    return {
        handoverTime: (document.getElementById('handoverTime') || {}).value || '',
        recipientHandle: (document.getElementById('recipientHandle') || {}).value || '',
        recipientSquadron: (document.getElementById('recipientSquadron') || {}).value || '',
        items: items,
    };
}

// Serialize the item-handover modal's per-line whole-unit amounts to the JSON its twin binds.
function _serializeItemHandoverForm() {
    const entries = [];
    document.querySelectorAll('#item-handover-modal .item-handover-line').forEach(function (line) {
        const idInput = line.querySelector('input[name$=".jobOrderItemId"]');
        const amtInput = line.querySelector('input[name$=".amount"]');
        const jobOrderItemId = idInput ? idInput.value : '';
        const amount = amtInput ? parseInt(amtInput.value, 10) : NaN;
        if (!jobOrderItemId || !(amount > 0)) return;
        entries.push({ jobOrderItemId: jobOrderItemId, amount: amount });
    });
    return {
        handoverTime: (document.getElementById('itemHandoverTime') || {}).value || '',
        recipientHandle: (document.getElementById('itemRecipientHandle') || {}).value || '',
        entries: entries,
    };
}

// Variante C (REQ-INV-027): a handover for THIS order (window.orderId) may draw only from the
// entry's own earmark to this order — never from a sibling order's slice or the free rest. Returns
// that slice's amount (0 when the entry carries no slice for this order), used as the handover cap.
function orderSliceAmount(inv) {
    if (!inv || !Array.isArray(inv.jobOrderAllocations)) return 0;
    const slice = inv.jobOrderAllocations.find((a) => a.jobOrderId === window.orderId);
    return slice && typeof slice.amount === 'number' ? slice.amount : 0;
}

// Formats a handover amount whole (no decimals) for a PIECE material, three decimals for SCU —
// matches the inventory chip / krtScuInput convention so the picker never shows "5.000" for pieces.
function _handoverFmtAmount(n, isPiece) {
    return isPiece ? String(Math.round(n)) : Number(n).toFixed(3);
}

// Whether the entry selected in a handover row holds a PIECE-counted material.
function _handoverRowIsPiece(row) {
    const sel = row.querySelector('select');
    const inv = sel ? cachedInventoryItems.find((i) => i.id === sel.value) : null;
    return !!(inv && inv.material && inv.material.quantityType === 'PIECE');
}

async function openHandoverModal() {
    document.getElementById('handover-modal').style.display = 'flex';
    if (!isInventoryCached) {
        try {
            const materials = document.querySelectorAll('.material-row[data-material-id]');
            let promises = Array.from(materials).map((row) => {
                const orderId = row.dataset.orderId;
                const matId = row.dataset.materialId;
                if (orderId && matId) {
                    return fetch(`/orders/${orderId}/materials/${matId}/inventory`).then((res) =>
                        res.json(),
                    );
                }
                return Promise.resolve([]);
            });
            const results = await Promise.all(promises);
            results.forEach((arr) => {
                if (arr && arr.length > 0) {
                    cachedInventoryItems.push(...arr);
                }
            });
            isInventoryCached = true;
        } catch (e) {
            console.error('Failed to load inventory items for handover', e);
        }
    }
}

function addHandoverItemRow() {
    const container = document.getElementById('handover-items-container');
    const index = container.children.length;

    const row = document.createElement('div');
    row.className = 'handover-item-row';
    row.style.display = 'grid';
    row.style.gridTemplateColumns = '2fr 1fr auto';
    row.style.gap = '1rem';
    row.style.alignItems = 'end';
    row.style.marginBottom = '1rem';
    row.style.background = 'var(--color-surface-input)';
    row.style.padding = '1rem';
    row.style.border = '1px solid var(--color-gray-3)';

    let options = '<option value="" disabled selected>-- Lagereintrag wählen --</option>';
    cachedInventoryItems.forEach((inv) => {
        const isPiece = inv.material && inv.material.quantityType === 'PIECE';
        const qtyLabel = isPiece ? labelPiece : labelScu;
        const formattedAmount = isPiece ? inv.amount.toFixed(0) : inv.amount.toFixed(3);
        const matName = (inv.material && inv.material.name) || '';
        const userName = (inv.user && inv.user.effectiveName) || '';
        options += `<option value="${escapeAttr(inv.id)}">${escapeHtml(matName)} (Qual. ${escapeHtml(inv.quality)}) - ${escapeHtml(formattedAmount)} ${escapeHtml(qtyLabel)} von ${escapeHtml(userName)}</option>`;
    });

    row.innerHTML = `
            <div>
                <label class="form-label-sm">Lagereintrag</label>
                <select name="items[${index}].inventoryItemId" required class="w-full">
                    ${options}
                </select>
            </div>
            <div>
                <label class="form-label-sm">${escapeHtml(labelMenge)} <span data-role="amount-unit"></span> <span class="scu-hint krtm-hidden" data-role="scu-hint" tabindex="0" role="img" aria-label="${escapeAttr(scuHintText)}"><span aria-hidden="true">?</span><span class="scu-hint__bubble" aria-hidden="true">${escapeHtml(scuHintText)}</span></span></label>
                <input type="text" inputmode="decimal" data-scu-decimal step="0.001" name="items[${index}].amount" min="0.001" required class="w-full">
            </div>
            <div>
                <button type="button" class="btn btn-quiet-danger btn-icon od-remove-btn" data-trigger="od-remove-handover-row" title="Entfernen" aria-label="Entfernen"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg></button>
            </div>
        `;
    const sel = row.querySelector('select');
    const amtInput = row.querySelector('input[data-scu-decimal]');
    if (sel && amtInput) {
        sel.addEventListener('change', () => {
            const inv = cachedInventoryItems.find((i) => i.id === sel.value);
            if (inv) {
                amtInput.max = orderSliceAmount(inv);
            } else {
                amtInput.removeAttribute('max');
            }
            const unitSpan = row.querySelector('[data-role="amount-unit"]');
            const rowScuHint = row.querySelector('[data-role="scu-hint"]');
            const qt = inv && inv.material ? inv.material.quantityType : null;
            if (qt === 'PIECE') {
                amtInput.setAttribute('step', '1');
                amtInput.setAttribute('min', '1');
                if (unitSpan) unitSpan.textContent = '(' + labelPiece + ')';
                if (rowScuHint) rowScuHint.classList.add('krtm-hidden');
            } else if (qt === 'SCU') {
                amtInput.setAttribute('step', '0.001');
                amtInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '(' + labelScu + ')';
                if (rowScuHint) rowScuHint.classList.remove('krtm-hidden');
            } else {
                amtInput.setAttribute('step', '0.001');
                amtInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '';
                if (rowScuHint) rowScuHint.classList.add('krtm-hidden');
            }
            _refreshHandoverMissionPicker(row);
        });
        // The mission "Herkunft" picker's visibility depends on the handed amount vs the mission
        // rest, so re-evaluate it on every amount keystroke too.
        amtInput.addEventListener('input', () => _refreshHandoverMissionPicker(row));
    }
    container.appendChild(row);
}

// Renders / refreshes a handover row's mission "Herkunft" picker (Variante C, REQ-INV-027). Shown
// only when the selected entry is earmarked to two or more missions AND the handed amount cannot come
// entirely from the not-yet-assigned mission rest — i.e. only when the mission clamp is ambiguous.
// Inputs default to 0, meaning the backend auto-clamps (rest-first, then proportional); a non-zero
// input directs that much of the handed amount out of that mission's earmark.
function _refreshHandoverMissionPicker(row) {
    const sel = row.querySelector('select');
    const amtInput = row.querySelector('input[data-scu-decimal]');
    const inv = sel ? cachedInventoryItems.find((i) => i.id === sel.value) : null;
    const missions = inv && Array.isArray(inv.missionAllocations) ? inv.missionAllocations : [];
    const missionRest = inv && typeof inv.missionRest === 'number' ? inv.missionRest : 0;
    const isPiece = !!(inv && inv.material && inv.material.quantityType === 'PIECE');
    const handed =
        amtInput && window.krtScuInput ? window.krtScuInput.parse(amtInput.value) : Number.NaN;
    const ambiguous = missions.length >= 2 && isFinite(handed) && handed > missionRest + 0.0005;

    let holder = row.querySelector('[data-role="mission-herkunft"]');
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
    // Rebuild the inputs only when the selected entry changed, so an amount keystroke never clobbers
    // what the user already typed into the mission fields.
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
        missions.forEach((m) => {
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
            max.textContent = '/ ' + _handoverFmtAmount(m.amount, isPiece);
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

// Recomputes a row's mission "from rest" hint and flags (danger) a plan the mission rest cannot
// cover, so the submit validation and the visible chip agree.
function _updateHandoverMissionRest(row) {
    const holder = row.querySelector('[data-role="mission-herkunft"]');
    const sel = row.querySelector('select');
    const amtInput = row.querySelector('input[data-scu-decimal]');
    if (!holder || !sel || !amtInput) return;
    const inv = cachedInventoryItems.find((i) => i.id === sel.value);
    const missionRest = inv && typeof inv.missionRest === 'number' ? inv.missionRest : 0;
    const isPiece = !!(inv && inv.material && inv.material.quantityType === 'PIECE');
    const handed = window.krtScuInput
        ? window.krtScuInput.parse(amtInput.value)
        : parseFloat(amtInput.value);
    let assigned = 0;
    holder.querySelectorAll('[data-mission-input]').forEach((inp) => {
        const v = parseFloat(inp.value);
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

// Collects a handover row's mission "deduct from" plan from its picker (only inputs > 0), or null
// when the row has no picker / no positive input (the backend then auto-clamps the mission dimension).
function _collectHandoverMissionReductions(row) {
    const holder = row.querySelector('[data-role="mission-herkunft"]');
    if (!holder) return null;
    const isPiece = _handoverRowIsPiece(row);
    const list = [];
    holder.querySelectorAll('[data-mission-input]').forEach((inp) => {
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

function validateHandoverAmounts() {
    const rows = document.querySelectorAll('#handover-items-container .handover-item-row');
    for (const row of rows) {
        const sel = row.querySelector('select');
        const inp = row.querySelector('input[data-scu-decimal]');
        if (!sel || !sel.value || !inp || !inp.value) continue;
        const inv = cachedInventoryItems.find((i) => i.id === sel.value);
        if (!inv) continue;
        const isPiece = !!(inv.material && inv.material.quantityType === 'PIECE');
        const entered = window.krtScuInput.parse(inp.value);
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
        // Variante C (REQ-INV-027): if the mission "Herkunft" picker is showing, its plan must be
        // applyable — each input within its slice, the total not exceeding the handed amount, and the
        // missions absorbing at least what the mission rest cannot (else the backend 422s).
        const holder = row.querySelector('[data-role="mission-herkunft"]');
        if (holder) {
            const missionRest = typeof inv.missionRest === 'number' ? inv.missionRest : 0;
            let assigned = 0;
            let overSlice = false;
            holder.querySelectorAll('[data-mission-input]').forEach((inp2) => {
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

function updateHandoverIndexes() {
    const rows = document.querySelectorAll('.handover-item-row');
    rows.forEach((row, index) => {
        const select = row.querySelector('select');
        const input = row.querySelector('input');
        if (select) select.name = 'items[' + index + '].inventoryItemId';
        if (input) input.name = 'items[' + index + '].amount';
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const handoverForm = document.getElementById('handover-form');
    if (handoverForm) {
        handoverForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!validateHandoverAmounts()) return;
            const orderId = handoverForm.getAttribute('data-order-id');
            if (!orderId || !window.krtFetch || typeof krtOrderWrite !== 'function') {
                handoverForm.submit();
                return;
            }
            const payload = _serializeHandoverForm();
            if (!payload.items.length) {
                showFrontendErrorToast(MSG_HANDOVER_NOITEMS);
                return;
            }
            await krtOrderWrite({
                method: 'POST',
                url: '/orders/' + orderId + '/handovers',
                payload: payload,
                toast: false,
                errorMessage: MSG_HANDOVER_FAILED,
                onSuccess: function (order) {
                    // Patch the edit-modal @Version (the handover may auto-complete + bump it);
                    // the status select gets its fresh version from the header swap below.
                    const editVer = document.querySelector('#edit-modal input[name="version"]');
                    if (editVer && order && order.version != null) editVer.value = order.version;
                    const modal = document.getElementById('handover-modal');
                    if (modal) modal.style.display = 'none';
                    // The handover spent stock → the cached inventory + the modal's rows are
                    // stale; drop them so a reopen re-fetches and starts clean.
                    isInventoryCached = false;
                    cachedInventoryItems = [];
                    const itemsContainer = document.getElementById('handover-items-container');
                    if (itemsContainer) itemsContainer.innerHTML = '';
                    showFrontendSuccessToast(MSG_HANDOVER_SUCCESS);
                    // Re-render the requirement table (stock/status), the handover history (new
                    // row) and the header (status select + version, if the order auto-completed);
                    // each swap also broadcasts its section to peers viewing this order (REQ-FE-015).
                    _refreshMaterialsSection(orderId);
                    _swapOrderSection(orderId, 'order-handovers-results', 'handovers');
                    _swapOrderSection(orderId, 'order-header-results', 'header');
                    // A handover that auto-completes the order removes it from the staff queue's
                    // default filter — tell peers viewing the queue to re-fetch (REQ-FE-015).
                    if (order && (order.status === 'COMPLETED' || order.status === 'REJECTED')) {
                        _publishOrdersQueue();
                    }
                },
            });
        });
    }
    const itemHandoverForm = document.getElementById('item-handover-form');
    if (itemHandoverForm) {
        itemHandoverForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!validateItemHandoverForm()) return;
            const orderId = itemHandoverForm.getAttribute('data-order-id');
            if (!orderId || !window.krtFetch || typeof krtOrderWrite !== 'function') {
                itemHandoverForm.submit();
                return;
            }
            const payload = _serializeItemHandoverForm();
            if (!payload.entries.length) {
                showFrontendErrorToast(MSG_HANDOVER_NOITEMS);
                return;
            }
            await krtOrderWrite({
                method: 'POST',
                url: '/orders/' + orderId + '/item-handovers',
                payload: payload,
                toast: false,
                errorMessage: MSG_HANDOVER_FAILED,
                onSuccess: function (order) {
                    const editVer = document.querySelector('#edit-modal input[name="version"]');
                    if (editVer && order && order.version != null) editVer.value = order.version;
                    const modal = document.getElementById('item-handover-modal');
                    if (modal) modal.style.display = 'none';
                    showFrontendSuccessToast(MSG_HANDOVER_SUCCESS);
                    // Re-render the ordered-items table (delivered/outstanding), the history (new
                    // row + button-gating), the modal's outstanding-line rows (fresh max=) and the
                    // header (status, if auto-completed). The datetime widget + recipient stay put,
                    // so the form binding and the one-shot datetime enhancer survive.
                    _swapOrderSection(orderId, 'order-items-results', 'items');
                    _swapOrderSection(orderId, 'order-item-handovers-results', 'item-handovers');
                    _swapOrderSection(orderId, 'item-handover-lines', 'item-handover-lines');
                    _swapOrderSection(orderId, 'order-header-results', 'header');
                    // A delivery that auto-completes the order removes it from the staff queue's
                    // default filter — tell peers viewing the queue to re-fetch (REQ-FE-015).
                    if (order && (order.status === 'COMPLETED' || order.status === 'REJECTED')) {
                        _publishOrdersQueue();
                    }
                },
            });
        });
    }
});

// Localize the created-date + elapsed-days (header facts-bar + meta-grid) and every handover
// timestamp. Extracted so it re-runs after a header / handover fragment swap (#575) — it was a
// one-shot DOMContentLoaded enhancer, so swapped-in dates would otherwise stay raw ISO strings.
// Variante A shows created/elapsed in BOTH the facts-bar and the "Zeit" meta-group, so this
// localizes every .od-created-date / .od-elapsed-days copy (the facts-bar copy additionally
// keeps #created-date-span / #elapsed-days-span / #elapsed-days-container).
function localizeOrderDates(root) {
    const scope = root || document;
    const now = new Date();
    scope.querySelectorAll('.od-created-date[data-utc]').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (!utcDateStr) return;
        const date = new Date(utcDateStr);
        if (!isNaN(date)) {
            el.textContent = date.toLocaleDateString();
        }
    });
    scope.querySelectorAll('.od-elapsed-days[data-utc]').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (!utcDateStr) return;
        const date = new Date(utcDateStr);
        if (isNaN(date)) return;
        const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
        el.textContent = diffDays > 0 ? diffDays : 0;
        // Colour the whole "<n> Tage" value (the .od-elapsed-cell wrapper), falling back to the
        // number span when no wrapper is present.
        const targetEl = el.closest('.od-elapsed-cell') || el;
        if (diffDays >= ORDER_AGE_RED) {
            targetEl.classList.add('text-danger');
        } else if (diffDays >= ORDER_AGE_YELLOW) {
            targetEl.classList.add('text-warning');
        }
    });
    scope.querySelectorAll('.handover-time-span').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (utcDateStr) {
            const dateObj = new Date(utcDateStr);
            if (!isNaN(dateObj)) {
                el.textContent = dateObj.toLocaleString([], {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit',
                });
            }
        }
    });
}
document.addEventListener('krt:swapped', (e) => {
    localizeOrderDates(e && e.detail ? e.detail.container : document);
});

document.addEventListener('DOMContentLoaded', () => {
    localizeOrderDates(document);

    // Material Row Management
    const materialsContainer = document.getElementById('materials-container');
    const addMaterialBtn = document.getElementById('add-material-btn');
    if (addMaterialBtn && materialsContainer) {
        // Mirror the amount field to the chosen material's quantity type. `min` must track `step`:
        // with min="0.001" left in place a step="1" would make HTML5 step-validation reject whole
        // numbers, so PIECE switches both to integer.
        function refreshEditMaterialUnit(row) {
            if (!row) {
                return;
            }
            const sel = row.querySelector('[data-role="material-select"]');
            const amountInput = row.querySelector('[data-role="material-amount"]');
            const unitSpan = row.querySelector('[data-role="amount-unit"]');
            const hint = row.querySelector('.scu-hint');
            if (!sel || !amountInput) {
                return;
            }
            const opt = sel.selectedOptions && sel.selectedOptions[0];
            const qt = opt ? opt.getAttribute('data-quantity-type') : '';
            if (qt === 'PIECE') {
                amountInput.setAttribute('step', '1');
                amountInput.setAttribute('min', '1');
                if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_PIECE + ')';
                if (hint) hint.classList.add('krtm-hidden');
            } else if (qt === 'SCU') {
                amountInput.setAttribute('step', '0.001');
                amountInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_SCU + ')';
                if (hint) hint.classList.remove('krtm-hidden');
            } else {
                amountInput.setAttribute('step', '0.001');
                amountInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '';
                if (hint) hint.classList.add('krtm-hidden');
            }
        }

        addMaterialBtn.addEventListener('click', () => {
            const rows = materialsContainer.querySelectorAll('.material-row');
            const nextIndex = rows.length;
            if (nextIndex > 0) {
                const templateRow = rows[0];
                const newRow = templateRow.cloneNode(true);

                const selects = newRow.querySelectorAll('select');
                selects.forEach((select) => {
                    const name = select.getAttribute('name');
                    if (name) {
                        select.setAttribute('name', name.replace(/\[\d+\]/, '[' + nextIndex + ']'));
                        const selectId = select.getAttribute('id');
                        if (selectId) {
                            select.setAttribute('id', selectId.replace(/\d+/, nextIndex));
                        }
                        // minQuality is a 650/Keine select; default a fresh row to 650. The
                        // material select resets to the empty "please choose" option.
                        select.value = name.includes('minQuality') ? '650' : '';
                    }
                });

                const inputs = newRow.querySelectorAll('input');
                inputs.forEach((input) => {
                    const name = input.getAttribute('name');
                    if (name) {
                        input.setAttribute('name', name.replace(/\[\d+\]/, '[' + nextIndex + ']'));
                        const inputId = input.getAttribute('id');
                        if (inputId) {
                            input.setAttribute('id', inputId.replace(/\d+/, nextIndex));
                        }
                        input.value = '';
                    }
                });

                materialsContainer.appendChild(newRow);
                refreshEditMaterialUnit(newRow);
            }
        });

        materialsContainer.addEventListener('change', (e) => {
            if (e.target && e.target.matches && e.target.matches('[data-role="material-select"]')) {
                refreshEditMaterialUnit(e.target.closest('.material-row'));
            }
        });
        materialsContainer.querySelectorAll('.material-row').forEach(refreshEditMaterialUnit);

        materialsContainer.addEventListener('click', (e) => {
            const removeBtn = e.target.closest && e.target.closest('.remove-material-btn');
            if (removeBtn) {
                const row = removeBtn.closest('.material-row');
                if (materialsContainer.querySelectorAll('.material-row').length > 1) {
                    document.getElementById('material-delete-confirm-modal').style.display = 'flex';
                    window.materialRowToDelete = row;
                } else {
                    showFrontendErrorToast(
                        'Es muss mindestens ein Material im Auftrag verbleiben.',
                    );
                }
            }
        });

        window.confirmRemoveMaterial = function () {
            if (window.materialRowToDelete) {
                window.materialRowToDelete.remove();
                reindexMaterials();
                window.materialRowToDelete = null;
            }
            document.getElementById('material-delete-confirm-modal').style.display = 'none';
        };

        function reindexMaterials() {
            const rows = materialsContainer.querySelectorAll('.material-row');
            rows.forEach((row, index) => {
                const inputs = row.querySelectorAll('input, select');
                inputs.forEach((input) => {
                    const name = input.getAttribute('name');
                    if (name) {
                        input.setAttribute('name', name.replace(/\[\d+\]/, '[' + index + ']'));
                        if (input.getAttribute('id')) {
                            input.setAttribute(
                                'id',
                                input.getAttribute('id').replace(/\d+/, index),
                            );
                        }
                    }
                });
            });
        }
    }
});

// Page-local krtFetch.write wrapper (#571 / #575): injects the already-localized order conflict
// strings so every order write shares ONE optimistic-lock UX (krtFetch hardcodes no user text),
// and retires the four bespoke CSRF readers on this page (krtFetch.write reads window.krtCsrf
// internally + retries once on a stale-token 403).
function krtOrderWrite(opts) {
    return window.krtFetch.write(
        Object.assign({}, opts, {
            // All order writes touch the same order @Version (one order per page), so serialize them
            // under one key: a status change, a variant-counting toggle, an edit save and a handover
            // run in submission order instead of racing each other into a self-collision 409. A
            // caller may override opts.serialize (the per-edge assignee writes use oaSend, a separate
            // raw fetch, and are unaffected).
            serialize: opts.serialize || 'order',
            conflict: {
                title: ORDER_CONFLICT.title,
                reloadLabel: ORDER_CONFLICT.reload,
                reloadQuestion: ORDER_CONFLICT.reloadQuestion,
                dismissLabel: ORDER_CONFLICT.dismiss,
                reloadDetailFallback: ORDER_CONFLICT.detail,
            },
        }),
    );
}

// Delete order in place (#575): add a KRT confirm (none existed — destructive + the
// no-native-dialogs rule), then krtFetch.write DELETE and navigate to the list. A backend
// rejection (e.g. the order still has linked inventory) keeps the user on the page with the
// error toast instead of a redirect-reflash. The classic POST form is the no-JS fallback.
(function () {
    const form = document.getElementById('delete-order-form');
    if (!form) return;
    form.addEventListener('submit', async function (e) {
        e.preventDefault();
        const orderId = form.getAttribute('data-order-id');
        if (!orderId || typeof window.showKrtConfirm !== 'function' || !window.krtFetch) {
            form.submit();
            return;
        }
        const ok = await window.showKrtConfirm(
            MSG_DELETE_TITLE,
            MSG_DELETE_MESSAGE,
            MSG_DELETE_CONFIRM,
            MSG_DELETE_CANCEL,
        );
        if (!ok) return;
        const res = await krtOrderWrite({
            method: 'DELETE',
            url: '/orders/' + orderId,
            toast: false,
            errorMessage: MSG_DELETE_ERROR,
        });
        if (res.ok) {
            // The order left the queue — tell peers viewing the staff queue to re-fetch (REQ-FE-015)
            // before we navigate away from this page.
            _publishOrdersQueue();
            window.location.assign('/orders');
        }
    });
})();

// Edit order in place (#575): serialize the modal form (header fields + the dynamic material
// editor) and krtFetch.write the JSON twin, then re-render the kv-list header and the material
// requirement section in place — no reload. The edit modal lives outside both swap containers,
// so its material editor survives; its hidden @Version input is patched from the returned order
// (the status select gets its fresh version from the header swap). Classic POST is the fallback.
function _serializeEditForm() {
    const materials = [];
    document.querySelectorAll('#materials-container .material-row').forEach(function (row) {
        const matSel = row.querySelector('[name$=".materialId"]');
        const qualSel = row.querySelector('[name$=".minQuality"]');
        const amtInput = row.querySelector('[name$=".amount"]');
        const materialId = matSel ? matSel.value : '';
        const amount =
            amtInput && window.krtScuInput
                ? window.krtScuInput.parse(amtInput.value)
                : amtInput
                  ? parseFloat(amtInput.value)
                  : NaN;
        if (!materialId || !(amount > 0)) return;
        materials.push({
            materialId: materialId,
            minQuality: qualSel && qualSel.value ? parseInt(qualSel.value, 10) : null,
            amount: amount,
        });
    });
    const versionInput = document.querySelector('#edit-modal input[name="version"]');
    return {
        requestingOrgUnitId: (document.getElementById('requestingOrgUnitId') || {}).value || null,
        handle: (document.getElementById('handle') || {}).value || '',
        comment: (document.getElementById('edit-comment') || {}).value || '',
        version: versionInput ? parseInt(versionInput.value, 10) : null,
        materials: materials,
    };
}
(function () {
    const form = document.getElementById('edit-order-form');
    if (!form) return;
    form.addEventListener('submit', async function (e) {
        e.preventDefault();
        const orderId = form.getAttribute('data-order-id');
        if (!orderId || !window.krtFetch) {
            form.submit();
            return;
        }
        const payload = _serializeEditForm();
        if (!payload.materials.length) {
            showFrontendErrorToast(MSG_MATERIAL_INVALID);
            return;
        }
        // REQ-ORDERS-023: a requesting owner's limited edit posts to the dedicated requester endpoint
        // (comment + not-yet-delivered materials only); the backend ignores handle/org-unit/status.
        const requesterView = form.getAttribute('data-requester-view') === 'true';
        const updateUrl = requesterView
            ? '/orders/' + orderId + '/requested-update'
            : '/orders/' + orderId + '/update';
        await krtOrderWrite({
            method: 'POST',
            url: updateUrl,
            payload: payload,
            toast: false,
            errorMessage: MSG_UPDATE_ERROR,
            onSuccess: function (dto) {
                const editVer = document.querySelector('#edit-modal input[name="version"]');
                if (editVer && dto && dto.version != null) editVer.value = dto.version;
                const modal = document.getElementById('edit-modal');
                if (modal) modal.style.display = 'none';
                showFrontendSuccessToast(MSG_UPDATE_SUCCESS);
                // Re-render the header (handle / status / priority) and the material requirement
                // section in place and broadcast both to peers viewing this order (REQ-FE-015).
                _swapOrderSection(orderId, 'order-header-results', 'header');
                _refreshMaterialsSection(orderId);
                // The edit can change the handle shown in the staff queue — refresh peers' queues too.
                _publishOrdersQueue();
            },
        });
    });
})();

// Re-render the material section in place (#575) after a claim create/edit/withdraw OR an
// inventory unlink. The "Offen" open-amount + the per-row stock/status cells are backend-derived
// and not returned per write, so a partial DOM patch would desync them — re-pull the whole table
// fragment instead. MATERIAL orders render the requirement table on #order-materials-results,
// ITEM orders the aggregated table on #order-aggregated-results; swap whichever exists.
function _refreshMaterialsSection(orderId, broadcastOpts) {
    // Route through the order live-sync seam so a local claim/edit/unlink re-render also broadcasts
    // materials/aggregated to peers viewing this order (REQ-FE-015). The seam swaps whichever of the
    // two containers this order actually renders (MATERIAL -> materials, ITEM -> aggregated) and
    // skips the absent one; broadcastOpts lets a receiver-applied refresh pass {broadcast:false} so
    // an inbound peer change is not echoed back.
    if (window.krtRefreshOrderSection) {
        return window.krtRefreshOrderSection(['materials', 'aggregated'], broadcastOpts);
    }
    // Fallback (foundation present but the section seam unavailable): the former direct swap.
    orderId = orderId || window.orderId || (document.getElementById('claim-order-id') || {}).value;
    const mat = document.getElementById('order-materials-results');
    const agg = document.getElementById('order-aggregated-results');
    const container = mat || agg;
    if (!orderId || !container || !window.krtFetch || !window.krtFetch.swap)
        return Promise.resolve(false);
    return window.krtFetch.swap({
        url: '/orders/' + orderId,
        container: container,
        fragmentValue: mat ? 'materials' : 'aggregated',
        history: false,
        preserveScroll: true,
    });
}

// Swap one order-detail section by container id + fragment value (#575). Used by the handover
// flow to re-render the handover history + the header (status select carrying the fresh
// @Version) in place. Routed through the order live-sync seam so the local re-render also
// broadcasts the section to peers (REQ-FE-015): the section key equals the fragment value, and the
// seam resolves the container from ORDER_SECTIONS — containerId is kept only for the fallback path.
// broadcastOpts lets a receiver-applied refresh pass {broadcast:false} to avoid an echo loop.
function _swapOrderSection(orderId, containerId, fragmentValue, broadcastOpts) {
    if (window.krtRefreshOrderSection) {
        return window.krtRefreshOrderSection(fragmentValue, broadcastOpts);
    }
    const c = document.getElementById(containerId);
    if (!c || !orderId || !window.krtFetch || !window.krtFetch.swap) return Promise.resolve(false);
    return window.krtFetch.swap({
        url: '/orders/' + orderId,
        container: c,
        fragmentValue: fragmentValue,
        history: false,
        preserveScroll: true,
    });
}

// Detach a linked inventory item in place (#575): the AJAX twin re-fetches the order so we can
// patch the bumped ORDER @Version (the detach mutates the aggregate, so the next status/handover
// write would otherwise 409) and then re-render the material section (stock/status recompute; the
// open drill-down collapses with the swap). Delegated on the button, so it survives the swap.
async function unlinkInventoryItem(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const invId = btn.getAttribute('data-inventory-item-id');
    if (!orderId || !invId) return;
    await krtOrderWrite({
        method: 'DELETE',
        url: '/orders/' + orderId + '/inventory/' + invId + '/unlink/ajax',
        toast: false,
        errorMessage: MSG_INVENTORY_UNLINK_ERROR,
        onSuccess: function (data) {
            if (data && data.version != null) {
                const sel = document.getElementById('status-select');
                if (sel) sel.dataset.version = data.version;
                const editVer = document.querySelector('#edit-modal input[name="version"]');
                if (editVer) editVer.value = data.version;
            }
            showFrontendSuccessToast(MSG_INVENTORY_UNLINK_SUCCESS);
            _refreshMaterialsSection(orderId);
        },
    });
}

function _openClaimModal(opts) {
    document.getElementById('claim-material-id').value = opts.materialId || '';
    document.getElementById('claim-quality').value = opts.quality || '';
    document.getElementById('claim-id').value = opts.claimId || '';
    const sel = document.getElementById('claim-squadron');
    const amountInput = document.getElementById('claim-amount');
    const open = parseFloat(opts.open || '0') || 0;
    const qualityLabel = opts.quality === 'GOOD' ? MSG_QUALITY_GOOD : MSG_QUALITY_NONE;
    document.getElementById('claim-modal-bucket').textContent =
        (opts.materialName || '') + ' — ' + qualityLabel;
    if (opts.edit) {
        document.getElementById('claim-modal-title').textContent = MSG_CLAIM_TITLE_EDIT;
        sel.value = opts.squadronId || '';
        sel.disabled = true;
        amountInput.value = opts.amount || '';
        const maxEdit = open + (parseFloat(opts.amount || '0') || 0);
        document.getElementById('claim-max').value = maxEdit;
        document.getElementById('claim-open-hint').textContent = MSG_CLAIM_MAX_HINT + ' ' + maxEdit;
        document.getElementById('claim-withdraw-btn').classList.remove('krtm-hidden');
    } else {
        document.getElementById('claim-modal-title').textContent = MSG_CLAIM_TITLE_ADD;
        sel.value = '';
        sel.disabled = false;
        amountInput.value = '';
        document.getElementById('claim-max').value = open;
        document.getElementById('claim-open-hint').textContent = MSG_CLAIM_MAX_HINT + ' ' + open;
        document.getElementById('claim-withdraw-btn').classList.add('krtm-hidden');
    }
    const claimScuHint = document.getElementById('claim-scu-hint');
    if (claimScuHint) claimScuHint.classList.toggle('krtm-hidden', opts.quantityType === 'PIECE');
    document.getElementById('claim-modal').style.display = 'flex';
}

function openClaimCreate(el) {
    _openClaimModal({
        materialId: el.dataset.materialId,
        quality: el.dataset.quality,
        materialName: el.dataset.materialName,
        quantityType: el.dataset.quantityType,
        open: el.dataset.open,
        edit: false,
    });
}

function openClaimEdit(el) {
    _openClaimModal({
        materialId: el.dataset.materialId,
        quality: el.dataset.quality,
        materialName: el.dataset.materialName,
        quantityType: el.dataset.quantityType,
        open: el.dataset.open,
        claimId: el.dataset.claimId,
        squadronId: el.dataset.squadronId,
        amount: el.dataset.amount,
        edit: true,
    });
}

function submitClaim() {
    const orderId = document.getElementById('claim-order-id').value;
    const materialId = document.getElementById('claim-material-id').value;
    const quality = document.getElementById('claim-quality').value;
    const squadronId = document.getElementById('claim-squadron').value;
    const amount = window.krtScuInput.parse(document.getElementById('claim-amount').value);
    const max = parseFloat(document.getElementById('claim-max').value);
    if (!squadronId) {
        showFrontendErrorToast(MSG_CLAIM_VALIDATION_SQUADRON);
        return;
    }
    if (!(amount > 0)) {
        showFrontendErrorToast(MSG_CLAIM_VALIDATION_AMOUNT);
        return;
    }
    if (amount > max + 1e-9) {
        showFrontendErrorToast(MSG_CLAIM_VALIDATION_OVERCLAIM);
        return;
    }
    krtOrderWrite({
        method: 'POST',
        url: '/orders/' + orderId + '/claims',
        payload: {
            materialId: materialId,
            qualityRequirement: quality,
            claimingOrgUnitId: squadronId,
            amount: amount,
        },
        toast: false,
        errorMessage: MSG_CLAIM_ERROR,
        onSuccess: function () {
            document.getElementById('claim-modal').style.display = 'none';
            showFrontendSuccessToast(MSG_CLAIM_SUCCESS);
            _refreshMaterialsSection();
        },
    });
}

function withdrawClaimAction() {
    const orderId = document.getElementById('claim-order-id').value;
    const claimId = document.getElementById('claim-id').value;
    if (!claimId) {
        return;
    }
    krtOrderWrite({
        method: 'POST',
        url: '/orders/' + orderId + '/claims/' + claimId + '/withdraw',
        toast: false,
        errorMessage: MSG_CLAIM_ERROR,
        onSuccess: function () {
            document.getElementById('claim-modal').style.display = 'none';
            showFrontendSuccessToast(MSG_CLAIM_WITHDRAW_SUCCESS);
            _refreshMaterialsSection();
        },
    });
}

let _pendingStatus = null;
let _previousStatus = null;

// The last successfully-saved status of the select, read from data-saved-status (set on every
// successful in-place change) and falling back to the server-rendered selected option. Replaces
// the reload the old flow relied on to reset the control after a change.
function _knownStatus(selectElement) {
    return (
        selectElement.dataset.savedStatus ||
        (selectElement.querySelector('option[selected]') || {}).value ||
        selectElement.options[0].value
    );
}

function updateStatus(selectElement) {
    const newStatus = selectElement.value;
    if (newStatus === 'COMPLETED' || newStatus === 'REJECTED') {
        _pendingStatus = newStatus;
        _previousStatus = _knownStatus(selectElement);
        document.getElementById('status-warning-modal').style.display = 'flex';
    } else {
        _doStatusUpdate(selectElement.dataset.orderId, newStatus, selectElement);
    }
}

function cancelStatusChange() {
    document.getElementById('status-warning-modal').style.display = 'none';
    const sel = document.getElementById('status-select');
    if (sel && _previousStatus) {
        sel.value = _previousStatus;
    }
    _pendingStatus = null;
    _previousStatus = null;
}

function confirmStatusChange() {
    document.getElementById('status-warning-modal').style.display = 'none';
    const sel = document.getElementById('status-select');
    if (!sel || !_pendingStatus) return;
    _doStatusUpdate(sel.dataset.orderId, _pendingStatus, sel);
    _pendingStatus = null;
    _previousStatus = null;
}

// The order's current optimistic-lock @Version, read from its canonical live carriers — the status
// select and, as a fallback, the edit-modal hidden input — both of which every order write's
// onSuccess patches. Read at SEND time (from inside a payload thunk) so a serialized order write
// picks up the version the write before it bumped, instead of a value captured when the handler
// first fired (the self-collision fix).
function _orderVersion() {
    const sel = document.getElementById('status-select');
    if (sel && sel.dataset.version != null && sel.dataset.version !== '') {
        return parseInt(sel.dataset.version, 10);
    }
    const editVer = document.querySelector('#edit-modal input[name="version"]');
    return editVer && editVer.value ? parseInt(editVer.value, 10) : null;
}

function _doStatusUpdate(orderId, status, selectElement) {
    // In-place (#575): no reload. Capture the last-good status up front so a failed change can
    // revert the optimistic <select>; krtOrderWrite reads CSRF via krtCsrf (+ retry-on-403) and
    // surfaces the 409 conflict reload-prompt itself.
    const savedStatus = selectElement ? _knownStatus(selectElement) : null;
    krtOrderWrite({
        method: 'POST',
        url: '/orders/' + orderId + '/status',
        payload: function () {
            return { status: status, version: _orderVersion() };
        },
        toast: false,
        errorMessage: MSG_STATUS_ERROR,
        onSuccess: function (data) {
            // Patch ONLY the two elements carrying the ORDER @Version — the status select (next
            // status change) and the edit-modal hidden input (next edit save). The assignee
            // edges carry their own per-edge version and must NOT be overwritten here.
            if (data && data.version != null && selectElement) {
                selectElement.dataset.version = data.version;
                const editVer = document.querySelector('#edit-modal input[name="version"]');
                if (editVer) editVer.value = data.version;
            }
            if (selectElement) selectElement.dataset.savedStatus = status;
            // The header kv-list shows the status + priority, both backend-derived: a terminal
            // status nulls the priority, and reactivating a terminal order assigns a fresh one
            // (JobOrderService.updateJobOrderStatus). Re-pull the header on EVERY status change so
            // the priority cell matches the server instead of going stale on the reactivate path
            // (mirrors the edit flow), and broadcast it to peers viewing this order (REQ-FE-015). The
            // swap also re-renders the delegated status select with a fresh @Version.
            _swapOrderSection(orderId, 'order-header-results', 'header');
            // COMPLETED/REJECTED additionally detach linked inventory server-side (lowering the
            // per-material stock + fulfilment cells); re-pull the materials section and drop any
            // open inventory drill-down (lazily re-fetched on the next row click).
            if (status === 'COMPLETED' || status === 'REJECTED') {
                document.querySelectorAll('.inventory-details-row').forEach(function (r) {
                    r.remove();
                });
                _refreshMaterialsSection(orderId);
            }
            // A status change moves the order in/out of the staff queue's default OPEN+IN_PROGRESS
            // filter — tell peers viewing the queue to re-fetch their own list (REQ-FE-015).
            _publishOrdersQueue();
            showFrontendSuccessToast(MSG_STATUS_SUCCESS);
        },
    }).then(function (res) {
        if (selectElement && (!res || !res.ok)) {
            selectElement.value = savedStatus;
        }
    });
}

// Blueprint-coverage variant-counting toggle (#822). Persist the chosen mode on the order, then
// re-render the whole coverage panel in place (no reload) so the counts + per-item hints reflect
// the new mode. The order @Version is bumped server-side, so patch it onto the two version
// carriers that live OUTSIDE this panel (status select + edit-modal hidden input) — otherwise the
// user's next status/edit write would 409. On failure the optimistic checkbox is reverted.
function _toggleBlueprintCounting(checkbox) {
    const orderId = checkbox.getAttribute('data-order-id');
    const desired = checkbox.checked;
    if (!orderId || !window.krtFetch) {
        return;
    }
    // Disable for the round-trip so a quick double-toggle can't fire a second, stale-version
    // write (a change-event control isn't covered by krtFetch's submit-button double-submit
    // guard). On success the panel swap replaces this checkbox; on failure we re-enable + revert.
    // The version is read lazily from the order's canonical carrier (status select / edit-modal
    // input) at send time, since this checkbox's own data-version is not patched by other order
    // writes — only those two carriers are.
    checkbox.disabled = true;
    krtOrderWrite({
        method: 'POST',
        url: '/orders/' + orderId + '/blueprint-variant-counting',
        payload: function () {
            return { countBlueprintsWithVariants: desired, version: _orderVersion() };
        },
        toast: false,
        errorMessage: MSG_BP_COUNTING_ERROR,
        onSuccess: function (data) {
            if (data && data.version != null) {
                const sel = document.getElementById('status-select');
                if (sel) sel.dataset.version = data.version;
                const editVer = document.querySelector('#edit-modal input[name="version"]');
                if (editVer) editVer.value = data.version;
            }
            showFrontendSuccessToast(MSG_BP_COUNTING_SUCCESS);
            _swapOrderSection(orderId, 'blueprint-owners-section', 'blueprint-owners');
        },
    }).then(function (res) {
        checkbox.disabled = false;
        if (!res || !res.ok) {
            checkbox.checked = !desired;
        }
    });
}

async function downloadHandoverReport(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const handoverId = btn.getAttribute('data-handover-id');
    const handoverTimeRaw = btn.getAttribute('data-handover-time');
    const orderNumberRaw =
        document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
        document.getElementById('order-display-id')?.textContent?.trim() ||
        '#' + orderId;
    const orderNumber = orderNumberRaw.replace(/^#/, '');
    let dateStr = '';
    let timeStr = '';
    if (handoverTimeRaw) {
        const d = new Date(handoverTimeRaw);
        dateStr =
            d.getFullYear() +
            '-' +
            String(d.getMonth() + 1).padStart(2, '0') +
            '-' +
            String(d.getDate()).padStart(2, '0');
        timeStr =
            String(d.getHours()).padStart(2, '0') + '-' + String(d.getMinutes()).padStart(2, '0');
    }
    const filename = 'Übergabe Auftrag #' + orderNumber + ' ' + dateStr + ' ' + timeStr + '.pdf';
    try {
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || '';
        const csrfHeader =
            document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        // Forward the user's actual IANA time zone so the backend can render handover date/time
        // in the user's local time zone instead of the server's ZoneId.systemDefault().
        const userTimeZone =
            Intl && Intl.DateTimeFormat ? Intl.DateTimeFormat().resolvedOptions().timeZone : '';
        const downloadHeaders = { [csrfHeader]: csrfToken };
        if (userTimeZone) {
            downloadHeaders['X-User-Time-Zone'] = userTimeZone;
        }
        const response = await fetch(
            '/api/v1/orders/' + orderId + '/handovers/' + handoverId + '/report',
            {
                method: 'GET',
                headers: downloadHeaders,
            },
        );
        if (!response.ok) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.target = '_blank';
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
    }
}

// Item-order delivery-note download. Mirrors downloadHandoverReport but targets the item-handover
// proxy endpoint; the backend renders date/time in the user's IANA zone forwarded below.
async function downloadItemHandoverReport(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const handoverId = btn.getAttribute('data-handover-id');
    const handoverTimeRaw = btn.getAttribute('data-handover-time');
    const orderNumberRaw =
        document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
        document.getElementById('order-display-id')?.textContent?.trim() ||
        '#' + orderId;
    const orderNumber = orderNumberRaw.replace(/^#/, '');
    let dateStr = '';
    let timeStr = '';
    if (handoverTimeRaw) {
        const d = new Date(handoverTimeRaw);
        dateStr =
            d.getFullYear() +
            '-' +
            String(d.getMonth() + 1).padStart(2, '0') +
            '-' +
            String(d.getDate()).padStart(2, '0');
        timeStr =
            String(d.getHours()).padStart(2, '0') + '-' + String(d.getMinutes()).padStart(2, '0');
    }
    const filename = 'Übergabe Auftrag #' + orderNumber + ' ' + dateStr + ' ' + timeStr + '.pdf';
    try {
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || '';
        const csrfHeader =
            document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        const userTimeZone =
            Intl && Intl.DateTimeFormat ? Intl.DateTimeFormat().resolvedOptions().timeZone : '';
        const downloadHeaders = { [csrfHeader]: csrfToken };
        if (userTimeZone) {
            downloadHeaders['X-User-Time-Zone'] = userTimeZone;
        }
        const response = await fetch(
            '/api/v1/orders/' + orderId + '/item-handovers/' + handoverId + '/report',
            {
                method: 'GET',
                headers: downloadHeaders,
            },
        );
        if (!response.ok) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.target = '_blank';
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
    }
}

// Block submission of an empty item handover (every amount blank/zero) so the user gets a toast
// instead of a server-side "no items" round-trip. The per-row max attribute enforces the upper
// bound natively; this only guards the lower bound across the whole form.
function validateItemHandoverForm() {
    const inputs = document.querySelectorAll('#item-handover-modal input[type="number"]');
    for (const inp of inputs) {
        const val = parseInt(inp.value, 10);
        if (!isNaN(val) && val > 0) {
            return true;
        }
    }
    showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_ITEMS);
    return false;
}

async function previewHandoverReport(btn) {
    const orderId = btn.getAttribute('data-order-id');
    const orderNumber =
        document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
        document.getElementById('order-display-id')?.textContent?.trim() ||
        '#' + orderId;
    const datePart = document.querySelector('#handover-modal .date-part')?.value;
    const timePart = document.querySelector('#handover-modal .time-part')?.value;
    const recipientHandle = document.getElementById('recipientHandle')?.value?.trim();
    const itemRows = document.querySelectorAll('#handover-items-container .handover-item-row');
    const hasValidItem = Array.from(itemRows).some((row) => {
        const sel = row.querySelector('select');
        const inp = row.querySelector('input[type="number"]');
        return sel && sel.value && inp && inp.value;
    });
    if (!datePart) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_DATE);
        return;
    }
    if (!timePart) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_TIME);
        return;
    }
    if (!recipientHandle) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_HANDLE);
        return;
    }
    if (!hasValidItem) {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_ITEMS);
        return;
    }
    if (!validateHandoverAmounts()) {
        return;
    }
    const items = [];
    document.querySelectorAll('.handover-item-row').forEach((row) => {
        const select = row.querySelector('select');
        const amountInput = row.querySelector('input[type="number"]');
        if (select && select.value && amountInput && amountInput.value) {
            const invId = select.value;
            const inv = cachedInventoryItems.find((i) => i.id === invId);
            if (inv) {
                items.push({
                    materialName: inv.material ? inv.material.name : '-',
                    locationName: inv.location ? inv.location.name : null,
                    amount: parseFloat(amountInput.value),
                    quality: inv.quality,
                    quantityType: inv.material ? inv.material.quantityType : null,
                });
            }
        }
    });
    let handoverTimeIso;
    try {
        const localDateStr = datePart + 'T' + timePart;
        const localDate = new Date(localDateStr);
        if (isNaN(localDate.getTime())) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_DATE);
            return;
        }
        handoverTimeIso = localDateStr;
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_VALIDATION_DATE);
        return;
    }
    const payload = {
        jobOrderNumber: orderNumber,
        handoverTime: handoverTimeIso,
        recipientHandle: recipientHandle,
        items: items,
    };
    try {
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || '';
        const csrfHeader =
            document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        const response = await fetch('/api/v1/orders/' + orderId + '/handovers/report/preview', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', [csrfHeader]: csrfToken },
            body: JSON.stringify(payload),
        });
        if (!response.ok) {
            showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
            return;
        }
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.target = '_blank';
        const previewOrderNumberRaw =
            document.getElementById('order-display-id')?.getAttribute('data-order-number') ||
            document.getElementById('order-display-id')?.textContent?.trim() ||
            '#' + orderId;
        const previewOrderNumber = previewOrderNumberRaw.replace(/^#/, '');
        const previewDate = datePart;
        const previewTime = timePart ? timePart.replace(':', '-') : '';
        a.download =
            'Übergabe Auftrag #' +
            previewOrderNumber +
            ' ' +
            previewDate +
            ' ' +
            previewTime +
            '.pdf';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch {
        showFrontendErrorToast(MSG_HANDOVER_REPORT_ERROR);
    }
}

async function toggleInventory(row) {
    const orderId = row.getAttribute('data-order-id');
    const materialId = row.getAttribute('data-material-id');
    const amountType = row.getAttribute('data-amount-type');

    let nextRow = row.nextElementSibling;
    if (nextRow && nextRow.classList.contains('inventory-details-row')) {
        nextRow.remove();
        return;
    }

    const detailsRow = document.createElement('tr');
    detailsRow.classList.add('inventory-details-row');
    detailsRow.innerHTML = `
            <td colspan="5" class="od-inv-cell">
                <div class="od-inv-note">${MSG_LOADING_INVENTORY}</div>
            </td>
        `;
    row.parentNode.insertBefore(detailsRow, row.nextSibling);

    try {
        const response = await fetch(
            '/orders/' + orderId + '/materials/' + materialId + '/inventory',
        );
        if (!response.ok) throw new Error('Network response was not ok');
        const items = await response.json();

        if (items.length === 0) {
            detailsRow.innerHTML = `
                    <td colspan="5" class="od-inv-cell">
                        <div class="od-inv-note">${MSG_EMPTY_INVENTORY}</div>
                    </td>
                `;
            return;
        }

        const unlinkColHeader = IS_LOGISTICIAN ? `<th class="od-inv-th"></th>` : '';
        const subColspan = IS_LOGISTICIAN ? 7 : 6;

        let html = `
                <td colspan="${subColspan}" class="p-0">
                    <div class="od-inv-panel">
                        <div class="table-responsive">
                            <table class="data-table od-inv-table">
                                <thead>
                                    <tr>
                                        <th class="od-inv-th">${MSG_OWNER}</th>
                                        <th class="od-inv-th">${MSG_SQUADRON}</th>
                                        <th class="od-inv-th">${MSG_LOCATION}</th>
                                        <th class="od-inv-th">${MSG_QUALITY}</th>
                                        <th class="od-inv-th">${MSG_QUANTITY}</th>
                                        ${unlinkColHeader}
                                    </tr>
                                </thead>
                                <tbody>
            `;

        for (const item of items) {
            const ownerName = item.user ? item.user.effectiveName : '-';
            const locationName = item.location ? item.location.name : '-';
            const quality = item.quality !== null ? item.quality : '-';
            const quantity =
                (item.amount !== null ? item.amount.toFixed(3) : '0.000') +
                ' ' +
                (amountType ? amountType : '');
            const itemSquadron = item.owningSquadron || null;
            const itemSquadronId = itemSquadron ? itemSquadron.id : null;
            const itemSquadronShorthand = itemSquadron ? itemSquadron.shorthand : null;
            const itemSquadronName = itemSquadron ? itemSquadron.name : '';
            const isForeignSquadron =
                itemSquadronId !== null &&
                ORDER_REQUESTING_SQUADRON_ID !== null &&
                itemSquadronId !== ORDER_REQUESTING_SQUADRON_ID;
            const rowClass = isForeignSquadron ? 'inventory-row-foreign-jobOrder' : '';
            const squadronBadgeClass = isForeignSquadron
                ? 'squadron-badge squadron-badge-foreign'
                : 'squadron-badge';
            const squadronCell = itemSquadronShorthand
                ? `<span class="${squadronBadgeClass}" title="${escapeAttr(itemSquadronName)}">${escapeHtml(itemSquadronShorthand)}</span>`
                : `<span class="squadron-badge squadron-badge-muted">&mdash;</span>`;
            const unlinkCell = IS_LOGISTICIAN
                ? `
                                        <td data-trigger="stop-propagation">
                                            <button type="button" class="btn btn-quiet-danger od-inv-unlink-btn"
                                                    data-trigger="od-unlink-inventory"
                                                    data-order-id="${escapeAttr(orderId)}"
                                                    data-inventory-item-id="${escapeAttr(item.id)}"
                                                    title="${escapeAttr(MSG_INVENTORY_UNLINK_TOOLTIP)}">&times;</button>
                                        </td>`
                : '';

            html += `
                                    <tr class="${rowClass}">
                                        <td>${escapeHtml(ownerName)}</td>
                                        <td>${squadronCell}</td>
                                        <td>${escapeHtml(locationName)}</td>
                                        <td>${escapeHtml(quality)}</td>
                                        <td>${escapeHtml(quantity)}</td>
                                        ${unlinkCell}
                                    </tr>
                `;
        }

        html += `
                                </tbody>
                            </table>
                        </div>
                    </div>
                </td>
            `;

        detailsRow.innerHTML = html;
    } catch (error) {
        console.error('Error fetching inventory items:', error);
        detailsRow.innerHTML = `
                <td colspan="5" class="od-inv-cell">
                    <div class="text-danger">Fehler beim Laden der Lagereinträge.</div>
                </td>
            `;
    }
}

// ---- Tab navigation (REQ-ORDERS-026) — the mission-detail .tab-nav pattern ----------------------
// The overview + KPI band stay; the sections below live one per .tab-pane. Deeplink via ?tab= / #tab=
// (falls back to localStorage then the first present tab), ArrowLeft/ArrowRight roving-tabindex nav,
// aria-selected toggled at runtime. Panes are all server-rendered; switching only toggles `.on`.
(function () {
    const nav = document.querySelector('.tab-nav[role="tablist"]');
    if (!nav) {
        return;
    }
    const tabs = Array.prototype.slice.call(nav.querySelectorAll('.tab[data-tab]'));
    if (tabs.length === 0) {
        return;
    }
    const validKeys = tabs.map(function (t) {
        return t.getAttribute('data-tab');
    });
    const storeKey = 'orders-detail-tab';

    function apply(key) {
        tabs.forEach(function (t) {
            const on = t.getAttribute('data-tab') === key;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', String(on));
            t.tabIndex = on ? 0 : -1;
        });
        document.querySelectorAll('.tab-panes .tab-pane').forEach(function (p) {
            p.classList.toggle('on', p.id === 'pane-' + key);
        });
        try {
            localStorage.setItem(storeKey, key);
        } catch (_e) {
            /* private mode: no persistence */
        }
    }

    function resolveInitial(useStore) {
        const q = new URLSearchParams(window.location.search).get('tab');
        if (q && validKeys.indexOf(q) >= 0) {
            return q;
        }
        const h = (window.location.hash.match(/tab=([\w-]+)/) || [])[1];
        if (h && validKeys.indexOf(h) >= 0) {
            return h;
        }
        if (useStore) {
            try {
                const s = localStorage.getItem(storeKey);
                if (s && validKeys.indexOf(s) >= 0) {
                    return s;
                }
            } catch (_e) {
                /* private mode: no persistence */
            }
        }
        return validKeys[0];
    }

    function show(key, push) {
        if (validKeys.indexOf(key) < 0) {
            return;
        }
        apply(key);
        if (push) {
            const url = new URL(window.location);
            url.searchParams.set('tab', key);
            url.hash = '';
            history.pushState({ tab: key }, '', url);
        }
    }

    tabs.forEach(function (t) {
        t.addEventListener('click', function () {
            show(t.getAttribute('data-tab'), true);
        });
    });
    nav.addEventListener('keydown', function (e) {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') {
            return;
        }
        const i = tabs.indexOf(document.activeElement);
        if (i < 0) {
            return;
        }
        e.preventDefault();
        const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
        next.focus();
        show(next.getAttribute('data-tab'), true);
    });
    window.addEventListener('popstate', function () {
        apply(resolveInitial(false));
    });
    apply(resolveInitial(true));
})();

// ---- Herstellung (production booking, REQ-ORDERS-025) -------------------------------------------
// The modal lets a logistician record how many units of an ordered item were manufactured and, per
// required material, exactly which linked inventory entries the material was drawn from. The demand
// per material scales the line's snapshot (requiredTotal × k / lineAmount, rounded for the quantity
// type) exactly as the backend does; "buchen" is enabled only when every material is covered.
let _prodMaterials = [];
let _prodContext = null;

function _prodRound3(x) {
    return Math.round(x * 1000) / 1000;
}

function _prodRoundForType(x, quantityType) {
    return quantityType === 'PIECE' ? Math.round(x) : _prodRound3(x);
}

function _prodFmtNum(x, quantityType) {
    return quantityType === 'PIECE' ? String(Math.round(x)) : _prodRound3(x).toFixed(3);
}

function _prodFmtQty(x, quantityType) {
    return quantityType === 'PIECE'
        ? String(Math.round(x)) + ' ' + PRODUCTION_I18N.unitPiece
        : _prodRound3(x).toFixed(3) + ' ' + PRODUCTION_I18N.unitScu;
}

// Per-material demand for k units, mirroring the backend: sum the rounded per-row demands
// (roundForQuantityType(requiredTotal * k / lineAmount)) across every snapshot row that maps to this
// material. Rounding each row before summing — not summing the raw totals first — keeps the frontend
// "buchen" gate in lockstep with the backend's exact-coverage 422, because round(a)+round(b) can
// differ from round(a+b) for PIECE materials.
function _prodDemand(mat, k) {
    const lineAmount = _prodContext && _prodContext.lineAmount ? _prodContext.lineAmount : 1;
    return mat.requiredTotals.reduce(function (sum, rt) {
        return sum + _prodRoundForType((rt * k) / lineAmount, mat.quantityType);
    }, 0);
}

// Toggle the collapsible "Bedarf je Stück" demand sub-row in the Herstellung table (the chevron
// button carries aria-controls -> the sub-row's id). Mirrors the bank booking-detail toggle: flip
// aria-expanded and the target's hidden attribute; the page CSS rotates the chevron off
// [aria-expanded='true'].
function _odToggleDemandRow(control) {
    if (!control) {
        return;
    }
    const expanded = control.getAttribute('aria-expanded') === 'true';
    control.setAttribute('aria-expanded', expanded ? 'false' : 'true');
    const id = control.getAttribute('aria-controls');
    const target = id ? document.getElementById(id) : null;
    if (target) {
        target.hidden = expanded;
    }
}

function openProductionModal(button) {
    const orderId = button.getAttribute('data-order-id');
    const itemId = button.getAttribute('data-item-id');
    const itemName = button.getAttribute('data-item-name') || '';
    const lineAmount = parseInt(button.getAttribute('data-amount'), 10) || 0;
    const manufactured = parseInt(button.getAttribute('data-manufactured'), 10) || 0;
    const version = button.getAttribute('data-version');
    const remaining = Math.max(0, lineAmount - manufactured);

    _prodContext = { orderId, itemId, lineAmount, manufactured, version, remaining };
    _prodMaterials = [];

    const title = document.getElementById('production-modal-title');
    if (title) {
        title.textContent = PRODUCTION_I18N.record + ' — ' + itemName;
    }

    const amtInput = document.getElementById('production-amount');
    if (amtInput) {
        amtInput.max = remaining;
        amtInput.value = remaining > 0 ? 1 : 0;
        amtInput.oninput = _prodReconcile;
    }
    const hint = document.getElementById('production-remaining-hint');
    if (hint) {
        hint.textContent =
            PRODUCTION_I18N.remaining +
            ': ' +
            remaining +
            ' (' +
            manufactured +
            ' / ' +
            lineAmount +
            ' ' +
            PRODUCTION_I18N.alreadyMade +
            ')';
    }

    const container = document.getElementById('production-materials');
    if (container) {
        container.innerHTML = '';
    }

    const modal = document.getElementById('production-modal');
    if (modal) {
        modal.classList.add('krtm-modal-open');
        modal.classList.remove('krtm-hidden');
        modal.style.removeProperty('display');
    }

    const row = button.closest('tr');
    const demandLis = row ? row.querySelectorAll('.od-production-demand li') : [];
    // A line's material snapshot can carry more than one row for the same material — a blueprint that
    // lists an ingredient twice, or a RESOURCE plus a bridged non-craftable ITEM that map to the same
    // Material. The backend aggregates demand per material id (demandByMaterial.merge over the rounded
    // per-row demands); mirror that here so each distinct material yields exactly one card. Otherwise
    // two cards share a data-prod-material value, querySelector reads only the first, and the second
    // row's demand can never be reconciled — the "buchen" button stays disabled forever.
    const byMaterialId = new Map();
    demandLis.forEach(function (li) {
        const materialId = li.getAttribute('data-material-id');
        if (!materialId) {
            return;
        }
        const requiredTotal = parseFloat(li.getAttribute('data-required-total')) || 0;
        const existing = byMaterialId.get(materialId);
        if (existing) {
            // Keep the per-row totals separate so demand stays a sum of rounded rows (see _prodDemand).
            existing.requiredTotals.push(requiredTotal);
            return;
        }
        byMaterialId.set(materialId, {
            materialId: materialId,
            materialName: li.getAttribute('data-material-name') || '',
            quantityType: li.getAttribute('data-quantity-type') || 'SCU',
            requiredTotals: [requiredTotal],
            entries: [],
        });
    });
    byMaterialId.forEach(function (mat) {
        _prodMaterials.push(mat);
        const card = document.createElement('div');
        card.className = 'card card--inset mb-1';
        card.setAttribute('data-prod-material', mat.materialId);
        card.innerHTML =
            '<div class="card-head"><h3 class="card-title">' +
            escapeHtml(mat.materialName) +
            '</h3><span class="chip chip--muted" data-prod-chip></span></div>' +
            '<div data-prod-entries><p class="text-muted">' +
            escapeHtml(PRODUCTION_I18N.loadingStock) +
            '</p></div>';
        if (container) {
            container.appendChild(card);
        }
        fetch('/orders/' + orderId + '/materials/' + mat.materialId + '/inventory')
            .then(function (r) {
                return r.ok ? r.json() : [];
            })
            .then(function (items) {
                mat.entries = (items || [])
                    .map(function (it) {
                        const alloc = (it.jobOrderAllocations || []).find(function (a) {
                            return a.jobOrderId === orderId;
                        });
                        const slice = alloc && alloc.amount != null ? alloc.amount : 0;
                        return {
                            inventoryItemId: it.id,
                            ownerName: it.user ? it.user.effectiveName : '-',
                            location: it.location ? it.location.name : '-',
                            quality: it.quality != null ? it.quality : '-',
                            slice: slice,
                            stock: it.amount != null ? it.amount : 0,
                            version: it.version,
                        };
                    })
                    .filter(function (e) {
                        return e.slice > 0;
                    });
                _renderProdMaterialEntries(card, mat);
                _prodReconcile();
            })
            .catch(function () {
                _renderProdMaterialEntries(card, mat);
                _prodReconcile();
            });
    });
    _prodReconcile();
}

function _renderProdMaterialEntries(card, mat) {
    const entriesEl = card.querySelector('[data-prod-entries]');
    if (!entriesEl) {
        return;
    }
    if (mat.entries.length === 0) {
        entriesEl.innerHTML =
            '<p class="text-muted">' + escapeHtml(PRODUCTION_I18N.noStock) + '</p>';
        return;
    }
    let html = '';
    mat.entries.forEach(function (e, idx) {
        const cap = Math.min(e.slice, e.stock);
        html +=
            '<div class="od-prod-entry"><div class="od-prod-src">' +
            escapeHtml(e.ownerName) +
            ' · ' +
            escapeHtml(String(e.location)) +
            ' <small>' +
            escapeHtml(MSG_QUALITY) +
            ' ' +
            escapeHtml(String(e.quality)) +
            ' · ' +
            escapeHtml(PRODUCTION_I18N.available) +
            ' ' +
            escapeHtml(_prodFmtQty(cap, mat.quantityType)) +
            '</small></div><input type="number" min="0" step="' +
            (mat.quantityType === 'PIECE' ? '1' : 'any') +
            '" value="0" data-prod-alloc data-prod-idx="' +
            idx +
            '"></div>';
    });
    entriesEl.innerHTML = html;
    entriesEl.querySelectorAll('[data-prod-alloc]').forEach(function (inp) {
        inp.addEventListener('input', _prodReconcile);
    });
}

function _prodReconcile() {
    if (!_prodContext) {
        return;
    }
    const amtInput = document.getElementById('production-amount');
    let k = parseInt(amtInput ? amtInput.value : '0', 10);
    if (!isFinite(k) || k < 1) {
        k = 0;
    }
    let allCovered = k >= 1 && k <= _prodContext.remaining;
    _prodMaterials.forEach(function (mat) {
        const demand = _prodDemand(mat, k);
        const card = document.querySelector('[data-prod-material="' + mat.materialId + '"]');
        if (!card) {
            return;
        }
        let assigned = 0;
        card.querySelectorAll('[data-prod-alloc]').forEach(function (inp) {
            assigned += parseFloat(inp.value) || 0;
        });
        assigned = mat.quantityType === 'PIECE' ? Math.round(assigned) : _prodRound3(assigned);
        const rest = _prodRound3(demand - assigned);
        const chip = card.querySelector('[data-prod-chip]');
        if (chip) {
            chip.textContent = PRODUCTION_I18N.reconcile
                .replace('{0}', _prodFmtNum(demand, mat.quantityType))
                .replace('{1}', _prodFmtNum(assigned, mat.quantityType))
                .replace('{2}', _prodFmtNum(rest, mat.quantityType));
            chip.className =
                'chip ' +
                (Math.abs(rest) < 1e-4
                    ? 'chip--success'
                    : rest < 0
                      ? 'chip--danger'
                      : 'chip--muted');
        }
        if (Math.abs(rest) >= 1e-4) {
            allCovered = false;
        }
    });
    const bookBtn = document.getElementById('production-book-btn');
    if (bookBtn) {
        bookBtn.disabled = !allCovered;
    }
}

function bookProduction() {
    if (!_prodContext) {
        return;
    }
    const amtInput = document.getElementById('production-amount');
    const k = parseInt(amtInput ? amtInput.value : '0', 10);
    if (!isFinite(k) || k < 1 || k > _prodContext.remaining) {
        showFrontendErrorToast(PRODUCTION_I18N.allocError);
        return;
    }
    const consumption = [];
    let ok = true;
    _prodMaterials.forEach(function (mat) {
        const demand = _prodDemand(mat, k);
        let assigned = 0;
        const card = document.querySelector('[data-prod-material="' + mat.materialId + '"]');
        if (card) {
            card.querySelectorAll('[data-prod-alloc]').forEach(function (inp) {
                const v = parseFloat(inp.value) || 0;
                if (v > 0) {
                    const idx = parseInt(inp.getAttribute('data-prod-idx'), 10);
                    const entry = mat.entries[idx];
                    if (entry) {
                        consumption.push({
                            inventoryItemId: entry.inventoryItemId,
                            materialId: mat.materialId,
                            amount: v,
                            version: entry.version,
                        });
                        assigned += v;
                    }
                }
            });
        }
        assigned = mat.quantityType === 'PIECE' ? Math.round(assigned) : _prodRound3(assigned);
        if (Math.abs(demand - assigned) >= 1e-4) {
            ok = false;
        }
    });
    if (!ok) {
        showFrontendErrorToast(PRODUCTION_I18N.allocError);
        return;
    }

    krtOrderWrite({
        method: 'POST',
        url: '/orders/' + _prodContext.orderId + '/items/' + _prodContext.itemId + '/production',
        payload: {
            amount: k,
            version: _prodContext.version ? parseInt(_prodContext.version, 10) : null,
            consumption: consumption,
        },
        toast: false,
        errorMessage: PRODUCTION_I18N.allocError,
        onSuccess: function () {
            const modal = document.getElementById('production-modal');
            if (modal) {
                modal.classList.remove('krtm-modal-open');
                modal.classList.add('krtm-hidden');
            }
            showFrontendSuccessToast(PRODUCTION_I18N.booked);
            if (window.krtRefreshOrderSection) {
                window.krtRefreshOrderSection([
                    'items',
                    'aggregated',
                    'header',
                    'kpi',
                    'item-handovers',
                    'item-handover-lines',
                ]);
            }
        },
    });
}

// CSP-safe delegated bindings (replaces the 18 inline on*= handlers in this template).
// Modal open/close on style.display still uses the global open-modal-display /
// close-modal-display common-handlers; the rest call into page-local functions defined
// in this script and the inline script higher up.
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('change', 'od-update-status', function (el) {
        updateStatus(el);
    });
    window.krtEvents.on('change', 'od-toggle-bp-counting', function (el) {
        _toggleBlueprintCounting(el);
    });
    window.krtEvents.on('click', 'od-toggle-inventory', function (el, ev) {
        // Skip the row inventory-toggle when the click originated on a claim control or the
        // in-drill-down unlink button (both live inside the clickable material row); krtEvents
        // fires each data-trigger independently, so without this guard the unlink click would
        // also collapse the drill-down.
        if (
            ev &&
            ev.target &&
            typeof ev.target.closest === 'function' &&
            ev.target.closest('[data-claim-control], [data-trigger="od-unlink-inventory"]')
        ) {
            return;
        }
        toggleInventory(el);
    });
    window.krtEvents.on('click', 'od-unlink-inventory', function (el) {
        unlinkInventoryItem(el);
    });
    window.krtEvents.on('click', 'claim-add', function (el) {
        openClaimCreate(el);
    });
    window.krtEvents.on('click', 'claim-edit', function (el) {
        openClaimEdit(el);
    });
    window.krtEvents.on('click', 'claim-submit', function () {
        submitClaim();
    });
    window.krtEvents.on('click', 'claim-withdraw', function () {
        withdrawClaimAction();
    });
    window.krtEvents.on('click', 'od-open-handover', openHandoverModal);
    window.krtEvents.on('click', 'od-open-production', openProductionModal);
    window.krtEvents.on('click', 'od-book-production', bookProduction);
    window.krtEvents.on('click', 'od-toggle-demand', _odToggleDemandRow);
    window.krtEvents.on('click', 'od-download-report', function (el) {
        downloadHandoverReport(el);
    });
    window.krtEvents.on('click', 'od-download-item-report', function (el) {
        downloadItemHandoverReport(el);
    });
    window.krtEvents.on('click', 'od-cancel-status', cancelStatusChange);
    window.krtEvents.on('click', 'od-confirm-status', confirmStatusChange);
    window.krtEvents.on('click', 'od-confirm-remove-material', function () {
        if (typeof window.confirmRemoveMaterial === 'function') window.confirmRemoveMaterial();
    });
    window.krtEvents.on('click', 'od-add-handover-item', addHandoverItemRow);
    window.krtEvents.on('click', 'od-preview-handover', function (el) {
        previewHandoverReport(el);
    });
    window.krtEvents.on('click', 'od-remove-handover-row', function (el) {
        // Mirrors the historical inline `this.parentElement.parentElement.remove();
        // updateHandoverIndexes();` — climb two parents to reach the handover row, drop it,
        // re-index so the remaining rows keep contiguous form field names.
        const row = el.parentElement && el.parentElement.parentElement;
        if (row) row.remove();
        updateHandoverIndexes();
    });
    window.krtEvents.on('submit', 'od-disable-submit', function (el) {
        const btn = el.querySelector('button[type=submit]');
        if (btn) btn.disabled = true;
    });
}

// Bearbeiter (assignee) section — AJAX enroll / unenroll / note edit. Every mutation re-renders
// the whole #assignees-section fragment (server-rendered HTML swapped in via outerHTML), so the
// per-edge @Version values are always fresh and there is no manual data-version DOM sync. All
// bindings are delegated (document level), so they survive the fragment swap.
(function () {
    // CSRF header via the shared krtCsrf reader (#575) — retires the bespoke meta read. Not
    // krtCsrf.headers(), because that forces Content-Type application/json; the assignee calls
    // vary the content type (json / form / none) and accept an HTML fragment, so build just the
    // CSRF header here and keep oaSend's per-path content-type logic.
    function oaCsrf() {
        const headers = {};
        const token = window.krtCsrf
            ? window.krtCsrf.token()
            : document.querySelector('meta[name="_csrf"]')?.content;
        const header = window.krtCsrf
            ? window.krtCsrf.headerName()
            : document.querySelector('meta[name="_csrf_header"]')?.content;
        if (token && header) headers[header] = token;
        return headers;
    }

    function oaOrderId() {
        const sec = document.getElementById('assignees-section');
        return sec ? sec.getAttribute('data-order-id') : null;
    }

    async function oaSend(method, url, opts) {
        opts = opts || {};
        function buildInit() {
            const headers = oaCsrf();
            headers['Accept'] = 'text/html';
            const init = { method: method, headers: headers };
            if (opts.json !== undefined) {
                headers['Content-Type'] = 'application/json';
                init.body = JSON.stringify(opts.json);
            } else if (opts.form !== undefined) {
                headers['Content-Type'] = 'application/x-www-form-urlencoded';
                init.body = opts.form;
            }
            return init;
        }
        let res;
        try {
            res = await fetch(url, buildInit());
            // A bare 403 is the CSRF filter rejecting a stale token (post-re-login / evicted
            // session). Refresh the token once and retry before treating it as a domain
            // "forbidden" (#575) — matches krtFetch.write's retry-on-403.
            if (res.status === 403 && window.krtCsrf && window.krtCsrf.refresh) {
                const refreshed = await window.krtCsrf.refresh();
                if (refreshed) res = await fetch(url, buildInit());
            }
        } catch {
            showFrontendErrorToast(opts.errorMsg || I18N_NOTE_ERROR);
            return false;
        }
        if (res.status === 409) {
            showFrontendErrorToast(I18N_NOTE_CONFLICT);
            setTimeout(() => window.location.reload(), 1500);
            return false;
        }
        if (res.status === 403) {
            showFrontendErrorToast(I18N_NOTE_FORBIDDEN);
            return false;
        }
        if (!res.ok) {
            showFrontendErrorToast(opts.errorMsg || I18N_NOTE_ERROR);
            return false;
        }
        const html = await res.text();
        const sec = document.getElementById('assignees-section');
        if (sec) {
            sec.outerHTML = html;
            // Let global enhancers re-process the swapped-in subtree (mirrors krtFetch.swap,
            // which the bespoke outerHTML swap predates).
            document.dispatchEvent(
                new CustomEvent('krt:swapped', {
                    detail: { container: document.getElementById('assignees-section') },
                }),
            );
        }
        if (opts.successMsg) showFrontendSuccessToast(opts.successMsg);
        // Broadcast the assignee change to peers viewing this order — their order:{id} receiver
        // re-fetches the assignees fragment into #order-assignees-results (REQ-FE-015). The actor
        // already applied its own outerHTML swap above, so notify (broadcast-only), not refresh.
        if (window.krtNotifyOrderChanged) {
            window.krtNotifyOrderChanged(['assignees']);
        }
        return true;
    }

    function oaUpdateCounter() {
        const ta = document.getElementById('assignee-note-text');
        const counter = document.getElementById('assignee-note-counter');
        if (ta && counter) counter.textContent = ta.value.length + ' / 500';
    }

    function oaAdd(userId) {
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        oaSend('POST', '/orders/' + orderId + '/assignees', {
            form: 'userId=' + encodeURIComponent(userId),
            successMsg: I18N_ADDED,
            errorMsg: I18N_ADD_ERROR,
        });
    }

    function oaRemove(el) {
        const userId = el.getAttribute('data-user-id');
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        oaSend('DELETE', '/orders/' + orderId + '/assignees/' + userId, {
            successMsg: I18N_REMOVED,
            errorMsg: I18N_REMOVE_ERROR,
        });
    }

    function oaOpenNote(el) {
        const userId = el.getAttribute('data-user-id');
        const userName = el.getAttribute('data-user-name') || '';
        const note = el.getAttribute('data-note') || '';
        const version = el.getAttribute('data-version') || '';
        document.getElementById('assignee-note-user-id').value = userId;
        document.getElementById('assignee-note-version').value = version;
        const ta = document.getElementById('assignee-note-text');
        ta.value = note;
        document.getElementById('assignee-note-for').textContent = I18N_NOTE_FOR.replace(
            '{0}',
            userName,
        );
        oaUpdateCounter();
        document.getElementById('assignee-note-modal').style.display = 'flex';
        ta.focus();
    }

    function oaSaveNote() {
        const userId = document.getElementById('assignee-note-user-id').value;
        const version = document.getElementById('assignee-note-version').value;
        const note = document.getElementById('assignee-note-text').value;
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        const payload = { note: note, version: version ? parseInt(version, 10) : null };
        oaSend('PUT', '/orders/' + orderId + '/assignees/' + userId + '/note', {
            json: payload,
            successMsg: I18N_NOTE_SAVED,
        }).then((ok) => {
            if (ok) document.getElementById('assignee-note-modal').style.display = 'none';
        });
    }

    function oaDeleteNote(el) {
        const userId = el.getAttribute('data-user-id');
        const version = el.getAttribute('data-version') || '';
        const orderId = oaOrderId();
        if (!userId || !orderId) return;
        const q = version ? '?version=' + encodeURIComponent(version) : '';
        oaSend('DELETE', '/orders/' + orderId + '/assignees/' + userId + '/note' + q, {
            successMsg: I18N_NOTE_DELETED,
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const ta = document.getElementById('assignee-note-text');
        if (ta) ta.addEventListener('input', oaUpdateCounter);
    });

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'oa-add-me', function (el) {
            oaAdd(el.getAttribute('data-user-id'));
        });
        window.krtEvents.on('click', 'oa-add-user', function () {
            const sel = document.getElementById('assignee-add-select');
            if (sel && sel.value) oaAdd(sel.value);
        });
        window.krtEvents.on('click', 'oa-remove-assignee', function (el) {
            oaRemove(el);
        });
        window.krtEvents.on('click', 'oa-edit-note', function (el) {
            oaOpenNote(el);
        });
        window.krtEvents.on('click', 'oa-save-note', function () {
            oaSaveNote();
        });
        window.krtEvents.on('click', 'oa-delete-note', function (el) {
            oaDeleteNote(el);
        });
    }
})();
