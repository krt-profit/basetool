// @ts-check
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

/* global inventoryConflictI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

const adminLager = /** @type {KrtInventoryApi} */ (window.krtInventory).createLager({
    triggerPrefix: 'inv-admin',
    basePath: '/inventory/all',
    stackPerOwner: true,
    stackPersonalFlag: false,
    refreshTable() {
        filterInventory();
    },
    notifyInventoryChanged() {
        broadcastInventoryAllChanged();
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

const ADMIN_INVENTORY_FILTER_KEY = 'inventory_admin_filters';

const ADMIN_INVENTORY_FILTER_PARAMS = [
    'materialIds',
    'gameItemIds',
    'locationIds',
    'minQuality',
    'jobOrderIds',
    'missionIds',
];

function readAdminInventoryFilterPref() {
    try {
        const raw = localStorage.getItem(ADMIN_INVENTORY_FILTER_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null;
    }
}

function writeAdminInventoryFilterPref(value) {
    try {
        localStorage.setItem(ADMIN_INVENTORY_FILTER_KEY, JSON.stringify(value));
    } catch (_e) {}
}

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

function snapshotAdminInventoryFilters() {
    if (adminLager.lagerIsItemsView()) {
        return {
            gameItems: adminInventoryFilterSelection('gameItemCheck'),
            locations: adminInventoryFilterSelection('locCheck'),
            jobOrders: adminInventoryFilterSelection('jobOrderCheck'),
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
    };
}

function persistAdminInventoryFilters() {
    const stored = readAdminInventoryFilterPref() || {};
    stored[adminLager.lagerIsItemsView() ? 'items' : 'material'] = snapshotAdminInventoryFilters();
    writeAdminInventoryFilterPref(stored);
}

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

function restoreAdminInventoryFilters() {
    let params;
    try {
        params = new URLSearchParams(window.location.search);
    } catch (_e) {
        return false;
    }
    if (ADMIN_INVENTORY_FILTER_PARAMS.some((p) => params.has(p))) {
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
    families.forEach(function (f) {
        if (applyAdminSavedSelection(f[0], f[1], f[2], f[3])) changed = true;
    });
    return changed;
}

function countActiveAdminInventoryFilters() {
    const snapshot = snapshotAdminInventoryFilters();
    let active = 0;
    ['materials', 'gameItems', 'locations', 'jobOrders', 'missions'].forEach(function (dimension) {
        if (Array.isArray(snapshot[dimension]) && snapshot[dimension].length > 0) active++;
    });
    if (typeof snapshot.minQuality === 'string' && snapshot.minQuality !== '') active++;
    return active;
}

function filterInventory() {
    persistAdminInventoryFilters();
    if (window.krtFilterPanel) window.krtFilterPanel.refresh('globalFilterPanel');
    const itemsView = adminLager.lagerIsItemsView();
    const activeMats = adminLager.collectChecked('matCheck');
    const activeGameItems = adminLager.collectChecked('gameItemCheck');
    const activeLocations = adminLager.collectChecked('locCheck');
    const activeJobOrders = adminLager.collectChecked('jobOrderCheck');
    const activeMissions = adminLager.collectChecked('missionCheck');

    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    const minQuality = minQualitySelect ? minQualitySelect.value : '';

    const container = document.getElementById('tableContainer');
    if (!container) return;

    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(window.location.origin + '/inventory/all');
    url.searchParams.append('fragment', 'true');

    if (itemsView) url.searchParams.append('view', 'items');
    activeMats.forEach((m) => url.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => url.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => url.searchParams.append('locationIds', l));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));

    const visibleUrl = new URL(window.location.origin + '/inventory/all');
    if (itemsView) visibleUrl.searchParams.append('view', 'items');
    activeMats.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => visibleUrl.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => visibleUrl.searchParams.append('locationIds', l));
    if (minQuality) visibleUrl.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => visibleUrl.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => visibleUrl.searchParams.append('missionIds', m));
    try {
        window.history.replaceState({}, '', visibleUrl.toString());
    } catch {}

    fetch(url, {
        method: 'GET',
        headers: {
            'X-Requested-With': 'XMLHttpRequest',
        },
    })
        .then((response) => response.text())
        .then((html) => {
            window.krtFetch.replaceWithTrustedHtml(container, html);
            adminLager.restoreExpandedTree();
        })
        .catch((error) => {
            console.error('Error fetching filtered inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

const INVENTORY_ALL_SECTIONS = {
    stock: { container: '#tableContainer', fragmentValue: 'stock' },
};

function broadcastInventoryAllChanged() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('inventory', Object.keys(INVENTORY_ALL_SECTIONS));
    }
}
window.krtNotifyInventoryChanged = broadcastInventoryAllChanged;

if (
    window.krtLiveSync &&
    typeof window.krtLiveSync.createReceiver === 'function' &&
    document.getElementById('tableContainer')
) {
    window.krtLiveSync.createReceiver({
        topic: 'inventory',
        sections: INVENTORY_ALL_SECTIONS,
        coalesceMs: 1500,
        refresh() {
            filterInventory();
        },
    });
}

const INVENTORY_ALL_ORDER_SECTIONS = {
    demand: { container: '#tableContainer' },
};

if (
    window.krtLiveSync &&
    typeof window.krtLiveSync.createReceiver === 'function' &&
    document.getElementById('tableContainer')
) {
    window.krtLiveSync.createReceiver({
        topic: 'orders',
        sections: INVENTORY_ALL_ORDER_SECTIONS,
        coalesceMs: 1500,
        refresh() {
            filterInventory();
        },
    });
}

function resetInventoryFilter() {
    ['matCheck', 'gameItemCheck', 'locCheck', 'jobOrderCheck', 'missionCheck'].forEach(
        function (cls) {
            const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
                document.getElementsByClassName(cls)
            );
            for (let i = 0; i < boxes.length; i++) boxes[i].checked = false;
        },
    );
    [
        'matAll',
        'gameItemAll',
        'locAll',
        'itemLocAll',
        'jobOrderAll',
        'itemJobOrderAll',
        'missionAll',
    ].forEach(function (id) {
        const el = adminCheckbox(id);
        if (el) el.checked = false;
    });
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    if (minQualitySelect) minQualitySelect.value = '';
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
    filterInventory();
}

document.addEventListener('DOMContentLoaded', function () {
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
    if (window.krtFilterPanel) {
        window.krtFilterPanel.registerCounter(
            'globalFilterPanel',
            countActiveAdminInventoryFilters,
        );
        window.krtFilterPanel.refresh('globalFilterPanel');
    }
    if (filtersRestored) filterInventory();
});

document.addEventListener('DOMContentLoaded', function () {
    const matSelect = document.getElementById('materialId');
    if (matSelect) {
        matSelect.addEventListener('change', function () {
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
    if (targetHint) targetHint.classList.toggle('krtm-hidden', !isScu);
    if (amountHint) amountHint.classList.toggle('krtm-hidden', !isScu);
    const mergeRow = document.getElementById('umbuchenMergeRow');
    const mergeCheckbox = adminUmbuchenInput('umbuchenMergeStock');
    if (mergeCheckbox) mergeCheckbox.checked = false;
    if (mergeRow) mergeRow.classList.toggle('krtm-hidden', !isScu);
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
    window.krtModal.open(modal);
    if (window.krtHerkunft && id) {
        window.krtHerkunft.populate('umbuchen', id);
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
    if (adminUmbuchenInFlight || !window.krtFetch || !adminUmbuchenItemId) return;
    const amountEl = adminUmbuchenInput('umbuchenAmount');
    const targetUserEl = adminUmbuchenInput('umbuchenTargetUserId');
    const targetLocationEl = adminUmbuchenInput('umbuchenTargetLocationId');
    const targetOrgUnitEl = adminUmbuchenInput('umbuchenTargetOwningOrgUnitId');
    const versionEl = adminUmbuchenInput('umbuchenVersion');
    if (!amountEl || !targetUserEl || !targetLocationEl || !targetOrgUnitEl || !versionEl) return;
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountEl.value)
        : parseFloat(amountEl.value);
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('umbuchenSubmitBtn')
    );
    const mergeCheckbox = adminUmbuchenInput('umbuchenMergeStock');
    if (window.krtHerkunft && !window.krtHerkunft.isValid('umbuchen')) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.overallocated);
        }
        return;
    }
    const reductions = window.krtHerkunft
        ? window.krtHerkunft.collect('umbuchen')
        : { jobOrderReductions: null, missionReductions: null };
    const payload = {
        amount,
        type: 'TRANSFER',
        targetUserId: targetUserEl.value || null,
        targetLocationId: targetLocationEl.value || null,
        targetOwningOrgUnitId: targetOrgUnitEl.value || null,
        version: parseInt(versionEl.value, 10),
        mergeStock: !!(mergeCheckbox && mergeCheckbox.checked),
        jobOrderReductions: reductions.jobOrderReductions,
        missionReductions: reductions.missionReductions,
    };
    const affectedOrderIds = adminLager.collectLeafOrderIds(adminUmbuchenItemId);
    adminUmbuchenInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/' + adminUmbuchenItemId + '/transfer',
            payload,
            successMessage: umbuchenI18n.success,
            errorMessage: umbuchenI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess() {
                adminLager.closeUmbuchenModal();
                filterInventory();
                broadcastInventoryAllChanged();
                adminLager.broadcastOrdersChanged(affectedOrderIds);
                adminLager.broadcastBoardChanged();
            },
        })
        .then(function () {
            adminUmbuchenInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

(function () {
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
        window.krtModal.close(dialog);
    }

    deleteBtn.addEventListener('click', function () {
        window.krtModal.open(modal);
    });

    cancelBtn.addEventListener('click', closeModal);

    window.addEventListener('click', function (e) {
        if (e.target === modal) {
            closeModal();
        }
    });

    confirmBtn.addEventListener('click', async function () {
        if (!window.krtFetch) return;
        confirmBtn.disabled = true;
        cancelBtn.disabled = true;
        try {
            await window.krtFetch.write({
                method: 'DELETE',
                url: '/inventory/all',
                toast: false,
                errorMessage: deleteBtn.getAttribute('data-error-failed') ?? undefined,
                onSuccess() {
                    showInventoryToast('success', deleteBtn.getAttribute('data-success'));
                    filterInventory();
                    broadcastInventoryAllChanged();
                    adminLager.broadcastBoardChanged();
                },
            });
            closeModal();
        } finally {
            confirmBtn.disabled = false;
            cancelBtn.disabled = false;
        }
    });
})();

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'inv-admin-toggle-multi', function (el) {
        adminLager.toggleMultiSelect(el.getAttribute('data-multi-target'));
    });
    window.krtEvents.on('change', 'inv-admin-toggle-all', function (el) {
        adminLager.toggleSelectAll(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterInventory();
    });
    window.krtEvents.on('change', 'inv-admin-update-state', function (el) {
        adminLager.updateSelectState(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterInventory();
    });
    window.krtEvents.on('change', 'inv-admin-filter', filterInventory);
    window.krtEvents.on('click', 'inv-admin-reset-filter', resetInventoryFilter);
    window.krtEvents.on('click', 'inv-admin-umbuchen', function (el) {
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
    window.krtEvents.on('click', 'inv-admin-open-note', function (el) {
        openNoteModal(el);
    });
    window.krtEvents.on('click', 'inv-admin-close-note', closeNoteModal);
    window.krtEvents.on('input', 'inv-admin-update-note-counter', updateNoteCounter);
    window.krtEvents.on('click', 'inv-admin-save-note', saveNote);
    window.krtEvents.on('click', 'inv-admin-remove-note', removeNote);
}

const adminUmbuchenFormEl = document.getElementById('umbuchenForm');
if (adminUmbuchenFormEl) {
    adminUmbuchenFormEl.addEventListener('submit', submitUmbuchen);
}

adminLager.bind();
