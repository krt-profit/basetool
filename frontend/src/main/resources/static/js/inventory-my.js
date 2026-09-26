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

/* global bulkI18n, bulkRebookI18n, orgUnitChangeI18n, inventoryConflictI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

const myLager = /** @type {KrtInventoryApi} */ (window.krtInventory).createLager({
    triggerPrefix: 'inv-my',
    basePath: '/inventory/my',
    stackPerOwner: false,
    stackPersonalFlag: true,
    refreshTable() {
        filterMyInventory();
    },
    notifyInventoryChanged() {
        broadcastInventoryChanged();
    },
    onStackEntriesLoaded(content) {
        applyBulkSelectionToLoaded(content);
        updateBulkCheckoutState();
    },
});

/**
 * The value of one of this page's form controls, or '' when the page does not render it.
 *
 * @param {string} id the element id
 * @returns {string} the control's value
 */
function myFieldValue(id) {
    const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    return el ? el.value : '';
}

/**
 * Writes one of this page's form controls, when the page renders it.
 *
 * @param {string} id the element id
 * @param {unknown} value the value, written as its string form ('' for null / undefined)
 */
function setMyFieldValue(id, value) {
    const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    if (el) el.value = value == null ? '' : String(value);
}

/**
 * Sets an element's inline display (a modal's `flex` / `none`), when the page renders it.
 *
 * @param {string} id the element id
 * @param {string} display the CSS display value
 */
function setMyDisplay(id, display) {
    const el = document.getElementById(id);
    if (el) el.style.display = display;
}

function bulkGroupSelector(groupCb) {
    const materialId = groupCb.getAttribute('data-material-id');
    if (materialId) {
        return '.inventory-item-checkbox[data-material-id="' + materialId + '"]';
    }
    return (
        '.inventory-item-checkbox[data-game-item-id="' +
        groupCb.getAttribute('data-game-item-id') +
        '"]'
    );
}

const bulkSelectedIds = new Set();

function getCheckedItemIds() {
    return Array.from(bulkSelectedIds);
}

function applyBulkSelectionToLoaded(root) {
    /** @type {NodeListOf<HTMLInputElement>} */ (
        (root || document).querySelectorAll('.inventory-item-checkbox')
    ).forEach(function (cb) {
        const id = cb.getAttribute('data-id');
        cb.checked = !!id && bulkSelectedIds.has(id);
    });
}

function updateBulkCheckoutState() {
    const count = bulkSelectedIds.size;
    const btn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkCheckoutBtn')
    );
    const rebookBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkRebookBtn')
    );
    const countSpan = document.getElementById('bulkCheckoutCount');
    if (btn) btn.disabled = count === 0;
    if (rebookBtn) rebookBtn.disabled = count === 0;
    const orgUnitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkOrgUnitBtn')
    );
    if (orgUnitBtn) orgUnitBtn.disabled = count === 0;
    if (countSpan) countSpan.textContent = count > 0 ? '(' + count + ')' : '';
    /** @type {NodeListOf<HTMLInputElement>} */ (
        document.querySelectorAll('.group-select-all')
    ).forEach(function (groupCb) {
        const groupBoxes = /** @type {NodeListOf<HTMLInputElement>} */ (
            document.querySelectorAll(bulkGroupSelector(groupCb))
        );
        let allChecked = groupBoxes.length > 0;
        groupBoxes.forEach(function (cb) {
            if (!cb.checked) allChecked = false;
        });
        groupCb.checked = allChecked;
        groupCb.indeterminate =
            !allChecked &&
            Array.from(groupBoxes).some(function (cb) {
                return cb.checked;
            });
    });
}

function onEntryCheckboxToggle(cb) {
    const id = cb.getAttribute('data-id');
    if (id) {
        if (cb.checked) bulkSelectedIds.add(id);
        else bulkSelectedIds.delete(id);
    }
    syncSelectAllButtonToSelection();
    updateBulkCheckoutState();
}

function toggleGroupCheckboxes(groupCb) {
    const groupBoxes = /** @type {NodeListOf<HTMLInputElement>} */ (
        document.querySelectorAll(bulkGroupSelector(groupCb))
    );
    groupBoxes.forEach(function (cb) {
        cb.checked = groupCb.checked;
        const id = cb.getAttribute('data-id');
        if (id) {
            if (groupCb.checked) bulkSelectedIds.add(id);
            else bulkSelectedIds.delete(id);
        }
    });
    syncSelectAllButtonToSelection();
    updateBulkCheckoutState();
}

let bulkSelectAllInFlight = false;

function setSelectAllButtonState(on) {
    const btn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkSelectAllBtn')
    );
    if (!btn) return;
    btn.setAttribute('data-state', on ? 'on' : 'off');
    const label = btn.getAttribute(on ? 'data-text-clear' : 'data-text-select');
    if (label) btn.textContent = label;
}

function syncSelectAllButtonToSelection() {
    if (bulkSelectedIds.size === 0) setSelectAllButtonState(false);
}

function clearBulkSelection() {
    bulkSelectedIds.clear();
    applyBulkSelectionToLoaded(document);
    setSelectAllButtonState(false);
    updateBulkCheckoutState();
}

function fetchAllMatchingEntryIds() {
    const itemsView = myLager.lagerIsItemsView();
    const activeMaterials = myLager.collectChecked('matCheck');
    const activeGameItems = myLager.collectChecked('gameItemCheck');
    const activeLocations = myLager.collectChecked('locCheck');
    const activeJobOrders = myLager.collectChecked('jobOrderCheck');
    const activeMissions = myLager.collectChecked('missionCheck');
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    const minQuality = minQualitySelect ? minQualitySelect.value : '';
    const personalOnly = personalFlagChecked('personalOnly', 'itemPersonalOnly');
    const nonPersonalOnly = personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly');

    const url = new URL(window.location.origin + '/inventory/my/entry-ids');
    if (itemsView) url.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => url.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => url.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => url.searchParams.append('locationIds', l));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));
    if (personalOnly) url.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) url.searchParams.append('nonPersonalOnly', 'true');

    return fetch(url, {
        method: 'GET',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
        credentials: 'same-origin',
    }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    });
}

async function toggleSelectAllInView() {
    const btn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkSelectAllBtn')
    );
    if (btn && btn.getAttribute('data-state') === 'on') {
        clearBulkSelection();
        return;
    }
    if (bulkSelectAllInFlight) return;
    bulkSelectAllInFlight = true;
    if (btn) btn.disabled = true;
    try {
        const ids = await fetchAllMatchingEntryIds();
        bulkSelectedIds.clear();
        (Array.isArray(ids) ? ids : []).forEach(function (id) {
            if (id) bulkSelectedIds.add(String(id));
        });
        applyBulkSelectionToLoaded(document);
        setSelectAllButtonState(bulkSelectedIds.size > 0);
        updateBulkCheckoutState();
    } catch (e) {
        console.error('Failed to select all inventory entries', e);
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(bulkI18n.selectAllFailed);
        }
    } finally {
        bulkSelectAllInFlight = false;
        if (btn) btn.disabled = false;
    }
}

function openBulkCheckoutModal() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        showInventoryToast('error', bulkI18n.errorEmpty);
        return;
    }
    const msg = bulkI18n.modalMessage.replace('{0}', String(ids.length));
    const msgEl = document.getElementById('bulkCheckoutModalMessage');
    if (msgEl) msgEl.textContent = msg;
    setMyDisplay('bulkCheckoutModal', 'flex');
}

function closeBulkCheckoutModal() {
    setMyDisplay('bulkCheckoutModal', 'none');
}

async function executeBulkCheckout() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        closeBulkCheckoutModal();
        showInventoryToast('error', bulkI18n.errorEmpty);
        return;
    }
    closeBulkCheckoutModal();
    if (!window.krtFetch) {
        return;
    }
    const affectedOrderIds = [];
    ids.forEach(function (itemId) {
        myLager.collectLeafOrderIds(itemId).forEach(function (orderId) {
            if (affectedOrderIds.indexOf(orderId) < 0) affectedOrderIds.push(orderId);
        });
    });
    await window.krtFetch.write({
        method: 'POST',
        url: '/inventory/bulk-checkout',
        payload: { itemIds: ids },
        toast: false,
        errorMessage: bulkI18n.errorFailed,
        conflict: inventoryConflictI18n,
        onSuccess() {
            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(
                    bulkI18n.success.replace('{0}', String(ids.length)),
                );
            }
            filterMyInventory();
            broadcastInventoryChanged();
            myLager.broadcastOrdersChanged(affectedOrderIds);
            myLager.broadcastBoardChanged();
        },
    });
}

window.addEventListener('click', function (event) {
    const bulkModal = document.getElementById('bulkCheckoutModal');
    if (event.target === bulkModal) closeBulkCheckoutModal();
    const rebookModal = document.getElementById('bulkRebookModal');
    if (event.target === rebookModal) closeBulkRebookModal();
});

let bulkRebookInFlight = false;

function bulkRebookMode() {
    const checked = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="bulkRebookMode"]:checked')
    );
    return checked ? checked.value : 'LOCATION';
}

function showBulkRebookError(message) {
    if (typeof window.showFrontendErrorToast === 'function') {
        window.showFrontendErrorToast(message);
    }
}

function currentInventoryUserId() {
    const table = document.getElementById('inventoryTable');
    return table ? table.getAttribute('data-user-id') : null;
}

function refreshBulkRebookOrgUnitPicker() {
    const wrapper = document.getElementById('bulkRebookOrgUnitWrapper');
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('bulkRebookOrgUnitId')
    );
    if (!wrapper || !select) return;
    const mode = bulkRebookMode();
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (mode === 'PERSONALIZE') return;
    const userSelect = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookTargetUserId')
    );
    const ownerId =
        mode === 'LOCATION' && userSelect && userSelect.value
            ? userSelect.value
            : currentInventoryUserId();
    if (!ownerId) return;
    fetch('/users/' + encodeURIComponent(ownerId) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            if (!Array.isArray(memberships) || memberships.length < 1) return;
            memberships.forEach(function (opt) {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            wrapper.style.display = 'block';
        })
        .catch(function () {
            wrapper.style.display = 'none';
        });
}

function toggleBulkRebookMode() {
    const mode = bulkRebookMode();
    const transferFields = document.getElementById('bulkRebookTransferFields');
    const hint = document.getElementById('bulkRebookPersonalHint');
    if (transferFields) transferFields.style.display = mode === 'LOCATION' ? 'block' : 'none';
    if (hint) {
        hint.classList.toggle('krtm-hidden', mode === 'LOCATION');
        if (mode === 'PERSONALIZE') hint.textContent = bulkRebookI18n.hintPersonalize;
        else if (mode === 'DEPERSONALIZE') hint.textContent = bulkRebookI18n.hintDepersonalize;
    }
    refreshBulkRebookOrgUnitPicker();
}

function openBulkRebookModal() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        showBulkRebookError(bulkRebookI18n.errorEmpty);
        return;
    }
    const msgEl = document.getElementById('bulkRebookModalMessage');
    if (msgEl) msgEl.textContent = bulkRebookI18n.modalMessage.replace('{0}', String(ids.length));

    const locationRadio = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="bulkRebookMode"][value="LOCATION"]')
    );
    if (locationRadio) locationRadio.checked = true;
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookMergeStock')
    );
    if (mergeCheckbox) mergeCheckbox.checked = false;
    const tl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookTargetLocationId')
    );
    if (tl && tl.krtCombobox) tl.krtCombobox.setValue('');
    else if (tl) tl.value = '';
    const tu = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookTargetUserId')
    );
    const me = currentInventoryUserId();
    if (tu && me) tu.value = me;

    toggleBulkRebookMode();
    setMyDisplay('bulkRebookModal', 'flex');
}

function closeBulkRebookModal() {
    setMyDisplay('bulkRebookModal', 'none');
}

function reportBulkRebookOutcome(body) {
    const rebooked = body && typeof body.rebooked === 'number' ? body.rebooked : 0;
    const skipped = body && typeof body.skipped === 'number' ? body.skipped : 0;
    if (rebooked === 0) {
        showBulkRebookError(bulkRebookI18n.noneMoved);
        return;
    }
    const message =
        skipped > 0
            ? bulkRebookI18n.successPartial
                  .replace('{0}', String(rebooked))
                  .replace('{1}', String(skipped))
            : bulkRebookI18n.success.replace('{0}', String(rebooked));
    if (typeof window.showFrontendSuccessToast === 'function') {
        window.showFrontendSuccessToast(message);
    }
}

function submitBulkRebook(event) {
    if (event) event.preventDefault();
    if (bulkRebookInFlight || !window.krtFetch) return;
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        closeBulkRebookModal();
        showBulkRebookError(bulkRebookI18n.errorEmpty);
        return;
    }
    const mode = bulkRebookMode();

    /** @type {string | null} */
    let targetUserId = null;
    /** @type {string | null} */
    let targetLocationId = null;
    if (mode === 'LOCATION') {
        targetUserId = myFieldValue('bulkRebookTargetUserId') || null;
        targetLocationId = myFieldValue('bulkRebookTargetLocationId') || null;
        if (!targetUserId && !targetLocationId) {
            showBulkRebookError(bulkRebookI18n.errorNoTarget);
            return;
        }
    }

    const orgWrapper = document.getElementById('bulkRebookOrgUnitWrapper');
    const orgSelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('bulkRebookOrgUnitId')
    );
    const orgUnitId =
        orgWrapper && window.getComputedStyle(orgWrapper).display !== 'none' && orgSelect
            ? orgSelect.value || null
            : null;
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookMergeStock')
    );

    const affectedOrderIds = [];
    ids.forEach(function (itemId) {
        myLager.collectLeafOrderIds(itemId).forEach(function (orderId) {
            if (affectedOrderIds.indexOf(orderId) < 0) affectedOrderIds.push(orderId);
        });
    });

    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkRebookSubmitBtn')
    );
    bulkRebookInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/bulk-rebook',
            payload: {
                itemIds: ids,
                mode,
                targetUserId,
                targetLocationId,
                targetOwningOrgUnitId: orgUnitId,
                mergeStock: !!(mergeCheckbox && mergeCheckbox.checked),
            },
            toast: false,
            errorMessage: bulkRebookI18n.errorFailed,
            conflict: inventoryConflictI18n,
            onSuccess(body) {
                closeBulkRebookModal();
                reportBulkRebookOutcome(body);
                filterMyInventory();
                broadcastInventoryChanged();
                myLager.broadcastOrdersChanged(affectedOrderIds);
                myLager.broadcastBoardChanged();
            },
        })
        .then(function () {
            bulkRebookInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

/**
 * A checkbox of this page by id (the select-all box of a multi-select family, a personal flag).
 *
 * @param {string | null} id the element id
 * @returns {HTMLInputElement | null} the checkbox, or null when absent
 */
function myCheckbox(id) {
    return id ? /** @type {HTMLInputElement | null} */ (document.getElementById(id)) : null;
}

/**
 * Clears the counterpart of a checked personal-flag filter and re-runs the filter.
 *
 * @param {HTMLElement} el the toggled checkbox
 */
function togglePersonalFilter(el) {
    const box = /** @type {HTMLInputElement} */ (el);
    if (box && box.checked) {
        /** @type {Record<string, string>} */
        const counterparts = {
            personalOnly: 'nonPersonalOnly',
            nonPersonalOnly: 'personalOnly',
            itemPersonalOnly: 'itemNonPersonalOnly',
            itemNonPersonalOnly: 'itemPersonalOnly',
        };
        const other = myCheckbox(counterparts[box.id]);
        if (other) other.checked = false;
    }
    filterMyInventory();
}

/**
 * Whether the personal-flag checkbox of the active view is checked.
 *
 * @param {string} materialId the material view's checkbox id
 * @param {string} itemId the items view's checkbox id
 * @returns {boolean} the flag
 */
function personalFlagChecked(materialId, itemId) {
    const el = myCheckbox(materialId) || myCheckbox(itemId);
    return el ? el.checked : false;
}

const MY_INVENTORY_FILTER_KEY = 'inventory_my_filters';

const MY_INVENTORY_FILTER_PARAMS = [
    'materialIds',
    'gameItemIds',
    'locationIds',
    'minQuality',
    'jobOrderIds',
    'missionIds',
    'personalOnly',
    'nonPersonalOnly',
];

function readMyInventoryFilterPref() {
    try {
        const raw = localStorage.getItem(MY_INVENTORY_FILTER_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null;
    }
}

function writeMyInventoryFilterPref(value) {
    try {
        localStorage.setItem(MY_INVENTORY_FILTER_KEY, JSON.stringify(value));
    } catch (_e) {}
}

function myInventoryFilterSelection(className) {
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

function snapshotMyInventoryFilters() {
    if (myLager.lagerIsItemsView()) {
        return {
            gameItems: myInventoryFilterSelection('gameItemCheck'),
            locations: myInventoryFilterSelection('locCheck'),
            jobOrders: myInventoryFilterSelection('jobOrderCheck'),
            personalOnly: personalFlagChecked('personalOnly', 'itemPersonalOnly'),
            nonPersonalOnly: personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly'),
        };
    }
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    return {
        materials: myInventoryFilterSelection('matCheck'),
        locations: myInventoryFilterSelection('locCheck'),
        minQuality: minQualitySelect ? minQualitySelect.value : '',
        jobOrders: myInventoryFilterSelection('jobOrderCheck'),
        missions: myInventoryFilterSelection('missionCheck'),
        personalOnly: personalFlagChecked('personalOnly', 'itemPersonalOnly'),
        nonPersonalOnly: personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly'),
    };
}

function persistMyInventoryFilters() {
    const stored = readMyInventoryFilterPref() || {};
    stored[myLager.lagerIsItemsView() ? 'items' : 'material'] = snapshotMyInventoryFilters();
    writeMyInventoryFilterPref(stored);
}

function applyMySavedSelection(saved, checkClass, allId, headerId) {
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
    myLager.updateSelectState(allId, checkClass, headerId);
    return any;
}

function restoreMyInventoryFilters() {
    let params;
    try {
        params = new URLSearchParams(window.location.search);
    } catch (_e) {
        return false;
    }
    if (MY_INVENTORY_FILTER_PARAMS.some((p) => params.has(p))) {
        persistMyInventoryFilters();
        return false;
    }
    const stored = readMyInventoryFilterPref();
    const saved = stored ? stored[myLager.lagerIsItemsView() ? 'items' : 'material'] : null;
    if (!saved || typeof saved !== 'object') return false;
    let changed = false;
    let families;
    if (myLager.lagerIsItemsView()) {
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
        if (applyMySavedSelection(f[0], f[1], f[2], f[3])) changed = true;
    });
    const personalBox =
        /** @type {HTMLInputElement | null} */ (document.getElementById('personalOnly')) ||
        /** @type {HTMLInputElement | null} */ (document.getElementById('itemPersonalOnly'));
    const nonPersonalBox =
        /** @type {HTMLInputElement | null} */ (document.getElementById('nonPersonalOnly')) ||
        /** @type {HTMLInputElement | null} */ (document.getElementById('itemNonPersonalOnly'));
    if (personalBox && saved.personalOnly === true) {
        personalBox.checked = true;
        changed = true;
    } else if (nonPersonalBox && saved.nonPersonalOnly === true) {
        nonPersonalBox.checked = true;
        changed = true;
    }
    return changed;
}

function countActiveMyInventoryFilters() {
    const snapshot = snapshotMyInventoryFilters();
    let active = 0;
    ['materials', 'gameItems', 'locations', 'jobOrders', 'missions'].forEach(function (dimension) {
        if (Array.isArray(snapshot[dimension]) && snapshot[dimension].length > 0) active++;
    });
    if (typeof snapshot.minQuality === 'string' && snapshot.minQuality !== '') active++;
    if (snapshot.personalOnly === true) active++;
    if (snapshot.nonPersonalOnly === true) active++;
    return active;
}

function filterMyInventory() {
    clearBulkSelection();
    persistMyInventoryFilters();
    if (window.krtFilterPanel) window.krtFilterPanel.refresh('myFilterPanel');
    const itemsView = myLager.lagerIsItemsView();
    const activeMaterials = myLager.collectChecked('matCheck');
    const activeGameItems = myLager.collectChecked('gameItemCheck');
    const activeLocations = myLager.collectChecked('locCheck');
    const activeJobOrders = myLager.collectChecked('jobOrderCheck');
    const activeMissions = myLager.collectChecked('missionCheck');
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    const minQuality = minQualitySelect ? minQualitySelect.value : '';
    const personalOnly = personalFlagChecked('personalOnly', 'itemPersonalOnly');
    const nonPersonalOnly = personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly');

    const container = document.getElementById('myInventoryTableContainer');
    if (!container) return;
    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(window.location.origin + '/inventory/my');
    url.searchParams.append('fragment', 'true');
    if (itemsView) url.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => url.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => url.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => url.searchParams.append('locationIds', l));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));
    if (personalOnly) url.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) url.searchParams.append('nonPersonalOnly', 'true');

    const visibleUrl = new URL(window.location.origin + '/inventory/my');
    if (itemsView) visibleUrl.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => visibleUrl.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => visibleUrl.searchParams.append('locationIds', l));
    if (minQuality) visibleUrl.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => visibleUrl.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => visibleUrl.searchParams.append('missionIds', m));
    if (personalOnly) visibleUrl.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) visibleUrl.searchParams.append('nonPersonalOnly', 'true');
    try {
        window.history.replaceState({}, '', visibleUrl.toString());
    } catch {}

    fetch(url, { method: 'GET', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then((response) => response.text())
        .then((html) => {
            window.krtFetch.replaceWithTrustedHtml(container, html);
            myLager.restoreExpandedTree();
        })
        .catch((error) => {
            console.error('Error fetching filtered personal inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

function resetMyInventoryFilter() {
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
        const el = myCheckbox(id);
        if (el) el.checked = false;
    });
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    if (minQualitySelect) minQualitySelect.value = '';
    ['personalOnly', 'nonPersonalOnly', 'itemPersonalOnly', 'itemNonPersonalOnly'].forEach(
        function (id) {
            const el = myCheckbox(id);
            if (el) el.checked = false;
        },
    );
    if (document.getElementById('materialHeader'))
        myLager.updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('gameItemHeader'))
        myLager.updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    if (document.getElementById('locationHeader'))
        myLager.updateSelectState('locAll', 'locCheck', 'locationHeader');
    if (document.getElementById('itemLocationHeader'))
        myLager.updateSelectState('itemLocAll', 'locCheck', 'itemLocationHeader');
    if (document.getElementById('jobOrderHeader'))
        myLager.updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    if (document.getElementById('itemJobOrderHeader'))
        myLager.updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
    if (document.getElementById('missionHeader'))
        myLager.updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    filterMyInventory();
}

const INVENTORY_MY_SECTIONS = {
    stock: { container: '#myInventoryTableContainer', fragmentValue: 'stock' },
};

function broadcastInventoryChanged() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('inventory', Object.keys(INVENTORY_MY_SECTIONS));
    }
}
window.krtNotifyInventoryChanged = broadcastInventoryChanged;

if (
    window.krtLiveSync &&
    typeof window.krtLiveSync.createReceiver === 'function' &&
    document.getElementById('myInventoryTableContainer')
) {
    window.krtLiveSync.createReceiver({
        topic: 'inventory',
        sections: INVENTORY_MY_SECTIONS,
        coalesceMs: 1500,
        refresh() {
            filterMyInventory();
        },
    });
}

const INVENTORY_MY_ORDER_SECTIONS = {
    demand: { container: '#myInventoryTableContainer' },
};

if (
    window.krtLiveSync &&
    typeof window.krtLiveSync.createReceiver === 'function' &&
    document.getElementById('myInventoryTableContainer')
) {
    window.krtLiveSync.createReceiver({
        topic: 'orders',
        sections: INVENTORY_MY_ORDER_SECTIONS,
        coalesceMs: 1500,
        refresh() {
            filterMyInventory();
        },
    });
}

document.addEventListener('DOMContentLoaded', function () {
    const filtersRestored = restoreMyInventoryFilters();
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('matCheck')
        ).length > 0
    ) {
        myLager.updateSelectState('matAll', 'matCheck', 'materialHeader');
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('gameItemCheck')
        ).length > 0
    ) {
        myLager.updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('locCheck')
        ).length > 0
    ) {
        if (document.getElementById('itemLocationHeader')) {
            myLager.updateSelectState('itemLocAll', 'locCheck', 'itemLocationHeader');
        } else {
            myLager.updateSelectState('locAll', 'locCheck', 'locationHeader');
        }
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('jobOrderCheck')
        ).length > 0
    ) {
        if (document.getElementById('itemJobOrderHeader')) {
            myLager.updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
        } else {
            myLager.updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
        }
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('missionCheck')
        ).length > 0
    ) {
        myLager.updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    }
    if (window.krtFilterPanel) {
        window.krtFilterPanel.registerCounter('myFilterPanel', countActiveMyInventoryFilters);
        window.krtFilterPanel.refresh('myFilterPanel');
    }
    if (filtersRestored) filterMyInventory();
});

let umbuchenItemId = null;
let umbuchenCurrentOwningOrgUnitId = null;
let umbuchenInFlight = false;

/**
 * Fills the de-personalize org-unit picker with the row owner's memberships of all four org-unit
 * kinds, preset to the row's owning unit or else the owner's primary unit; hidden when the owner
 * has no membership.
 */
function refreshUmbuchenPersonalOrgUnitPicker(ownerId) {
    const wrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('umbuchenPersonalOrgUnitId')
    );
    if (!wrapper || !select) return;
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (!ownerId) return;
    fetch('/users/' + encodeURIComponent(ownerId) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            if (!Array.isArray(memberships) || memberships.length < 1) return;
            memberships.forEach(function (opt) {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            if (
                umbuchenCurrentOwningOrgUnitId &&
                memberships.some(function (m) {
                    return m.orgUnitId === umbuchenCurrentOwningOrgUnitId;
                })
            ) {
                select.value = umbuchenCurrentOwningOrgUnitId;
            }
            wrapper.style.display = 'block';
        })
        .catch(function () {
            wrapper.style.display = 'none';
        });
}

function toggleUmbuchenMode() {
    const modeInput = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="umbuchenMode"]:checked')
    );
    const mode = modeInput ? modeInput.value : 'LOCATION';
    const transferFields = document.getElementById('umbuchenTransferFields');
    const personalFields = document.getElementById('umbuchenPersonalFields');
    const targetUser = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetUserId')
    );
    const targetLocation = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetLocationId')
    );
    if (!transferFields || !personalFields || !targetUser || !targetLocation) return;
    if (mode === 'PERSONAL') {
        transferFields.style.display = 'none';
        personalFields.style.display = 'block';
        targetUser.required = false;
        targetLocation.required = false;
    } else {
        transferFields.style.display = 'block';
        personalFields.style.display = 'none';
        targetUser.required = true;
        targetLocation.required = true;
    }
    if (window.krtHerkunft) {
        window.krtHerkunft.recompute('umbuchen');
    }
}

function openUmbuchenModal(
    id,
    amount,
    version,
    materialId,
    userId,
    locationId,
    locationName,
    quantityType,
    personal,
    hasAssoc,
    owningOrgUnitId,
) {
    umbuchenItemId = id;
    umbuchenCurrentOwningOrgUnitId = owningOrgUnitId || null;
    myLager.setUmbuchenCurrentOwningOrgUnit(umbuchenCurrentOwningOrgUnitId);
    const isScu = quantityType !== 'PIECE';
    const amountEl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenAmount')
    );
    const targetEl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetAmount')
    );
    if (!amountEl || !targetEl) return;
    amountEl.setAttribute('step', isScu ? '0.001' : '1');
    targetEl.setAttribute('step', isScu ? '0.001' : '1');
    const targetHint = document.getElementById('umbuchen-target-scu-hint');
    const amountHint = document.getElementById('umbuchen-amount-scu-hint');
    if (targetHint) targetHint.classList.toggle('krtm-hidden', !isScu);
    if (amountHint) amountHint.classList.toggle('krtm-hidden', !isScu);

    const mergeRow = document.getElementById('umbuchenMergeRow');
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenMergeStock')
    );
    if (mergeCheckbox) mergeCheckbox.checked = false;
    if (mergeRow) mergeRow.classList.toggle('krtm-hidden', !isScu);

    amountEl.value = amount ?? '';
    amountEl.max = amount ?? '';
    targetEl.value = '0';
    setMyFieldValue('umbuchenMaxAmount', amount);
    setMyFieldValue('umbuchenVersion', version);
    setMyFieldValue('umbuchenSourcePersonal', personal ? 'true' : 'false');
    const amountOf = document.getElementById('umbuchenAmountOfText');
    if (amountOf)
        amountOf.textContent = (amountOf.getAttribute('data-template') ?? '').replace(
            '{0}',
            amount ?? '',
        );

    const tu = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetUserId')
    );
    const tl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetLocationId')
    );
    if (tu) tu.value = userId;
    if (tl && tl.krtCombobox) {
        tl.krtCombobox.setValue(locationId, locationName);
    } else if (tl) {
        tl.value = locationId;
    }
    myLager.refreshUmbuchenTransferOrgUnitPicker();

    const personalizing = !personal;
    const modeText = document.getElementById('umbuchenModePersonalText');
    const personalLabel = document.getElementById('umbuchenModePersonalLabel');
    const hint = document.getElementById('umbuchenPersonalHint');
    if (modeText)
        modeText.textContent = personalizing
            ? umbuchenI18n.modePersonalize
            : umbuchenI18n.modeDepersonalize;
    if (hint)
        hint.textContent = personalizing
            ? umbuchenI18n.hintPersonalize
            : umbuchenI18n.hintDepersonalize;
    const personalDisabled = personalizing && hasAssoc;
    if (personalLabel) personalLabel.style.display = personalDisabled ? 'none' : '';
    if (!personalizing) {
        refreshUmbuchenPersonalOrgUnitPicker(userId);
    } else {
        const w = document.getElementById('umbuchenPersonalOrgUnitWrapper');
        if (w) w.style.display = 'none';
    }

    const locationMode = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="umbuchenMode"][value="LOCATION"]')
    );
    if (locationMode) locationMode.checked = true;
    toggleUmbuchenMode();
    setMyDisplay('umbuchenModal', 'flex');
    if (window.krtHerkunft) {
        window.krtHerkunft.populate('umbuchen', id);
    }
}

function submitUmbuchen(event) {
    if (event && event.defaultPrevented) return;
    if (event) event.preventDefault();
    if (umbuchenInFlight || !window.krtFetch || !umbuchenItemId) return;
    const modeInput = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="umbuchenMode"]:checked')
    );
    const mode = modeInput ? modeInput.value : 'LOCATION';
    const amountValue = myFieldValue('umbuchenAmount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountValue)
        : parseFloat(amountValue);
    const version = parseInt(myFieldValue('umbuchenVersion'), 10);
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('umbuchenSubmitBtn')
    );
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenMergeStock')
    );
    const mergeStock = !!(mergeCheckbox && mergeCheckbox.checked);

    let url, payload;
    if (mode === 'PERSONAL') {
        url = '/inventory/' + umbuchenItemId + '/personal-rebook';
        const orgWrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
        const orgSelect = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('umbuchenPersonalOrgUnitId')
        );
        const orgUnitId =
            orgWrapper && window.getComputedStyle(orgWrapper).display !== 'none' && orgSelect
                ? orgSelect.value || null
                : null;
        payload = {
            amount,
            version,
            targetOwningOrgUnitId: orgUnitId,
            mergeStock,
        };
    } else {
        if (window.krtHerkunft && !window.krtHerkunft.isValid('umbuchen')) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.overallocated);
            }
            return;
        }
        const reductions = window.krtHerkunft
            ? window.krtHerkunft.collect('umbuchen')
            : { jobOrderReductions: null, missionReductions: null };
        url = '/inventory/' + umbuchenItemId + '/transfer';
        payload = {
            amount,
            type: 'TRANSFER',
            targetUserId: myFieldValue('umbuchenTargetUserId') || null,
            targetLocationId: myFieldValue('umbuchenTargetLocationId') || null,
            targetOwningOrgUnitId: myFieldValue('umbuchenTargetOwningOrgUnitId') || null,
            version,
            mergeStock,
            jobOrderReductions: reductions.jobOrderReductions,
            missionReductions: reductions.missionReductions,
        };
    }

    const affectedOrderIds = mode === 'PERSONAL' ? [] : myLager.collectLeafOrderIds(umbuchenItemId);
    umbuchenInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url,
            payload,
            successMessage: umbuchenI18n.success,
            errorMessage: umbuchenI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess() {
                myLager.closeUmbuchenModal();
                filterMyInventory();
                broadcastInventoryChanged();
                myLager.broadcastOrdersChanged(affectedOrderIds);
                myLager.broadcastBoardChanged();
            },
        })
        .then(function () {
            umbuchenInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

/**
 * The row being re-stamped, or the selection when {@code itemIds} is set (REQ-INV-052).
 *
 * @type {{ id: string | null, version: number | null, itemIds: string[] | null }}
 */
let orgUnitChangeTarget = { id: null, version: null, itemIds: null };
let orgUnitChangeInFlight = false;

/**
 * Fills the org-unit change picker with "no unit" plus the caller's direct memberships of all four
 * kinds, preset to the row's current unit.
 *
 * @param {string | null} currentOrgUnitId the row's current unit, or null
 */
function fillOrgUnitChangePicker(currentOrgUnitId) {
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('orgUnitChangeTarget')
    );
    if (!select) return;
    select.innerHTML = '';
    const none = document.createElement('option');
    none.value = '';
    none.textContent = orgUnitChangeI18n.none;
    select.appendChild(none);
    const me = currentInventoryUserId();
    if (!me) return;
    fetch('/users/' + encodeURIComponent(me) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            if (!Array.isArray(memberships)) return;
            memberships.forEach(function (opt) {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            select.value =
                currentOrgUnitId &&
                memberships.some(function (m) {
                    return m.orgUnitId === currentOrgUnitId;
                })
                    ? currentOrgUnitId
                    : '';
        })
        .catch(function () {
            select.value = '';
        });
}

/**
 * Opens the org-unit change dialog for one personal row.
 *
 * @param {Element} el the row's action button
 */
function openOrgUnitChangeModal(el) {
    const version = parseInt(el.getAttribute('data-version') || '', 10);
    orgUnitChangeTarget = {
        id: el.getAttribute('data-id'),
        version: Number.isNaN(version) ? null : version,
        itemIds: null,
    };
    const msgEl = document.getElementById('orgUnitChangeMessage');
    if (msgEl) msgEl.textContent = orgUnitChangeI18n.messageSingle;
    const mergeRow = document.getElementById('orgUnitChangeMergeRow');
    if (mergeRow) {
        mergeRow.style.display = el.getAttribute('data-quantity-type') === 'SCU' ? '' : 'none';
    }
    resetOrgUnitChangeMerge();
    fillOrgUnitChangePicker(el.getAttribute('data-owning-org-unit-id') || null);
    setMyDisplay('orgUnitChangeModal', 'flex');
}

/** Opens the org-unit change dialog for the marked rows. */
function openBulkOrgUnitChangeModal() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        showBulkRebookError(orgUnitChangeI18n.errorEmpty);
        return;
    }
    orgUnitChangeTarget = { id: null, version: null, itemIds: ids };
    const msgEl = document.getElementById('orgUnitChangeMessage');
    if (msgEl) msgEl.textContent = orgUnitChangeI18n.messageBulk.replace('{0}', String(ids.length));
    const mergeRow = document.getElementById('orgUnitChangeMergeRow');
    if (mergeRow) mergeRow.style.display = '';
    resetOrgUnitChangeMerge();
    fillOrgUnitChangePicker(null);
    setMyDisplay('orgUnitChangeModal', 'flex');
}

function resetOrgUnitChangeMerge() {
    const merge = /** @type {HTMLInputElement | null} */ (
        document.getElementById('orgUnitChangeMergeStock')
    );
    if (merge) merge.checked = false;
}

function closeOrgUnitChangeModal() {
    setMyDisplay('orgUnitChangeModal', 'none');
}

/**
 * Reports a bulk org-unit change's counts as a toast.
 *
 * @param {{ changed?: number, skipped?: number } | null} body the result counts
 */
function reportBulkOrgUnitOutcome(body) {
    const changed = body && typeof body.changed === 'number' ? body.changed : 0;
    const skipped = body && typeof body.skipped === 'number' ? body.skipped : 0;
    if (changed === 0) {
        showBulkRebookError(orgUnitChangeI18n.noneChanged);
        return;
    }
    const message =
        skipped > 0
            ? orgUnitChangeI18n.successBulkPartial
                  .replace('{0}', String(changed))
                  .replace('{1}', String(skipped))
            : orgUnitChangeI18n.successBulk.replace('{0}', String(changed));
    if (typeof window.showFrontendSuccessToast === 'function') {
        window.showFrontendSuccessToast(message);
    }
}

/**
 * Submits the org-unit change for the one row or the marked selection, then re-renders the list in
 * place and tells peers.
 *
 * @param {Event} event the form submit
 */
function submitOrgUnitChange(event) {
    if (event) event.preventDefault();
    if (orgUnitChangeInFlight || !window.krtFetch) return;
    const orgUnitId = myFieldValue('orgUnitChangeTarget') || null;
    const merge = /** @type {HTMLInputElement | null} */ (
        document.getElementById('orgUnitChangeMergeStock')
    );
    const mergeStock = !!(merge && merge.checked);
    const bulk = Array.isArray(orgUnitChangeTarget.itemIds);
    if (!bulk && !orgUnitChangeTarget.id) return;
    const url = bulk
        ? '/inventory/bulk-org-unit'
        : '/inventory/' + orgUnitChangeTarget.id + '/org-unit';
    const payload = bulk
        ? { itemIds: orgUnitChangeTarget.itemIds, targetOwningOrgUnitId: orgUnitId, mergeStock }
        : { version: orgUnitChangeTarget.version, targetOwningOrgUnitId: orgUnitId, mergeStock };
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('orgUnitChangeSubmitBtn')
    );
    orgUnitChangeInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url,
            payload,
            toast: !bulk,
            successMessage: orgUnitChangeI18n.success,
            errorMessage: orgUnitChangeI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess(body) {
                closeOrgUnitChangeModal();
                if (bulk) reportBulkOrgUnitOutcome(body);
                filterMyInventory();
                broadcastInventoryChanged();
                myLager.broadcastBoardChanged();
            },
        })
        .then(function () {
            orgUnitChangeInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'inv-my-toggle-multi', function (el) {
        myLager.toggleMultiSelect(el.getAttribute('data-multi-target'));
    });
    window.krtEvents.on('change', 'inv-my-toggle-all', function (el) {
        myLager.toggleSelectAll(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterMyInventory();
    });
    window.krtEvents.on('change', 'inv-my-update-state', function (el) {
        myLager.updateSelectState(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterMyInventory();
    });
    window.krtEvents.on('change', 'inv-my-filter', filterMyInventory);
    window.krtEvents.on('change', 'inv-my-personal-filter', togglePersonalFilter);
    window.krtEvents.on('click', 'inv-my-reset-filter', resetMyInventoryFilter);
    window.krtEvents.on('click', 'inv-my-open-bulk', openBulkCheckoutModal);
    window.krtEvents.on('change', 'inv-my-toggle-group-cb', function (el) {
        toggleGroupCheckboxes(el);
    });
    window.krtEvents.on('change', 'inv-my-update-bulk-state', function (el) {
        onEntryCheckboxToggle(el);
    });
    window.krtEvents.on('click', 'inv-my-select-all', toggleSelectAllInView);
    window.krtEvents.on('click', 'inv-my-umbuchen', function (el) {
        openUmbuchenModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
            el.getAttribute('data-material-id'),
            el.getAttribute('data-user-id'),
            el.getAttribute('data-location-id'),
            el.getAttribute('data-location-name'),
            el.getAttribute('data-quantity-type'),
            el.getAttribute('data-personal') === 'true',
            el.getAttribute('data-has-assoc') === 'true',
            el.getAttribute('data-owning-org-unit-id'),
        );
    });
    window.krtEvents.on('click', 'inv-my-open-note', function (el) {
        openNoteModal(el);
    });
    window.krtEvents.on('change', 'inv-my-toggle-umbuchen-mode', toggleUmbuchenMode);
    window.krtEvents.on('click', 'inv-my-close-note', closeNoteModal);
    window.krtEvents.on('input', 'inv-my-update-note-counter', updateNoteCounter);
    window.krtEvents.on('click', 'inv-my-save-note', saveNote);
    window.krtEvents.on('click', 'inv-my-remove-note', removeNote);
    window.krtEvents.on('click', 'inv-my-close-bulk', closeBulkCheckoutModal);
    window.krtEvents.on('click', 'inv-my-execute-bulk', executeBulkCheckout);
    window.krtEvents.on('click', 'inv-my-open-bulk-rebook', openBulkRebookModal);
    window.krtEvents.on('click', 'inv-my-close-bulk-rebook', closeBulkRebookModal);
    window.krtEvents.on('change', 'inv-my-toggle-bulk-rebook-mode', toggleBulkRebookMode);
    window.krtEvents.on(
        'change',
        'inv-my-bulk-rebook-user-changed',
        refreshBulkRebookOrgUnitPicker,
    );
    window.krtEvents.on('click', 'inv-my-org-unit', openOrgUnitChangeModal);
    window.krtEvents.on('click', 'inv-my-open-bulk-org-unit', openBulkOrgUnitChangeModal);
    window.krtEvents.on('click', 'inv-my-close-org-unit', closeOrgUnitChangeModal);
}

const umbuchenFormEl = document.getElementById('umbuchenForm');
if (umbuchenFormEl) {
    umbuchenFormEl.addEventListener('submit', submitUmbuchen);
}
const bulkRebookFormEl = document.getElementById('bulkRebookForm');
if (bulkRebookFormEl) {
    bulkRebookFormEl.addEventListener('submit', submitBulkRebook);
}
const orgUnitChangeFormEl = document.getElementById('orgUnitChangeForm');
if (orgUnitChangeFormEl) {
    orgUnitChangeFormEl.addEventListener('submit', submitOrgUnitChange);
}

myLager.bind();
