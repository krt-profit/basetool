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

/* global inventoryConflictI18n, umbuchenI18n, assocI18n */

import {
    closeNoteModal,
    openNoteModal,
    removeNote,
    saveNote,
    showInventoryToast,
    updateNoteCounter,
} from '/js/inventory/inventory-note-modal.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtFilterChips } from '/js/kernel/krt-filter-chips.js';
import { krtFilterPanel } from '/js/kernel/krt-filter-panel.js';
import { krtInventory } from '/js/inventory/inventory-common.js';
import { krtHerkunft } from '/js/inventory/inventory-herkunft.js';
import { inventoryHooks } from '/js/inventory/inventory-hooks.js';
import { initPoolChange } from '/js/inventory/inventory-pool-change.js';

const adminLager = /** @type {KrtInventoryApi} */ (krtInventory).createLager({
    triggerPrefix: 'inv-admin',
    basePath: '/inventory/all',
    stackPerOwner: true,
    stackPersonalFlag: false,
    refreshTable() {
        filterInventory();
    },
    notifyInventoryChanged() {
        inventoryAll.notify('stock');
    },
});

/**
 * A checkbox by id (the select-all box of a multi-select family).
 *
 * @param {string | null} id the element id
 * @returns {HTMLInputElement | null} the checkbox, or null when absent
 */
function adminCheckbox(id) {
    return id ? /** @type {HTMLInputElement | null} */ (document.getElementById(id)) : null;
}

/**
 * The „gestohlen" select of the active view (REQ-INV-053), when the page renders one.
 *
 * @returns {HTMLSelectElement | null} the select, or null when absent
 */
function adminStolenFilterSelect() {
    return /** @type {HTMLSelectElement | null} */ (
        document.getElementById('stolenFilter') || document.getElementById('itemStolenFilter')
    );
}

/**
 * The „gestohlen" filter of the active view: '' for all, 'non' without, 'only' stolen only.
 *
 * @returns {string} the filter value
 */
function adminStolenFilterValue() {
    const el = adminStolenFilterSelect();
    return el ? el.value : '';
}

const ADMIN_INVENTORY_FILTER_KEY = 'inventory_admin_filters';

/**
 * Reads the stored filter preference.
 *
 * @returns {any} the stored object, or null when absent or unreadable
 */
function readAdminInventoryFilterPref() {
    try {
        const raw = localStorage.getItem(ADMIN_INVENTORY_FILTER_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null;
    }
}

/**
 * Stores the filter preference; a storage failure is ignored.
 *
 * @param {unknown} value the JSON-serialisable preference
 */
function writeAdminInventoryFilterPref(value) {
    try {
        localStorage.setItem(ADMIN_INVENTORY_FILTER_KEY, JSON.stringify(value));
    } catch (_e) {}
}

/**
 * The values of a filter family's ticked boxes.
 *
 * @param {string} className the family's checkbox class
 * @returns {string[] | null} the ticked values, or null when none or all are ticked
 */
function adminInventoryFilterSelection(className) {
    const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(className)
    );
    /** @type {string[]} */
    const picked = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) picked.push(boxes[i].value);
    }
    return picked.length === 0 || picked.length === boxes.length ? null : picked;
}

/**
 * @typedef {object} AdminFilterSnapshot
 * @property {string[] | null} [materials] the material filter
 * @property {string[] | null} [gameItems] the game-item filter
 * @property {string[] | null} [locations] the location filter
 * @property {string[] | null} [jobOrders] the job-order filter
 * @property {string[] | null} [missions] the mission filter
 * @property {string} [minQuality] the minimum quality
 * @property {string} stolen the stolen filter value
 * @property {KrtItemAttributeSnapshot} [attributes] the item-attribute filters (items view)
 */

/**
 * The current filter selection of the active view.
 *
 * @returns {AdminFilterSnapshot} the selection
 */
function snapshotAdminInventoryFilters() {
    if (adminLager.lagerIsItemsView()) {
        return {
            gameItems: adminInventoryFilterSelection('gameItemCheck'),
            locations: adminInventoryFilterSelection('locCheck'),
            jobOrders: adminInventoryFilterSelection('jobOrderCheck'),
            stolen: adminStolenFilterValue(),
            attributes: adminLager.snapshotItemAttributes(),
        };
    }
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    return {
        materials: adminInventoryFilterSelection('matCheck'),
        locations: adminInventoryFilterSelection('locCheck'),
        minQuality: minQualitySelect ? minQualitySelect.value : '',
        jobOrders: adminInventoryFilterSelection('jobOrderCheck'),
        missions: adminInventoryFilterSelection('missionCheck'),
        stolen: adminStolenFilterValue(),
    };
}

/** Stores the current filter selection under the active view's key. */
function persistAdminInventoryFilters() {
    const stored = readAdminInventoryFilterPref() || {};
    stored[adminLager.lagerIsItemsView() ? 'items' : 'material'] = snapshotAdminInventoryFilters();
    writeAdminInventoryFilterPref(stored);
}

/**
 * Ticks the boxes of a family from a stored selection.
 *
 * @param {unknown} saved the stored values
 * @param {string} checkClass the family's checkbox class
 * @param {string} allId the family's select-all box id
 * @param {string} headerId the family's header id
 * @returns {boolean} true when at least one box was ticked
 */
function applyAdminSavedSelection(saved, checkClass, allId, headerId) {
    if (!Array.isArray(saved) || saved.length === 0) return false;
    const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(checkClass)
    );
    if (boxes.length === 0) return false;
    let any = false;
    for (let i = 0; i < boxes.length; i++) {
        const on = saved.indexOf(boxes[i].value) >= 0;
        boxes[i].checked = on;
        if (on) any = true;
    }
    adminLager.updateSelectState(allId, checkClass, headerId);
    return any;
}

/**
 * Restores the stored filters unless the address already carries filter parameters.
 *
 * @returns {boolean} true when a stored filter was applied
 */
function restoreAdminInventoryFilters() {
    let params;
    try {
        params = new URLSearchParams(window.location.search);
    } catch (_e) {
        return false;
    }
    if (
        [...adminLager.lagerFilterParams(), ...adminLager.itemAttributeParams()].some((p) =>
            params.has(p),
        )
    ) {
        persistAdminInventoryFilters();
        return false;
    }
    const stored = readAdminInventoryFilterPref();
    const saved = stored ? stored[adminLager.lagerIsItemsView() ? 'items' : 'material'] : null;
    if (!saved || typeof saved !== 'object') return false;
    let changed = false;
    let families;
    if (adminLager.lagerIsItemsView()) {
        families = [
            [saved.gameItems, 'gameItemCheck', 'gameItemAll', 'gameItemHeader'],
            [saved.locations, 'locCheck', 'itemLocAll', 'itemLocationHeader'],
            [saved.jobOrders, 'jobOrderCheck', 'itemJobOrderAll', 'itemJobOrderHeader'],
        ];
        if (adminLager.restoreItemAttributes(saved.attributes)) changed = true;
    } else {
        families = [
            [saved.materials, 'matCheck', 'matAll', 'materialHeader'],
            [saved.locations, 'locCheck', 'locAll', 'locationHeader'],
            [saved.jobOrders, 'jobOrderCheck', 'jobOrderAll', 'jobOrderHeader'],
            [saved.missions, 'missionCheck', 'missionAll', 'missionHeader'],
        ];
        const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('minQuality')
        );
        if (minQualitySelect && typeof saved.minQuality === 'string' && saved.minQuality !== '') {
            minQualitySelect.value = saved.minQuality;
            if (minQualitySelect.value === saved.minQuality) changed = true;
        }
    }
    families.forEach((f) => {
        if (applyAdminSavedSelection(f[0], f[1], f[2], f[3])) changed = true;
    });
    const stolenSelect = adminStolenFilterSelect();
    if (stolenSelect && (saved.stolen === 'non' || saved.stolen === 'only')) {
        stolenSelect.value = saved.stolen;
        changed = true;
    }
    return changed;
}

/**
 * The number of active filters, for the filter panel's badge.
 *
 * @returns {number} the count
 */
function countActiveAdminInventoryFilters() {
    const snapshot = snapshotAdminInventoryFilters();
    let active = 0;
    /** @type {Array<'materials' | 'gameItems' | 'locations' | 'jobOrders' | 'missions'>} */ ([
        'materials',
        'gameItems',
        'locations',
        'jobOrders',
        'missions',
    ]).forEach((dimension) => {
        const picked = snapshot[dimension];
        if (Array.isArray(picked) && picked.length > 0) active++;
    });
    if (typeof snapshot.minQuality === 'string' && snapshot.minQuality !== '') active++;
    if (snapshot.stolen === 'non' || snapshot.stolen === 'only') active++;
    return active + adminLager.countActiveItemAttributes(snapshot.attributes);
}

/** Re-pulls the grouped table with the current filters and mirrors them into the address. */
function filterInventory() {
    persistAdminInventoryFilters();
    krtFilterPanel.refresh('globalFilterPanel');
    krtFilterChips.refresh();
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    const selection = {
        itemsView: adminLager.lagerIsItemsView(),
        materials: adminLager.collectChecked('matCheck'),
        gameItems: adminLager.collectChecked('gameItemCheck'),
        locations: adminLager.collectChecked('locCheck'),
        minQuality: minQualitySelect ? minQualitySelect.value : '',
        jobOrders: adminLager.collectChecked('jobOrderCheck'),
        missions: adminLager.collectChecked('missionCheck'),
        stolen: adminStolenFilterValue(),
    };

    const container = document.getElementById('tableContainer');
    if (!container) return;

    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(`${window.location.origin}/inventory/all`);
    url.searchParams.append('fragment', 'true');

    adminLager.appendLagerFilterParams(url.searchParams, selection);

    const visibleUrl = new URL(`${window.location.origin}/inventory/all`);
    adminLager.appendLagerFilterParams(visibleUrl.searchParams, selection);
    try {
        window.history.replaceState({}, '', visibleUrl.toString());
    } catch {}

    krtFetch
        .get(url.toString(), { key: 'inventory-admin-table' })
        .then((response) => {
            if (!response || !response.ok) {
                throw new Error(`Inventory fragment refused (${response ? response.status : 0})`);
            }
            return response.text();
        })
        .then((html) => {
            krtFetch.replaceWithTrustedHtml(container, html);
            adminLager.restoreExpandedTree();
        })
        .catch((error) => {
            if (error instanceof DOMException && error.name === 'AbortError') return;
            console.error('Error fetching filtered inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

const inventoryAll = krtPageSections.create({
    topicClass: 'INVENTORY_ALL',
    sections: {
        stock: { container: '#tableContainer', fragmentValue: 'stock' },
    },
    topic: 'inventory',
    receiver: document.getElementById('tableContainer')
        ? {
              coalesceMs: 1500,
              refresh() {
                  filterInventory();
              },
          }
        : undefined,
});

inventoryHooks.notifyChanged = () => inventoryAll.notify('stock');

krtPageSections.create({
    topicClass: 'ORDERS_QUEUE',
    sections: {
        demand: { container: '#tableContainer' },
    },
    topic: 'orders',
    receiver: document.getElementById('tableContainer')
        ? {
              coalesceMs: 1500,
              refresh() {
                  filterInventory();
              },
          }
        : undefined,
});

/** Clears every filter and re-pulls the table. */
function resetInventoryFilter() {
    ['matCheck', 'gameItemCheck', 'locCheck', 'jobOrderCheck', 'missionCheck'].forEach((cls) => {
        const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName(cls)
        );
        for (let i = 0; i < boxes.length; i++) boxes[i].checked = false;
    });
    [
        'matAll',
        'gameItemAll',
        'locAll',
        'itemLocAll',
        'jobOrderAll',
        'itemJobOrderAll',
        'missionAll',
    ].forEach((id) => {
        const el = adminCheckbox(id);
        if (el) el.checked = false;
    });
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    if (minQualitySelect) minQualitySelect.value = '';
    const stolenSelect = adminStolenFilterSelect();
    if (stolenSelect) stolenSelect.value = '';
    if (document.getElementById('materialHeader'))
        adminLager.updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('gameItemHeader'))
        adminLager.updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    if (document.getElementById('locationHeader'))
        adminLager.updateSelectState('locAll', 'locCheck', 'locationHeader');
    if (document.getElementById('itemLocationHeader'))
        adminLager.updateSelectState('itemLocAll', 'locCheck', 'itemLocationHeader');
    if (document.getElementById('jobOrderHeader'))
        adminLager.updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    if (document.getElementById('itemJobOrderHeader'))
        adminLager.updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
    if (document.getElementById('missionHeader'))
        adminLager.updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    adminLager.resetItemAttributes();
    filterInventory();
}

document.addEventListener('DOMContentLoaded', () => {
    const filtersRestored = restoreAdminInventoryFilters();
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('matCheck')
        ).length > 0
    ) {
        adminLager.updateSelectState('matAll', 'matCheck', 'materialHeader');
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('gameItemCheck')
        ).length > 0
    ) {
        adminLager.updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('locCheck')
        ).length > 0
    ) {
        if (document.getElementById('itemLocationHeader')) {
            adminLager.updateSelectState('itemLocAll', 'locCheck', 'itemLocationHeader');
        } else {
            adminLager.updateSelectState('locAll', 'locCheck', 'locationHeader');
        }
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('jobOrderCheck')
        ).length > 0
    ) {
        if (document.getElementById('itemJobOrderHeader')) {
            adminLager.updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
        } else {
            adminLager.updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
        }
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('missionCheck')
        ).length > 0
    ) {
        adminLager.updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    }
    adminLager.syncItemAttributeStates();
    krtFilterPanel.registerCounter('globalFilterPanel', countActiveAdminInventoryFilters);
    krtFilterPanel.refresh('globalFilterPanel');
    krtFilterChips.refresh();
    if (filtersRestored) filterInventory();
});

document.addEventListener('DOMContentLoaded', () => {
    const matSelect = document.getElementById('materialId');
    if (matSelect) {
        matSelect.addEventListener('change', () => {
            filterJobOrdersByMaterial(/** @type {HTMLSelectElement} */ (matSelect).value);
        });
    }
});

/**
 * Narrows the job-order select to the orders that need the chosen material.
 *
 * @param {string} matId the chosen material id, or '' for all
 */
function filterJobOrdersByMaterial(matId) {
    const jobSelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('jobOrderId')
    );
    if (!jobSelect) return;

    let hasSelectedValidOption = false;

    for (let i = 1; i < jobSelect.options.length; i++) {
        const option = jobSelect.options[i];

        if (!matId) {
            option.style.display = '';
            option.disabled = false;
            if (option.selected) hasSelectedValidOption = true;
        } else {
            const materialsStr = option.getAttribute('data-materials');
            if (materialsStr) {
                const materials = materialsStr.split(',');
                if (materials.includes(matId)) {
                    option.style.display = '';
                    option.disabled = false;
                    if (option.selected) hasSelectedValidOption = true;
                } else {
                    option.style.display = 'none';
                    option.disabled = true;
                }
            } else {
                option.style.display = 'none';
                option.disabled = true;
            }
        }
    }

    if (jobSelect.selectedIndex > 0 && !hasSelectedValidOption) {
        jobSelect.value = '';
    }
}

/** @type {string | null} */
let adminUmbuchenItemId = null;
let adminUmbuchenInFlight = false;
/** @type {{ userId: string | null, locationId: string | null }} */
let adminUmbuchenSource = { userId: null, locationId: null };

/**
 * An Umbuchen form control by id.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement | null} the control, or null when absent
 */
function adminUmbuchenInput(id) {
    return /** @type {HTMLInputElement | null} */ (document.getElementById(id));
}

/**
 * @param {string | null} id the entry id
 * @param {string | null} amount the entry's amount
 * @param {string | null} version the entry's optimistic-lock version
 * @param {string | null} materialId the entry's material (unused here, kept for the call shape)
 * @param {string | null} userId the current owner's id
 * @param {string | null} userName the current owner's label
 * @param {string | null} locationId the current location's id
 * @param {string | null} locationName the current location's label
 * @param {string | null} quantityType `PIECE` or `SCU`
 * @param {string | null} owningOrgUnitId the row's owning org unit
 */
function openUmbuchenModal(
    id,
    amount,
    version,
    materialId,
    userId,
    userName,
    locationId,
    locationName,
    quantityType,
    owningOrgUnitId,
) {
    adminUmbuchenItemId = id;
    adminUmbuchenSource = { userId: userId || null, locationId: locationId || null };
    adminLager.setUmbuchenCurrentOwningOrgUnit(owningOrgUnitId || null);
    const isScu = quantityType !== 'PIECE';
    const amountEl = adminUmbuchenInput('umbuchenAmount');
    const targetEl = adminUmbuchenInput('umbuchenTargetAmount');
    const maxEl = adminUmbuchenInput('umbuchenMaxAmount');
    const versionEl = adminUmbuchenInput('umbuchenVersion');
    const umbuchenUser = adminUmbuchenInput('umbuchenTargetUserId');
    const umbuchenLocation = adminUmbuchenInput('umbuchenTargetLocationId');
    const modal = document.getElementById('umbuchenModal');
    if (
        !amountEl ||
        !targetEl ||
        !maxEl ||
        !versionEl ||
        !umbuchenUser ||
        !umbuchenLocation ||
        !modal
    ) {
        return;
    }
    amountEl.setAttribute('step', isScu ? '0.001' : '1');
    targetEl.setAttribute('step', isScu ? '0.001' : '1');
    const targetHint = document.getElementById('umbuchen-target-scu-hint');
    const amountHint = document.getElementById('umbuchen-amount-scu-hint');
    if (targetHint) targetHint.classList.toggle('is-hidden', !isScu);
    if (amountHint) amountHint.classList.toggle('is-hidden', !isScu);
    const mergeRow = document.getElementById('umbuchenMergeRow');
    const mergeCheckbox = adminUmbuchenInput('umbuchenMergeStock');
    if (mergeCheckbox) mergeCheckbox.checked = false;
    if (mergeRow) mergeRow.hidden = !isScu;
    amountEl.value = amount ?? '';
    amountEl.max = amount ?? '';
    targetEl.value = '0';
    maxEl.value = amount ?? '';
    versionEl.value = version ?? '';
    const amountOf = document.getElementById('umbuchenAmountOfText');
    if (amountOf)
        amountOf.textContent = (amountOf.getAttribute('data-template') ?? '').replace(
            '{0}',
            amount ?? '',
        );
    if (umbuchenUser.krtCombobox) {
        umbuchenUser.krtCombobox.setValue(userId ?? '', userName ?? undefined);
    } else {
        umbuchenUser.value = userId ?? '';
    }
    if (umbuchenLocation.krtCombobox) {
        umbuchenLocation.krtCombobox.setValue(locationId ?? '', locationName ?? undefined);
    } else {
        umbuchenLocation.value = locationId ?? '';
    }
    adminLager.refreshUmbuchenTransferOrgUnitPicker();
    krtModal.open(modal);
    if (id) {
        krtHerkunft.populate('umbuchen', id);
    }
}

/**
 * Submits the Umbuchen transfer in place through krtFetch, then re-pulls the table and pokes the
 * peer rooms the transfer changed.
 *
 * @param {Event} event the form submit
 */
function submitUmbuchen(event) {
    if (event && event.defaultPrevented) return;
    if (event) event.preventDefault();
    if (adminUmbuchenInFlight || !adminUmbuchenItemId) return;
    const amountEl = adminUmbuchenInput('umbuchenAmount');
    const targetUserEl = adminUmbuchenInput('umbuchenTargetUserId');
    const targetLocationEl = adminUmbuchenInput('umbuchenTargetLocationId');
    const targetOrgUnitEl = adminUmbuchenInput('umbuchenTargetOwningOrgUnitId');
    const versionEl = adminUmbuchenInput('umbuchenVersion');
    if (!amountEl || !targetUserEl || !targetLocationEl || !targetOrgUnitEl || !versionEl) return;
    const amount = krtScuInput.parse(amountEl.value);
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('umbuchenSubmitBtn')
    );
    const mergeCheckbox = adminUmbuchenInput('umbuchenMergeStock');
    const targetUserId = targetUserEl.value || null;
    const targetLocationId = targetLocationEl.value || null;
    if (
        (targetUserId === null || targetUserId === adminUmbuchenSource.userId) &&
        (targetLocationId === null || targetLocationId === adminUmbuchenSource.locationId)
    ) {
        showFrontendErrorToast(umbuchenI18n.unchanged);
        return;
    }
    if (!krtHerkunft.isValid('umbuchen')) {
        showFrontendErrorToast(assocI18n.overallocated);
        return;
    }
    const reductions = krtHerkunft.collect('umbuchen');
    const payload = {
        amount,
        type: 'TRANSFER',
        targetUserId,
        targetLocationId,
        targetOwningOrgUnitId: targetOrgUnitEl.value || null,
        version: parseInt(versionEl.value, 10),
        mergeStock: !!(mergeCheckbox && mergeCheckbox.checked),
        jobOrderReductions: reductions.jobOrderReductions,
        missionReductions: reductions.missionReductions,
    };
    const affectedOrderIds = adminLager.collectLeafOrderIds(adminUmbuchenItemId);
    adminUmbuchenInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    krtFetch
        .write({
            method: 'POST',
            url: `/inventory/${adminUmbuchenItemId}/transfer`,
            payload,
            successMessage: umbuchenI18n.success,
            errorMessage: umbuchenI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess() {
                adminLager.closeUmbuchenModal();
                filterInventory();
                inventoryAll.notify('stock');
                adminLager.broadcastOrdersChanged(affectedOrderIds);
                adminLager.broadcastBoardChanged();
            },
        })
        .then(() => {
            adminUmbuchenInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

/** Wires the delete-all dialog of the global Lager. */
function initInventoryAdmin() {
    const deleteBtn = document.getElementById('delete-all-global-inventory-btn');
    const modal = document.getElementById('delete-all-global-inventory-modal');
    const confirmBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('delete-all-global-inventory-confirm-btn')
    );
    const cancelBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('delete-all-global-inventory-cancel-btn')
    );

    if (!deleteBtn || !modal || !confirmBtn || !cancelBtn) return;

    const dialog = modal;
    function closeModal() {
        krtModal.close(dialog);
    }

    deleteBtn.addEventListener('click', () => {
        krtModal.open(modal);
    });

    cancelBtn.addEventListener('click', closeModal);

    window.addEventListener('click', (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });

    confirmBtn.addEventListener('click', async () => {
        confirmBtn.disabled = true;
        cancelBtn.disabled = true;
        try {
            await krtFetch.write({
                method: 'DELETE',
                url: '/inventory/all',
                toast: false,
                errorMessage: deleteBtn.getAttribute('data-error-failed') ?? undefined,
                onSuccess() {
                    showInventoryToast(
                        'success',
                        /** @type {string} */ (deleteBtn.getAttribute('data-success')),
                    );
                    filterInventory();
                    inventoryAll.notify('stock');
                    adminLager.broadcastBoardChanged();
                },
            });
            closeModal();
        } finally {
            confirmBtn.disabled = false;
            cancelBtn.disabled = false;
        }
    });
}

initInventoryAdmin();

krtEvents.on('click', 'inv-admin-toggle-multi', (el) => {
    adminLager.toggleMultiSelect(el.getAttribute('data-multi-target'));
});
krtEvents.on('change', 'inv-admin-toggle-all', (el) => {
    adminLager.toggleSelectAll(
        el.getAttribute('data-all-id'),
        el.getAttribute('data-check-class'),
        el.getAttribute('data-header-id'),
    );
    filterInventory();
});
krtEvents.on('change', 'inv-admin-update-state', (el) => {
    adminLager.updateSelectState(
        el.getAttribute('data-all-id'),
        el.getAttribute('data-check-class'),
        el.getAttribute('data-header-id'),
    );
    filterInventory();
});
krtEvents.on('change', 'inv-admin-filter', filterInventory);
krtEvents.on('click', 'inv-admin-reset-filter', resetInventoryFilter);
krtEvents.on('click', 'inv-admin-umbuchen', (el) => {
    openUmbuchenModal(
        el.getAttribute('data-id'),
        el.getAttribute('data-amount'),
        el.getAttribute('data-version'),
        el.getAttribute('data-material-id'),
        el.getAttribute('data-user-id'),
        el.getAttribute('data-user-name'),
        el.getAttribute('data-location-id'),
        el.getAttribute('data-location-name'),
        el.getAttribute('data-quantity-type'),
        el.getAttribute('data-owning-org-unit-id'),
    );
});
krtEvents.on('click', 'inv-admin-open-note', (el) => {
    openNoteModal(el);
});
krtEvents.on('click', 'inv-admin-close-note', closeNoteModal);
krtEvents.on('input', 'inv-admin-update-note-counter', updateNoteCounter);
krtEvents.on('click', 'inv-admin-save-note', saveNote);
krtEvents.on('click', 'inv-admin-remove-note', removeNote);

const adminUmbuchenFormEl = document.getElementById('umbuchenForm');
if (adminUmbuchenFormEl) {
    adminUmbuchenFormEl.addEventListener('submit', submitUmbuchen);
}

initPoolChange(() => {
    filterInventory();
    inventoryAll.notify('stock');
    adminLager.broadcastBoardChanged();
});

adminLager.bind();
