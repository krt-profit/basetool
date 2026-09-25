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

/* global MSG_UNIT_PIECE, MSG_UNIT_SCU, INV_ADD_MSG, INV_ORDER_NEED_MSG */

document.addEventListener('DOMContentLoaded', function () {
    const matSelect = document.getElementById('materialId');
    if (matSelect) {
        matSelect.addEventListener('change', function () {
            filterOrderSelects(this.value);
            updateAmountFieldForMaterial(this);
        });
        if (matSelect.value) {
            updateAmountFieldForMaterial(matSelect);
        }
    }

    const gameItemSelect = document.getElementById('gameItemId');
    if (gameItemSelect) {
        gameItemSelect.addEventListener('change', function () {
            filterOrderSelects(this.value);
            syncGameItemNameEcho(this);
        });
    }

    const qualityInput = document.getElementById('quality');
    if (qualityInput) {
        qualityInput.addEventListener('input', relabelOrderOptions);
    }

    const personalToggle = document.getElementById('personal');
    if (personalToggle) {
        personalToggle.addEventListener('change', syncPersonalAllocations);
    }

    document.addEventListener('click', function (event) {
        if (event.target.closest('[data-trigger="inv-input-add-order"]')) {
            addAllocRow('jobOrder');
        } else if (event.target.closest('[data-trigger="inv-input-add-mission"]')) {
            addAllocRow('mission');
        } else if (event.target.closest('[data-trigger="inv-input-remove-alloc"]')) {
            removeAllocRow(event.target.closest('[data-trigger="inv-input-remove-alloc"]'));
        }
    });
    document.addEventListener('input', function (event) {
        if (event.target.matches('[data-alloc-amount]') || event.target.id === 'amount') {
            updateAllocOver();
        }
    });
    document.addEventListener('change', function (event) {
        if (event.target.matches('[data-alloc-target]')) {
            updateAllocOver();
        } else if (event.target.matches('[data-trigger="inv-input-mode-toggle"]')) {
            applyCatalogMode();
        }
    });

    applyCatalogMode();

    startOrderNeedsLiveSync();
});

const INVENTORY_INPUT_ORDER_SECTIONS = {
    demand: { container: '#jobOrderAllocGroup', fragmentValue: 'demand' },
};

function startOrderNeedsLiveSync() {
    if (
        !window.krtLiveSync ||
        typeof window.krtLiveSync.createReceiver !== 'function' ||
        !document.getElementById('jobOrderAllocGroup')
    ) {
        return;
    }
    window.krtLiveSync.createReceiver({
        topic: 'orders',
        sections: INVENTORY_INPUT_ORDER_SECTIONS,
        coalesceMs: 1500,
        refresh: refreshOrderNeeds,
    });
}

function refreshOrderNeeds() {
    fetch('/inventory/order-needs', {
        headers: { 'X-Requested-With': 'XMLHttpRequest', Accept: 'application/json' },
    })
        .then(function (response) {
            return response.ok ? response.json() : null;
        })
        .then(function (needs) {
            if (!needs) return;
            orderNeeds = needs;
            relabelOrderOptions();
        })
        .catch(function () {});
}

function currentCatalogMode() {
    const checked = document.querySelector('input[name="inventoryCatalogMode"]:checked');
    return checked && checked.value === 'item' ? 'item' : 'material';
}

function activeCatalogId() {
    const el = document.getElementById(
        currentCatalogMode() === 'item' ? 'gameItemId' : 'materialId',
    );
    return el ? el.value : '';
}

function setCatalogBlockDisabled(blockId, disabled) {
    const block = document.getElementById(blockId);
    if (!block) return;
    block.querySelectorAll('input, select, textarea, button').forEach(function (el) {
        el.disabled = disabled;
    });
}

function applyCatalogMode() {
    const mode = currentCatalogMode();
    const materialBlock = document.getElementById('mode-material-fields');
    const itemBlock = document.getElementById('mode-item-fields');
    if (materialBlock) materialBlock.hidden = mode !== 'material';
    if (itemBlock) itemBlock.hidden = mode !== 'item';
    setCatalogBlockDisabled('mode-material-fields', mode !== 'material');
    setCatalogBlockDisabled('mode-item-fields', mode !== 'item');
    if (mode === 'item') {
        applyItemAmountMode();
    } else {
        const matSelect = document.getElementById('materialId');
        if (matSelect) updateAmountFieldForMaterial(matSelect);
    }
    syncPersonalAllocations();
    filterOrderSelects(activeCatalogId());
}

function applyItemAmountMode() {
    const amountInput = document.getElementById('amount');
    const unitSpan = document.getElementById('amount-unit');
    const scuHint = document.getElementById('amount-scu-hint');
    if (amountInput) amountInput.setAttribute('step', '1');
    if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_PIECE + ')';
    if (scuHint) scuHint.classList.add('krtm-hidden');
    updateMergeOptIn('PIECE');
}

function syncGameItemNameEcho(control) {
    const echo = document.getElementById('gameItemName');
    if (!echo) return;
    let label = '';
    const wrapper = control.closest('.krt-combobox');
    if (wrapper) {
        const textbox = wrapper.querySelector('.krt-combobox__input');
        label = textbox ? textbox.value.trim() : '';
    } else if (control.tagName === 'SELECT' && control.selectedOptions[0]) {
        label = control.selectedOptions[0].textContent.trim();
    }
    echo.value = label;
}

function updateAmountFieldForMaterial(selectElement) {
    const amountInput = document.getElementById('amount');
    const unitSpan = document.getElementById('amount-unit');
    const scuHint = document.getElementById('amount-scu-hint');
    if (!amountInput) return;

    let qtType = selectElement.dataset.quantityType || '';
    if (!qtType && selectElement.tagName === 'SELECT') {
        const opt = selectElement.selectedOptions && selectElement.selectedOptions[0];
        qtType = (opt && opt.getAttribute('data-quantity-type')) || '';
    }

    if (qtType === 'PIECE') {
        amountInput.setAttribute('step', '1');
        unitSpan.textContent = '(' + MSG_UNIT_PIECE + ')';
        if (scuHint) scuHint.classList.add('krtm-hidden');
    } else if (qtType === 'SCU') {
        amountInput.setAttribute('step', '0.001');
        unitSpan.textContent = '(' + MSG_UNIT_SCU + ')';
        if (scuHint) scuHint.classList.remove('krtm-hidden');
    } else {
        amountInput.setAttribute('step', '0.001');
        unitSpan.textContent = '';
        if (scuHint) scuHint.classList.add('krtm-hidden');
    }

    updateMergeOptIn(qtType);
}

function updateMergeOptIn(qtType) {
    const mergeRow = document.getElementById('merge-stock-row');
    const mergeCheckbox = document.getElementById('mergeStock');
    if (!mergeRow) return;
    if (qtType === 'SCU') {
        mergeRow.classList.remove('krtm-hidden');
    } else {
        mergeRow.classList.add('krtm-hidden');
        if (mergeCheckbox) mergeCheckbox.checked = false;
    }
}

function allocConfig(dimension) {
    return dimension === 'jobOrder'
        ? {
              rows: 'jobOrderAllocRows',
              template: 'jobOrderRowTemplate',
              prefix: 'jobOrderAllocations',
              group: 'jobOrderAllocGroup',
              singleHint: 'jobOrderAllocSingleHint',
          }
        : {
              rows: 'missionAllocRows',
              template: 'missionRowTemplate',
              prefix: 'missionAllocations',
              group: 'missionAllocGroup',
              singleHint: 'missionAllocSingleHint',
          };
}

function allocTargetedRows(dimension) {
    return Array.prototype.filter.call(
        document.querySelectorAll('#' + allocConfig(dimension).rows + ' [data-alloc-target]'),
        function (select) {
            return !!select.value;
        },
    );
}

function updateAllocSingleHint(dimension) {
    const hint = document.getElementById(allocConfig(dimension).singleHint);
    if (!hint) return;
    hint.classList.toggle('krtm-hidden', allocTargetedRows(dimension).length > 1);
}

function reindexAllocRows(dimension) {
    const cfg = allocConfig(dimension);
    const container = document.getElementById(cfg.rows);
    if (!container) return;
    container.querySelectorAll('[data-alloc-row]').forEach(function (row, index) {
        const select = row.querySelector('[data-alloc-target]');
        const amount = row.querySelector('[data-alloc-amount]');
        if (select) select.name = cfg.prefix + '[' + index + '].targetId';
        if (amount) amount.name = cfg.prefix + '[' + index + '].amount';
    });
}

function addAllocRow(dimension) {
    const cfg = allocConfig(dimension);
    const container = document.getElementById(cfg.rows);
    const template = document.getElementById(cfg.template);
    if (!container || !template) return;
    container.appendChild(template.content.cloneNode(true));
    reindexAllocRows(dimension);
    if (dimension === 'jobOrder') {
        filterOrderSelects(activeCatalogId());
    }
    updateAllocOver();
}

function removeAllocRow(button) {
    const row = button.closest('[data-alloc-row]');
    if (!row) return;
    const missionContainer = document.getElementById('missionAllocRows');
    const dimension = missionContainer && missionContainer.contains(row) ? 'mission' : 'jobOrder';
    row.remove();
    reindexAllocRows(dimension);
    updateAllocOver();
}

function filterOrderSelects(catalogId) {
    const attr = currentCatalogMode() === 'item' ? 'data-game-items' : 'data-materials';
    document.querySelectorAll('#jobOrderAllocRows [data-alloc-target]').forEach(function (select) {
        let hasSelectedValid = false;
        for (let i = 1; i < select.options.length; i++) {
            const option = select.options[i];
            const idsStr = option.getAttribute(attr);
            const compatible = !catalogId || (idsStr && idsStr.split(',').includes(catalogId));
            option.style.display = compatible ? '' : 'none';
            option.disabled = !compatible;
            if (compatible && option.selected) hasSelectedValid = true;
        }
        if (select.selectedIndex > 0 && !hasSelectedValid) select.value = '';
    });
    relabelOrderOptions();
    updateAllocOver();
}

let orderNeeds = readEmbeddedOrderNeeds();

function readEmbeddedOrderNeeds() {
    const group = document.getElementById('jobOrderAllocGroup');
    const raw = group ? group.getAttribute('data-order-needs') : '';
    if (!raw) return {};
    try {
        return JSON.parse(raw) || {};
    } catch (_malformed) {
        return {};
    }
}

function needsMapFor(itemMode) {
    const key = itemMode ? 'gameItems' : 'materials';
    return (orderNeeds && orderNeeds[key]) || {};
}

function orderNeedFor(orderId, materialId) {
    const buckets = needsMapFor(false)[orderId];
    if (!buckets || !buckets.length || !materialId) return null;
    let outstanding = 0;
    let floor = null;
    let matched = false;
    buckets.forEach(function (bucket) {
        if (bucket.materialId !== materialId) return;
        matched = true;
        if (typeof bucket.outstandingAmount === 'number') outstanding += bucket.outstandingAmount;
        if (
            typeof bucket.qualityFloor === 'number' &&
            (floor === null || bucket.qualityFloor > floor)
        ) {
            floor = bucket.qualityFloor;
        }
    });
    return matched ? { outstanding, floor } : null;
}

function orderItemNeedFor(orderId, gameItemId) {
    const needs = needsMapFor(true)[orderId];
    if (!needs || !needs.length || !gameItemId) return null;
    const match = needs.find(function (need) {
        return need.gameItemId === gameItemId;
    });
    if (!match) return null;
    return {
        outstanding: typeof match.outstandingAmount === 'number' ? match.outstandingAmount : 0,
        floor: null,
    };
}

function pickedMaterialQuantityType() {
    const el = document.getElementById('materialId');
    if (!el) return '';
    if (el.dataset && el.dataset.quantityType) return el.dataset.quantityType;
    const opt = el.selectedOptions && el.selectedOptions[0];
    return (opt && opt.getAttribute('data-quantity-type')) || '';
}

function formatNeedAmount(amount, isPiece) {
    const n = typeof amount === 'number' ? amount : parseFloat(amount);
    if (isNaN(n)) return '0';
    return isPiece ? String(Math.round(n)) : n.toFixed(3);
}

function relabelOrderOptions() {
    const itemMode = currentCatalogMode() === 'item';
    const catalogId = activeCatalogId();
    const isPiece = itemMode || pickedMaterialQuantityType() === 'PIECE';
    const unit = isPiece ? MSG_UNIT_PIECE : MSG_UNIT_SCU;
    const grade = itemMode ? NaN : parseInt((document.getElementById('quality') || {}).value, 10);
    document.querySelectorAll('#jobOrderAllocRows [data-alloc-target]').forEach(function (select) {
        for (let i = 1; i < select.options.length; i++) {
            applyOrderOptionLabel(select.options[i], catalogId, itemMode, isPiece, unit, grade);
        }
    });
    const template = document.getElementById('jobOrderRowTemplate');
    if (template && template.content) {
        template.content.querySelectorAll('[data-alloc-target] option').forEach(function (option) {
            if (option.value) {
                applyOrderOptionLabel(option, catalogId, itemMode, isPiece, unit, grade);
            }
        });
    }
}

function applyOrderOptionLabel(option, catalogId, itemMode, isPiece, unit, grade) {
    if (!option.dataset.baseLabel) option.dataset.baseLabel = option.textContent;
    const base = option.dataset.baseLabel;
    const need = !catalogId
        ? null
        : itemMode
          ? orderItemNeedFor(option.value, catalogId)
          : orderNeedFor(option.value, catalogId);
    if (!need) {
        option.textContent = base;
        return;
    }
    const parts = [];
    parts.push(
        need.outstanding > 0
            ? INV_ORDER_NEED_MSG.outstanding.replace(
                  '{0}',
                  formatNeedAmount(need.outstanding, isPiece) + ' ' + unit,
              )
            : INV_ORDER_NEED_MSG.covered,
    );
    if (need.floor !== null && !isNaN(grade) && grade < need.floor) {
        parts.push(INV_ORDER_NEED_MSG.qualityFloor.replace('{0}', String(need.floor)));
    }
    option.textContent = base + ' · ' + parts.join(' · ');
}

function allocDimensionSum(dimension) {
    let sum = 0;
    document
        .querySelectorAll('#' + allocConfig(dimension).rows + ' [data-alloc-amount]')
        .forEach(function (input) {
            const value = parseFloat(input.value);
            if (!isNaN(value)) sum += value;
        });
    return sum;
}

function updateAllocOver() {
    updateAllocSingleHint('jobOrder');
    updateAllocSingleHint('mission');
    const overEl = document.getElementById('inputAllocOver');
    if (!overEl) return;
    const amountEl = document.getElementById('amount');
    const raw = amountEl ? amountEl.value : '';
    const amount = window.krtScuInput && amountEl ? window.krtScuInput.parse(raw) : parseFloat(raw);
    const total = isNaN(amount) ? 0 : amount;
    const over =
        allocDimensionSum('jobOrder') > total + 1e-6 || allocDimensionSum('mission') > total + 1e-6;
    overEl.classList.toggle('krtm-hidden', !over);
}

function syncPersonalAllocations() {
    const personal = document.getElementById('personal');
    const on = !!(personal && personal.checked);
    const itemMode = currentCatalogMode() === 'item';
    ['jobOrder', 'mission'].forEach(function (dimension) {
        const cfg = allocConfig(dimension);
        const hide = on || (dimension === 'mission' && itemMode);
        const group = document.getElementById(cfg.group);
        if (group) group.classList.toggle('krtm-hidden', hide);
        if (hide) {
            const container = document.getElementById(cfg.rows);
            if (container) container.innerHTML = '';
        }
    });
    updateAllocOver();
}

(function () {
    if (!window.krtFetch) return;
    const form = document.getElementById('inventory-input-form');
    if (!form) return;
    form.addEventListener('submit', function (event) {
        event.preventDefault();
        window.krtFetch.submitForm({
            form,
            submitter: form.querySelector('button[type="submit"]'),
            toast: false,
            errorMessage: INV_ADD_MSG.failed,
            onError(status, problem) {
                let msg = INV_ADD_MSG.failed;
                const ownerRequired =
                    window.krtFetch && window.krtFetch.ownerOrgUnitRequiredMessage
                        ? window.krtFetch.ownerOrgUnitRequiredMessage(problem)
                        : null;
                if (ownerRequired) msg = ownerRequired;
                else if (problem && problem.code === 'INVENTORY_PERSONAL_ASSIGNMENT')
                    msg = INV_ADD_MSG.personalAssignment;
                else if (problem && problem.code === 'VALIDATION') msg = INV_ADD_MSG.validation;
                else if (problem && problem.detail) msg = problem.detail;
                if (window.showFrontendErrorToast) window.showFrontendErrorToast(msg);
                return true;
            },
            onSuccess(body) {
                const target = body && body.targetUrl ? body.targetUrl : '/inventory';
                window.location.assign(
                    target +
                        (target.indexOf('?') >= 0 ? '&' : '?') +
                        'success=success.inventory.add',
                );
            },
        });
    });
})();
