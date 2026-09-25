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

/*
 * Page module for the squadron-wide inventory admin view (templates/inventory-admin.html,
 * route /inventory/all), extracted verbatim from the template's two inline script blocks
 * (issue #924 part 2).
 *
 * Covers: the note-modal bindings, the multi-select filter widgets with the AJAX table re-swap
 * (filterInventory replaces #tableContainer via outerHTML) inside their collapsible panel
 * (REQ-INV-037), the Umbuchen (TRANSFER) modal writing through window.krtFetch, the admin
 * delete-all flow, the shared-Lager live-sync room and the page's delegated inv-admin-*
 * krtEvents bindings. The tree, the book-out modal, the allocation chips and the cross-room
 * live-sync pokes are the Lager behaviour this page shares with inventory-my.js; they live in
 * inventory-common.js (FE-SIMP-03) and are wired below through adminLager.
 *
 * Loaded as a classic synchronous script at the end of the body, immediately after the
 * inline i18n bootstrap block that declares the dictionaries listed in the global directive
 * below and after inventory-common.js; the parse-time DOM lookups (delete-all elements,
 * umbuchenForm) rely on that document position.
 */
/* global inventoryConflictI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

// The Lager behaviour shared with the personal page (inventory-common.js). The `admin` prefix keeps
// this top-level name distinct from inventory-my.js's: both are classic scripts sharing one global
// lexical environment (ADR-0069), and the type checker reports a cross-file redeclaration (TS6200).
// inventory-common.js loads ahead of this script (see the header), so the module is installed.
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

// ===================== Per-browser filter persistence (REQ-UI-017) =============================
// One JSON object under a single localStorage key holds BOTH views' filter states
// ({material: {...}, items: {...}}), so switching between the Material and the Items view keeps
// each view's own selection. A multi-select dimension stores its checked values, or null when
// zero or all boxes are checked — both mean "no filter" on this page (zero checked is the
// rendered default; all checked sends every id for the same result). Absence of the key keeps
// the server-rendered defaults. The URL wins: this page mirrors its filters into the address
// bar via history.replaceState, so a load WITH filter query params adopts that state and
// re-persists it — only a bare URL restores from storage. All storage access is guarded so
// privacy modes that deny it degrade to the defaults instead of breaking the page.
const ADMIN_INVENTORY_FILTER_KEY = 'inventory_admin_filters';

// The filter query params this page mirrors into the URL. The view= switch is server-rendered
// navigation, not a filter, so it never counts as one (a /inventory/all?view=items URL is still
// "bare" and restores the items view's stored filters).
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
        return null; // corrupt value / storage unavailable: fall back to the defaults
    }
}

function writeAdminInventoryFilterPref(value) {
    try {
        localStorage.setItem(ADMIN_INVENTORY_FILTER_KEY, JSON.stringify(value));
    } catch (_e) {
        /* storage unavailable */
    }
}

// Checked values of a multi-select dimension, or null when zero or all boxes are checked (= "no
// filter"). Storing null — not the full option list — keeps catalog options added later
// included automatically.
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

// Snapshot of the ACTIVE view's widget state — only that view's filter form exists in the DOM
// (REQ-INV-030), so the other view's slot is never touched by a persist.
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

// Persists the active view's current widget state into its slot of the shared two-view JSON.
// Called from filterInventory, so every filter change — including the reset button's cleared
// state — is stored immediately; a state-preserving re-run (live-sync refresh, modal write) just
// rewrites the same snapshot.
function persistAdminInventoryFilters() {
    const stored = readAdminInventoryFilterPref() || {};
    stored[adminLager.lagerIsItemsView() ? 'items' : 'material'] = snapshotAdminInventoryFilters();
    writeAdminInventoryFilterPref(stored);
}

// Applies a saved multi-select subset to its checkbox family. Saved values whose checkbox no
// longer exists are dropped silently; an entirely-stale subset leaves every box unchecked — this
// page's all-unchecked "no filter" default. The select-all box and the dropdown header text are
// re-synced via updateSelectState. Returns whether any box ended up checked (i.e. the widget now
// differs from the bare-URL rendered default).
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

// Restores the active view's saved filter state on a bare-URL load and returns whether the
// widgets now differ from the rendered default — the caller then triggers the page's existing
// fragment re-fetch exactly once. A load WITH filter query params adopts the server-rendered
// URL state instead and re-persists it (URL wins).
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
    // [saved subset, checkbox class, select-all id, header id] per multi-select family of the
    // active view (the jobOrder family renders item-prefixed all/header ids in the items view).
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
            // A stale quality (no matching option) resets the select to '' — keep the default.
            if (minQualitySelect.value === saved.minQuality) changed = true;
        }
    }
    families.forEach(function (f) {
        if (applyAdminSavedSelection(f[0], f[1], f[2], f[3])) changed = true;
    });
    return changed;
}

// ===================== Filter panel collapse (REQ-INV-037) =====================================
// Twin of the "Mein Lager" panel. The filter widgets are a full row above the table and wrap onto
// lines of their own, which pushes the table down. The panel collapses out of the flow; the
// preference is per browser and lives in the SAME localStorage object as the filter values, under a
// top-level slot. Top-level, not per view, because it describes the page's chrome rather than one
// view's selection — switching Material <-> Items must not silently re-open a panel the user closed.
//
// persistAdminInventoryFilters() re-reads the whole object and replaces only its view slot, so the
// two writers never clobber each other.

// Number of dimensions currently narrowing the table. Derived from the very snapshot the
// persistence layer stores, so a filter dimension added there is counted here automatically
// instead of silently missing from the badge. A multi-select is null in that snapshot when zero
// OR all of its boxes are ticked — both mean "no filter" on this page — so "all ticked"
// correctly counts as nothing. The shared Lager has no personal-entry flags (they are a "Mein
// Lager" dimension), so its snapshot carries none either.
function countActiveAdminInventoryFilters() {
    const snapshot = snapshotAdminInventoryFilters();
    let active = 0;
    ['materials', 'gameItems', 'locations', 'jobOrders', 'missions'].forEach(function (dimension) {
        if (Array.isArray(snapshot[dimension]) && snapshot[dimension].length > 0) active++;
    });
    if (typeof snapshot.minQuality === 'string' && snapshot.minQuality !== '') active++;
    return active;
}

// The collapse itself lives in krt-filter-panel.js (REQ-FE-021). This page supplies only
// the COUNT, because its dimensions are not readable from the panel's own controls, and it
// registers that counter inside DOMContentLoaded rather than here: this file is a
// non-deferred script at the end of the body, so it executes BEFORE the deferred
// krt-filter-panel.js and window.krtFilterPanel does not exist yet at this point. A
// top-level registration would be silently skipped and the panel would fall back to the
// generic scan, which counts the wrong things here.

function filterInventory() {
    // REQ-INV-030: the rebuilt fragment URL is derived from the page's own filter state PLUS the
    // active view, so a filter change, a modal write and a live-sync peer refresh all re-render
    // whichever view (Material or Items) is on screen. Only the active view's filter form exists
    // in the DOM, so the class-driven collections of the other view are simply empty.
    //
    // Persist the current filter selection per browser (REQ-UI-017) — every filter change,
    // including the reset button, funnels through here, so the snapshot is always current.
    persistAdminInventoryFilters();
    // Same funnel, same reason: the count on the (possibly collapsed) toggle must track the
    // widgets, or a collapsed panel starts hiding an active filter.
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

    // Update browser URL (without fragment param) so the filter is bookmarkable / reloadable
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
    } catch {
        /* ignore */
    }

    fetch(url, {
        method: 'GET',
        headers: {
            'X-Requested-With': 'XMLHttpRequest',
        },
    })
        .then((response) => response.text())
        .then((html) => {
            window.krtFetch.replaceWithTrustedHtml(container, html);
            // A fragment swap does not re-fire DOMContentLoaded, so re-apply the persisted tree
            // expansion (REQ-INV-002) — otherwise a filter change or a modal write collapses every
            // row the user had opened.
            adminLager.restoreExpandedTree();
        })
        .catch((error) => {
            console.error('Error fetching filtered inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

// Live peer-sync for the shared Lager (REQ-FE-010 / REQ-FE-015, #1307).
// INVENTORY_ALL_SECTIONS is the single source of truth shared by the write-side broadcast and the
// receive-side refresh (the three-mirror-points rule); its one key mirrors the server-side
// LiveSyncTopicClass.INVENTORY_ALL whitelist. The whole shared /inventory/all grouped table is one
// opaque "stock" section: any allocation / book-out / transfer / delete-all write tells peers to
// re-pull their own filtered fragment, so no stock data ever crosses the socket.
const INVENTORY_ALL_SECTIONS = {
    stock: { container: '#tableContainer', fragmentValue: 'stock' },
};

// Tell other users viewing the shared Lager that the stock changed. The keys derive from the seam
// map so the broadcast can never drift from the whitelisted sections; the relay excludes the origin
// socket, so the acting viewer never receives its own change (no echo, no self-refresh).
function broadcastInventoryAllChanged() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('inventory', Object.keys(INVENTORY_ALL_SECTIONS));
    }
}
// Exposed so the shared note modal (inventory-note-modal.js) can notify from either inventory page.
window.krtNotifyInventoryChanged = broadcastInventoryAllChanged;

// Inbound peer changes: subscribe to the global "inventory" room and re-fetch this viewer's own
// filtered grouped table in place. filterInventory preserves the viewer's filter + tree expansion,
// and a collapsed stack comes back data-stack-loaded=false so its chips refresh on the next expand
// (the lazy-load requirement). createReceiver coalesces bursts (1500 ms) and defers behind the
// "updates available" pill while a modal is open or an edit is focused in the table.
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

// #1740 (REQ-INV-039): the allocation popover's order options carry what each order still NEEDS, and
// that figure also moves when nothing here changed — a handover recorded on the order, an edited
// line, an order opened or closed. Those writes poke the global `orders` room, never `inventory`,
// so this page joins it as a second receiver.
//
// It reuses that room's existing `demand` key rather than adding one: this page renders a SUBSET of
// what the room already invalidates (the material-collection precedent), and `demand` is published
// by exactly the writers that move the figure. Re-pulling the grouped table is the whole refresh —
// a collapsed stack comes back data-stack-loaded=false, so the freshly labelled options arrive with
// the next expand.
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
    // Restore the persisted per-browser filter state first (REQ-UI-017): on a bare URL the
    // saved selection is applied to the widgets, and — only when it differs from the rendered
    // default — the existing fragment re-fetch runs exactly once at the end of this handler.
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
        // Same shared-class / per-view-ids shape as jobOrderCheck below: the location filter
        // renders in both views, with item-prefixed ids in the items view.
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
        // The material and the items view render different header/all ids for the shared
        // jobOrderCheck class (unique ids in the template source); exactly one pair exists.
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
    // After the restore, never before it: the count chip reads the widgets, so they must see the restored selection
    // rather than the bare server-rendered one.
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

// ===================== Umbuchen (rebooking) modal — transfer relocated from Ausbuchen =========
// The squadron-wide /all view rebooks only between Ort/Nutzer/OrgUnit (the former book-out
// TRANSFER); the personal<->shared toggle is owner-scoped and lives on /inventory/my.
//
// The `admin` prefix on this file's Umbuchen state is deliberate — do not "tidy" it away.
// inventory-my.js runs its own Umbuchen modal with the same state. These are classic scripts sharing
// ONE global lexical environment (ADR-0069), so identical top-level names would die on `SyntaxError:
// Identifier … has already been declared` on a page loading both (the type checker reports it as
// TS6200). The book-out modal and the rest of the Lager behaviour both pages share moved into
// inventory-common.js (FE-SIMP-03), which keeps its state inside one closure per page.
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

// Opens the Umbuchen (transfer) modal for one leaf entry. `userName` and `locationName` are the
// row's current owner/location labels: both target pickers are REMOTE comboboxes (remote-users /
// remote-locations), so presetting them needs the label alongside the id — the loaded item set
// cannot resolve a label locally.
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
    // #1328: preset the target-OrgUnit picker to the row's current owning unit.
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
    // REQ-INV-026: the per-action stock-merge opt-in is offered only for an SCU material (a PIECE
    // transfer always merges server-side). Reset it on every open.
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
    // The target-user picker is a REMOTE combobox (remote-users, #1193): like the location picker
    // below, presetting it needs the label with the id — a bare setValue(id) cannot resolve the
    // name from the on-demand item set and would clear the field on every modal open.
    if (umbuchenUser.krtCombobox) {
        umbuchenUser.krtCombobox.setValue(userId ?? '', userName ?? undefined);
    } else {
        umbuchenUser.value = userId ?? '';
    }
    // The location picker is a REMOTE searchable combobox (remote-locations, REQ-FE-016): the
    // catalog is fetched per query, so the row's location is outside the loaded set — setValue()
    // must carry the label with the id (a bare id would clear the selection, a bare .value write
    // would leave the textbox stale).
    if (umbuchenLocation.krtCombobox) {
        umbuchenLocation.krtCombobox.setValue(locationId ?? '', locationName ?? undefined);
    } else {
        umbuchenLocation.value = locationId ?? '';
    }
    adminLager.refreshUmbuchenTransferOrgUnitPicker();
    // Inline `flex` (not `block`) preserves `.modal`'s flex centring; `block` would override the
    // stylesheet flex and pin the dialog to the top of the viewport (#1328).
    window.krtModal.open(modal);
    // Variante C (REQ-INV-027): build the transfer "Herkunft" picker (the moved row inherits the
    // reduced tags) after the modal is shown, so its initial validity gates the submit button.
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
    // REQ-INV-026: per-action stock-merge opt-in (only rendered for SCU; PIECE always merges).
    const mergeCheckbox = adminUmbuchenInput('umbuchenMergeStock');
    // Variante C (REQ-INV-027): a transfer carries its reduced tags onto the moved row. An invalid
    // plan already disables the submit button; guard the Enter-key path too.
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
    // Read the earmarked orders before the write (the leaf is replaced on the post-write re-swap).
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

// ---- Delete All Global Inventory (Admin) ----
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
            // #577: delete-all in place via the shared krtFetch (CSRF + retry-on-403). On
            // success everything is gone, so re-render the (now empty) grouped table via the
            // existing filter swap instead of a full-page reload.
            await window.krtFetch.write({
                method: 'DELETE',
                url: '/inventory/all',
                toast: false,
                errorMessage: deleteBtn.getAttribute('data-error-failed') ?? undefined,
                onSuccess() {
                    showInventoryToast('success', deleteBtn.getAttribute('data-success'));
                    filterInventory();
                    broadcastInventoryAllChanged();
                    // Every offer on a wiped row is cascade-deleted, so the board is stale too. The
                    // per-order rooms are NOT poked here: a full wipe cannot enumerate the affected
                    // orders client-side, and this admin-only nuke is rare — an open order view
                    // self-heals its collection on the next interaction (documented limitation).
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

// Variante C allocation chips (REQ-INV-027): the shared /inventory/all view is read-only for
// non-association roles — the editable chips and their popover are gated behind sec:authorize in
// stackEntriesAdmin — so adminLager.bind() only ever meets the interactive markup those roles get.

// CSP-safe delegated bindings (replaces the 28 inline on*= handlers across this template).
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

// The Umbuchen form is a stable top-level element (outside the swapped table container), so a
// direct submit listener bound once survives the grouped-table re-swaps.
const adminUmbuchenFormEl = document.getElementById('umbuchenForm');
if (adminUmbuchenFormEl) {
    adminUmbuchenFormEl.addEventListener('submit', submitUmbuchen);
}

// The tree, allocation-chip, book-out and shared Umbuchen handlers (inventory-common.js).
adminLager.bind();
