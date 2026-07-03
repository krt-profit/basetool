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

/* global MSG_HANDOVER_SUCCESS, MSG_HANDOVER_FAILED, MSG_HANDOVER_NOITEMS, labelPiece, labelScu, scuHintText, labelMenge, ORDER_AGE_YELLOW, ORDER_AGE_RED, MSG_UNIT_SCU, MSG_UNIT_PIECE, MSG_STATUS_SUCCESS, MSG_STATUS_ERROR, ORDER_CONFLICT, MSG_DELETE_TITLE, MSG_DELETE_MESSAGE, MSG_DELETE_CONFIRM, MSG_DELETE_CANCEL, MSG_DELETE_ERROR, MSG_UPDATE_SUCCESS, MSG_UPDATE_ERROR, MSG_MATERIAL_INVALID, MSG_CLAIM_TITLE_ADD, MSG_CLAIM_TITLE_EDIT, MSG_CLAIM_MAX_HINT, MSG_QUALITY_GOOD, MSG_QUALITY_NONE, MSG_CLAIM_SUCCESS, MSG_CLAIM_WITHDRAW_SUCCESS, MSG_CLAIM_ERROR, MSG_CLAIM_VALIDATION_SQUADRON, MSG_CLAIM_VALIDATION_AMOUNT, MSG_CLAIM_VALIDATION_OVERCLAIM, MSG_BP_COUNTING_SUCCESS, MSG_BP_COUNTING_ERROR, MSG_HANDOVER_REPORT_ERROR, MSG_HANDOVER_REPORT_VALIDATION_DATE, MSG_HANDOVER_REPORT_VALIDATION_TIME, MSG_HANDOVER_REPORT_VALIDATION_HANDLE, MSG_HANDOVER_REPORT_VALIDATION_ITEMS, MSG_HANDOVER_REPORT_VALIDATION_AMOUNT, MSG_OWNER, MSG_LOCATION, MSG_QUALITY, MSG_QUANTITY, MSG_SQUADRON, MSG_LOADING_INVENTORY, MSG_EMPTY_INVENTORY, MSG_INVENTORY_UNLINK_TOOLTIP, MSG_INVENTORY_UNLINK_SUCCESS, MSG_INVENTORY_UNLINK_ERROR, IS_LOGISTICIAN, ORDER_REQUESTING_SQUADRON_ID, I18N_ADDED, I18N_REMOVED, I18N_NOTE_SAVED, I18N_NOTE_DELETED, I18N_ADD_ERROR, I18N_REMOVE_ERROR, I18N_NOTE_ERROR, I18N_NOTE_CONFLICT, I18N_NOTE_FORBIDDEN, I18N_NOTE_FOR, showFrontendErrorToast, showFrontendSuccessToast */

let cachedInventoryItems = [];
let isInventoryCached = false;

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
            items.push({ inventoryItemId: inventoryItemId, amount: amount });
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
                <label class="form-label-sm">${escapeHtml(labelMenge)} <span data-role="amount-unit"></span> <span class="scu-hint" data-role="scu-hint" tabindex="0" role="img" aria-label="${escapeAttr(scuHintText)}" style="display:none;"><span aria-hidden="true">?</span><span class="scu-hint__bubble" aria-hidden="true">${escapeHtml(scuHintText)}</span></span></label>
                <input type="text" inputmode="decimal" data-scu-decimal step="0.001" name="items[${index}].amount" min="0.001" required class="w-full">
            </div>
            <div>
                <button type="button" class="btn btn-quiet-danger btn-icon" style="padding: 0.5rem;" data-trigger="od-remove-handover-row" title="Entfernen" aria-label="Entfernen"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg></button>
            </div>
        `;
    const sel = row.querySelector('select');
    const amtInput = row.querySelector('input[data-scu-decimal]');
    if (sel && amtInput) {
        sel.addEventListener('change', () => {
            const inv = cachedInventoryItems.find((i) => i.id === sel.value);
            if (inv) {
                amtInput.max = inv.amount;
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
                if (rowScuHint) rowScuHint.style.display = 'none';
            } else if (qt === 'SCU') {
                amtInput.setAttribute('step', '0.001');
                amtInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '(' + labelScu + ')';
                if (rowScuHint) rowScuHint.style.display = '';
            } else {
                amtInput.setAttribute('step', '0.001');
                amtInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '';
                if (rowScuHint) rowScuHint.style.display = 'none';
            }
        });
    }
    container.appendChild(row);
}

function validateHandoverAmounts() {
    const rows = document.querySelectorAll('#handover-items-container .handover-item-row');
    for (const row of rows) {
        const sel = row.querySelector('select');
        const inp = row.querySelector('input[data-scu-decimal]');
        if (!sel || !sel.value || !inp || !inp.value) continue;
        const inv = cachedInventoryItems.find((i) => i.id === sel.value);
        if (!inv) continue;
        const entered = window.krtScuInput.parse(inp.value);
        if (entered > inv.amount) {
            const isPiece = inv.material && inv.material.quantityType === 'PIECE';
            const available = isPiece ? inv.amount.toFixed(0) : inv.amount.toFixed(3);
            const materialName = inv.material ? inv.material.name : sel.value;
            const msg = MSG_HANDOVER_REPORT_VALIDATION_AMOUNT.replace('{0}', materialName).replace(
                '{1}',
                available,
            );
            showFrontendErrorToast(msg);
            return false;
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
                    // row) and the header (status select + version, if the order auto-completed).
                    _refreshMaterialsSection(orderId);
                    _swapOrderSection(orderId, 'order-handovers-results', 'handovers');
                    _swapOrderSection(orderId, 'order-header-results', 'header');
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
                if (hint) hint.style.display = 'none';
            } else if (qt === 'SCU') {
                amountInput.setAttribute('step', '0.001');
                amountInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_SCU + ')';
                if (hint) hint.style.display = '';
            } else {
                amountInput.setAttribute('step', '0.001');
                amountInput.setAttribute('min', '0.001');
                if (unitSpan) unitSpan.textContent = '';
                if (hint) hint.style.display = 'none';
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
        await krtOrderWrite({
            method: 'POST',
            url: '/orders/' + orderId + '/update',
            payload: payload,
            toast: false,
            errorMessage: MSG_UPDATE_ERROR,
            onSuccess: function (dto) {
                const editVer = document.querySelector('#edit-modal input[name="version"]');
                if (editVer && dto && dto.version != null) editVer.value = dto.version;
                const modal = document.getElementById('edit-modal');
                if (modal) modal.style.display = 'none';
                showFrontendSuccessToast(MSG_UPDATE_SUCCESS);
                const header = document.getElementById('order-header-results');
                if (header && window.krtFetch.swap) {
                    window.krtFetch.swap({
                        url: '/orders/' + orderId,
                        container: header,
                        fragmentValue: 'header',
                        history: false,
                        preserveScroll: true,
                    });
                }
                _refreshMaterialsSection(orderId);
            },
        });
    });
})();

// Re-render the material section in place (#575) after a claim create/edit/withdraw OR an
// inventory unlink. The "Offen" open-amount + the per-row stock/status cells are backend-derived
// and not returned per write, so a partial DOM patch would desync them — re-pull the whole table
// fragment instead. MATERIAL orders render the requirement table on #order-materials-results,
// ITEM orders the aggregated table on #order-aggregated-results; swap whichever exists.
function _refreshMaterialsSection(orderId) {
    orderId = orderId || (document.getElementById('claim-order-id') || {}).value;
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
// @Version) in place.
function _swapOrderSection(orderId, containerId, fragmentValue) {
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
        document.getElementById('claim-withdraw-btn').style.display = '';
    } else {
        document.getElementById('claim-modal-title').textContent = MSG_CLAIM_TITLE_ADD;
        sel.value = '';
        sel.disabled = false;
        amountInput.value = '';
        document.getElementById('claim-max').value = open;
        document.getElementById('claim-open-hint').textContent = MSG_CLAIM_MAX_HINT + ' ' + open;
        document.getElementById('claim-withdraw-btn').style.display = 'none';
    }
    const claimScuHint = document.getElementById('claim-scu-hint');
    if (claimScuHint) claimScuHint.style.display = opts.quantityType === 'PIECE' ? 'none' : '';
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
            // (mirrors the edit flow). The swap also re-renders the delegated status select with a
            // fresh @Version.
            const header = document.getElementById('order-header-results');
            if (header && window.krtFetch.swap) {
                window.krtFetch.swap({
                    url: '/orders/' + orderId,
                    container: header,
                    fragmentValue: 'header',
                    history: false,
                    preserveScroll: true,
                });
            }
            // COMPLETED/REJECTED additionally detach linked inventory server-side (lowering the
            // per-material stock + fulfilment cells); re-pull the materials section and drop any
            // open inventory drill-down (lazily re-fetched on the next row click).
            if (status === 'COMPLETED' || status === 'REJECTED') {
                document.querySelectorAll('.inventory-details-row').forEach(function (r) {
                    r.remove();
                });
                _refreshMaterialsSection(orderId);
            }
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
            <td colspan="5" style="padding: 1rem; background: var(--color-surface-input);">
                <div style="font-style: italic; color: var(--color-gray-2);">${MSG_LOADING_INVENTORY}</div>
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
                    <td colspan="5" style="padding: 1rem; background: var(--color-surface-input);">
                        <div style="font-style: italic; color: var(--color-gray-2);">${MSG_EMPTY_INVENTORY}</div>
                    </td>
                `;
            return;
        }

        const unlinkColHeader = IS_LOGISTICIAN
            ? `<th style="background: var(--color-gray-4); font-size: 0.85rem;"></th>`
            : '';
        const subColspan = IS_LOGISTICIAN ? 7 : 6;

        let html = `
                <td colspan="${subColspan}" class="p-0">
                    <div style="background: var(--color-surface-input); border: 1px solid var(--color-gray-3); border-top: none;">
                        <div class="table-responsive">
                            <table class="data-table" style="margin: 0; border: none; background: transparent;">
                                <thead>
                                    <tr>
                                        <th style="background: var(--color-gray-4); font-size: 0.85rem;">${MSG_OWNER}</th>
                                        <th style="background: var(--color-gray-4); font-size: 0.85rem;">${MSG_SQUADRON}</th>
                                        <th style="background: var(--color-gray-4); font-size: 0.85rem;">${MSG_LOCATION}</th>
                                        <th style="background: var(--color-gray-4); font-size: 0.85rem;">${MSG_QUALITY}</th>
                                        <th style="background: var(--color-gray-4); font-size: 0.85rem;">${MSG_QUANTITY}</th>
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
                                            <button type="button" class="btn btn-quiet-danger"
                                                    data-trigger="od-unlink-inventory"
                                                    data-order-id="${escapeAttr(orderId)}"
                                                    data-inventory-item-id="${escapeAttr(item.id)}"
                                                    style="padding: 0.1rem 0.5rem; font-size: 0.8rem; min-height: 44px;"
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
                <td colspan="5" style="padding: 1rem; background: var(--color-surface-input);">
                    <div class="text-danger">Fehler beim Laden der Lagereinträge.</div>
                </td>
            `;
    }
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
