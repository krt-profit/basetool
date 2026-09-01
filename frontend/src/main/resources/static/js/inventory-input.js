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
 * Inventory-input page module (/inventory/input), extracted verbatim from the former two inline
 * scripts of inventory-input.html (ADR-0069, follow-up to #924).
 *
 * First: mirrors the amount field to the material's quantity type (PIECE integer step vs. SCU 0.001
 * step + hint) and drives the Variante-C split-at-check-in rows (REQ-INV-027, R4) — repeatable
 * job-order / mission allocation rows cloned from hidden templates and bound as indexed form params
 * (jobOrderAllocations[i].targetId / .amount), each order row filtered to the chosen material, with
 * an over-allocation hint and a personal-toggle that clears/hides the sections. A dimension naming
 * exactly one target may leave its amount blank — the write controller then earmarks the whole entry
 * amount to it; the per-dimension shorthand hint is hidden as soon as a second target is named.
 * Second (an IIFE): the #577 in-place book-into-inventory submit through krtFetch.submitForm —
 * navigate-after-AJAX on success, a code-specific inline error toast on failure; the classic
 * POST->redirect stays the no-JS fallback. Both former blocks always render, so they are combined
 * into this single module in document order.
 *
 * The Material <-> Item catalog-mode toggle (design §6.2, REQ-INV-029) swaps only the
 * catalog-specific blocks of the single shared form: the inactive block is hidden AND its controls
 * disabled (so `required` never blocks submit and disabled controls stay out of the POST — the
 * client half of the materialId/gameItemId XOR). Item mode books whole units (PIECE semantics:
 * step 1, Stück unit, no SCU hint, no merge opt-in — items always auto-merge), has no mission
 * allocation section (REQ-INV-031) and filters the order rows by the option's data-game-items CSV
 * instead of data-materials.
 *
 * MSG_UNIT_PIECE / MSG_UNIT_SCU and the INV_ADD_MSG error strings are defined by the inline Thymeleaf
 * bootstrap block of inventory-input.html, which executes immediately before this classic script.
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

    // Item mode (design §6.2): the remote-game-items picker re-filters the order rows by
    // data-game-items and mirrors its committed label into the gameItemName echo, which seeds the
    // picker's option on a classic-path validation redisplay (no by-id catalog resolve exists).
    const gameItemSelect = document.getElementById('gameItemId');
    if (gameItemSelect) {
        gameItemSelect.addEventListener('change', function () {
            filterOrderSelects(this.value);
            syncGameItemNameEcho(this);
        });
    }

    // REQ-INV-039: the quality-floor marker on an order option is relative to the grade being
    // booked, so a changed grade re-decides which options carry it.
    const qualityInput = document.getElementById('quality');
    if (qualityInput) {
        qualityInput.addEventListener('input', relabelOrderOptions);
    }

    const personalToggle = document.getElementById('personal');
    if (personalToggle) {
        personalToggle.addEventListener('change', syncPersonalAllocations);
    }

    // Delegated controls for the Variante-C split-at-check-in rows (REQ-INV-027, R4).
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

    // Apply the server-derived initial mode (item on a gameItemId-carrying redisplay) so the
    // inactive block starts disabled and the amount/allocation wiring matches the visible catalog.
    applyCatalogMode();

    startOrderNeedsLiveSync();
});

// #1740 (REQ-FE-010, REQ-INV-039): the need figures go stale the moment a peer books stock against
// one of these orders, records a handover, or edits a line — so the form joins the global `orders`
// room and re-reads them.
//
// It reuses that room's existing `demand` section rather than introducing a key: this page renders
// a SUBSET of what the room already invalidates (the material-collection precedent), and `demand`
// is poked by exactly the writers that move this figure — orders-detail on any queue-visible
// mutation, and both Lager pages after an order-linked stock write.
//
// The refresh replaces the map and re-labels rather than swapping a fragment. A swap cannot reach
// the cloned rows, and it would take a picked order and a typed amount with it.
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

// Re-reads the figures through the frontend relay (a page script cannot reach /api/v1 on this
// origin) and re-labels in place. A failed read leaves the standing labels alone: a figure that is
// a few seconds old beats a picker that suddenly says nothing.
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
        .catch(function () {
            /* keep the standing labels */
        });
}

// ===== Material <-> Item catalog-mode toggle (design §6.2) =====

// Resolves the active catalog mode from the radio pair; defaults to material.
function currentCatalogMode() {
    const checked = document.querySelector('input[name="inventoryCatalogMode"]:checked');
    return checked && checked.value === 'item' ? 'item' : 'material';
}

// The active mode's picked catalog id ('' when nothing is picked yet). Reads the enhanced
// combobox's hidden input via getElementById — both pickers keep their original ids (REQ-FE-016).
function activeCatalogId() {
    const el = document.getElementById(
        currentCatalogMode() === 'item' ? 'gameItemId' : 'materialId',
    );
    return el ? el.value : '';
}

// Disables/enables every control inside a catalog block (same mechanism as orders-create's
// setFormDisabled): disabled controls are omitted from the POST body and their `required` never
// blocks the visible mode's submit. Covers the enhanced comboboxes too — both their hidden
// value-carrying input and the visible textbox match 'input'.
function setCatalogBlockDisabled(blockId, disabled) {
    const block = document.getElementById(blockId);
    if (!block) return;
    block.querySelectorAll('input, select, textarea, button').forEach(function (el) {
        el.disabled = disabled;
    });
}

// Applies the active catalog mode: swap + disable the catalog blocks, switch the amount field
// (item mode = PIECE semantics: whole units, Stück, no SCU hint, no merge opt-in), recompute the
// allocation sections (no mission dimension in item mode) and re-filter the order rows.
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

// Item-mode amount semantics: a gameItem row is booked in whole units like a PIECE material —
// integer step, Stück unit, no SCU hint, and the merge opt-in is never offered (items always
// auto-merge server-side, REQ-INV-026).
function applyItemAmountMode() {
    const amountInput = document.getElementById('amount');
    const unitSpan = document.getElementById('amount-unit');
    const scuHint = document.getElementById('amount-scu-hint');
    if (amountInput) amountInput.setAttribute('step', '1');
    if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_PIECE + ')';
    if (scuHint) scuHint.classList.add('krtm-hidden');
    updateMergeOptIn('PIECE');
}

// Mirrors the item picker's committed label into the hidden gameItemName echo so a classic-path
// validation redisplay can seed the remote combobox's single option with a readable label. Reads
// the enhanced control's textbox (precedented in bank.js/inventory-admin.js); the raw-<select>
// fallback covers a not-yet-enhanced control.
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

    // The material picker is a searchable combobox (REQ-FE-016): the selected option's
    // data-quantity-type is mirrored onto the hidden input carrying #materialId, replacing the
    // former selectedOptions[0] read (the native <option>s are gone after enhancement). The raw
    // <select> fallback covers a not-yet-enhanced control (e.g. the enhancer script failed to
    // execute), matching the sibling material-unit refreshers in orders-create/orders-detail.
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

// REQ-INV-026: the per-action stock-merge opt-in is offered only for an SCU material — a PIECE
// book-in always merges server-side, so the checkbox is hidden and reset for PIECE / no selection.
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

// ===== Variante C split-at-check-in (REQ-INV-027, R4) =====
// Repeatable job-order / mission allocation rows, cloned from the hidden <template>s and bound as
// indexed form params (jobOrderAllocations[i].targetId / .amount) the FormData submit carries; the
// single scalar selects were replaced by these lists.

// Per-dimension DOM/param handles for the split-at-check-in rows.
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

// Counts a dimension's target-carrying rows — the same "how many targets did the user name?" the
// write controller's single-target shorthand keys on, so the client hint and the server default
// agree. A freshly added, not-yet-picked row carries no target and does not count.
function allocTargetedRows(dimension) {
    return Array.prototype.filter.call(
        document.querySelectorAll('#' + allocConfig(dimension).rows + ' [data-alloc-target]'),
        function (select) {
            return !!select.value;
        },
    );
}

// The single-target shorthand only applies while a dimension names at most one target (the write
// controller then earmarks the whole entry amount to it); with two or more targets every amount has
// to be typed, so the hint would be wrong and is hidden.
function updateAllocSingleHint(dimension) {
    const hint = document.getElementById(allocConfig(dimension).singleHint);
    if (!hint) return;
    hint.classList.toggle('krtm-hidden', allocTargetedRows(dimension).length > 1);
}

// Renumbers a dimension's rows so the indexed form params stay contiguous (Spring binding needs no
// gaps) after an add or remove.
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

// Appends a fresh allocation row for the dimension, cloned from its <template>.
function addAllocRow(dimension) {
    const cfg = allocConfig(dimension);
    const container = document.getElementById(cfg.rows);
    const template = document.getElementById(cfg.template);
    if (!container || !template) return;
    container.appendChild(template.content.cloneNode(true));
    reindexAllocRows(dimension);
    if (dimension === 'jobOrder') {
        // Filter the fresh row by the active catalog mode's picked entry (material or gameItem).
        filterOrderSelects(activeCatalogId());
    }
    updateAllocOver();
}

// Removes the allocation row owning `button` and renumbers the surviving rows of that dimension.
function removeAllocRow(button) {
    const row = button.closest('[data-alloc-row]');
    if (!row) return;
    const missionContainer = document.getElementById('missionAllocRows');
    const dimension = missionContainer && missionContainer.contains(row) ? 'mission' : 'jobOrder';
    row.remove();
    reindexAllocRows(dimension);
    updateAllocOver();
}

// Filters every job-order row select to the orders that request the chosen catalog entry,
// clearing an incompatible current selection — the multi-row successor of the former single
// #jobOrderId filter. Mode-aware (design §6.2): material mode keys the filter on the option's
// data-materials CSV, item mode on its data-game-items sibling (the gameItems an ITEM order's
// lines request, REQ-INV-031).
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
    // Clearing an incompatible selection above fires no `change`, so the Σ / over-warning and the
    // single-target hint have to be recomputed here — otherwise switching the material silently
    // leaves both reading the dropped order row.
    relabelOrderOptions();
    updateAllocOver();
}

// ===== Outstanding need on the order options (REQ-INV-039) =====
//
// Splitting a haul across orders used to mean opening each candidate order in another tab to see
// what it still needed. The figure now rides on the option itself: `#1042 - Handle (OPEN) · noch
// 1200.000 SCU`.
//
// It is deliberately NOT stored per <option>: the allocation rows are cloned from the <template>,
// so N rows hold N copies of the same option list, and a peer's booking would have to be written
// into every one of them. The figures live in ONE module-level map keyed by order id, and every
// label is (re)computed from it — the same map the live-sync refresh below replaces wholesale.

// The whole need payload: { materials: {orderId: [...]}, gameItems: {orderId: [...]} }. Both catalog
// modes read from this one object, so switching Material <-> Item relabels without a second fetch.
let orderNeeds = readEmbeddedOrderNeeds();

// Seeds the map from the blob the page rendered. A malformed or absent value degrades to "no
// figures" — unlabelled options are a lesser failure on a form whose job is booking stock in.
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

// The order-keyed map for a catalog mode. A missing half answers as an empty map, never undefined,
// so every caller can index it without a guard of its own.
function needsMapFor(itemMode) {
    const key = itemMode ? 'gameItems' : 'materials';
    return (orderNeeds && orderNeeds[key]) || {};
}

// One order's outstanding need for one material, summed across its quality buckets, plus the
// highest floor among them. A material required at two quality levels yields two buckets; the sum
// is what the order still needs of that material, and the floor is what the entered grade is
// checked against. Returns null when this order does not require the material at all.
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
    return matched ? { outstanding: outstanding, floor: floor } : null;
}

// The item-mode sibling. Deliberately not the same function with another key: an item order's need
// is a flat whole-unit count with no quality dimension to sum across and no floor to report
// (REQ-INV-029), so there is exactly one entry per game item and nothing to fold.
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

// The picked material's quantity type, read the same way updateAmountFieldForMaterial reads it (the
// combobox mirrors it onto the hidden input; the raw <select> is the un-enhanced fallback).
function pickedMaterialQuantityType() {
    const el = document.getElementById('materialId');
    if (!el) return '';
    if (el.dataset && el.dataset.quantityType) return el.dataset.quantityType;
    const opt = el.selectedOptions && el.selectedOptions[0];
    return (opt && opt.getAttribute('data-quantity-type')) || '';
}

// Renders an amount in its material's own precision — whole for PIECE, three decimals for SCU
// (REQ-INV-027) — mirroring inventory-admin.js's assocFormatAmount so the two surfaces print one
// number identically.
function formatNeedAmount(amount, isPiece) {
    const n = typeof amount === 'number' ? amount : parseFloat(amount);
    if (isNaN(n)) return '0';
    return isPiece ? String(Math.round(n)) : n.toFixed(3);
}

// Rewrites every order option's label to carry the outstanding need for the picked catalog entry.
//
// Both modes carry a figure, from two different calculations (#1742): a material bucket's
// `required - booked` in the material's own unit, and an item line's `ordered - delivered -
// earmarked` in whole pieces. The mode decides which map is read; nothing else about the labelling
// differs, except that item mode has no quality marker because item rows carry no quality.
//
// The server-rendered label is stashed on first pass and every rewrite starts from it, so repeated
// material changes never stack suffixes.
function relabelOrderOptions() {
    const itemMode = currentCatalogMode() === 'item';
    const catalogId = activeCatalogId();
    // Item stock is counted in whole pieces and carries no quality at all (REQ-INV-029), so item
    // mode has no grade to compare against and never renders the quality marker.
    const isPiece = itemMode || pickedMaterialQuantityType() === 'PIECE';
    const unit = isPiece ? MSG_UNIT_PIECE : MSG_UNIT_SCU;
    const grade = itemMode ? NaN : parseInt((document.getElementById('quality') || {}).value, 10);
    document.querySelectorAll('#jobOrderAllocRows [data-alloc-target]').forEach(function (select) {
        for (let i = 1; i < select.options.length; i++) {
            applyOrderOptionLabel(select.options[i], catalogId, itemMode, isPiece, unit, grade);
        }
    });
    // The <template>'s own options are the master every future row is cloned from, so they carry the
    // current labels too — otherwise the next "+ Auftrag" row would appear unlabelled until the
    // material was touched again.
    const template = document.getElementById('jobOrderRowTemplate');
    if (template && template.content) {
        template.content.querySelectorAll('[data-alloc-target] option').forEach(function (option) {
            if (option.value) {
                applyOrderOptionLabel(option, catalogId, itemMode, isPiece, unit, grade);
            }
        });
    }
}

// Applies (or removes) one option's need suffix.
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
    // An allocation is gated on the MATERIAL alone — stock below an order's quality floor may be
    // earmarked to it and simply will not reduce the figure beside it. Saying so is the difference
    // between a number and a misleading one.
    if (need.floor !== null && !isNaN(grade) && grade < need.floor) {
        parts.push(INV_ORDER_NEED_MSG.qualityFloor.replace('{0}', String(need.floor)));
    }
    option.textContent = base + ' · ' + parts.join(' · ');
}

// Sums a dimension's entered allocation amounts.
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

// Shows the over-allocation warning when either dimension's Σ exceeds the entry amount (the backend
// enforces the same R5 rule with a 422), and refreshes both single-target shorthand hints — this is
// the one place every add / remove / target-change / amount-change already routes through.
//
// A blank amount under the shorthand needs no special casing here: it resolves to the entry amount
// server-side, which by definition never exceeds itself, so counting it as 0 reaches the same
// verdict.
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

// Personal stock can never be assigned: hide + clear both allocation sections while personal is
// ticked (the backend rejects a personal entry carrying assignments too). Applies to both catalog
// modes; item mode additionally hides + clears the mission section outright — item rows reject
// the mission dimension (REQ-INV-031).
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

// #577: book an item into the inventory in place. The classic POST->redirect stays the no-JS
// fallback; here we submit via the X-Requested-With twin and navigate to the source listing on
// success (navigate-after-AJAX), keeping the form + a toast on a validation/backend error
// instead of a flash-redirect. FormData submit lets the browser serialize the SCU-decimal amount
// + owner-picker exactly like the classic POST.
(function () {
    if (!window.krtFetch) return; // no-JS / no-foundation: the classic POST->redirect runs.
    const form = document.getElementById('inventory-input-form');
    if (!form) return;
    // Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF
    // header (no Content-Type so the browser sets the multipart boundary), the bare-403
    // refresh-and-retry, X-Reauthenticate and the double-submit guard. This handler keeps only
    // its page behaviour: navigate-after-AJAX on success (toast:false — the destination renders
    // its own toast) and the code-specific inline error toast (onError). The classic
    // POST->redirect stays the no-JS fallback (the guard above returns before this listener).
    form.addEventListener('submit', function (event) {
        event.preventDefault();
        window.krtFetch.submitForm({
            form: form,
            submitter: form.querySelector('button[type="submit"]'),
            toast: false,
            errorMessage: INV_ADD_MSG.failed,
            onError: function (status, problem) {
                let msg = INV_ADD_MSG.failed;
                if (problem && problem.code === 'INVENTORY_PERSONAL_ASSIGNMENT')
                    msg = INV_ADD_MSG.personalAssignment;
                else if (problem && problem.code === 'VALIDATION') msg = INV_ADD_MSG.validation;
                else if (problem && problem.detail) msg = problem.detail;
                if (window.showFrontendErrorToast) window.showFrontendErrorToast(msg);
                return true;
            },
            onSuccess: function (body) {
                // Navigate to the source listing carrying a success signal the destination's
                // toast fragment renders — a pre-navigation toast would be torn down by the load.
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
