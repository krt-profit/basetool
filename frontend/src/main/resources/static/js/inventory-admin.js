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
 * Covers: the note modal (open/close/counter/save/remove with the krtCsrf retry-on-403 and
 * syncVersion DOM writeback), the multi-select filter widgets with the AJAX table re-swap
 * (filterInventory replaces #tableContainer via outerHTML), material-group / stack tree
 * expansion with localStorage persistence plus lazy stack-entry loading and pagination,
 * the book-out (DISCARD/SELL) and Umbuchen (TRANSFER) modals writing through
 * window.krtFetch, the admin delete-all flow, the Variante-C allocation chips (add / edit /
 * remove of a job-order or mission quantity slice via the per-allocation endpoints, REQ-INV-027),
 * and the delegated inv-admin-* krtEvents bindings.
 *
 * Loaded as a classic synchronous script at the end of the body, immediately after the
 * inline i18n bootstrap block that declares the dictionaries listed in the global directive
 * below; the parse-time DOM lookups (delete-all elements, bookOutForm / umbuchenForm) rely
 * on that document position.
 */
/* global stackEntriesI18n, inventoryConflictI18n, bookOutI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

function toggleMultiSelect(id) {
    const el = document.getElementById(id);
    const isOpened = el.classList.contains('open');

    document.querySelectorAll('.multi-select-options').forEach(function (opt) {
        opt.classList.remove('open');
    });

    if (!isOpened) {
        el.classList.add('open');
    }
}

function getTranslations(headerId) {
    const header = document.getElementById(headerId);
    return {
        allText: header.getAttribute('data-all') || 'Alle',
        selectedTextStr: header.getAttribute('data-selected') || 'gewählt',
    };
}

function toggleSelectAll(allId, checkClass, headerId) {
    const isAllChecked = document.getElementById(allId).checked;
    const checkboxes = document.getElementsByClassName(checkClass);
    for (let i = 0; i < checkboxes.length; i++) {
        checkboxes[i].checked = isAllChecked;
    }
    updateSelectedText(checkboxes, headerId);
}

function updateSelectState(allId, checkClass, headerId) {
    const checkboxes = document.getElementsByClassName(checkClass);
    let allChecked = true;
    for (let i = 0; i < checkboxes.length; i++) {
        if (!checkboxes[i].checked) {
            allChecked = false;
            break;
        }
    }
    document.getElementById(allId).checked = allChecked;
    updateSelectedText(checkboxes, headerId);
}

function updateSelectedText(checkboxes, headerId) {
    const translations = getTranslations(headerId);
    let count = 0;
    const total = checkboxes.length;
    let firstChecked = null;
    for (let i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].checked) {
            count++;
            if (!firstChecked) firstChecked = checkboxes[i].previousElementSibling.innerText;
        }
    }

    const headerSpan = document.getElementById(headerId).querySelector('.selected-text');
    if (count === total || count === 0) {
        headerSpan.innerText = translations.allText;
    } else if (count === 1) {
        headerSpan.innerText = firstChecked;
    } else {
        headerSpan.innerText = count + ' ' + translations.selectedTextStr;
    }
}

document.addEventListener('click', function (e) {
    if (!e.target.closest('.multi-select-container')) {
        document.querySelectorAll('.multi-select-options').forEach(function (opt) {
            if (opt.classList.contains('open')) {
                opt.classList.remove('open');
            }
        });
    }
});

function collectChecked(className) {
    const boxes = document.getElementsByClassName(className);
    const values = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) values.push(boxes[i].value);
    }
    return values;
}

function filterInventory() {
    const activeMats = collectChecked('matCheck');
    const activeJobOrders = collectChecked('jobOrderCheck');
    const activeMissions = collectChecked('missionCheck');

    const minQualitySelect = document.getElementById('minQuality');
    const minQuality = minQualitySelect ? minQualitySelect.value : '';

    const container = document.getElementById('tableContainer');
    if (!container) return;

    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(window.location.origin + '/inventory/all');
    url.searchParams.append('fragment', 'true');

    activeMats.forEach((m) => url.searchParams.append('materialIds', m));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));

    // Update browser URL (without fragment param) so the filter is bookmarkable / reloadable
    const visibleUrl = new URL(window.location.origin + '/inventory/all');
    activeMats.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
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
            container.outerHTML = html;
            // A fragment swap does not re-fire DOMContentLoaded, so re-apply the persisted tree
            // expansion (REQ-INV-002) — otherwise a filter change or a modal write collapses every
            // row the user had opened.
            restoreExpandedTree();
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

// Cross-feature live-sync (#1309): an inventory write also changes surfaces in OTHER rooms.
// broadcastOrdersChanged tells each affected job order's detail viewers to re-pull their material
// collection (its stock column tracks the earmark roll-up, not just deliveries), and
// broadcastBoardChanged tells the Materialbörse to re-pull its board after a stock-reducing write
// (the backend clamps an offer down to the remaining stock). The actor is not in those rooms, so
// there is no self-refresh; an unaffected peer's re-fetch is a harmless no-op.
function broadcastOrdersChanged(orderIds) {
    if (!window.krtLiveSync || typeof window.krtLiveSync.sendChanged !== 'function') return;
    (orderIds || []).forEach(function (orderId) {
        if (orderId)
            window.krtLiveSync.sendChanged('order:' + orderId, ['materials', 'aggregated']);
    });
}
function broadcastBoardChanged() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('materialboard', ['board']);
    }
}
// The job-order target-ids currently earmarked on an entry's leaf row (read before a stock write, so
// the affected orders are known even for a rest-first book-out the backend auto-distributes).
function collectLeafOrderIds(itemId) {
    const leaf = document.querySelector('.tree-row--leaf[data-item-id="' + itemId + '"]');
    if (!leaf) return [];
    const ids = [];
    leaf.querySelectorAll(
        '.assoc-split[data-assoc-field="JOB_ORDER"] [data-assoc-chip][data-target-id]',
    ).forEach(function (chip) {
        const id = chip.getAttribute('data-target-id');
        if (id && ids.indexOf(id) < 0) ids.push(id);
    });
    return ids;
}

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
        refresh: function () {
            filterInventory();
        },
    });
}

function resetInventoryFilter() {
    ['matCheck', 'jobOrderCheck', 'missionCheck'].forEach(function (cls) {
        const boxes = document.getElementsByClassName(cls);
        for (let i = 0; i < boxes.length; i++) boxes[i].checked = false;
    });
    ['matAll', 'jobOrderAll', 'missionAll'].forEach(function (id) {
        const el = document.getElementById(id);
        if (el) el.checked = false;
    });
    const minQualitySelect = document.getElementById('minQuality');
    if (minQualitySelect) minQualitySelect.value = '';
    if (document.getElementById('materialHeader'))
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('jobOrderHeader'))
        updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    if (document.getElementById('missionHeader'))
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    filterInventory();
}

document.addEventListener('DOMContentLoaded', function () {
    if (document.getElementsByClassName('matCheck').length > 0) {
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    }
    if (document.getElementsByClassName('jobOrderCheck').length > 0) {
        updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    }
    if (document.getElementsByClassName('missionCheck').length > 0) {
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    }
});

// ===================== Lager tree expand/collapse view-state persistence (REQ-INV-002) =========
// The grouped tree (material group -> owner/location stack -> lazy leaf entries) is re-rendered in
// place on every grouped-table re-swap: a filter change AND — the bug this guards — every modal
// write (book-out and Umbuchen both call filterInventory in their onSuccess). Because a fragment
// swap replaces #inventoryTable wholesale and does NOT re-fire DOMContentLoaded, the expanded state
// must be re-applied explicitly after each swap; otherwise closing a modal collapses every row the
// user had opened. Both the material-group expansion (persisted as expanded_rows_lager_*) and the
// stack expansion (persisted as expanded_stacks_lager_*) live in localStorage, keyed per user, and
// are re-applied by restoreExpandedTree().

// The per-user localStorage suffix taken from the tree's data-user-id, or null when the table is
// absent / anonymous (nothing is then persisted).
function lagerUserId() {
    const table = document.getElementById('inventoryTable');
    const userId = table ? table.getAttribute('data-user-id') : null;
    return userId && userId !== 'unknown' ? userId : null;
}

// localStorage key holding the array of expanded material-group ids.
function groupStorageKey() {
    const userId = lagerUserId();
    return userId ? 'expanded_rows_lager_' + userId : null;
}

// localStorage key holding the array of expanded stack ids.
function stackStorageKey() {
    const userId = lagerUserId();
    return userId ? 'expanded_stacks_lager_' + userId : null;
}

// A stack's identity is exactly the page-less stack-entries URL its data-attributes build, so the
// same stack maps to the same key across re-renders and a /all stack can never collide with a /my
// one (they carry a different path prefix).
function stackKey(headerRow) {
    return buildStackEntriesUrl(headerRow, null);
}

// Reads a persisted expansion array, tolerating absent / corrupt storage.
function readExpanded(key) {
    if (!key) return [];
    try {
        return JSON.parse(localStorage.getItem(key) || '[]');
    } catch (e) {
        console.warn('LocalStorage error', e);
        return [];
    }
}

// Persists an expansion array, tolerating a storage write failure (quota / privacy mode).
function writeExpanded(key, values) {
    if (!key) return;
    try {
        localStorage.setItem(key, JSON.stringify(values));
    } catch (e) {
        console.warn('LocalStorage error', e);
    }
}

// Re-applies the persisted material-group expansion to the freshly rendered tree.
function restoreExpandedGroups() {
    const expandedRows = readExpanded(groupStorageKey());
    if (expandedRows.length === 0) return;
    document.querySelectorAll('.tree-row--group').forEach(function (row) {
        const materialId = row.getAttribute('data-material-id');
        if (materialId && expandedRows.includes(materialId)) {
            const nextRow = row.nextElementSibling;
            const icon = row.querySelector('.toggle-icon');
            if (nextRow && nextRow.classList.contains('tree-group-items')) {
                nextRow.style.display = 'block';
                if (icon) icon.textContent = '▼';
            }
        }
    });
}

// Re-applies the persisted stack expansion and re-triggers the lazy entry load for each restored
// stack (the re-rendered header comes back with data-stack-loaded="false", so the leaf rows —
// carrying the fresh post-write amounts — are fetched again).
function restoreExpandedStacks() {
    const expandedStacks = readExpanded(stackStorageKey());
    if (expandedStacks.length === 0) return;
    document.querySelectorAll('.stack-header').forEach(function (row) {
        if (!expandedStacks.includes(stackKey(row))) return;
        const nextRow = row.nextElementSibling;
        const icon = row.querySelector('.toggle-icon');
        if (nextRow && nextRow.classList.contains('tree-stack-entries')) {
            nextRow.style.display = 'block';
            if (icon) icon.textContent = '▼';
            if (row.getAttribute('data-stack-loaded') !== 'true') {
                loadStackEntries(row, 0);
            }
        }
    });
}

// Restores the whole tree (groups first, then their stacks) — run on initial load and after every
// in-place grouped-table re-swap.
function restoreExpandedTree() {
    restoreExpandedGroups();
    restoreExpandedStacks();
}

function toggleGroup(row) {
    const nextRow = row.nextElementSibling;
    const icon = row.querySelector('.toggle-icon');
    const materialId = row.getAttribute('data-material-id');
    if (!nextRow || !nextRow.classList.contains('tree-group-items')) return;

    const key = groupStorageKey();
    const expandedRows = readExpanded(key);
    if (window.getComputedStyle(nextRow).display === 'none') {
        nextRow.style.display = 'block';
        if (icon) icon.textContent = '▼';
        if (materialId && !expandedRows.includes(materialId)) {
            expandedRows.push(materialId);
            writeExpanded(key, expandedRows);
        }
    } else {
        nextRow.style.display = 'none';
        if (icon) icon.textContent = '▶';
        if (materialId) {
            writeExpanded(
                key,
                expandedRows.filter((id) => id !== materialId),
            );
        }
    }
}

function toggleStack(row) {
    const nextRow = row.nextElementSibling;
    const icon = row.querySelector('.toggle-icon');
    if (!nextRow || !nextRow.classList.contains('tree-stack-entries')) return;
    const key = stackStorageKey();
    const expandedStacks = readExpanded(key);
    const id = stackKey(row);
    if (window.getComputedStyle(nextRow).display === 'none') {
        nextRow.style.display = 'block';
        if (icon) icon.textContent = '▼';
        // Persist so a later in-place re-swap (filter change or modal write) re-opens this stack.
        if (id && !expandedStacks.includes(id)) {
            expandedStacks.push(id);
            writeExpanded(key, expandedStacks);
        }
        // Append-only Lager: a stack's entries are not inlined. Fetch them on first
        // expand from /inventory/all/stack/entries (ADR-0003, REQ-INV-002); subsequent
        // toggles just reveal the already-loaded rows.
        if (row.getAttribute('data-stack-loaded') !== 'true') {
            loadStackEntries(row, 0);
        }
    } else {
        nextRow.style.display = 'none';
        if (icon) icon.textContent = '▶';
        if (id) {
            writeExpanded(
                key,
                expandedStacks.filter((k) => k !== id),
            );
        }
    }
}

// Builds the lazy stack-entries fetch URL from the stack-key data-attributes the server
// stamped on the stack-header row. A global stack is per-owner, so userId is part of the
// key; the global Lager is non-personal, so no personal flag is sent. An absent dimension
// is omitted so the backend's null-safe match selects rows where it is itself absent.
function buildStackEntriesUrl(headerRow, page) {
    const params = new URLSearchParams();
    params.set('materialId', headerRow.getAttribute('data-material-id'));
    params.set('userId', headerRow.getAttribute('data-user-id'));
    params.set('locationId', headerRow.getAttribute('data-location-id'));
    const quality = headerRow.getAttribute('data-quality');
    if (quality !== null && quality !== '') params.set('quality', quality);
    const owningOrgUnitId = headerRow.getAttribute('data-owning-org-unit-id');
    if (owningOrgUnitId) params.set('owningOrgUnitId', owningOrgUnitId);
    if (page != null) params.set('page', page);
    return '/inventory/all/stack/entries?' + params.toString();
}

// Replaces a stack's entries container with a single status line (loading / error),
// built via textContent so the i18n string is never interpreted as HTML.
function setStackEntriesStatus(content, message, isError) {
    content.innerHTML = '';
    const div = document.createElement('div');
    div.className = 'stack-entries-status';
    if (isError) div.classList.add('hud-box-error');
    div.style.padding = '1rem 2.5rem';
    div.style.color = 'var(--color-gray-2)';
    div.textContent = message;
    content.appendChild(div);
}

// Fetches one page of a stack's entries and injects the server-rendered fragment. The
// injected rows carry the same data-trigger hooks as before, so the page's delegated
// krtEvents handlers (book-out, note, association) keep working without re-binding.
function loadStackEntries(headerRow, page) {
    const entriesRow = headerRow.nextElementSibling;
    if (!entriesRow) return;
    const content = entriesRow.querySelector('.stack-entries-content');
    if (!content) return;
    setStackEntriesStatus(content, stackEntriesI18n.loading, false);
    fetch(buildStackEntriesUrl(headerRow, page), {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
    })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
        })
        .then(function (html) {
            content.innerHTML = html;
            headerRow.setAttribute('data-stack-loaded', 'true');
            // The entries are injected via innerHTML (not krtFetch.swap), so no krt:swapped fires —
            // enhance the Variante-C allocation "+ Zuordnen" <select data-krt-combobox> popovers by
            // hand (REQ-INV-027), else they stay raw native selects instead of the HUD combobox and
            // the add-open reset (hidden.krtCombobox.setValue / input focus) has nothing to target.
            if (typeof window.krtEnhanceComboboxes === 'function') {
                window.krtEnhanceComboboxes(content);
            }
        })
        .catch(function (e) {
            console.error('Failed to load stack entries', e);
            setStackEntriesStatus(content, stackEntriesI18n.error, true);
        });
}

// Pagination handler: re-fetches the target page for the stack owning the clicked button
// and replaces the entries container in place.
function goToStackEntriesPage(btn) {
    const entriesRow = btn.closest('.tree-stack-entries');
    if (!entriesRow) return;
    const headerRow = entriesRow.previousElementSibling;
    if (!headerRow) return;
    const page = parseInt(btn.getAttribute('data-page'), 10);
    loadStackEntries(headerRow, isNaN(page) ? 0 : page);
}

// Restore the persisted tree expansion on initial load (the same restore runs after each in-place
// grouped-table re-swap — see filterInventory).
document.addEventListener('DOMContentLoaded', restoreExpandedTree);

document.addEventListener('DOMContentLoaded', function () {
    const matSelect = document.getElementById('materialId');
    if (matSelect) {
        matSelect.addEventListener('change', function () {
            filterJobOrdersByMaterial(this.value);
        });
    }
});

function filterJobOrdersByMaterial(matId) {
    const jobSelect = document.getElementById('jobOrderId');
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

function updateAmountFromTarget() {
    const targetAmountInput = document.getElementById('targetAmount');
    const amountInput = document.getElementById('amount');
    const maxAmountInput = document.getElementById('maxAmount');

    if (targetAmountInput && amountInput && maxAmountInput) {
        const target = window.krtScuInput.parse(targetAmountInput.value);
        const max = parseFloat(maxAmountInput.value) || 0;

        if (!isNaN(target)) {
            const diff = Math.max(0, max - target);
            amountInput.value = Number(diff.toFixed(3));
        }
    }
}

function updateTargetFromAmount() {
    const targetAmountInput = document.getElementById('targetAmount');
    const amountInput = document.getElementById('amount');
    const maxAmountInput = document.getElementById('maxAmount');

    if (targetAmountInput && amountInput && maxAmountInput) {
        const amount = window.krtScuInput.parse(amountInput.value);
        const max = parseFloat(maxAmountInput.value) || 0;

        if (!isNaN(amount)) {
            const diff = Math.max(0, max - amount);
            targetAmountInput.value = Number(diff.toFixed(3));
        }
    }
}

function toggleBookOutTypeFields() {
    const typeSell = document.querySelector('input[name="type"][value="SELL"]').checked;
    const sellFields = document.getElementById('sellFields');
    const terminal = document.getElementById('terminal');
    const sellAmount = document.getElementById('sellAmount');
    if (typeSell) {
        sellFields.style.display = 'block';
        terminal.required = true;
        sellAmount.required = true;
    } else {
        sellFields.style.display = 'none';
        terminal.required = false;
        sellAmount.required = false;
    }
    const submitBtn = document.getElementById('bookOutSubmitBtn');
    if (submitBtn) {
        submitBtn.textContent = submitBtn.getAttribute(
            typeSell ? 'data-text-sell' : 'data-text-discard',
        );
    }
}

// ===================== Umbuchen (rebooking) modal — transfer relocated from Ausbuchen =========
// The squadron-wide /all view rebooks only between Ort/Nutzer/OrgUnit (the former book-out
// TRANSFER); the personal<->shared toggle is owner-scoped and lives on /inventory/my.
let umbuchenItemId = null;
// #1328: the row's current owning org-unit id, used to preset the target-OrgUnit picker so a
// submit that does not touch the picker keeps the stock in its current unit (null = ownerless row).
let umbuchenCurrentOwningOrgUnitId = null;
let umbuchenInFlight = false;

function updateUmbuchenAmountFromTarget() {
    const targetEl = document.getElementById('umbuchenTargetAmount');
    const amountEl = document.getElementById('umbuchenAmount');
    const maxEl = document.getElementById('umbuchenMaxAmount');
    if (targetEl && amountEl && maxEl) {
        const target = window.krtScuInput.parse(targetEl.value);
        const max = parseFloat(maxEl.value) || 0;
        if (!isNaN(target)) {
            amountEl.value = Number(Math.max(0, max - target).toFixed(3));
        }
    }
}

function updateUmbuchenTargetFromAmount() {
    const targetEl = document.getElementById('umbuchenTargetAmount');
    const amountEl = document.getElementById('umbuchenAmount');
    const maxEl = document.getElementById('umbuchenMaxAmount');
    if (targetEl && amountEl && maxEl) {
        const amount = window.krtScuInput.parse(amountEl.value);
        const max = parseFloat(maxEl.value) || 0;
        if (!isNaN(amount)) {
            targetEl.value = Number(Math.max(0, max - amount).toFixed(3));
        }
    }
}

function refreshUmbuchenTransferOrgUnitPicker() {
    const wrapper = document.getElementById('umbuchenTargetOwningOrgUnitWrapper');
    const select = document.getElementById('umbuchenTargetOwningOrgUnitId');
    const userSelect = document.getElementById('umbuchenTargetUserId');
    if (!wrapper || !select || !userSelect) return;
    const targetUserId = userSelect.value;
    select.innerHTML = '';
    if (!targetUserId) {
        wrapper.style.display = 'none';
        return;
    }
    // #1328: offer the selected owner's (target user's) direct memberships across ALL FOUR
    // org-unit kinds — Staffel + SK + Bereich + OL — via ?allKinds=true (mirrors the bank
    // counterparty picker, REQ-BANK-044). The default (allKinds=false) returns only Staffel/SK, so
    // a Bereich/OL-member target could not be booked into their Bereich/OL pool even though the
    // backend resolver (resolveOrgUnitForPickerOutputNullable) accepts it.
    fetch('/api/v1/users/' + encodeURIComponent(targetUserId) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            // #1328: always show the picker when the target has at least one membership and preset
            // it to the row's current owning org unit (or the target's primary when that unit is not
            // one of the target's memberships — e.g. a cross-user transfer), so submitting without
            // changing it keeps the stock in its current unit. Hidden only for a membershipless
            // target: the row is then ownerless and there is nothing to pick.
            if (!Array.isArray(memberships) || memberships.length < 1) {
                wrapper.style.display = 'none';
                return;
            }
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

function openUmbuchenModal(
    id,
    amount,
    version,
    materialId,
    userId,
    locationId,
    quantityType,
    owningOrgUnitId,
) {
    umbuchenItemId = id;
    umbuchenCurrentOwningOrgUnitId = owningOrgUnitId || null;
    const isScu = quantityType !== 'PIECE';
    const amountEl = document.getElementById('umbuchenAmount');
    const targetEl = document.getElementById('umbuchenTargetAmount');
    amountEl.setAttribute('step', isScu ? '0.001' : '1');
    targetEl.setAttribute('step', isScu ? '0.001' : '1');
    const targetHint = document.getElementById('umbuchen-target-scu-hint');
    const amountHint = document.getElementById('umbuchen-amount-scu-hint');
    if (targetHint) targetHint.classList.toggle('krtm-hidden', !isScu);
    if (amountHint) amountHint.classList.toggle('krtm-hidden', !isScu);
    // REQ-INV-026: the per-action stock-merge opt-in is offered only for an SCU material (a PIECE
    // transfer always merges server-side). Reset it on every open.
    const mergeRow = document.getElementById('umbuchenMergeRow');
    const mergeCheckbox = document.getElementById('umbuchenMergeStock');
    if (mergeCheckbox) mergeCheckbox.checked = false;
    if (mergeRow) mergeRow.classList.toggle('krtm-hidden', !isScu);
    amountEl.value = amount;
    amountEl.max = amount;
    targetEl.value = 0;
    document.getElementById('umbuchenMaxAmount').value = amount;
    document.getElementById('umbuchenVersion').value = version;
    const amountOf = document.getElementById('umbuchenAmountOfText');
    if (amountOf)
        amountOf.textContent = amountOf.getAttribute('data-template').replace('{0}', amount);
    const umbuchenUser = document.getElementById('umbuchenTargetUserId');
    // The target-user <select> is upgraded into a searchable combobox; its id moves to the
    // hidden input, so use the combobox API to sync both the value and the visible label.
    if (umbuchenUser.krtCombobox) {
        umbuchenUser.krtCombobox.setValue(userId);
    } else {
        umbuchenUser.value = userId;
    }
    // The location picker is a searchable combobox (REQ-FE-016): setValue() syncs the hidden
    // value AND the visible label — a bare .value write would leave the textbox stale.
    const umbuchenLocation = document.getElementById('umbuchenTargetLocationId');
    if (umbuchenLocation.krtCombobox) {
        umbuchenLocation.krtCombobox.setValue(locationId);
    } else {
        umbuchenLocation.value = locationId;
    }
    refreshUmbuchenTransferOrgUnitPicker();
    // Inline `flex` (not `block`) preserves `.modal`'s flex centring; `block` would override the
    // stylesheet flex and pin the dialog to the top of the viewport (#1328).
    document.getElementById('umbuchenModal').style.display = 'flex';
    // Variante C (REQ-INV-027): build the transfer "Herkunft" picker (the moved row inherits the
    // reduced tags) after the modal is shown, so its initial validity gates the submit button.
    if (window.krtHerkunft) {
        window.krtHerkunft.populate('umbuchen', id);
    }
}

function closeUmbuchenModal() {
    if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
    if (window.krtHerkunft) {
        window.krtHerkunft.reset('umbuchen');
    }
    document.getElementById('umbuchenModal').style.display = 'none';
}

function submitUmbuchen(event) {
    if (event && event.defaultPrevented) return;
    if (event) event.preventDefault();
    if (umbuchenInFlight || !window.krtFetch || !umbuchenItemId) return;
    const amountEl = document.getElementById('umbuchenAmount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountEl.value)
        : parseFloat(amountEl.value);
    const submitBtn = document.getElementById('umbuchenSubmitBtn');
    // REQ-INV-026: per-action stock-merge opt-in (only rendered for SCU; PIECE always merges).
    const mergeCheckbox = document.getElementById('umbuchenMergeStock');
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
        amount: amount,
        type: 'TRANSFER',
        targetUserId: document.getElementById('umbuchenTargetUserId').value || null,
        targetLocationId: document.getElementById('umbuchenTargetLocationId').value || null,
        targetOwningOrgUnitId:
            document.getElementById('umbuchenTargetOwningOrgUnitId').value || null,
        version: parseInt(document.getElementById('umbuchenVersion').value, 10),
        mergeStock: !!(mergeCheckbox && mergeCheckbox.checked),
        jobOrderReductions: reductions.jobOrderReductions,
        missionReductions: reductions.missionReductions,
    };
    // Read the earmarked orders before the write (the leaf is replaced on the post-write re-swap).
    const affectedOrderIds = collectLeafOrderIds(umbuchenItemId);
    umbuchenInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/' + umbuchenItemId + '/transfer',
            payload: payload,
            successMessage: umbuchenI18n.success,
            errorMessage: umbuchenI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess: function () {
                closeUmbuchenModal();
                filterInventory();
                broadcastInventoryAllChanged();
                broadcastOrdersChanged(affectedOrderIds);
                broadcastBoardChanged();
            },
        })
        .then(function () {
            umbuchenInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

// The item id the open book-out modal targets; set when the modal opens, read by submitBookOut.
let bookOutItemId = null;
// Guards against a second submit (Enter / rapid click) landing while the first write is in
// flight — a duplicate book-out on the same version would otherwise 409.
let bookOutInFlight = false;

// #577 part 2: submit the book-out in place via the shared krtFetch/krtCsrf foundation, reusing
// the existing POST /inventory/{id}/transfer proxy (the backend book-out endpoint, equivalent
// for DISCARD / TRANSFER / SELL). On success the grouped table is re-swapped (the server
// regroups) instead of the page reloading; the classic POST stays the no-JS fallback.
function submitBookOut(event) {
    // scu-decimal-input.js canonicalises + validates the amount fields in the capture phase
    // first; if it found an invalid amount it already blocked the submit (preventDefault +
    // reportValidity). Respect that and do not fire the AJAX write.
    if (event.defaultPrevented) {
        return;
    }
    event.preventDefault();
    if (bookOutInFlight || !window.krtFetch || !bookOutItemId) {
        return;
    }
    const typeInput = document.querySelector('input[name="type"]:checked');
    const type = typeInput ? typeInput.value : 'DISCARD';
    const amountEl = document.getElementById('amount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountEl.value)
        : parseFloat(amountEl.value);
    const sellAmountEl = document.getElementById('sellAmount');
    // Ausbuchen now only discards or sells — the TRANSFER (Umbuchung) mode moved to the
    // dedicated Umbuchen modal. The transfer-only fields stay null on the book-out payload.
    // Variante C (REQ-INV-027): the "Herkunft" picker chooses which order/mission slices (or the
    // rest) the deduction comes from and, for a SELL, which missions get the coupled proceeds. An
    // invalid plan already disables the submit button; guard the Enter-key path too. A null list
    // means "take it from the rest" (SELL → that portion is personal).
    if (window.krtHerkunft && !window.krtHerkunft.isValid('bookout')) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.overallocated);
        }
        return;
    }
    const reductions = window.krtHerkunft
        ? window.krtHerkunft.collect('bookout')
        : { jobOrderReductions: null, missionReductions: null };
    const payload = {
        amount: amount,
        type: type,
        terminal: type === 'SELL' ? document.getElementById('terminal').value || null : null,
        sellAmount:
            type === 'SELL' && sellAmountEl.value !== '' ? Number(sellAmountEl.value) : null,
        version: parseInt(document.getElementById('version').value, 10),
        jobOrderReductions: reductions.jobOrderReductions,
        missionReductions: reductions.missionReductions,
    };
    const submitBtn = document.getElementById('bookOutSubmitBtn');
    // Read the earmarked orders before the write (the leaf is replaced on the post-write re-swap).
    const affectedOrderIds = collectLeafOrderIds(bookOutItemId);
    bookOutInFlight = true;
    if (submitBtn) {
        submitBtn.disabled = true;
    }
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/' + bookOutItemId + '/transfer',
            payload: payload,
            successMessage: bookOutI18n.success,
            errorMessage: bookOutI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess: function () {
                closeBookOutModal();
                filterInventory();
                broadcastInventoryAllChanged();
                broadcastOrdersChanged(affectedOrderIds);
                broadcastBoardChanged();
            },
        })
        .then(function () {
            bookOutInFlight = false;
            if (submitBtn) {
                submitBtn.disabled = false;
            }
        });
}

function openBookOutModal(id, amount, version, materialId, userId, locationId, quantityType) {
    bookOutItemId = id;
    const bookOutForm = document.getElementById('bookOutForm');
    bookOutForm.action = window.safeSameOriginUrl(
        '/inventory/' + id + '/book-out',
        bookOutForm.action,
    );

    const amountInput = document.getElementById('amount');
    const targetAmountInput = document.getElementById('targetAmount');
    const isScu = quantityType !== 'PIECE';
    if (quantityType === 'PIECE') {
        amountInput.setAttribute('step', '1');
        targetAmountInput.setAttribute('step', '1');
    } else {
        amountInput.setAttribute('step', '0.001');
        targetAmountInput.setAttribute('step', '0.001');
    }
    const targetScuHint = document.getElementById('bookout-target-scu-hint');
    const amountScuHint = document.getElementById('bookout-amount-scu-hint');
    if (targetScuHint) targetScuHint.classList.toggle('krtm-hidden', !isScu);
    if (amountScuHint) amountScuHint.classList.toggle('krtm-hidden', !isScu);

    amountInput.value = amount;
    document.getElementById('amount').max = amount;
    document.getElementById('targetAmount').value = 0;
    document.getElementById('maxAmount').value = amount;
    const amountOfSpan = document.getElementById('amountOfText');
    if (amountOfSpan) {
        amountOfSpan.textContent = amountOfSpan
            .getAttribute('data-template')
            .replace('{0}', amount);
    }
    document.getElementById('version').value = version;
    document.querySelector('input[name="type"][value="DISCARD"]').checked = true;
    toggleBookOutTypeFields();

    const typeSellRadio = document.querySelector('input[name="type"][value="SELL"]');
    const sellNotPossibleReason = document.getElementById('sellNotPossibleReason');
    typeSellRadio.disabled = true;
    if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'none';

    const terminalSelect = document.getElementById('terminal');
    terminalSelect.innerHTML = '<option value="" disabled selected>...laden...</option>';
    if (materialId) {
        fetch('/api/proxy/materials/' + materialId + '/terminals')
            .then((r) => {
                if (!r.ok) throw new Error('Network response was not ok');
                return r.json();
            })
            .then((data) => {
                terminalSelect.innerHTML =
                    '<option value="" disabled selected>...wählen...</option>';
                if (data && data.length > 0) {
                    typeSellRadio.disabled = false;
                    data.forEach((terminal) => {
                        const opt = document.createElement('option');
                        opt.value = terminal.terminalName;
                        if (terminal.priceSell && terminal.priceSell > 0) {
                            opt.textContent =
                                terminal.terminalName + ' (' + terminal.priceSell + ' aUEC)';
                        } else {
                            opt.textContent = terminal.terminalName;
                        }
                        terminalSelect.appendChild(opt);
                    });
                } else {
                    terminalSelect.innerHTML =
                        '<option value="" disabled selected>Keine Terminals gefunden</option>';
                    typeSellRadio.disabled = true;
                    if (typeSellRadio.checked) {
                        document.querySelector('input[name="type"][value="DISCARD"]').checked =
                            true;
                        toggleBookOutTypeFields();
                    }
                    if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'inline';
                }
            })
            .catch((e) => {
                console.error('Error loading terminals:', e);
                terminalSelect.innerHTML =
                    '<option value="" disabled selected>Fehler beim Laden</option>';
                typeSellRadio.disabled = true;
                if (typeSellRadio.checked) {
                    document.querySelector('input[name="type"][value="DISCARD"]').checked = true;
                    toggleBookOutTypeFields();
                }
                if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'inline';
            });
    } else {
        terminalSelect.innerHTML =
            '<option value="" disabled selected>Kein Material gewählt</option>';
        typeSellRadio.disabled = true;
        if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'inline';
    }

    // Inline `flex` (not `block`) so `.modal`'s flex centring is preserved (see openUmbuchenModal).
    document.getElementById('bookOutModal').style.display = 'flex';
    // Variante C (REQ-INV-027): build the "Herkunft" (deduct-from) picker from this entry's chips
    // now that the modal is shown, so its initial validity gates the submit button.
    if (window.krtHerkunft) {
        window.krtHerkunft.populate('bookout', id);
    }
}

function closeBookOutModal() {
    if (typeof window.resetUnsavedChanges === 'function') {
        window.resetUnsavedChanges();
    }
    if (window.krtHerkunft) {
        window.krtHerkunft.reset('bookout');
    }
    document.getElementById('bookOutModal').style.display = 'none';
}

window.onclick = function (event) {
    const modal = document.getElementById('bookOutModal');
    if (event.target === modal) {
        modal.style.display = 'none';
    }
};

// ---- Delete All Global Inventory (Admin) ----
(function () {
    const deleteBtn = document.getElementById('delete-all-global-inventory-btn');
    const modal = document.getElementById('delete-all-global-inventory-modal');
    const confirmBtn = document.getElementById('delete-all-global-inventory-confirm-btn');
    const cancelBtn = document.getElementById('delete-all-global-inventory-cancel-btn');

    if (!deleteBtn || !modal || !confirmBtn || !cancelBtn) return;

    function closeModal() {
        modal.style.display = 'none';
    }

    deleteBtn.addEventListener('click', function () {
        modal.style.display = 'flex';
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
                errorMessage: deleteBtn.getAttribute('data-error-failed'),
                onSuccess: function () {
                    showInventoryToast('success', deleteBtn.getAttribute('data-success'));
                    filterInventory();
                    broadcastInventoryAllChanged();
                    // Every offer on a wiped row is cascade-deleted, so the board is stale too. The
                    // per-order rooms are NOT poked here: a full wipe cannot enumerate the affected
                    // orders client-side, and this admin-only nuke is rare — an open order view
                    // self-heals its collection on the next interaction (documented limitation).
                    broadcastBoardChanged();
                },
            });
            closeModal();
        } finally {
            confirmBtn.disabled = false;
            cancelBtn.disabled = false;
        }
    });
})();

// ── Variante C allocation chips (REQ-INV-027) ──────────────────────────────
// Each .assoc-split (one per dimension per entry) renders its job-order / mission
// allocations as chips + a trailing rest chip, plus a "+ Zuordnen" combobox
// popover. Add / edit / remove call the per-allocation endpoints
// (POST/PATCH/DELETE /inventory/{id}/allocation) and update the split in place
// from the returned InventoryItemDto (chips + rest + version), so the drilled-down
// stack stays expanded and no full-page reload is needed (REQ-FE-001). The shared
// /inventory/all view is read-only for non-association roles (the editable chips +
// popover are gated behind sec:authorize in stackEntriesAdmin), so this module only
// ever binds to the interactive markup those roles receive.
const ASSOC_EPS = 0.0005;

// Formats an amount for a chip / rest label: whole for PIECE, three decimals for SCU.
function assocFormatAmount(amount, isPiece) {
    const n = typeof amount === 'number' ? amount : parseFloat(amount);
    if (isNaN(n)) return '0';
    return isPiece ? String(Math.round(n)) : n.toFixed(3);
}

// Hides every open allocation popover except `except` (the one being opened).
function assocCloseAllPops(except) {
    document.querySelectorAll('[data-assoc-pop]').forEach(function (p) {
        if (p !== except) p.classList.add('krtm-hidden');
    });
}

// Anchors a `position: fixed` allocation popover to its trigger in viewport space. The
// popover is fixed (not absolute) so the horizontally-scrolling ancestors
// (#tableContainer.overflow-x-auto + .table-responsive) can't crop it at their bottom edge;
// fixed positioning drops the stylesheet's top/left, so they are recomputed here from the
// trigger wrap's rect (mirrors the old `top: calc(100% + 5px); left: 0`). When the trigger sits
// near the viewport bottom there is no room to drop the popover downward — and a fixed box can't
// be scrolled into view — so it flips above the trigger (bottom-anchored, so a later switch to
// the taller/shorter amount section stays glued to the trigger). Mirrors krt-searchable-select's
// positionListbox (REQ-UI-011). Runs while the popover is visible so offsetHeight is measurable.
function assocPositionPop(pop) {
    const wrap = pop.closest('.assoc-add-wrap');
    if (!wrap) return;
    const rect = wrap.getBoundingClientRect();
    const gap = 5;
    const below = window.innerHeight - rect.bottom;
    const above = rect.top;
    const flipUp = below < pop.offsetHeight + gap && above > below;
    pop.style.left = rect.left + 'px';
    if (flipUp) {
        pop.style.top = 'auto';
        pop.style.bottom = window.innerHeight - rect.top + gap + 'px';
    } else {
        pop.style.bottom = 'auto';
        pop.style.top = rect.bottom + gap + 'px';
    }
}

// Keeps the currently-open popover glued to its trigger as the window or the table's own
// scroll container moves (capture catches inner-container scrolls, which do not bubble).
// Only one popover is open at a time (assocCloseAllPops), so the first visible one wins.
function assocRepositionOpenPop() {
    const pop = document.querySelector('[data-assoc-pop]:not(.krtm-hidden)');
    if (pop) assocPositionPop(pop);
}

// Switches a popover to its combobox (pick) section.
function assocShowPickSection(pop) {
    const pick = pop.querySelector('[data-assoc-pop-pick]');
    const amount = pop.querySelector('[data-assoc-pop-amount]');
    if (pick) pick.classList.remove('krtm-hidden');
    if (amount) amount.classList.add('krtm-hidden');
}

// Switches a popover to its amount-editor section; `showRemove` reveals Entfernen (edit mode).
function assocShowAmountSection(pop, showRemove) {
    const pick = pop.querySelector('[data-assoc-pop-pick]');
    const amount = pop.querySelector('[data-assoc-pop-amount]');
    if (pick) pick.classList.add('krtm-hidden');
    if (amount) amount.classList.remove('krtm-hidden');
    const removeBtn = pop.querySelector('[data-trigger="inv-admin-assoc-remove"]');
    if (removeBtn) removeBtn.classList.toggle('krtm-hidden', !showRemove);
}

// Builds one allocation chip element from a returned allocation DTO.
function assocBuildChip(field, alloc, isPiece) {
    const isOrder = field === 'JOB_ORDER';
    const chip = document.createElement('span');
    chip.className = 'assoc-chip ' + (isOrder ? 'assoc-chip--order' : 'assoc-chip--mission');
    chip.setAttribute('role', 'button');
    chip.setAttribute('tabindex', '0');
    chip.setAttribute('data-trigger', 'inv-admin-assoc-edit');
    chip.setAttribute('data-assoc-chip', isOrder ? 'jobOrder' : 'mission');
    chip.setAttribute('data-target-id', isOrder ? alloc.jobOrderId : alloc.missionId);
    chip.setAttribute('data-amount', alloc.amount);
    const label = isOrder ? '#' + alloc.jobOrderDisplayId : alloc.missionName;
    chip.appendChild(document.createTextNode(label + ' · '));
    const amt = document.createElement('span');
    amt.className = 'assoc-chip__amt';
    amt.textContent = assocFormatAmount(alloc.amount, isPiece);
    chip.appendChild(amt);
    return chip;
}

// Recomputes a rest chip's tone + label: 0 -> success, unassigned remainder -> muted "frei",
// over-allocation (negative) -> danger. isPiece formats the amount whole (no decimals) for a
// PIECE material, three decimals for SCU.
function assocUpdateRestChip(el, rest, isPiece) {
    if (!el) return;
    el.classList.remove('chip--success', 'chip--muted', 'chip--danger');
    if (rest == null || Math.abs(rest) <= ASSOC_EPS) {
        el.classList.add('chip--success');
        el.textContent = assocI18n.restZero;
    } else if (rest < 0) {
        el.classList.add('chip--danger');
        el.textContent = assocI18n.restOver.replace('{0}', assocFormatAmount(-rest, isPiece));
    } else {
        el.classList.add('chip--muted');
        el.textContent = assocI18n.restFree.replace('{0}', assocFormatAmount(rest, isPiece));
    }
}

// Re-renders a split's chips + rest from the returned entry DTO and propagates the fresh
// entry version to every data-version control in the leaf row (both dimensions share the token).
function assocRerender(split, dto) {
    const field = split.getAttribute('data-assoc-field');
    const isPiece = split.getAttribute('data-piece') === 'true';
    const isOrder = field === 'JOB_ORDER';
    const allocs = (isOrder ? dto.jobOrderAllocations : dto.missionAllocations) || [];
    const rest = isOrder ? dto.jobOrderRest : dto.missionRest;
    split.querySelectorAll('[data-assoc-chip]').forEach(function (c) {
        c.remove();
    });
    const addWrap = split.querySelector('.assoc-add-wrap');
    allocs.forEach(function (a) {
        split.insertBefore(assocBuildChip(field, a, isPiece), addWrap);
    });
    assocUpdateRestChip(split.querySelector('[data-assoc-rest]'), rest, isPiece);
    if (
        dto.version != null &&
        window.krtFetch &&
        typeof window.krtFetch.syncVersion === 'function'
    ) {
        const leaf = split.closest('.tree-row--leaf');
        if (leaf) window.krtFetch.syncVersion(leaf, dto.version);
    }
}

// Sends the allocation write, serialized per entry so a rapid second edit of the same row waits
// for the fresh version (REQ-INV-026 / REQ-FE-003 avoid a self-inflicted 409).
function assocSubmit(split, pop, method) {
    const entryId = split.getAttribute('data-entry-id');
    const field = split.getAttribute('data-assoc-field');
    const targetId = pop.getAttribute('data-assoc-target');
    const isPiece = split.getAttribute('data-piece') === 'true';
    let amount = null;
    if (method !== 'DELETE') {
        const input = pop.querySelector('[data-assoc-amount-input]');
        amount = parseFloat(input ? input.value : '');
        if (isNaN(amount) || amount <= 0 || (isPiece && amount % 1 !== 0)) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.amountRequired);
            }
            return;
        }
        amount = Math.round(amount * 1000) / 1000;
    }
    const run = function () {
        // Read the entry version at SEND time, not click time (REQ-FE-003): both chip dimensions
        // share the entry @Version and the inv-assoc key, so a rapid 2nd edit of the same entry is
        // queued behind the 1st — which force-increments the version and syncs it onto data-version
        // via assocRerender. Reading data-version here (inside the serialized task) picks up that
        // fresh value, avoiding a self-inflicted 409.
        const version = parseInt(split.getAttribute('data-version'), 10);
        const body = { field: field, targetId: targetId, amount: amount, version: version };
        return assocSend(entryId, method, body, split, pop);
    };
    if (window.krtFetch && typeof window.krtFetch.serialize === 'function') {
        return window.krtFetch.serialize('inv-assoc:' + entryId, run);
    }
    return run();
}

async function assocSend(entryId, method, body, split, pop) {
    // #577: CSRF via the shared krtCsrf single source of truth (REQ-FE-002) with retry-on-403.
    let headers = window.krtCsrf
        ? window.krtCsrf.headers()
        : { 'Content-Type': 'application/json' };
    function send() {
        return fetch('/inventory/' + entryId + '/allocation', {
            method: method,
            headers: headers,
            body: JSON.stringify(body),
        });
    }
    try {
        let response = await send();
        if (response.status === 403 && window.krtCsrf && window.krtCsrf.refresh) {
            const refreshed = await window.krtCsrf.refresh();
            if (refreshed) {
                headers = window.krtCsrf.headers();
                response = await send();
            }
        }
        if (response.ok) {
            let dto = null;
            try {
                dto = await response.json();
            } catch {
                /* tolerate empty body */
            }
            if (dto) assocRerender(split, dto);
            pop.classList.add('krtm-hidden');
            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(assocI18n.saved);
            }
            broadcastInventoryAllChanged();
            // A job-order earmark change shifts that order's material collection.
            if (body && body.field === 'JOB_ORDER') {
                broadcastOrdersChanged([body.targetId]);
            }
        } else if (response.status === 409) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.conflict);
            }
            setTimeout(() => location.reload(), 2000);
        } else if (response.status === 422) {
            // Over-allocation (REQ-INV-027 R5): a toast, not a reload — the pop stays open so the
            // user can lower the amount.
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.overallocated);
            }
        } else if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.failed);
        }
    } catch (e) {
        console.error(e);
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.failed);
        }
    }
}

// CSP-safe delegated bindings (replaces the 28 inline on*= handlers across this template).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'inv-admin-toggle-multi', function (el) {
        toggleMultiSelect(el.getAttribute('data-multi-target'));
    });
    window.krtEvents.on('change', 'inv-admin-toggle-all', function (el) {
        toggleSelectAll(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterInventory();
    });
    window.krtEvents.on('change', 'inv-admin-update-state', function (el) {
        updateSelectState(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterInventory();
    });
    window.krtEvents.on('change', 'inv-admin-filter', filterInventory);
    window.krtEvents.on('click', 'inv-admin-reset-filter', resetInventoryFilter);
    window.krtEvents.on('click', 'inv-admin-toggle-group', function (el) {
        toggleGroup(el);
    });
    window.krtEvents.on('click', 'inv-admin-toggle-stack', function (el) {
        toggleStack(el);
    });
    window.krtEvents.on('click', 'inv-admin-stack-page', function (el) {
        goToStackEntriesPage(el);
    });
    // Variante C allocation chips (REQ-INV-027).
    window.krtEvents.on('click', 'inv-admin-assoc-add-open', function (el) {
        const split = el.closest('.assoc-split');
        const pop = split ? split.querySelector('[data-assoc-pop]') : null;
        if (!pop) return;
        const wasHidden = pop.classList.contains('krtm-hidden');
        assocCloseAllPops(pop);
        if (wasHidden) {
            pop.removeAttribute('data-assoc-target');
            assocShowPickSection(pop);
            const hidden = pop.querySelector('input[type="hidden"]');
            if (hidden && hidden.krtCombobox) hidden.krtCombobox.setValue('');
            pop.classList.remove('krtm-hidden');
            assocPositionPop(pop);
            const cbInput = pop.querySelector('.krt-combobox__input');
            if (cbInput) cbInput.focus();
        } else {
            pop.classList.add('krtm-hidden');
        }
    });
    window.krtEvents.on('change', 'inv-admin-assoc-pick', function (el) {
        const value = el.value;
        if (!value) return;
        const pop = el.closest('[data-assoc-pop]');
        if (!pop) return;
        pop.setAttribute('data-assoc-target', value);
        pop.setAttribute('data-assoc-mode', 'add');
        assocShowAmountSection(pop, false);
        const input = pop.querySelector('[data-assoc-amount-input]');
        if (input) {
            input.value = '';
            input.focus();
        }
    });
    window.krtEvents.on('click', 'inv-admin-assoc-edit', function (el) {
        const split = el.closest('.assoc-split');
        const pop = split ? split.querySelector('[data-assoc-pop]') : null;
        if (!pop) return;
        assocCloseAllPops(pop);
        pop.setAttribute('data-assoc-target', el.getAttribute('data-target-id'));
        pop.setAttribute('data-assoc-mode', 'edit');
        assocShowAmountSection(pop, true);
        const input = pop.querySelector('[data-assoc-amount-input]');
        if (input) input.value = el.getAttribute('data-amount');
        pop.classList.remove('krtm-hidden');
        assocPositionPop(pop);
        if (input) input.focus();
    });
    window.krtEvents.on('click', 'inv-admin-assoc-save', function (el) {
        const pop = el.closest('[data-assoc-pop]');
        const split = el.closest('.assoc-split');
        if (!pop || !split) return;
        assocSubmit(split, pop, pop.getAttribute('data-assoc-mode') === 'edit' ? 'PATCH' : 'POST');
    });
    window.krtEvents.on('click', 'inv-admin-assoc-remove', function (el) {
        const pop = el.closest('[data-assoc-pop]');
        const split = el.closest('.assoc-split');
        if (!pop || !split) return;
        assocSubmit(split, pop, 'DELETE');
    });
    // Keep the fixed popover anchored to its trigger while the page or the table's own
    // horizontal scroll container moves (capture reaches inner-container scrolls that don't bubble).
    window.addEventListener('scroll', assocRepositionOpenPop, true);
    window.addEventListener('resize', assocRepositionOpenPop);
    // Close popovers on an outside click; keyboard: Enter saves the amount, Enter/Space opens a
    // chip's editor (the chips are role=button but a <span> gets no synthetic click on key press).
    document.addEventListener('click', function (e) {
        if (
            !e.target.closest('[data-assoc-pop]') &&
            !e.target.closest('.assoc-add') &&
            !e.target.closest('[data-assoc-chip]')
        ) {
            assocCloseAllPops(null);
        }
    });
    document.addEventListener('keydown', function (e) {
        if (!e.target || typeof e.target.matches !== 'function') return;
        if (e.key === 'Enter' && e.target.matches('[data-assoc-amount-input]')) {
            e.preventDefault();
            const pop = e.target.closest('[data-assoc-pop]');
            const split = e.target.closest('.assoc-split');
            if (pop && split) {
                assocSubmit(
                    split,
                    pop,
                    pop.getAttribute('data-assoc-mode') === 'edit' ? 'PATCH' : 'POST',
                );
            }
        } else if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('[data-assoc-chip]')) {
            e.preventDefault();
            e.target.click();
        }
    });
    window.krtEvents.on('click', 'inv-admin-bookout', function (el) {
        openBookOutModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
            el.getAttribute('data-material-id'),
            el.getAttribute('data-user-id'),
            el.getAttribute('data-location-id'),
            el.getAttribute('data-quantity-type'),
        );
    });
    window.krtEvents.on('click', 'inv-admin-umbuchen', function (el) {
        openUmbuchenModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
            el.getAttribute('data-material-id'),
            el.getAttribute('data-user-id'),
            el.getAttribute('data-location-id'),
            el.getAttribute('data-quantity-type'),
            el.getAttribute('data-owning-org-unit-id'),
        );
    });
    window.krtEvents.on('click', 'inv-admin-open-note', function (el) {
        openNoteModal(el);
    });
    window.krtEvents.on('click', 'inv-admin-close-bookout', closeBookOutModal);
    window.krtEvents.on('input', 'inv-admin-amount-from-target', updateAmountFromTarget);
    window.krtEvents.on('input', 'inv-admin-target-from-amount', updateTargetFromAmount);
    window.krtEvents.on('change', 'inv-admin-toggle-bookout-type', toggleBookOutTypeFields);
    window.krtEvents.on('click', 'inv-admin-close-umbuchen', closeUmbuchenModal);
    window.krtEvents.on(
        'input',
        'inv-admin-umbuchen-amount-from-target',
        updateUmbuchenAmountFromTarget,
    );
    window.krtEvents.on(
        'input',
        'inv-admin-umbuchen-target-from-amount',
        updateUmbuchenTargetFromAmount,
    );
    window.krtEvents.on(
        'change',
        'inv-admin-umbuchen-target-user-changed',
        refreshUmbuchenTransferOrgUnitPicker,
    );
    window.krtEvents.on('click', 'inv-admin-close-note', closeNoteModal);
    window.krtEvents.on('input', 'inv-admin-update-note-counter', updateNoteCounter);
    window.krtEvents.on('click', 'inv-admin-save-note', saveNote);
    window.krtEvents.on('click', 'inv-admin-remove-note', removeNote);
}

// The book-out form is a stable top-level element (outside the swapped table container), so a
// direct submit listener bound once survives the grouped-table re-swaps.
const bookOutFormEl = document.getElementById('bookOutForm');
if (bookOutFormEl) {
    bookOutFormEl.addEventListener('submit', submitBookOut);
}
const umbuchenFormEl = document.getElementById('umbuchenForm');
if (umbuchenFormEl) {
    umbuchenFormEl.addEventListener('submit', submitUmbuchen);
}
